# Pipeline Step 进度展示与 SSE 刷新设计

> 2026-06-08 上线前设计收口。第 3 项公开查询契约已经按 `pipelineInstanceId` 修正，本设计只覆盖剩余的展示策略、SSE 刷新边界、Worker 支持矩阵和后续落地计划。

## 1. 目标

Pipeline 进度只解决一个问题：长时间运行的文件处理 step 到底有没有继续推进，已经处理了多少，是否疑似卡住。

本设计不追求给整条 pipeline 做统一百分比，也不把每一行进度都实时推给前端。前端展示的是快照，SSE 只负责告诉页面“有状态变化，可以提前刷新”。

## 2. 设计原则

| 原则 | 决策 |
|---|---|
| 数据真相源 | Worker 执行中的进度来自 SDK `ProgressReporter` / step 内部进度上报；平台查询以 `pipelineInstanceId` 聚合 step 快照 |
| 查询契约 | Console 公开接口按 `pipelineInstanceId` 查询，不再按 `tenantId + workerCodes` 作为前端主路径 |
| SSE 定位 | SSE 是低频 dirty signal，不承载 `rowsProcessed` 明细，不做高频进度数据面 |
| 降级能力 | 没有进度、没有总量、SSE 断开、心跳变旧时，页面仍能用轮询展示状态和 `—` |
| 展示口径 | 只在有行数语义的 step 展示 processed / total / ETA；原子任务不制造假进度 |
| 安全边界 | 进度 payload 不允许包含文件内容、SQL、请求头、密钥、下游响应正文等敏感信息 |

## 3. 数据流

```text
Worker step
  -> SDK ProgressReporter / PipelineStageProgressSink
  -> heartbeat / lease renew details
  -> orchestrator progress cache / pipeline_progress
  -> console-api GET /api/console/queries/pipeline-progress?pipelineInstanceId=
  -> FE FilePipelineObservability / detail page snapshot
```

说明：

1. `pipeline_progress` 可以作为 checkpoint / resume / 查询补偿来源，但前端不应直接依赖数据库轮询。
2. heartbeat 频率天然是秒级到几十秒级，不适合做毫秒级动画。
3. Console 查询返回的是当前 step 快照。历史吞吐、ETA、stale 判断可以在前端按最近几次快照轻量计算。

## 4. SSE 刷新策略

### 4.1 保留现有 SSE

现有 `job-instances` SSE 继续保留，职责是通知作业状态变化。前端收到 `job-instance-updated` 后做防抖刷新，不直接拿 SSE payload 当页面数据。

### 4.2 Pipeline 进度事件

如果后续要让 Pipeline Observability 页面更及时，可以增加一个轻量事件：

| 字段 | 说明 |
|---|---|
| `event` | `pipeline-progress-dirty` |
| `tenantId` | 租户隔离校验用，不跨租户广播 |
| `pipelineInstanceId` | 前端刷新该 pipeline 的进度快照 |
| `jobInstanceId` | 可空；由 job 触发的 pipeline 可带上 |
| `reason` | `STEP_PROGRESS` / `STEP_STATUS` / `HEARTBEAT_STALE` 等枚举 |
| `version` | 可空递增版本，用于前端去重 |
| `updatedAt` | 服务端事件时间 |

约束：

1. 不在 SSE 中放 `rowsProcessed`、`totalRowsHint`、错误堆栈、文件路径、下游响应等明细。
2. 服务端按 `tenantId + pipelineInstanceId + reason` 做节流，建议 5 到 10 秒最多发一次 dirty event。
3. 前端收到事件后防抖 400ms 到 2s 调 `loadProgress()`，不要每个事件立即打查询。
4. SSE 断开时继续保留 30 秒轮询，重连使用指数退避。

### 4.3 为什么不做高频 SSE 进度

| 方案 | 不采用原因 |
|---|---|
| 每 chunk 推 SSE | 高压导入 / 导出会把控制面变成进度消息通道，影响真正调度链路 |
| WebSocket 双向进度 | 当前没有前端控制 worker 的需求，复杂度高于收益 |
| 每行落库再查 | 写放大明显，且进度是运行态观测，不是审计事实 |
| 全局 0-100% 进度条 | step 工作量不可比，容易出现前 80% 很快、最后 20% 长时间卡住的误导 |

## 5. 前端展示边界

| 页面 | 展示策略 | 说明 |
|---|---|---|
| JobInstance 列表 | 不展示 step 行级进度 | 列表只保留实例状态、耗时、失败摘要，避免重查询 |
| JobInstanceDetail Steps | 展示 step 状态、时间、错误；进度列默认可选 | 只有能定位到 `pipelineInstanceId + stepId` 时才展示 processed / total，否则显示 `—` |
| JobInstanceDetail Heartbeat | 展示当前 task heartbeat details | 适合所有长任务，包括没有行数语义的 atomic / dispatch |
| FilePipelineObservability Steps | 主展示面 | 按 pipeline instance 查询 step 进度，展示 processed / total / stale / ETA |
| Worker 看板 | 不展示每个 step 进度 | 只展示 worker 在线、负载、当前任务摘要，避免 workerCode 查询回潮 |

## 6. Worker / Step 支持矩阵

| 类型 | 是否展示行级进度 | 推荐字段 | 备注 |
|---|---|---|---|
| IMPORT `LOAD` | 是 | `rowsProcessed`, `totalRowsHint`, `lastHeartbeatAt` | 分批 flush 后上报；总量未知时只显示 processed |
| EXPORT `GENERATE` | 是 | `rowsProcessed`, `totalRowsHint`, `lastHeartbeatAt` | 流式写文件时上报；total 可由查询 count / planner hint 提供 |
| PROCESS `COMPUTE` / `COMMIT` | 可选 | `rowsProcessed`, `totalRowsHint` | 只有 copy / aggregate / 插件能给出稳定行数时才上报 |
| DISPATCH | 默认否 | receipt / checksum / retry / channel status | 分发更关心远端回执和完整性，不强行显示行数 |
| ATOMIC SQL / HTTP / SHELL / STORED_PROC | 默认否 | heartbeat details JSON | 原子任务不制造百分比；业务 handler 可选择上报自定义 progress |
| TRIGGER / ORCHESTRATOR 内部任务 | 否 | status event / backlog / lag | 这类是控制面状态，不归 pipeline step 进度展示 |

## 7. 状态与降级

| 场景 | 页面行为 |
|---|---|
| `rowsProcessed == null` | 显示 `—`，不显示进度条 |
| `totalRowsHint == null` | 显示 processed 计数，不计算百分比和 ETA |
| `rowsProcessed > totalRowsHint` | 显示计数，百分比上限 clamp 到 100%，并记录前端异常埋点 |
| `lastHeartbeatAt` 超过 90 秒 | 显示 stale / 心跳过旧，不直接判失败 |
| step 已终态 | 保留最后一次快照；成功态可显示 100%，失败态显示最后 processed |
| SSE 断开 | 页面继续轮询，状态栏可显示连接恢复中 |

## 8. 验收标准

1. `GET /api/console/queries/pipeline-progress?pipelineInstanceId=` 返回同一 pipeline 下所有 step 的快照，跨租户访问被拒绝。
2. FilePipelineObservability 在开启进度列时能展示 IMPORT / EXPORT 的 processed / total / stale 状态。
3. 没有进度数据的 PROCESS / DISPATCH / ATOMIC step 页面不报错，显示 `—` 或 heartbeat details。
4. SSE 事件只触发刷新，不携带行级明细；关闭 SSE 后 30 秒轮询仍能看到进度变化。
5. 高压任务下 SSE dirty event 被节流，不因进度刷新影响调度、lease renew、heartbeat 主链路。
6. 进度 payload 通过敏感字段扫描，不出现 secret、token、password、authorization、文件正文等字段。

## 9. 落地计划

| 优先级 | 事项 | 状态 |
|---|---|---|
| P0 | 公开查询契约改为 `pipelineInstanceId`，OpenAPI / API protocol 对齐 | 已完成 |
| P0 | FilePipelineObservability 以轮询方式读取 step 进度快照 | 已具备 |
| P1 | SSE 收到 `pipeline-progress-dirty` 后触发 `loadProgress()` 防抖刷新 | 已做 |
| P1 | 后端增加低频 `pipeline-progress-dirty` 事件，按 pipeline 节流 | 已做 |
| P1 | PROCESS copy / aggregate 有稳定行数时接入 `ProgressReporter` | 待做 |
| P2 | JobInstanceDetail Steps 能通过 job step 映射到 pipeline step 后展示进度列 | 待做 |
| P2 | 增加前端单测：无 total、stale、终态、SSE 断开降级 | 待做 |
| P2 | 增加后端测试：跨租户查询、空进度、终态快照、敏感字段过滤 | 待做 |
| P3 | 增加观测指标：SSE 连接数、dirty event drop / throttle、stale progress 数量 | 待做 |

## 10. 不做项

1. 不做全局 pipeline 百分比。
2. 不做每行、每 chunk 的 SSE 推送。
3. 不让前端按 `workerCode` 查询运行中进度作为主路径。
4. 不为了展示效果给 SQL / HTTP / SHELL 这类原子任务编造百分比。
5. 不把 progress 明细写入 outbox，避免控制面消息放大。
