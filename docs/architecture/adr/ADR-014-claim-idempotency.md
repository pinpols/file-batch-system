# ADR-014 · CLAIM 幂等保护 (invocation-id 模型)

- **Status**: Accepted — 已落地 (2026-05-03)；Flyway **V95**（`job_partition` / `job_partition_archive` 镜像列）；renew/report（及 ADR-016 `renew-batch` 项）可选 `partitionInvocationId`；省略时 orchestrator 兼容旧 worker。
- **Date**: 2026-05-03
- **Related**: ADR-002 (outbox) / docs/analysis/worker-vs-industry-2026-05-03.md P1-6

---

## 背景

当前 `TaskDispatchExecutor.execute` 路径:

```
Kafka receive → CLAIM (status RUNNING + lease) → execute 业务 → REPORT
```

CLAIM 守护:`updateStatus where partition_status='READY'` CAS,确保同一 partition 同时只一个 worker 持有。

**漏洞**:

1. Kafka rebalance 触发 partition 重指派 → 同一消息可能投给两个 worker 实例
2. 假设 worker A 已 CLAIM partition 进入 RUNNING,然后:
  - A 进程 GC pause / 网络抖动 → lease 过期
  - `PartitionLeaseReclaimScheduler` 把 partition 释放回 READY
  - worker B 再次 CLAIM 成功 → 业务执行
  - A 恢复 → 继续执行 → **业务跑两次**

当前依赖业务侧自带幂等性:

- `SqlTransformCompute` 有 ON CONFLICT
- `outbox_event` 有 `uk_outbox_event_key UNIQUE (tenant_id, event_key)`
- `job_instance` 有 `uk_job_instance_tenant_dedup`

但**不是所有业务路径都幂等** — file-import 解析阶段写 `file_record` / `pipeline_step_run` 等表通常无幂等约束。

## 业界对比


| 系统                 | 机制                                                                                                               |
| ------------------ | ---------------------------------------------------------------------------------------------------------------- |
| **Temporal**       | activity 有 invocation_id (server-assigned UUID),server 端 dedupe;worker reconnect 后查 server 知道当前 invocation 是否还有效 |
| **Stripe pattern** | idempotency_key 客户端生成,server 端 idempotency_key table 短期(24h)存请求 → 响应,重复 key 返回缓存响应                               |
| **AWS SQS**        | DeduplicationId + 5min 窗口去重,但不解决"已成功执行后重复消费"问题                                                                   |


## 决策提案

**采用 invocation_id 模型**(类 Temporal):

### 数据模型

`job_partition` 加列:

- `current_invocation_id VARCHAR(64)` — CLAIM 时 server-assigned UUID
- `invocation_started_at TIMESTAMPTZ`

CLAIM 时:

```sql
UPDATE job_partition
SET partition_status = 'RUNNING',
    worker_code = ?,
    current_invocation_id = ?,  -- server 生成新 UUID
    invocation_started_at = now(),
    lease_expire_at = ?,
    version = version + 1
WHERE id = ? AND partition_status = 'READY' AND version = ?
RETURNING current_invocation_id
```

worker 拿到 invocation_id 后,在所有后续协议(REPORT / renewLease / DLQ envelope)都携带这个 invocation_id。

### REPORT 时 server 端幂等校验

```sql
-- 校验 invocation_id 一致;不一致说明 partition 被 reclaim 后另一 worker 已 CLAIM
SELECT current_invocation_id FROM job_partition WHERE id = ?
```

如果 `current_invocation_id` 与 worker 上报的不一致 → server 拒绝 REPORT,worker 进入"我已是过期 invocation"状态,后续业务执行结果丢弃,不影响 active invocation。

### renewLease 同理校验

worker 续约时带上 invocation_id,server 校验 → 不一致拒绝 → worker 知道自己已被替换 → 主动 cancel 本地业务执行(配合 P0-1 的 cancellation/timeout)。

## 实施落地（与代码一致）

- **DB**：`db/migration/V95__job_partition_invocation.sql` — `batch.job_partition` 与 `archive.job_partition_archive` 增加 `current_invocation_id`、`invocation_started_at`。
- **Orchestrator**：任务认领路径生成 invocation 并写入分区；`EffectiveTaskConfig` 向下透传；`POST .../renew` 与 `POST .../leases/renew-batch`（ADR-016）请求体可带 `partitionInvocationId`；`report` 携带时若与 `job_partition.current_invocation_id` 不一致 → `CONFLICT`（`error.task.invocation_mismatch`）。
- **Worker**：claim 快照写入 `PulledTask` / 执行上下文；续租与上报携带；`ActiveTaskLeaseRegistry` 保留 invocation 供续租使用。
- **兼容**：省略 `partitionInvocationId` 时维持过渡期语义（renew/report 不强依赖 invocation 匹配）。

## 影响面

**正面**:

- 真正解决 CLAIM 重复执行问题
- 配合 P0-1 cancellation,worker 在被替换后能主动停业务,节省资源

**负面**:

- 协议演进：TaskDispatchMessage / Renew / Report 增加可选 `partitionInvocationId`（老 worker 省略字段 → server 兼容）。
- 运维需确保 **V95** 已应用至目标环境后再滚动升级 worker。

## 替代方案 (被拒绝)

### A. 业务侧自管幂等

否决理由:不是所有业务都有自然幂等点,且把责任分散到 N 处业务代码风险大。

### B. orch 端 idempotency_key 表 (Stripe pattern)

否决理由:多一张表 + worker 端要生成稳定 key,复杂度类似 invocation_id 但 server 主动权弱。

## 验收标准

- **V95** 已纳入迁移链；热表与 **archive** 镜像列一致（`ArchiveSchemaDriftCheck`）。
- CLAIM/RENEW/REPORT（及 batch renew）对 **省略** `partitionInvocationId` 的旧 worker 仍可用。
- 集成/单测覆盖：invocation 不一致时 report 拒绝续约语义（见 `DefaultTaskOutcomeServiceTest`、`WorkerClaimProgressCompleteIntegrationTest` 等）。

## 不变量

- 老 worker 不带 invocation_id 时 server 接受(过渡期),但 log warn
- invocation_id 仅用于幂等判定,**不替代** lease 机制(lease 仍是超时回收的依据)

## 替代讨论

参见 worker-vs-industry-2026-05-03.md P1-6。