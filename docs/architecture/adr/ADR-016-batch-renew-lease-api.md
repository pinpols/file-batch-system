# ADR-016 · Batch Renew Lease API — 减少 worker → orch HTTP 风暴

- **Status**: Proposed (2026-05-03,需单独立项实施 ~2-3 小时)
- **Date**: 2026-05-03
- **Related**: ADR-014 (CLAIM 幂等) / docs/analysis/worker-vs-industry-2026-05-03.md P2-14

---

## 背景

`WorkerTaskLeaseRenewer.renewActiveTaskLeases` 每 10s 周期为每个 in-flight task 单独调一次 orch `/internal/tasks/{taskId}/renew`:

```
worker N tasks × 10s 周期 = N HTTP req per worker per 10s
M workers × N tasks = M×N HTTP req / 10s (平均 M×N/10 QPS to orch renew endpoint)
```

高并发租户场景:

- 100 worker × 50 task each = 5000 task,每 10s = 500 QPS 单 endpoint
- orch 单 endpoint 响应 + DB UPDATE 单条 → 网络/DB 同等放大

P1-8 (本次已落地) 的熔断减轻了 orch 不可达时的 worker hammer,但**正常路径下的 N+1 HTTP 模式没解**。

## 业界对比


| 系统                      | 机制                                                                                             |
| ----------------------- | ---------------------------------------------------------------------------------------------- |
| **Temporal**            | activity heartbeat 用 long-poll + bidirectional gRPC stream,activity 完成前 server-side 持续心跳,无 N+1 |
| **AWS SQS**             | ChangeMessageVisibilityBatch — 单个 API 调用更新最多 10 条消息可见性                                         |
| **Spring Batch (远程分区)** | 集中式 job repository,单 update 多 step status,但单机模式                                                |


## 决策提案

**新增 batch renew endpoint + worker 收集后单 HTTP 调用**:

### orch 端

新 endpoint `POST /internal/tasks/leases/renew-batch`:

```json
Request:
{
  "items": [
    {"tenantId": "tenantA", "taskId": 123, "workerId": "worker-1"},
    {"tenantId": "tenantA", "taskId": 124, "workerId": "worker-1"},
    ...
  ]
}

Response:
{
  "results": [
    {"taskId": 123, "renewed": true},
    {"taskId": 124, "renewed": false, "reason": "already_canceled"}
  ]
}
```

实现:

- Controller 接收 batch,转发到 service
- Service 选择实施策略:
  - **MVP**: 内部循环现有单 renew(只省 HTTP roundtrip,SQL 仍 N 次)
  - **完整**: mapper 加 batch update SQL with `RETURNING id`,SQL 变 1 次 (PG 14+ 原生支持)

### worker 端

`TaskExecutionClient` 加方法:

```java
Map<Long, Boolean> renewBatch(List<RenewItem> items);
```

`HttpTaskExecutionClient` 实现:批量打包 → 单 HTTP POST → 解析返回 map。

`WorkerTaskLeaseRenewer.renewActiveTaskLeases` 改:

- 收集本 tick 所有 active lease
- 一次 batch 调用
- 解析返回 → 按 taskId 分发到 attemptRenew 的成功/失败统计 (复用熔断逻辑)

`fastRetryFailedLeases` 不变(单条快速重试,数量一般少)。

## 影响面

**正面**:

- HTTP req 量从 `M×N/10 QPS` 降到 `M/10 QPS` (省去 N 倍)
- TCP 连接复用 + JSON 解析 overhead 减少
- 配合完整版 batch SQL,DB UPDATE 也省

**负面**:

- 协议 breaking change → 需要 worker / orch 兼容性窗口
- batch 调用失败时,fallback 到逐条 renew?还是直接全部失败标 consecutive++?
  - 推荐 **fallback 逐条** — 网络层失败时降级到原行为,业务无感知
- 工时:2-3 小时 (MVP) / 半天 (含 batch SQL)

## 替代方案 (被拒绝)

### A. WebSocket / gRPC stream 持续心跳

否决理由:本系统 RestClient 单向 HTTP 已经定型,引入双向流改动面巨大。

### B. 直接调高 renew 周期 (10s → 30s)

否决理由:lease 默认 120s,30s 周期下 4 次失败 = 120s 接近 lease 上限,触发回收风险升高。

### C. worker 内多线程 renew

否决理由:解决 worker 端串行问题,但 orch endpoint 仍被打。

## 验收标准

- orch endpoint 落地,老的单 renew endpoint 保留向后兼容
- worker 端 batch 调用 + fallback 逐条
- 集成测试:模拟 50 task,验证单 tick 仅 1 个 HTTP req
- metric:`batch.worker.lease.renew.batch.size` 暴露 batch size 分布

## 不变量

- 老 worker(无 batch 支持)继续用单 endpoint,过渡期共存
- batch 调用失败时降级 → 逐条 renew(与现有行为一致)
- 任一 task renew 失败时,熔断状态机判定不变(全失败 → OPEN)

## 替代讨论

参见 worker-vs-industry-2026-05-03.md P2-14。