# E2E 测试覆盖（场景矩阵 + 三条主链路）

## 1. 三条主链路覆盖（Import / Export / Dispatch）


## 目标与范围

本文分析 `batch-e2e-tests` 中三个 E2E 测试类当前覆盖了哪些核心链路、哪些风险点仍未覆盖，并给出后续可执行的覆盖优化清单。

涉及测试类（截至 2026-05-03，共 19 个 E2E in `batch-e2e-tests` + 1 个在 `batch-trigger` 模块）：

**主链路（Happy Path）**
- `ImportPipelineE2eIT`、`ExportPipelineE2eIT`、`DispatchPipelineE2eIT`、`ProcessPipelineE2eIT`
- `OutboxForwarderE2eIT`（outbox 自动轮询）
- `ExportContentVerificationE2eIT`（内容级验证）

**失败分支**
- `ImportFailureE2eIT`、`ImportFailurePipelineE2eIT`
- `ExportFailurePipelineE2eIT`、`ExportStorageFailureE2eIT`
- `DispatchFailurePipelineE2eIT`
- `ProcessFailurePipelineE2eIT`（加工链路失败 + 补偿）

**稳健性**
- `MultiTenantConcurrentE2eIT`（多租户并发隔离）
- `DedupJobLaunchE2eIT`（顺序 + 并发 dedup 幂等）
- `OutboxForwarderRetryE2eIT`（Outbox 失败重试）
- `WorkerDrainE2eIT`（Worker 排空接管）
- `WorkerProcessRestartRecoveryE2eIT`（worker 重启后 lease/认领恢复）
- `DeadLetterApprovalReplayE2eIT`（死信审批重放全链路）
- `TriggerAsyncLaunchFullChainE2eIT`（ADR-010 trigger → outbox → Kafka → orchestrator launch 全链路）

**触发器异步链路**（在 `batch-trigger` 模块，非 batch-e2e-tests 但属端到端覆盖）
- `TriggerAsyncLaunchE2eIT`（trigger 模块内 outbox relay + Kafka publish）

---

## 当前已覆盖的核心链路

### 1) ImportPipelineE2eIT

已覆盖：

- API 触发启动：`LaunchService.launch(...)`
- 调度与任务创建：通过 `job_definition/workflow_definition/trigger_request` 动态种子驱动
- Outbox -> Kafka 分发：`E2eOutboxPublishSupport.publishAllPending(...)`
- Worker 消费与执行：import worker claim + import pipeline 执行
- 回报闭环：`job_task.task_status` 最终到 `SUCCESS`
- 业务结果写入数据库：校验 `biz.customer_account` 目标数据存在

结论：Import 主闭环已打通，且包含“业务数据入库”断言，不是只看任务状态。

### 2) ExportPipelineE2eIT

已覆盖：

- 业务源数据准备：在 business 库写入 `biz.settlement_batch` + `biz.settlement_detail`
- API 触发 + 调度 + outbox + Kafka + export worker 执行
- 回报闭环：`job_task.task_status = SUCCESS`
- 业务侧结果验证：读取 `biz.settlement_batch.total_amount`（验证导出流程对业务批次可读）

结论：Export 主链路已完整串通，覆盖“业务源数据 -> 导出执行 -> 任务成功”路径。

### 3) DispatchPipelineE2eIT

已覆盖：

- 派发通道配置准备：`batch.file_channel_config`（LOCAL 通道）
- 派发文件记录准备：`batch.file_record`（OUTPUT/LOCAL）
- API 触发 + 调度 + outbox + Kafka + dispatch worker 执行
- 回报闭环：`job_task.task_status = SUCCESS`
- 文件状态流转验证：`batch.file_record.file_status = DISPATCHED`

结论：Dispatch 主链路已覆盖“通道 + 文件 + 派发执行 + 状态流转”。

---

## 三个用例的共性覆盖能力

这三条 E2E 一起，已经验证了如下平台级关键能力：

- 触发请求到任务执行的端到端串联（orchestrator + worker）
- Testcontainers 环境中的 PostgreSQL/Kafka 实连执行
- Outbox 模式下的消息发布能力（测试中使用手动发布）
- Worker 消费与任务 claim/report 协同
- 不同 worker 类型（IMPORT/EXPORT/DISPATCH）路由链路可达

---

## 覆盖状态（截至 2026-04-08）

### A. Outbox Forwarder 定时轮询机制 ✅ 已覆盖

`OutboxForwarderE2eIT`：不手动 publish，开启真实轮询器，验证 NEW→PUBLISHED 状态机和消费到成功全链路。

### B. 异常与重试分支 ✅ 已覆盖

- `ImportFailureE2eIT`：字段/模板不匹配，验证失败状态与错误记录写入数据库
- `ImportFailurePipelineE2eIT`：导入 pipeline 失败态推进与最终回报
- `ExportFailurePipelineE2eIT`：导出 pipeline 失败态推进与最终回报
- `ExportStorageFailureE2eIT`：存储阶段失败场景
- `DispatchFailurePipelineE2eIT`：无效通道/目标不可达，验证失败与回执/补偿状态
- `OutboxForwarderRetryE2eIT`：Outbox 首次失败后的重试推进

### C. 导出/分发产物内容级断言 ✅ 已覆盖

- `ExportContentVerificationE2eIT`：使用 `ExportFileVerifier` 验证文件行数、MinIO 内容、金额汇总（`total_amount ≥ 285.00`）、内容片段（`E2E-CV-001`/`E2E-CV-002`）
- `DispatchPipelineE2eIT`：使用 `DispatchReceiptVerifier` 验证文件状态、回执码、渠道码、审计日志条数

### D. 并发与竞争场景 ⚠️ 部分覆盖

`MultiTenantConcurrentE2eIT` 覆盖多租户并行触发与数据隔离。单 worker 并发 claim 竞争、同 dedupKey 幂等冲突场景尚未有专项 E2E（已有单元测试覆盖逻辑）。

### E. dedup 幂等 ✅ 已覆盖

- `DedupJobLaunchE2eIT`：顺序重复提交和并发重复提交都能稳定落到同一 dedup 语义

### F. 多租户隔离 ✅ 已覆盖

`MultiTenantConcurrentE2eIT`：t1/t2/t3 租户同时触发，校验数据隔离、队列公平、无串租户污染。

---

## 覆盖优化完成情况（截至 2026-04-08）

## P0 ✅ 全部完成

- ✅ `OutboxForwarderE2eIT`：真实轮询验证
- ✅ `ImportFailureE2eIT`：失败分支
- ✅ `ExportStorageFailureE2eIT`：失败分支
- ✅ `DispatchFailurePipelineE2eIT`：失败分支

## P1 ✅ 全部完成

- ✅ `ExportContentVerificationE2eIT`：文件内容断言（行数/金额/MinIO 内容片段）—— `ExportFileVerifier`
- ✅ `DispatchPipelineE2eIT`：回执一致性断言（文件状态/回执码/渠道码/审计条数）—— `DispatchReceiptVerifier`

## P2 ✅ 已完成多租户 + dedup；单 worker claim 竞争仍待专项

- ✅ `MultiTenantConcurrentE2eIT`：多租户并行触发与数据隔离
- ✅ `DedupJobLaunchE2eIT`：顺序 + 并发 dedup 幂等
- ⚠️ 单 worker 并发 claim 竞争：暂无专项 E2E，由单元测试和集成测试覆盖

---

## 建议的文档与测试组织方式

- 在 `batch-e2e-tests` 下新增分层：
  - `happy-path`（现有三条）
  - `failure-path`
  - `resilience`（重试、并发、幂等）
- 为每个 E2E 用例补“覆盖声明”注释（覆盖点/未覆盖点），防止后续误判覆盖范围。

---

## 已知失败测试

无。`OutboxForwarderRetryE2eIT` 已通过 `@DynamicPropertySource` + `OrchestratorWireMockSupport.registerOrchestratorBaseUrls` 修复，当前全套 E2E 无已知失败。

---

## 当前结论（截至 2026-04-08）

E2E 套件已覆盖：主链路（Import/Export/Dispatch）、Outbox 自动轮询与重试、失败分支（三链路 + pipeline 级失败）、内容级验收（导出/分发）、多租户并发隔离、dedup 幂等（顺序 + 并发）、Worker 排空接管、死信审批重放，共 **15 个 E2E 测试类**。

能够作为生产风险防线级别的回归门禁。剩余未覆盖的场景：单 worker 并发 claim 竞争（低风险，有单元和集成测试回退）。

---

## 2. 场景矩阵


更新时间：2026-04-02

这份矩阵只记录 `batch-e2e-tests` 里已经落地的端到端场景，以及当前仍缺的高价值场景。

字段说明：

- `作业类型`：Import / Export / Dispatch / 平台共性
- `触发类型`：当前 E2E 基本都走 `API`；平台级轮询类用 `N/A`
- `故障类型`：主链路、失败、重试、并发、幂等、租约回收等
- `覆盖状态`：`已覆盖` / `未覆盖`

## 已覆盖

| 测试类 | 作业类型 | 触发类型 | 场景 / 故障类型 | 覆盖状态 | 备注 |
|---|---|---|---|---|---|
| `ImportPipelineE2eIT` | Import | API | 主链路成功 | 已覆盖 | launch -> outbox -> worker -> 业务表写入数据库 |
| `ImportFailureE2eIT` | Import | API | 模板不存在、字段校验失败、重试耗尽 / 死信 | 已覆盖 | 失败态与错误记录写入数据库 |
| `ImportFailurePipelineE2eIT` | Import | API | import pipeline 失败推进 | 已覆盖 | 失败分支回报闭环 |
| `OutboxForwarderE2eIT` | 平台共性 / Import | API | Outbox 自动轮询 | 已覆盖 | NEW -> PUBLISHED |
| `OutboxForwarderRetryE2eIT` | 平台共性 | N/A | Outbox 失败重试与恢复 / 耗尽 | 已覆盖 | mock publisher 控制成功/失败序列 |
| `WorkerDrainE2eIT` | Import | API | worker drain timeout、接管、去注册 | 已覆盖 | DRAINING -> DECOMMISSIONED |
| `ExportPipelineE2eIT` | Export | API | 主链路成功 | 已覆盖 | business table -> file -> register -> success |
| `ExportContentVerificationE2eIT` | Export | API | 产物内容级验证 | 已覆盖 | 行数、金额、内容片段、MinIO |
| `ExportFailurePipelineE2eIT` | Export | API | export pipeline 失败推进 | 已覆盖 | 失败态回报闭环 |
| `ExportStorageFailureE2eIT` | Export | API | 存储失败（MinIO / object store） | 已覆盖 | 产物写入阶段故障 |
| `DispatchPipelineE2eIT` | Dispatch | API | 主链路成功 | 已覆盖 | 通道、文件、回执状态流转 |
| `DispatchFailurePipelineE2eIT` | Dispatch | API | 通道不可达 / 目标失败 | 已覆盖 | 失败与补偿路径 |
| `MultiTenantConcurrentE2eIT` | 跨作业 / Import | API | 多租户并发隔离 | 已覆盖 | 同时触发不串租户 |
| `DedupJobLaunchE2eIT` | 跨作业 / Import | API | 顺序去重、并发去重 | 已覆盖 | 同 dedupKey 稳定收敛 |

## 未覆盖

| 作业类型 | 触发类型 | 场景 / 故障类型 | 回退情况 | 建议优先级 | 说明 |
|---|---|---|---|---|---|
| Import | MANUAL | 人工发起的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 trigger / launch 相关集成测试，没有专项 E2E |
| Import | EVENT | 事件触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有触发元数据和调度链路验证，没有专项 E2E |
| Import | CATCH_UP | 补跑触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 catch-up / misfire 集成验证，没有专项 E2E |
| Import | SCHEDULED | Quartz 定时触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 Quartz 注册 / cluster 基础验证，没有专项 E2E |
| Export | MANUAL | 人工发起的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有调度和 worker 链路集成验证，没有专项 E2E |
| Export | EVENT | 事件触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 launch / outbox / worker 逻辑验证，没有专项 E2E |
| Export | CATCH_UP | 补跑触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有调度和补跑逻辑验证，没有专项 E2E |
| Export | SCHEDULED | Quartz 定时触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 Quartz / 调度集成验证，没有专项 E2E |
| Dispatch | MANUAL | 人工发起的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 worker / channel 适配器测试，没有专项 E2E |
| Dispatch | EVENT | 事件触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有 dispatch 流程与健康检查验证，没有专项 E2E |
| Dispatch | CATCH_UP | 补跑触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有重试 / 回执 / 补偿逻辑验证，没有专项 E2E |
| Dispatch | SCHEDULED | Quartz 定时触发的完整端到端链路 | E2E缺口但已有集成回退 | 低 | 有调度入口和 worker dispatch 验证，没有专项 E2E |
| 平台共性 | N/A | Quartz 多实例 / 集群 failover | E2E缺口但已有集成回退 | 中 | 已有 JDBC store / cluster state 集成验证，但没有完整故障切换 E2E |
| 平台共性 | N/A | 长时间 soak / 反复重入竞态 | E2E缺口但已有集成回退 | 中 | 已有并发 claim / scheduler 重入回归，但没有长时间 soak E2E |
| 平台共性 | N/A | 补偿审批 / replay / dead letter 人工闭环 | E2E缺口但已有集成回退 | 中 | 有审批 / replay 控制器和服务层测试，没有专项 E2E |
| 平台共性 | N/A | 真实外部渠道 SFTP / EMAIL / OSS | E2E缺口但已有集成回退 | 中 | 已补 [`DispatchExternalChannelIntegrationTest`](<repo-root>/batch-worker-dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchExternalChannelIntegrationTest.java)，E2E 仍缺但风险已下降 |
| 平台共性 | N/A | worker 进程级重启后的恢复 | E2E缺口但已有集成回退 | 中 | 已补 [`WorkerProcessRestartRecoveryIntegrationTest`](<repo-root>/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkerProcessRestartRecoveryIntegrationTest.java)，覆盖 worker 进程重启后的注册续跑与任务继续 |
| 平台共性 | N/A | staging live rollout / rollback smoke | E2E缺口但已有脚本回退 | 中 | 已补 [`run-staging-live-smoke.sh`](<repo-root>/scripts/ci/run-staging-live-smoke.sh)，用于 staging 的 live deploy + rollback smoke |

## 结论

当前 E2E 已覆盖核心主链路、典型失败、Outbox 重试、多租户隔离和 dedup 幂等；未覆盖部分主要集中在非 API 触发、Quartz 集群故障切换、长时间竞态、真实外部渠道和少量部署级 smoke。worker 重启恢复和 staging smoke 已分别补上集成与脚本回退。
