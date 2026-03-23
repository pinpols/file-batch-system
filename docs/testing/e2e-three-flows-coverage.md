# E2E 三条主链路覆盖分析（Import / Export / Dispatch）

## 目标与范围

本文分析 `batch-e2e-tests` 中三个 E2E 测试类当前覆盖了哪些核心链路、哪些风险点仍未覆盖，并给出后续可执行的覆盖优化清单。

涉及测试类：

- `batch-e2e-tests/src/test/java/com/example/batch/e2e/ImportPipelineE2eIT.java`
- `batch-e2e-tests/src/test/java/com/example/batch/e2e/ExportPipelineE2eIT.java`
- `batch-e2e-tests/src/test/java/com/example/batch/e2e/DispatchPipelineE2eIT.java`

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

## 仍未覆盖或覆盖较弱的风险点

### A. Outbox Forwarder 定时轮询机制

当前测试使用 `E2eOutboxPublishSupport.publishAllPending(...)` 人工发布，未覆盖真实调度轮询器行为（扫描、重试、退避）。

### B. 异常与重试分支

当前主要验证 happy path；以下场景缺少 E2E 验证：

- worker 执行失败后的重试策略生效
- 达到重试上限后的 dead-letter 行为细节
- dispatch 的 ACK 超时/补偿路径

### C. 导出/分发产物内容级断言

目前更多验证“状态成功”与“关键行存在”，未做深度内容校验：

- export 生成文件内容结构、字段映射、行数/汇总是否正确
- dispatch 输出目标路径中的文件内容是否与期望一致

### D. 并发与竞争场景

未覆盖多 worker 并发 claim、重复消息消费幂等、同 dedupKey 并发触发冲突等高风险场景。

### E. 多租户隔离

现有用例基本单租户 `t1`，未覆盖多租户并行时的数据隔离与路由隔离。

---

## 后续覆盖优化建议（按优先级）

## P0（建议优先补齐）

- 新增 `OutboxForwarderE2eIT`：不手动 publish，开启短轮询，验证 outbox 自动发布到消费成功。
- 为三条用例各补一个“失败分支”：
  - import：模板/字段不匹配，验证失败状态与错误记录落库
  - export：业务源缺失，验证失败与错误码
  - dispatch：无效 channel 或目标不可达，验证失败与回执/补偿状态

## P1（增强可信度）

- Export 增加“文件内容断言”：
  - 校验导出对象存在、行数、关键字段值
- Dispatch 增加“落地文件断言”：
  - 校验 LOCAL 路径文件存在、内容摘要、状态与审计记录一致

## P2（稳健性与规模）

- 并发测试：
  - 双 worker 同 topic 竞争 claim，确保只处理一次
  - 同 dedupKey 重复触发，确保幂等
- 多租户并行触发：
  - tenant A/B 同时运行，互不污染

---

## 建议的文档与测试组织方式

- 在 `batch-e2e-tests` 下新增分层：
  - `happy-path`（现有三条）
  - `failure-path`
  - `resilience`（重试、并发、幂等）
- 为每个 E2E 用例补“覆盖声明”注释（覆盖点/未覆盖点），防止后续误判覆盖范围。

---

## 当前结论

三个 E2E 测试已经覆盖了平台最核心的“触发 -> 调度 -> outbox -> Kafka -> worker -> 回报”主链路，能够作为主干回归门禁。

若目标提升到“生产风险防线”，下一步应优先补齐 outbox 自动轮询、失败重试/补偿、产物内容断言与并发幂等场景。
