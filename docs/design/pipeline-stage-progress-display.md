# Pipeline Step 进度展示与 SSE 刷新设计

> 2026-10-04 运行时并发语义复核：内置 Worker 已从进程级单槽升级为 task/pipeline/stage 隔离的注册表，控制面按 pipeline 与 stage 聚合并发分片；系统首次上线统一使用当前协议，不保留 SDK 标量字段和 workerCode 查询兼容入口。

## 1. 目标

Pipeline 进度只解决一个问题：长时间运行的文件处理 step 到底有没有继续推进，已经处理了多少，是否疑似卡住。

本设计不追求给整条 pipeline 做统一百分比，也不把每一行进度都实时推给前端。2026-10-25 需求调整：进度查询以服务端主动推送安全完整快照为主，初始/重连/重置时补查；浏览器在线时不因每个 dirty 事件重复查询。当前Job阶段与Pipeline观测页面均已接入完整快照，真实Worker与恢复证据按配对前端实施矩阵分别记录。

## 2. 设计原则

| 原则 | 决策 |
|---|---|
| 数据真相源 | Worker 执行中的进度来自 SDK `ProgressReporter` / step 内部进度上报；平台查询以 `pipelineInstanceId` 聚合 step 快照 |
| 查询契约 | Console 公开接口按 `pipelineInstanceId` 查询，不再按 `tenantId + workerCodes` 作为前端主路径 |
| SSE 定位 | 低频、订阅驱动、同租户同对象共享采样并推送安全完整快照；不按行或 chunk 推送。旧 dirty signal仅保留兼容入口，当前前端主页面在线不再逐事件GET |
| 降级能力 | 没有进度、没有总量、SSE 断开、心跳变旧时，页面仍能用轮询展示状态和 `—` |
| 展示口径 | 只在有行数语义的 step 展示 processed / total / ETA；原子任务不制造假进度 |
| 安全边界 | 进度 payload 不允许包含文件内容、SQL、请求头、密钥、下游响应正文等敏感信息 |

## 3. 数据流

```text
Worker step
  -> SDK ProgressReporter / PipelineStageProgressRegistry
  -> heartbeat.pipelineProgress[]
     {taskId, pipelineInstanceId, stageCode, rowsProcessed, totalRowsHint}
  -> orchestrator task-aware progress cache
  -> GET /internal/pipeline-progress/by-pipeline
  -> console-api 完整快照 /api/console/pipeline-progress + /events
  -> FE FilePipelineObservability（在线直接应用安全完整快照）
  -> Job详情按精确步骤/尝试身份复用实时补值
```

说明：

1. 运行态缓存按 `tenantId + pipelineInstanceId + taskId + stageCode + workerCode + partitionInvocationId + pipelineStepRunId` 隔离，同一 Worker 并发执行多个分片时不会相互覆盖。
2. 控制面按 `pipelineInstanceId + stageCode` 聚合当前分片；`rowsProcessed` 求和，只有所有分片都提供 total 时才汇总 `totalRowsHint`。
3. `pipeline_progress` 是 checkpoint / resume 的持久真相源；节点本地缓存只补充实时观测，5 分钟未刷新即过期，不承担恢复语义。
4. 内部查询在同一租户只读事务快照核对 Job/Task/Partition/Pipeline 状态、Worker 归属、分区认领与有效租约、阶段最新尝试。失租、终态、dry-run 和身份缺失样本不补实时值。旧进程空心跳不删除带身份的新样本；同一次执行迟到的较小计数不回退，旧样本保留原时间戳，TTL 后清除。
5. heartbeat 频率天然是秒级到几十秒级，不适合做毫秒级动画。
6. 删除 Java/Python SDK 的进程级标量上报、全局进度 sink 及 workerCode 查询。BYO SDK 不伪造 pipeline 标识；通用任务进度回调保持独立，不等同于文件 pipeline 的实时行数。
7. PROCESS未写文件checkpoint时，Console可从对应成功阶段尝试的正式`output_summary`补充持久计数：COMMIT读取实际`publishedCount`，其余阶段读取`processedCount`。仅接收非负Long范围整数，字符串、负数、溢出及缺失保持未知，真实0保留0；不返回原摘要内容。历史尝试与最新尝试分别投影，不把旧成功计数填到新运行尝试，也不以输入条数推算发布条数。

## 4. SSE 刷新策略

### 4.1 当前实现与目标边界

Workflow运行与租户业务日查询已提供完整快照 `/events`：服务端每2秒对被订阅对象共享采样，变化才推送，无订阅停止。前端共用范围隔离和迟到读保护，断线低频补查。Job阶段与Pipeline页面现已接入完整快照；真实服务+SQL状态夹具已验证Pipeline传输、授权及无在线周期GET，真实Worker业务链仍须单独验收，不能用这些证据替代。

### 4.2 既有 dirty SSE

现有 `job-instances` SSE 继续保留，职责是通知作业状态变化。前端收到 `job-instance-updated` 后做防抖刷新，不直接拿 SSE payload 当页面数据。

### 4.3 既有 Pipeline 进度事件

低频 `pipeline-progress-dirty` 事件已存在；Pipeline Observability 页面当前使用完整快照流，dirty 事件供订阅者按需触发快照查询：

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

1. 既有 dirty 流不带计数；新的完整快照流可带安全的 `rowsProcessed`、`totalRowsHint` 和状态，不携带错误堆栈、文件路径或下游响应正文。
2. 服务端按 `tenantId + pipelineInstanceId + reason` 做节流，建议 5 到 10 秒最多发一次 dirty event。
3. 前端收到事件后防抖 400ms 到 2s 调 `loadProgress()`，不要每个事件立即打查询。
4. SSE 断开时继续保留 30 秒轮询，重连使用指数退避。

当前低频 dirty 发布器按 `updated_at, tenant_id, pipeline_instance_id` 顺序分页，避免同一更新时间超过单页上限时遗漏后续实例。发布端口成功返回后才登记该更新并推进扫描游标；发布异常或尚在节流窗口的更新保留在当前扫描位置，下一轮继续尝试。dirty 事件仍只提示重新读取快照，不代表前端已经收到或展示了更新；Pipeline 页面当前使用完整快照流。

### 4.4 为什么不做高频 SSE 进度

| 方案 | 不采用原因 |
|---|---|
| 每 chunk 推 SSE | 高压导入 / 导出会把控制面变成进度消息通道，影响真正调度链路 |
| WebSocket 双向进度 | 当前没有前端控制 worker 的需求，复杂度高于收益 |
| 每行写入数据库再查 | 写放大明显，且进度是运行态观测，不是审计事实 |
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
| IMPORT `LOAD` | 是 | `taskId`, `pipelineInstanceId`, `stageCode`, `rowsProcessed`, `totalRowsHint` | 分批 flush 后上报；总量未知时只显示 processed |
| EXPORT `GENERATE` | 是 | `taskId`, `pipelineInstanceId`, `stageCode`, `rowsProcessed`, `totalRowsHint` | 分页生成时上报；并发分片由控制面聚合 |
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

1. `/api/console/pipeline-progress` 与 `/events` 返回已授权租户/ID集合的完整状态与各阶段最新尝试，跨租户访问被拒绝；旧单实例查询保留兼容，历史尝试由分页接口提供。
2. FilePipelineObservability 在开启进度列时能展示 IMPORT / EXPORT 的 processed / total / stale 状态。
3. 没有进度数据的 PROCESS / DISPATCH / ATOMIC step 页面不报错，显示 `—` 或 heartbeat details。
4. 完整快照流在线直接更新页面；初始、重连与重置补查，断线后保留最后快照并标注过期风险，降级补查有退避且恢复推送后停止。旧 dirty 路径的验证不能冒充目标已迁移。
5. 高压任务下完整快照按被订阅范围共享、变化才推送，无订阅停止，不因进度刷新影响调度、lease renew、heartbeat 主链路；旧dirty节流不等于本项已经验收。
6. 进度 payload 通过敏感字段扫描，不出现 secret、token、password、authorization、文件正文等字段。

## 9. 落地计划

| 优先级 | 事项 | 状态 |
|---|---|---|
| P0 | 公开查询契约改为 `pipelineInstanceId`，OpenAPI / API protocol 对齐 | 已完成 |
| P0 | FilePipelineObservability 直接消费完整推送，初始/失联才补查 | 已迁移并做真实传输联调 |
| P1 | Pipeline页直接应用完整快照；旧dirty端点保留兼容 | 已迁移并做真实传输联调 |
| P1 | 后端增加低频 `pipeline-progress-dirty` 事件，按 pipeline 节流 | 已做 |
| P1 | 内置 Worker 任务级进度隔离、并发分片聚合、删除旧 workerCode 查询 | 已完成 |
| P1 | PROCESS copy / aggregate 有稳定行数时接入 `ProgressReporter` | 待做 |
| P2 | JobInstanceDetail Steps 能通过 job step 映射到 pipeline step 后展示进度列 | 待做 |
| P2 | 增加前端单测：无 total、stale、终态、SSE 断开降级 | 待做 |
| P2 | 增加后端测试：并发任务隔离、分片聚合、心跳清理、移除旧协议回退 | 已完成 |
| P2 | 增加后端测试：跨租户公开查询、终态快照、敏感字段过滤 | 待做 |
| P3 | 增加观测指标：SSE 连接数、dirty event drop / throttle、stale progress 数量 | 待做 |

## 10. 不做项

1. 不做全局 pipeline 百分比。
2. 不做每行、每 chunk 的 SSE 推送。
3. 不让前端按 `workerCode` 查询运行中进度作为主路径。
4. 不为了展示效果给 SQL / HTTP / SHELL 这类原子任务编造百分比。
5. 不把 progress 明细写入 outbox，避免控制面消息放大。

## 2026-10-25 WDI 作业详情承接进展

新增 `GET /api/console/job-instances/{id}/execution-progress` 和 `/events`，完整快照按租户/Job隔离，复用订阅引用计数采样；在线直接推送阶段与分区/任务计数，首次/重连读取，失联才退避查询。后台读取显式设置事务RLS租户，不携带业务载荷、参数快照或凭据。

阶段顺序只采用运行实例 `system_pipeline_code/version`，尚无Pipeline时采用本次Workflow执行快照内的冻结标识；不读取当前Job定义。版本未知或出现目录之外的阶段时保留实际阶段、明确图不可核实，不补造阶段。阶段重试按stage_code、run_seq和id选择最新尝试；缺执行记录在活跃Job下为PENDING，Job终态为NOT_EXECUTED，不计作成功。

作业详情将平台阶段图、分区与已创建技术任务分母分开展示，另嵌入所属Workflow冻结DAG作为依赖上下文。一个Job实例至多一个Pipeline由V124唯一索引保证，不能把同作业内多个技术任务误称为各自独立Pipeline。当批处理行数只读持久化checkpoint，旧页面迁移及cache补充尚未完成；后续变化见下一节。新Job接口仍不能据此宣称真实Worker全链路验收完成。


## 2026-10-25 Pipeline完整快照落地

新增 `/api/console/pipeline-progress` 与 `/events`，客户端范围为当前页Pipeline ID集合（1至100个、去重排序），租户/对象范围授权后共享变化采样。完整快照提供运行状态、运行固定平台阶段目录、最新阶段尝试及安全行数；历史尝试仍由分页接口提供，不将新尝试插入旧页破坏分页。未知版本不使用当前字典补图。

前端移除dirty+GET与固定轮询，在线直接应用完整快照；初始/重连/reset读取，失联退避查询。运行列表、阶段概览和表格状态同步；阶段表处理行数/总量未知时保持空值。单实例旧查询仅保留兼容；当前页面文件摘要同样从完整快照读取。

Job实时补值匹配stepRunId与runSeq，仅在Job和步骤都未终态、步骤运行且持久计数为空时应用；Job或Pipeline根已终态时不吸收迟到缓存。持久值不被缓存覆盖，旧尝试和已完成阶段不补实时值。真实Worker与故障恢复业务验收仍未据此关闭。

已处理行数或总量未知时ETA为空，不能将null当作0；精确Pipeline深链在运行/阶段标签间保留ID过滤，不用模糊keyword关联历史实例。


### 源认领围栏与 Workflow 阶段联动（2026-10-25）

内置 Worker 从原始执行上下文附带分区认领与阶段执行 ID，不在心跳发送时借用新认领身份。Orchestrator 先通过有效认领和当前阶段尝试过滤，再按阶段聚合。旧版缺身份样本降级为无实时补值，持久 checkpoint 仍正常展示。空心跳不能证明同名进程的认领归属，因此不据此删除带身份缓存；数据库过滤决定能否展示，TTL 决定缓存保留期限。

Workflow 文件节点的当前步骤优先取关联 Pipeline 的实际运行阶段，按 step_code 排除旧尝试；有 Pipeline 但无运行阶段时不回退到技术任务别名。无 Pipeline 的普通作业继续使用任务步骤。图表承接现有运维总览、作业详情与 Workflow 运行详情，展示层不自行推断状态或未知分母百分比。

内部进度响应附带本次聚合采用的 `stepRunIds`；Console 与 Orchestrator 不共享数据库快照，Console 只有在来源包含自身持久步骤 ID 时才补计数和总量。来源身份缺失或读取期间已切换尝试时保留未知，等待下次快照。该保护不把多服务读取包装为分布式原子快照。
