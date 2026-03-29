# dialog-8
控制台前后端联调收口与运行稳定性增强

本文档用于把“前端已按 OpenAPI 构建完成，但尚未做真实后端联调”的剩余工作拆成一轮可执行脚本，目标是把当前的 adapter fallback、契约待确认项和运行日志暴露出来的稳定性问题一次性收口。

## 当前基线

- 当前验收口径仍然是：
  - `npm run build` 通过
  - 页面可加载
  - 请求路径与 OpenAPI 对齐
- 本轮明确跳过真实联调，因此前端为了不阻塞构建，在若干查询场景里采用了“先全量拉取、再本地过滤 / 分页”的 fallback
- 从运行日志看，六个主服务已经能拉起：
  - `console`：`8080`
  - `trigger`：`8081`
  - `orchestrator`：`8082`
  - `worker-import`：`8083`
  - `worker-export`：`8084`
  - `worker-dispatch`：`8085`
- 但日志同时暴露了几类不应在联调阶段继续忽略的问题：
  - `orchestrator` 周期性报 `failed to ensure minio bucket: batch-dev`
  - `worker-import` 周期性报 `failed to ensure import scanner bucket`
  - `worker-import` / `worker-export` 出现 heartbeat 上报失败
  - `worker-import` / `worker-export` / `worker-dispatch` 有零星 `DisconnectException`
  - 多服务存在 Hikari `Thread starvation or clock leap detected`
- 这些问题不阻塞前端页面构建，但会直接影响联调结论、稳定性判断和后续验收可信度

## 本轮收口的后端待确认项

以下 10 项保留为联调 checklist，前端当前实现已做 fallback 或保守处理；其中第 4 轮已经把下载、审批、执行日志和告警动作收口成明确契约：

1. 列表分页：`GET .../query/*` 目前多为仅 tenantId 查询，需确认是否补服务端分页和过滤
2. Job / Workflow 详情：需确认是否补 `instances/{id}`、`workflow-runs/{id}` 这类 detail 接口
3. `job-step-instances` / `workflow-node-runs`：需确认是否补 detail / filter，而不是前端全量查找
4. Excel multipart：需确认 `file` 字段名、`uploadToken` 和 preview / apply 路径
5. 报表下载：需确认 blob 与 JSON 错误体的 `Content-Type` 约定
6. 审批状态枚举：需确认终态是否稳定为 `APPROVED` / `REJECTED` / `CLOSED` / `CANCELLED`
7. 执行日志：当前以前端 `query/audits` 代替，需要确认是否存在专用 execution-log API
8. 告警治理：需确认 confirm / silence / close 等 command 路径
9. 调度快照：已统一为 `ConsoleSchedulerSnapshotResponse.policies / queues / workers`
10. `query/workflow-topology`：已统一为 `ConsoleWorkflowTopologyResponse`

第 4 轮已完成的收口项：

- 报表下载成功返回 raw bytes，失败返回标准 JSON 错误 envelope
- 审批终态枚举固定为 `PENDING / APPROVED / REJECTED / EXECUTED`
- 执行日志新增 `GET /api/console/query/execution-logs`，作为 `audits` 的别名视图
- 告警动作新增 `ack / silence / close` 命令路径

## 第1轮：查询契约收口

- 用户：先把所有 `query/*` 列表接口的分页、过滤、排序契约定死。
- 我：会逐个核对前端现在依赖的查询接口，统一成服务端分页契约，收掉前端 adapter 的本地分页 fallback。
- 本轮分页决策：
  - 控制台主列表统一采用手写分页 SQL，不引入分页插件
  - 请求统一采用 `pageNo / pageSize / sortBy / sortOrder + filters`
  - 响应统一采用 `CommonResponse<PageResponse<T>>`
  - Mapper 层统一补 `countByQuery + selectByQuery(limit/offset)`，由 service 组装分页结果
  - `limit` 仅保留给内部批处理、导出或轻量上限查询，不再作为控制台主列表分页协议
- 这样定的原因：
  - 现有仓库已经有 `PageRequest / PageResponse` 基础模型
  - 查询条件普遍带租户、状态、时间区间、traceId，不适合再引一层隐式分页插件改写 SQL
  - 手写分页 SQL 对 count、排序和多租户过滤更可控，联调时更容易定位问题
- 验收：
  - 前端不再在 adapter 层偷偷做大列表分页
  - OpenAPI 能明确哪些列表是分页接口，哪些是轻量字典接口
  - 控制台分页口径不再混用 `PageResponse`、`limit` 和前端本地分页
- 重点对象：
  - job instances
  - workflow runs
  - job step instances
  - workflow node runs
  - approval list
  - alert list
- 交付：回写控制台 OpenAPI 和联调说明
- 状态：已完成，`query/*` 主列表已统一为服务端分页，详情接口也已补齐

## 第2轮：详情接口补齐

- 用户：把目前依赖“列表里查一条”的详情页全部收口成稳定 detail 接口。
- 我：会补或确认以下 detail 契约：
  - `job instances/{id}`
  - `workflow runs/{id}`
  - `job step instances/{id}`
  - `workflow node runs/{id}`
- 验收：
  - 前端详情页不再依赖全量列表扫描
  - 大数据量时详情页打开速度不受列表规模影响
- 交付：OpenAPI、DTO、前端路由对照表
- 状态：已完成四个核心详情接口

## 第3轮：Excel 上传 / 预览 / 应用协议确认

- 用户：把 Excel 相关 controller 路径和 multipart 契约定死，避免联调时前后端互猜。
- 我：会核对并固定：
  - multipart 字段名是否统一为 `file`
  - `uploadToken` 的返回位置和生命周期
  - `preview` / `apply` / `export` 的路径和 request body
  - 失败时的错误结构
- 验收：
  - 前端上传链路不再靠魔法常量兜底
  - Excel 预览错误可直接映射页面提示
- 交付：Excel 协议说明、OpenAPI 回写、前端 adapter TODO 清单
- 状态：已完成，四条 Excel 流程的 `file / uploadToken / apply` 协议已和 controller / OpenAPI 对齐

## 第4轮：下载、审批、执行日志、告警动作收口

- 用户：把“能查不能做”或“只能猜状态”的页面动作补成可执行闭环。
- 我：会把以下项收口成明确契约并落到后端：
  - 报表下载成功返回 raw bytes，失败仍走 JSON 错误 envelope
  - 审批终态枚举固定为 `PENDING / APPROVED / REJECTED / EXECUTED`
  - execution-log 以 `query/execution-logs` 作为 `audits` 的别名视图
  - alert confirm / silence / close command 路径补齐
- 验收：
  - 前端不再硬编码审批终态集合
  - 执行日志页不再拿 audit 假装日志
  - 告警页可从只读列表演进到最小治理闭环
- 交付：命令接口清单、状态枚举表、错误体约定
- 状态：已完成

## 第5轮：调度快照与工作流拓扑格式统一

- 用户：把当前“能展示但格式不统一”的调度和拓扑数据定成长期契约。
- 我：已经统一为：
  - `ConsoleSchedulerSnapshotResponse` 继续以 `policies / queues / workers` 作为展示主对象
  - `query/workflow-topology` 返回 `ConsoleWorkflowTopologyResponse`，字段固定为 `workflowDefinition / nodes / edges / workflowRuns / nodeRuns`
- 验收：
  - `QuotaPanel` / `QueueConfig` 无需前端猜字段
  - Designer 不再同时维护两套 DAG 输入格式
- 交付：拓扑 DTO、调度快照 DTO、前端组件数据映射说明
- 状态：已完成

## 第6轮：真实联调清单

- 用户：把“文档记录，联调时核对”的事项变成一张可执行清单。
- 我：会输出按页面分组的联调 checklist：
  - 首页 / 运营总览
  - Job / Workflow 查询
  - Worker 运维
  - Excel 导入导出
  - 审批 / 告警
  - 调度快照 / 拓扑
- 验收：
  - 每个页面都有请求、返回、错误、权限、空态、分页的核对项
  - 联调结果可以打勾，不再靠聊天记录零散回忆
- 交付：联调 checklist、问题清单模板、回归命令
- 状态：未开始

## 第7轮：联调阻塞项优先修复

- 用户：联调时先修真正阻塞页面验收的项，不要把风格问题和 blocker 混在一起。
- 我：会把问题分成三类：
  - P0：接口不存在 / 形状错 / 权限错 / 下载上传失败
  - P1：服务端无分页、detail 缺失导致性能差
  - P2：枚举命名、字段别名、展示口径不一致
- 验收：
  - P0 在本轮清零
  - P1 有明确后续排期
  - P2 通过字段映射或文档说明短期消化
- 交付：联调问题分级表、修复优先级、回归结果
- 状态：未开始

## 第8轮：运行稳定性增强

- 用户：把日志里已经暴露的问题先收口，否则联调结论不可信。
- 我：会重点处理：
  - `orchestrator` 的 MinIO bucket 自检失败
  - `worker-import` 的 import scanner bucket 自检失败
  - worker heartbeat 上报失败时的容错与告警
  - Kafka `DisconnectException` 的频率、恢复策略和告警
  - Hikari `Thread starvation or clock leap detected` 的环境归因
- 验收：
  - 周期调度不再持续刷 bucket 初始化错误
  - worker heartbeat 失败不再无提示沉没
  - 日志中的高频噪音有归因，有处理策略
- 重点日志：
  - `/Users/dengchao/Downloads/file-batch-system_副本/logs/orchestrator.log`
  - `/Users/dengchao/Downloads/file-batch-system_副本/logs/worker-import.log`
  - `/Users/dengchao/Downloads/file-batch-system_副本/logs/worker-export.log`
  - `/Users/dengchao/Downloads/file-batch-system_副本/logs/worker-dispatch.log`
  - `/Users/dengchao/Downloads/file-batch-system_副本/logs/console.log`
  - `/Users/dengchao/Downloads/file-batch-system_副本/logs/trigger.log`
- 交付：稳定性问题清单、根因假设、修复顺序和观测指标
- 这轮实际落地的收口：
  - MinIO bucket 自检失败改为带冷却时间的 best-effort 检查，失败时调度直接跳过本轮，不再每次刷堆栈
  - `worker heartbeat` 和 `shutdown` 失败改为降级告警，避免网络抖动把进程日志打满
  - `worker shutdown` 现在会在状态同步失败时仍然清理本地 runtime state
  - Hikari 的 `Thread starvation or clock leap detected` 仍然保留为环境噪音归因项，不在本轮强行改业务逻辑
- 状态：已完成

## 建议执行顺序

1. 先做第1到第4轮，先把真正阻塞前端联调的契约定死
2. 再做第5到第7轮，形成页面级联调闭环
3. 第8轮并行推进，不要等前端联调全部完成后才看运行稳定性

## 当前建议

- 如果下一步仍然不做真实联调，就先只补第1轮到第5轮的文档和 OpenAPI
- 如果下一步开始联调，优先拉通：
  - 列表分页 / detail
  - Excel upload / preview / apply
  - 审批状态枚举
  - 执行日志专用接口
  - 告警 command 路径
- 同时把 MinIO bucket 初始化失败和 worker heartbeat 失败列为联调前置稳定性问题，不建议继续忽略
