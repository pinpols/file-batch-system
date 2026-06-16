# file-batch-system 核心概念与主链路

## 系统定位
批量任务编排控制面 + 文件/任务交付闭环。10 个 Maven 模块:trigger 触发 → orchestrator 派发 → workers 执行 → console-api 控制面。
**不是**数据治理 / 容器资源编排 / 合规审计平台。

## 主链路(状态流转的唯一路径)
`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- **Orchestrator 是唯一状态主机**:worker 不能直接写 `job_instance` / `workflow_run` / `workflow_node_run`。
- Worker 执行前**必须先 CLAIM**(领取);执行完通过 REPORT 上报结果,由 orchestrator 落状态。
- `outbox_event` 的写入**必须与任务状态同事务**(事务性发件箱,保证不丢事件)。

## Pipeline vs Workflow vs Job(三个不同概念,别混)
- **Pipeline** = 文件处理流水线(IMPORT/EXPORT/PROCESS/DISPATCH 固定 5-6 个 stage,内置不可扩展),数据在 `pipeline_*` 表,worker 内部记录,运维一般不介入。
- **Workflow** = 用户编排的 DAG(任意 Job 组合 + GATEWAY 分支 + 补偿 + 审批),数据在 `workflow_*` 表,支持人工干预。
- **Job** = 单个执行单元,数据在 `job_*` 表。
- 跨域引用是单向的:`workflow_node.related_pipeline_code → pipeline_definition.job_code`,不反向。

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
