# file-batch-system 核心概念与主链路

## 系统定位
批量任务编排控制面 + 文件/任务交付闭环。运行时主链是 trigger 触发 → orchestrator 派发 → workers 执行 → console-api 控制面。
平台运行时按 10 个逻辑模块理解;仓库还包含 SDK、examples、load-tests、security-scan、测试支撑等 Maven / 非 Maven 工程资产,不要把仓库 pom 数量当成运行时服务数量。
**不是**数据治理 / 容器资源编排 / 合规审计平台。

## 主链路(状态流转的唯一路径)
`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- **Orchestrator 是唯一状态主机**:worker 不能直接写 `job_instance` / `workflow_run` / `workflow_node_run`。
- Worker 执行前**必须先 CLAIM**(领取);执行完通过 REPORT 上报结果,由 orchestrator 落状态。
- `outbox_event` 的写入**必须与任务状态同事务**(事务性发件箱,保证不丢事件)。

## Pipeline vs Workflow vs Job(三个不同概念,别混)
- **Pipeline** = 文件处理流水线(IMPORT/EXPORT/PROCESS/DISPATCH 固定 stage 顺序),数据在 `pipeline_*` 表,worker 内部记录,运维一般不介入。Pipeline 顺序由平台控制,但固定步骤内部存在显式插件/策略点:Import load、Export data/format、Process compute、Dispatch channel adapter。
- **Workflow** = 用户编排的 DAG(任意 Job 组合 + GATEWAY 分支 + 补偿 + 审批),数据在 `workflow_*` 表,支持人工干预。
- **Job** = 单个执行单元,数据在 `job_*` 表。
- 跨域引用是单向的:`workflow_node.related_pipeline_code → pipeline_definition.job_code`,不反向。

## 插件 / SPI 边界
- 大任务类型 SPI 由 `BatchTaskExecutorRegistry` 按 `taskType()` 注册;同一个 taskType 重复注册会启动失败,不允许外部插件静默覆盖 `IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC` 主链。
- Worker 内部插件由配置 id、格式或渠道类型显式选择;不是"有插件就默认优先"。
- Dispatch 渠道适配器由 `DispatchChannelGateway` 在启动期构建 `channelType -> adapter` 注册表;同一官方渠道被多个 adapter 支持时启动失败,避免依赖 Spring bean 顺序接管真实渠道。
- 第三方插件只能实现明确扩展点,不能绕过 claim、lease、progress、report、dry-run 和幂等契约。

## 异步事件三张表(分工,不能互相复用)
- `outbox_event`:通用业务事件。
- `event_outbox_retry`:投递失败的退避重试。
- `trigger_outbox_event`:trigger fire → orchestrator launch 的调度事件。
判定:是 trigger fire 就进 `trigger_outbox_event`,否则进 `outbox_event`。

## 多租户隔离
所有业务表带 `tenant_id`;所有 UNIQUE/PRIMARY 约束含 `tenant_id`。幂等承重在全局 UNIQUE 上(如 `outbox_event(tenant_id,event_key)`、`job_instance(tenant_id,dedup_key,run_attempt)`)。

## 工作角色
- **trigger**:接收触发请求,fire 出调度事件。
- **orchestrator**:状态主机,派发分区/任务,处理 launch / 续租 / report / 补偿。
- **worker**(import/export/process/dispatch/atomic):CLAIM 后执行,REPORT 回上报。
- **console-api**:控制台后端,只读查询 + 运维代理(通过 orchestrator 内部接口操作,不直接改 outbox)。
