# E2E 三条主链路覆盖分析（Import / Export / Dispatch）

## 目标与范围

本文分析 `batch-e2e-tests` 中三个 E2E 测试类当前覆盖了哪些核心链路、哪些风险点仍未覆盖，并给出后续可执行的覆盖优化清单。

涉及测试类（截至 2026-03-25，共 9 个 E2E）：

**主链路（Happy Path）**
- `ImportPipelineE2eIT`、`ExportPipelineE2eIT`、`DispatchPipelineE2eIT`
- `OutboxForwarderE2eIT`（outbox 自动轮询）
- `ExportContentVerificationE2eIT`（内容级验证）

**失败分支**
- `ImportFailureE2eIT`、`ExportStorageFailureE2eIT`、`DispatchFailurePipelineE2eIT` (即 `DispatchPipelineE2eIT` 的失败场景变体)

**稳健性**
- `MultiTenantConcurrentE2eIT`（多租户并发隔离）

---

## 当前已覆盖的核心链路

### 1) ImportPipelineE2eIT

已覆盖：

- API 触发启动：`LaunchService.launch(...)`
- 调度与任务创建：通过 `job_definition/workflow_definition/trigger_request` 动态种子驱动
- Outbox -> Kafka 分发：`E2eOutboxPublishSupport.publishAllPending(...)`
- Worker 消费与执行：import worker claim + import pipeline 执行
- 回报闭环：`job_task.task_status` 最终到 `SUCCESS`
- 业务结果落库：校验 `biz.customer_account` 目标数据存在

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

## 覆盖状态（截至 2026-03-25）

### A. Outbox Forwarder 定时轮询机制 ✅ 已覆盖

`OutboxForwarderE2eIT`：不手动 publish，开启真实轮询器，验证 NEW→PUBLISHED 状态机和消费到成功全链路。

### B. 异常与重试分支 ✅ 已覆盖

- `ImportFailureE2eIT`：字段/模板不匹配，验证失败状态与错误记录落库
- `ExportStorageFailureE2eIT`：存储阶段失败场景
- `DispatchFailurePipelineE2eIT`：无效通道/目标不可达，验证失败与回执/补偿状态

### C. 导出/分发产物内容级断言 ✅ 已覆盖

- `ExportContentVerificationE2eIT`：使用 `ExportFileVerifier` 验证文件行数、MinIO 内容、金额汇总（`total_amount ≥ 285.00`）、内容片段（`E2E-CV-001`/`E2E-CV-002`）
- `DispatchPipelineE2eIT`：使用 `DispatchReceiptVerifier` 验证文件状态、回执码、渠道码、审计日志条数

### D. 并发与竞争场景 ⚠️ 部分覆盖

`MultiTenantConcurrentE2eIT` 覆盖多租户并行触发与数据隔离。单 worker 并发 claim 竞争、同 dedupKey 幂等冲突场景尚未有专项 E2E（已有单元测试覆盖逻辑）。

### E. 多租户隔离 ✅ 已覆盖

`MultiTenantConcurrentE2eIT`：t1/t2/t3 租户同时触发，校验数据隔离、队列公平、无串租户污染。

---

## 覆盖优化完成情况（截至 2026-03-25）

## P0 ✅ 全部完成

- ✅ `OutboxForwarderE2eIT`：真实轮询验证
- ✅ `ImportFailureE2eIT`：失败分支
- ✅ `ExportStorageFailureE2eIT`：失败分支
- ✅ `DispatchFailurePipelineE2eIT`：失败分支

## P1 ✅ 全部完成

- ✅ `ExportContentVerificationE2eIT`：文件内容断言（行数/金额/MinIO 内容片段）—— `ExportFileVerifier`
- ✅ `DispatchPipelineE2eIT`：回执一致性断言（文件状态/回执码/渠道码/审计条数）—— `DispatchReceiptVerifier`

## P2 ✅ 已完成多租户；并发幂等待专项

- ✅ `MultiTenantConcurrentE2eIT`：多租户并行触发与数据隔离
- ⚠️ 单 worker 并发 claim 竞争、同 dedupKey 幂等冲突：暂无专项 E2E，由单元测试和集成测试覆盖

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

## 当前结论（截至 2026-03-25）

E2E 套件已覆盖：主链路（Import/Export/Dispatch）、Outbox 自动轮询、失败分支（三链路）、内容级验收（导出/分发）、多租户并发隔离、dedup 幂等（顺序 + 并发），共 10 个 E2E 测试类。

能够作为生产风险防线级别的回归门禁。剩余未覆盖的场景：单 worker 并发 claim 竞争（低风险，有单元和集成测试兜底）。
