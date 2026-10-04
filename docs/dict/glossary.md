# 业务术语字典

> 跨团队 / 新人 / 外部对接的术语统一口径。每条 1-2 句定义 + 链到详细设计。

## 调度与编排

| 术语 | 定义 | 详见 |
|---|---|---|
| **job** | 一个可被触发执行的业务任务定义。配置在 `job_definition` 表。 | [`../design/database-schema-guide.md`](../design/database-schema-guide.md) |
| **job_instance** | job 的一次执行根记录；普通作业和工作流作业都先创建它。状态值以 `JobInstanceStatus` 为准，包含暂停态和 dry-run 终态。 | [`../architecture/core-model.md`](../architecture/core-model.md) |
| **partition** | job_instance 的分片单元。一个 job_instance 切成 N 个 partition 并行执行。 | [`../architecture/adr/ADR-005-partition-count-resolver-chain.md`](../architecture/adr/ADR-005-partition-count-resolver-chain.md) |
| **task** | Worker 实际领取、执行和回报的工作项。task 必属于 job_instance，通常关联 partition；补偿、重放等特殊任务允许不关联 partition。 | [`../architecture/core-model.md`](../architecture/core-model.md) |
| **step_instance** | 面向步骤状态、审计和控制台展示的运行镜像；它引用 task，可选引用 partition，不是 Worker 内部 stage 的持久化副本。 | [`../architecture/core-model.md`](../architecture/core-model.md) §3.7 |
| **schedule_type** | job_definition 的调度表达方式，由 `ScheduleType` 定义：<!-- enum-sync:ScheduleType:start -->`CRON`, `FIXED_RATE`, `MANUAL`<!-- enum-sync:ScheduleType:end -->。它不表示一次请求从哪里发起。 | [`ScheduleType.java`](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/ScheduleType.java) |
| **trigger_type** | 一次 trigger_request / job_instance 的触发来源，由 `TriggerType` 定义：<!-- enum-sync:TriggerType:start -->`API`, `MANUAL`, `EVENT`, `CATCH_UP`, `SCHEDULED`, `RERUN`<!-- enum-sync:TriggerType:end -->。它与 schedule_type 是两个维度。 | [`TriggerType.java`](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/TriggerType.java) |
| **launch** | 触发请求转化为 job_instance 的动作（trigger → orchestrator）。详见 ADR-003 T1/T2 拆分。 | [`../architecture/adr/ADR-003-launch-t1-t2-split.md`](../architecture/adr/ADR-003-launch-t1-t2-split.md) |
| **claim** | Worker 收到 Kafka 派发消息后，向 Orchestrator 发起的任务所有权转换。Orchestrator 通过状态 CAS、目标 Worker、partition invocation 和 lease 校验决定是否准许执行；它不是本地悲观锁。 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §2 |
| **dispatch** | 文件分发动作（worker-dispatch 把生成好的文件推到外部渠道：SFTP / API / OSS / Email 等）。 | [`../design/file-pipeline-design.md`](../design/file-pipeline-design.md) |

## 工作流

| 术语 | 定义 | 详见 |
|---|---|---|
| **workflow** | 可包含任务、文件步骤、等待和子作业节点的 DAG。配置在 `workflow_definition` + `workflow_node` + `workflow_edge`。 | [`../architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) |
| **workflow_run** | workflow 的一次执行视图，关联触发它的根 job_instance；其直接子记录是 workflow_node_run，不是 partition。 | [`../architecture/core-model.md`](../architecture/core-model.md) |
| **workflow_node_run** | 某个 DAG 节点在 workflow_run 内的一次运行记录，按 `(workflow_run_id, node_code, run_seq)` 区分重试序列。 | 同上 |
| **node** | DAG 节点类型由 `WorkflowNodeType` 定义：<!-- enum-sync:WorkflowNodeType:start -->`TASK`, `GATEWAY`, `FILE_STEP`, `START`, `END`, `JOB`, `WAIT`<!-- enum-sync:WorkflowNodeType:end -->。`JOB` 节点会拉起独立子 job_instance，`WAIT` 节点等待外部条件。 | 同上 |
| **edge** | DAG 边类型由 `WorkflowEdgeType` 定义：<!-- enum-sync:WorkflowEdgeType:start -->`SUCCESS`, `FAILURE`, `CONDITION`, `ALWAYS`<!-- enum-sync:WorkflowEdgeType:end -->。 | 同上 |
| **GATEWAY** | 网关节点，按 join_mode 汇聚多个前驱，不直接执行 Worker 任务。 | 同上 |
| **join_mode** | 网关汇聚策略，由 `WorkflowJoinMode` 定义：<!-- enum-sync:WorkflowJoinMode:start -->`ALL`, `ANY`, `N_OF`<!-- enum-sync:WorkflowJoinMode:end -->；N_OF 的阈值由节点参数提供。 | 同上 |

## 文件链路

| 术语 | 定义 | 详见 |
|---|---|---|
| **file_record** | 文件流转主表。一行 = 一次文件接收 / 处理 / 分发。 | [`../design/file-pipeline-design.md`](../design/file-pipeline-design.md) |
| **biz_date** | 业务日期。批量系统的核心时间维度，跟自然日不一定相等（节假日 / 周末 / 调休）。 | [`../design/batch-day-design.md`](../design/batch-day-design.md) |
| **batch_day** | 某租户、某业务日历、某 biz_date 的批次日实例，唯一键是 `(tenant_id, calendar_code, biz_date)`；同一天不同日历不会共享实例。 | 同上 |
| **batch_window** | 批量窗口。某段时间允许某类任务执行；窗外任务挂起或拒绝。 | 同上 |
| **arrival** | 文件到达。INBOUND 文件落到 file_record 的瞬间。 | [`../design/sla-and-quality.md`](../design/sla-and-quality.md) §2 |
| **arrival_state** | `file_record.metadata_json.arrivalState` 上的文件组到达治理状态，不是 job_instance 状态。稳定主状态为 WAITING_ARRIVAL / TRIGGERED / TIMEOUT，人工确认等等待原因由文件治理链路补充。 | 同上 |

## 可靠性 / 治理

| 术语 | 定义 | 详见 |
|---|---|---|
| **outbox** | 事务性 Outbox 模式。业务状态和 outbox 行在同一数据库事务落库，relay 再异步投递 Kafka；依靠幂等键和重试实现至少一次交付，不把数据库事务扩展到 Kafka。 | [`../architecture/adr/ADR-002-transactional-outbox.md`](../architecture/adr/ADR-002-transactional-outbox.md) |
| **DLQ** | Dead Letter Queue。Kafka 消费失败超过阈值的消息进 DLQ topic + `dead_letter_task` 表，等人工 / AI 重放。 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §1.8 |
| **run_mode** | 一次执行的意图，由 `RunMode` 定义：<!-- enum-sync:RunMode:start -->`NORMAL`, `RETRY`, `RERUN`, `RECOVER`, `COMPENSATE`<!-- enum-sync:RunMode:end -->。它属于运行上下文，不是生命周期状态。 | [`../architecture/core-model.md`](../architecture/core-model.md) §5 |
| **retry** | 系统对同一执行单元、同一业务意图做技术性重试，通常由 retry policy 自动驱动。 | [`../architecture/core-model.md`](../architecture/core-model.md) §6 |
| **rerun** | 运营人员或控制台明确要求重新执行某个业务范围，通常创建新的执行尝试或结果版本。 | 同上 |
| **recover** | 租约、进程、网络或 checkpoint 中断后继续推进原业务意图，不表示业务回滚。 | 同上 |
| **compensation** | 对已发生的业务副作用执行声明式纠正或反向步骤；Orchestrator 只调度补偿，具体副作用由 Worker / 插件执行。 | [`../runbook/compensation-cleanup.md`](../runbook/compensation-cleanup.md) |
| **misfire** | 计划触发时刻已到但 Quartz 未按时执行的情况。CRON 不由 Quartz 直接 fire-now，后续是否补跑由 catch-up policy、pending approval 和补点链路裁决。 | [`../runbook/trigger-operations.md`](../runbook/trigger-operations.md) |
| **drain** | Worker 优雅下线。停止接新 task + 等已认领 task 完成 + 释放 lease + 退出。 | [`../runbook/rolling-upgrade-workers.md`](../runbook/rolling-upgrade-workers.md) |
| **lease** | 租约。worker 占用 partition 的时间窗口，过期未续被其他 worker 抢占。 | `PartitionLeaseProperties` |
| **shedlock** | 调度任务的跨实例互斥锁。provider 可为 Redis 或 JDBC：Orchestrator 默认 Redis，Trigger 的辅助调度锁使用 JDBC；同一锁域的所有实例必须使用同一 provider。 | [`../runbook/ha-elastic-scaling.md`](../runbook/ha-elastic-scaling.md) |
| **bypass_mode** | 全链路安全旁路总开关（`batch.security.bypass-mode`）。仅本地 / 联调；prod 禁用。 | [`../coding-conventions.md`](../coding-conventions.md) §21 |
| **idempotency_key** | 幂等键。客户端在写接口的 `Idempotency-Key` header，相同值 N 次调用 = 1 次执行。 | [`../api/console-api-protocol.md`](../api/console-api-protocol.md) |
| **result_version** | 结果版本主模型。同一 `(tenant, business_key)` 多次重跑产生的产物各自一行，状态 PENDING/EFFECTIVE/SUPERSEDED/ARCHIVED；EFFECTIVE 唯一，下游 SQL 统一查它。 | [`../architecture/adr/ADR-017-result-version-model.md`](../architecture/adr/ADR-017-result-version-model.md) |
| **cross_day_dependency** | 跨批量日 DAG 依赖。`workflow_node.cross_day_dependencies` JSONB 声明上游 (jobCode, bizDateOffset/Range)；启动前由 resolver 查 `result_version` EFFECTIVE 解析；缺则 `WAITING_DEPENDENCY` 等。 | [`../architecture/adr/ADR-018-cross-batch-day-dag-dependency.md`](../architecture/adr/ADR-018-cross-batch-day-dag-dependency.md) |
| **business_domain** | 业务域。同租户内多业务（交易/风控/合规）的可选额外配额维度，启用后参与限流决策链；支持父子借调。当前 Accepted 但实施 gated。 | [`../architecture/adr/ADR-019-cross-domain-rate-limit.md`](../architecture/adr/ADR-019-cross-domain-rate-limit.md) |
| **batch_day_replay_session** | 批量日维度重放聚合。同 (tenant, calendar, bizDate) 至多 1 个 active session；scope ∈ ALL/ALL_FAILED/SUBSET_JOB_CODES/OUTPUTS_ONLY；接审批 + 重跑透传 result_policy。 | [`../architecture/adr/ADR-020-batch-day-replay.md`](../architecture/adr/ADR-020-batch-day-replay.md) |
| **batch_day_operation_audit** | 批量日治理操作审计独立表。FREEZE/RELEASE/SKIP/REOPEN/CLOSE 等高风险动作每次写一行，与 `job_execution_log` 双写但独立检索维度。 | V105 migration |
| **fire_sequence** | trigger 本地计划计数。同一 (schedule_timezone, scheduled_local_date, scheduled_local_time) 连续触发递增；DST overlap 第二次触发 = 2，正常 = 1。 | V104 migration |

## 多租户 / 安全

| 术语 | 定义 | 详见 |
|---|---|---|
| **tenant** | 租户。配置 / 任务 / 文件 / 审计的最大隔离边界。 | [`../design/multi-tenant-and-security.md`](../design/multi-tenant-and-security.md) §1 |
| **GLOBAL_ROLES** | 跨租户角色（ADMIN / AUDITOR / CONFIG_ADMIN）。可读所有租户数据，写仍需审批。 | 同上 §2 |
| **secret_version** | 密钥版本。同一 secret_ref 多版本并存，支持轮换窗口 + 兼容期。 | 同上 §5 |
| **config_release** | 配置发布版本。DRAFT → PUBLISHED → GRAY → ROLLED_BACK，已创建实例不被在线修改穿透。 | 同上 §12 |

## 模块代号

| 简称 | 全名 | 职责 |
|---|---|---|
| **trigger** | batch-trigger | Quartz 定时触发器与手动触发入口 |
| **orchestrator** | batch-orchestrator | 编排引擎，状态主机 |
| **worker-import** | batch-worker-import | 文件导入 worker |
| **worker-export** | batch-worker-export | 文件导出 worker |
| **worker-dispatch** | batch-worker-dispatch | 文件分发 worker |
| **worker-process** | batch-worker-process | 数据加工 worker（WAP 模式 + SQL transform 插件） |
| **worker-atomic** | batch-worker-atomic | 原子任务 worker（受控 shell / SQL / HTTP / 存储过程等执行器） |
| **worker-core** | batch-worker-core | Worker 共享框架 |
| **console-api** | batch-console-api | 控制台 BFF |
| **common** | batch-common | 跨模块共享代码 |

## 维护规则

- **本字典定义手写、枚举块自动校验**——业务解释由维护者编写；`enum-sync` 块必须与 Java 枚举一致，由 `scripts/ci/check-terminology-doc-sync.py` 阻断漂移。
- **不重复 design 文档**——只给术语下定义 + 跳转链接，不展开原理。
- **新增术语阈值**：跨团队 / 跨模块出现 ≥ 3 次理解分歧时再加。否则散落在 design 文档里上下文解释更清楚。
- **不接受同义词污染**：每概念一个权威词，废弃词标 deprecated 并给替代。
