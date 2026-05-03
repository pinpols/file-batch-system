# ADR-015 · Worker 端本地 outbox — report 重投与防重复执行

- **Status**: Proposed (2026-05-03,需单独立项实施 ~2-3 天)
- **Date**: 2026-05-03
- **Related**: ADR-014 (CLAIM 幂等) / docs/analysis/worker-vs-industry-2026-05-03.md P1-10

---

## 背景

当前 `DefaultTaskExecutionWrapper.execute` 路径:

```
CLAIM → 业务 execute → REPORT (HTTP POST orch)
                          ↓ 失败
                       AbstractTaskConsumer.doConsume catch
                          ↓
                       publishToDlqSafely → DLQ
                          ↓
                       运维 replay → 重新派 task → 重新 execute → 业务跑两次
```

**漏洞**:
- 业务**已成功执行**,但 REPORT 因网络抖动 / orch GC pause / 短暂不可达失败
- 当前流程把它当成"任务失败"处理,送 DLQ → 运维 replay → orch 重派 → 业务重复执行
- `LeaseRenewer` 的熔断 (P1-8) 减轻了 orch 长不可达的连锁,但**业务已成功这种情况无关熔断**

业务结果的唯一可靠来源是 `REPORT` 落到 orch 的 `job_task_result`。REPORT 失败 = 结果丢失。

## 业界对比

| 系统 | 机制 |
|---|---|
| **Temporal** | activity 完成后 worker 持久化结果到本地,SDK 自动 retry (exponential backoff) 直到 server 接收 |
| **Kafka Streams** | EOS (exactly-once semantics) 通过 transactional producer + sink 端 atomic offset commit |
| **Airflow** | task instance 状态写本地 SQLite,scheduler 拉取 (polling) — 与本系统反向 |

## 决策提案

**worker 端引入本地 outbox 表** (SQLite/嵌入式 H2 / 或复用 worker host PG 上一个 schema):

### 数据流

```
业务 execute → 写本地 outbox (含 invocation_id + result + traceId)
                  ↓ 同事务 ack Kafka offset
              本地 outbox poll → HTTP REPORT orch
                  ↓ 成功
              本地 outbox 标 PUBLISHED
                  ↓ 失败
              本地 outbox 重试 (指数退避 + jitter,与 P1-8 熔断协同)
```

**关键不变量**:
- 业务结果一旦写入本地 outbox,Kafka offset 即可 ack — orch 重派路径被 ADR-014 invocation_id 兜底(老 invocation 的 result orch 拒收)
- 本地 outbox 与业务执行**同事务**(SQLite atomic) — 业务 success 但 outbox 未写 = transaction rollback,业务也回滚
- REPORT 重试**与业务执行解耦** — worker 进程崩溃重启后,本地 outbox 上的未发记录被新 worker 进程接管(同 worker_code 启动后扫本地 outbox 续推)

### 技术选型

- **SQLite** (推荐):嵌入式,worker host 不依赖外部服务;outbox 表小(~MB 级),性能足
- 复用 worker host PG (备选):需要 worker 部署带 PG datasource,部署复杂度提升

## 影响面

**正面**:
- 业务结果不再因 REPORT 失败而丢失 → 重派/重复执行场景大幅减少
- 与 ADR-014 invocation_id 协同:重派的"过期 invocation" REPORT 被 orch 拒收,不污染 active invocation

**负面**:
- worker 容器加 SQLite 文件依赖 (volume 挂载 / persistent storage)
- 本地 outbox poll loop + 状态机
- worker 进程崩溃后本地 outbox 文件不丢失才能保证 → K8s ephemeral pod 需要 PVC
- 工时:2-3 天
  - SQLite schema + mapper
  - Worker 端 outbox writer + poller + retry
  - 与现有 LeaseRenewer 熔断协同(orch 不可达时不浪费 retry)
  - 端到端测试覆盖"REPORT 失败 → 本地缓存 → 重启后重投" 场景

## 替代方案 (被拒绝)

### A. REPORT 改 Kafka 异步

orch 端从 HTTP `/internal/tasks/{taskId}/report` 改为 Kafka topic `batch.task.result.report`,worker producer 发,orch consumer 处理。

**优点**:Kafka 持久化 + 异步,自带重投。

**否决理由**:
- 协议 breaking change (老 worker 仍走 HTTP)
- worker 仍需要"同事务 producer + ack" 机制确保业务-发布原子性
- 复杂度类似本地 outbox,但增加 Kafka 流量

### B. orch 端 polling 替代 worker push

orch 周期 poll worker 的 `/internal/results` endpoint。

**否决理由**:
- 反 push/pull 方向,worker 暴露 endpoint 增加攻击面
- 延迟敏感场景不友好

## 验收标准

- [ ] worker SQLite outbox schema 落地
- [ ] WorkerOutboxWriter / WorkerOutboxPoller / WorkerOutboxRetryScheduler
- [ ] 端到端测试:模拟 orch 不可达 60s,业务结果写本地 outbox,orch 恢复后自动 REPORT
- [ ] 与 ADR-014 invocation_id 协同测试

## 不变量

- 业务执行 + 本地 outbox 写入**必须同事务**
- 本地 outbox 持久化必须配合 K8s PVC / VM 持久卷,**不能用 emptyDir**
- 老 worker(无本地 outbox)不变保留现有 DLQ 路径,过渡期共存

## 替代讨论

参见 worker-vs-industry-2026-05-03.md P1-10。
