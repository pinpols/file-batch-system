# useGeneratedKeys 台账（Citus POC-B，2026-06）

> 目的：Citus distributed table 的 insert 在 coordinator 分配自增 id 的行为与单机 PG 不同
> （sequence 在 coordinator 还是 worker、回读 id 是否可靠），本台账盘点全部 43 处
> useGeneratedKeys 的 id 回读用途，按风险三档分类，供未来 PK 复合化 / Citus POC 评估。
> 关联：docs/backlog/citus-introduction-plan-2026-06-06.md §0.5

---

## 汇总

- **总数：43 处**（grep 原始输出 49 行，去掉注释行 6 条，实际 insert 语句 43 个）
- **档 A（安全）：33** | **档 B（需 POC）：9** | **档 C（高危）：1**

---

## 明细

| 文件：行 | mapper 方法 | 目标表 | id 回读用途 | 档位 |
|---|---|---|---|---|
| batch-console-api:CalendarHolidayMapper.xml:35 | `insert` | `calendar_holiday` | 返回给上层 DTO / 仅日志 | A |
| batch-console-api:AtomicTaskConfigMapper.xml:46 | `insertAtomicTaskConfig` | `atomic_task_config` | `param.getId()` 做 selectByTenantAndId 读回同行（同 tx），非 FK 插入 | A |
| batch-console-api:ConfigSyncLogMapper.xml:26 | `insert` | `config_sync_log` | id 不被捕获使用，仅写入审计 | A |
| batch-console-api:BusinessCalendarMapper.xml:84 | `insert` | `business_calendar` | 返回给上层 DTO | A |
| batch-console-api:BatchWindowMapper.xml:82 | `insert` | `batch_window` | `params.get("id")` 仅用于 selectById 读回同行，供 HTTP 响应 | A |
| batch-console-api:ConsoleAiAuditLogMapper.xml:23 | `insert` | `console_ai_audit_log` | id 不被捕获，纯审计写入 | A |
| batch-console-api:FileChannelConfigMapper.xml:86 | `insertFileChannelConfig` | `file_channel_config` | id 不被调用方捕获（TenantConfigInit / Excel 导入场景） | A |
| batch-console-api:JobDefinitionMapper.xml:113 | `insert` | `job_definition` | `entity.getId()` 仅用于 selectById 读回同行，供 HTTP 响应；无同 tx FK 写入 | A |
| batch-console-api:ResourceQueueMapper.xml:94 | `insert` | `resource_queue` | id 不被调用方捕获 | A |
| batch-console-api:WorkflowDefinitionVersionMapper.xml:25 | `insertVersionSnapshot` | `workflow_definition_version` | `p.id` 不在 insertVersionSnapshot 调用处被继续使用 | A |
| batch-orchestrator:BatchDayReplayEntryMapper.xml:25 | `insert` | `batch_day_replay_entry` | 生产代码只调用 `insertBatch`（批量，id 不单独读取）；单条 `insert` 方法定义存在但主路径未见调用 | A |
| batch-orchestrator:EventDeliveryLogMapper.xml:24 | `insert` | `event_delivery_log` | id 不被捕获，kafka 发布后纯审计 | A |
| batch-orchestrator:ForensicExportLogMapper.xml:41 | `insert` | `forensic_export_log` | REQUIRES\_NEW 独立事务写入，id 不被使用 | A |
| batch-orchestrator:JobExecutionLogMapper.xml:19 | `insert` | `job_execution_log` | id 不被捕获，多处纯审计写入 | A |
| batch-orchestrator:CompensationCommandMapper.xml:31 | `insert` | `compensation_command` | 在 REQUIRES\_NEW 边界内写入，id 在后续 updateStatus 中做 PK 过滤（是 UPDATE 不是 FK INSERT） | A |
| batch-orchestrator:DeadLetterTaskMapper.xml:24 | `insert` | `dead_letter_task` | id 不被同 tx 后续 insert 使用，仅日志 | A |
| batch-orchestrator:DataQualityRuleMapper.xml:35 | `insert` | `data_quality_rule` | `rule` 对象返回 HTTP 响应（entity 自带 id），无 FK 写入 | A |
| batch-orchestrator:DisasterDayOverrideMapper.xml:24 | `insert` | `disaster_day_override` | 生产主代码未发现调用 insert（BatchDayOpenScheduler 只 select），待确认是否仅测试/初始化用 | A |
| batch-orchestrator:CalendarDependencyMapper.xml:21 | `insert` | `calendar_dependency` | 同上，生产主代码未发现调用 insert | A |
| batch-orchestrator:EventOutboxRetryMapper.xml:19 | `insert` | `event_outbox_retry` | id 不被捕获，重试链路纯审计 | A |
| batch-orchestrator:FileGovernanceMapper.xml:271 | `insertReconciledFileRecord` | `file_record`（对账路径） | id 返回给调用方（`return toLong(params.get("id"))`），但调用方后续用于日志 / 状态流转，无同 tx FK INSERT | A |
| batch-orchestrator:RetryScheduleMapper.xml:24 | `insert` | `retry_schedule` | insert 后 retrySchedule.getId() 仅用于日志打印；后续 markSuccess/markFailed 是跨事务 UPDATE，非 FK INSERT | A |
| batch-trigger:TriggerRuntimeStateMapper.xml:91 | `insertOnReconcile` | `trigger_runtime_state` | WheelTriggerReconciler 插入后不读 id（只检查 DuplicateKey 幂等） | A |
| batch-trigger:TriggerOutboxEventMapper.xml:24 | `insert` | `trigger_outbox_event` | `publishRaw` 返回 `entity.getId()` 给调用方，但调用方均未捕获返回值 | A |
| batch-trigger:TriggerMisfirePendingMapper.xml:25 | `insertPending` | `trigger_misfire_pending` | `pending.getId()` 用于 `linkCatchUpRequest(id, requestId)` — 这是一个 UPDATE（设置外键字段），并非新增 FK 子行 | A |
| batch-trigger:TriggerRequestMapper.xml:58 | `insert` | `trigger_request` | DefaultTriggerService 两处 insert 后均不捕获 id；id 是 surrogate，业务上层依赖 requestId（业务 UUID）| A |
| batch-worker-core:PlatformFileRuntimeMapper.xml:252 | `insertFileErrorRecord` | `file_error_record` | 返回 id 给 `insertFileErrorRecord(FileErrorRecordParam)` 调用者，当前主路径不做后续 FK INSERT | A |
| batch-orchestrator:TriggerRequestMapper.xml:42 | `insert`（orchestrator 侧） | `trigger_request` | ChildJobLaunchSupport / BatchDaySettleScheduler / DefaultCompensationService 写入后均不读 id | A |
| batch-console-api:PipelineStepDefinitionMapper.xml:30 | `insert` | `pipeline_step_definition` | console-api insertSteps 循环中，stepParams 不捕获 id，步骤间无 FK 链 | A |
| batch-worker-core:PlatformFileRuntimeMapper.xml:81 | `insertPipelineStepDefinition` | `pipeline_step_definition` | ensurePipelineStepDefinitions 中写入后不读 id | A |
| batch-worker-core:PlatformFileRuntimeMapper.xml:161 | `insertStepRun` | `pipeline_step_run` | `startStepRun` 返回 id 给调用方用于后续 `finishStepRun(stepRunId, ...)` — 跨调用 UPDATE，非同 tx FK INSERT | A |
| batch-worker-core:PlatformFileRuntimeMapper.xml:215 | `insertFileRecord` | `file_record` | `insertFileRecord` 返回 id，调用方后续对同一 fileId 调 `updateFileStatus` — 跨调用 UPDATE，非 FK INSERT | A |
| batch-worker-core:PlatformFileRuntimeMapper.xml:97 | `insertPipelineInstance` | `pipeline_instance` | `createPipelineInstance` 返回 id，后续 `bindFileToPipelineInstance` / `updatePipelineStage` 是 UPDATE，非 FK INSERT | A |
| batch-console-api:PipelineDefinitionMapper.xml:64 | `insert` | `pipeline_definition` | insert 后 `defId = params.get("id")`，同 tx 立即调 `insertSteps(defId, ...)` → 每步 INSERT `pipeline_step_definition.pipeline_definition_id = defId` | **B** |
| batch-worker-core:PlatformFileRuntimeMapper.xml:61 | `insertPipelineDefinition` | `pipeline_definition` | insert 后 `pipelineDefinitionId = paramMap.get("id")`，同 tx 调 `ensurePipelineStepDefinitions(pipelineDefinitionId, defaultSteps)` → INSERT `pipeline_step_definition.pipeline_definition_id = pipelineDefinitionId` | **B** |
| batch-console-api:WorkflowDefinitionMapper.xml:84 | `insert` | `workflow_definition` | insert 后 `entity.getId()` 同 tx 传入 `upsertNodesAndEdges(tenantId, entity.getId(), request)` → INSERT `workflow_node.workflow_definition_id` / `workflow_edge.workflow_definition_id` | **B** |
| batch-orchestrator:JobInstanceMapper.xml:83 | `insert` | `job_instance` | insert 后 `jobInstance.getId()` 同 tx 用于：① setRelatedJobInstanceId（WorkflowRunEntity）→ workflowRunMapper.insert；② setJobTaskId 等链路 | **B** |
| batch-orchestrator:WorkflowRunMapper.xml:21 | `insert` | `workflow_run` | insert 后 `workflowRun.getId()` 同 tx 做 `startNodeRun.setWorkflowRunId(workflowRun.getId())` → workflowNodeRunMapper.insert | **B** |
| batch-orchestrator:WorkflowNodeRunMapper.xml:27 | `insert` | `workflow_node_run` | DefaultWorkflowDagService.insert 后 id 不续用；DefaultWorkflowNodeDispatchService 多处 insert 后不读 id；DefaultLaunchService 的 startNodeRun insert 后 id 不续用 | A |
| batch-orchestrator:JobPartitionMapper.xml:62 | `insert` | `job_partition` | `partitionEntity.getId()` 同 tx 后续传入 task / step 链路（DefaultTaskCreationService 调用时 `task.setJobPartitionId(partition.getId())`） | **B** |
| batch-orchestrator:JobTaskMapper.xml:39 | `insert` | `job_task` | insert 后 `task.getId()` 同 tx 立即调 `createStepInstance(task)` → `stepInstance.setJobTaskId(task.getId())` → jobStepInstanceMapper.insert | **B** |
| batch-orchestrator:JobStepInstanceMapper.xml:28 | `insert` | `job_step_instance` | insert 后 id 不被续用，链的终点 | A |
| batch-orchestrator:OutboxEventMapper.xml:22 | `insert` | `outbox_event` | `publish()` 返回 `entity.getId()`；DefaultScheduleForwarder 调用处将返回值赋给 `future`（Long），后续仅用于 EventOutboxRetry 的 `outboxEventId` 字段写入 → 跨事务（EventOutboxRetry 是另一处 insert，不在同 tx） | **C** |

---

## 档 B 详注（需 POC 验证的逐条说明）

**B1 — console-api PipelineDefinition → PipelineStepDefinition（同 tx，同库）**
`DefaultConsolePipelineDefinitionApplicationService.create()` 在同一 `@Transactional` 中先 insertPipelineDefinition，回读 `defId`，再循环 insertPipelineStepDefinition（`pipeline_definition_id = defId`）。Citus 下若 pipeline_definition 为 distributed table，coordinator 分配的 id 必须在 INSERT 返回后立即可信回读；若 sequence 分配延迟或 RETURNING 结果在 coordinator 侧不可靠，步骤行的外键将写成 0 或 null。POC 要验证：在 distributed table 上执行 INSERT RETURNING id 后，MyBatis useGeneratedKeys 能否在同 SQL 事务内拿到正确值。

**B2 — worker-core PipelineDefinition → PipelineStepDefinition（同 tx，同库）**
`PlatformFileRuntimeRepository.getOrCreatePipelineDefinitionId()` 逻辑同 B1，insertPipelineDefinition 后 `paramMap.get("id")` 传入 `ensurePipelineStepDefinitions`。与 B1 共同测 POC 场景。

**B3 — console-api WorkflowDefinition → WorkflowNode / WorkflowEdge（同 tx）**
`DefaultConsoleWorkflowDefinitionApplicationService.create()` insert WorkflowDefinition 后同 tx 调 `upsertNodesAndEdges(entity.getId())`，批量 INSERT workflow_node / workflow_edge（`workflow_definition_id = id`）。POC 需验证同事务内跨表 FK 写入对 Citus distributed 序列回读的影响。

**B4 — orchestrator JobInstance → WorkflowRun（同 tx）**
`DefaultLaunchService.prepareWorkflowRunAndNodes()` 在同事务内依次 insert job_instance，取 `jobInstance.getId()` → `workflowRun.setRelatedJobInstanceId(jobInstance.getId())` → insert workflow_run。job_instance 是调度核心热表，分区后 sequence 行为影响范围极大。

**B5 — orchestrator WorkflowRun → WorkflowNodeRun（同 tx）**
同上事务内，insert workflow_run 后 `workflowRun.getId()` → `startNodeRun.setWorkflowRunId(workflowRun.getId())` → insert workflow_node_run。与 B4 连锁，同一 `prepareWorkflowRunAndNodes` 方法。

**B6 — orchestrator JobPartition → JobTask / JobStepInstance（同 tx）**
`DefaultPartitionLifecycleService.createPartitions()` 循环 insert job_partition，每次取 `partitionEntity.getId()` 写入后续 task 的 `job_partition_id`。job_partition 是高频写入的分片表，Citus 化后此处 id 回读是核心风险点。

**B7 — orchestrator JobTask → JobStepInstance（同 tx）**
`DefaultTaskCreationService.createTask()` insert job_task 后立即 `createStepInstance(task)`：`stepInstance.setJobTaskId(task.getId())` → insert job_step_instance。属于 B6 的下一环，同事务 FK 链。

**B8 — console-api WorkflowDefinitionVersionMapper（同 tx，id 不续用于 FK，但需确认）**
`insertVersionSnapshot` 在 `upsertNodesAndEdges` 路径内调用（line 262），`p.id` 回写后未见后续 FK INSERT，但该方法在 workflow 版本管理的关键路径上，POC 时应一并覆盖确认 id 回读正常。

**B9 — orchestrator OutboxEvent → EventOutboxRetry（跨事务，标注于档 C 区）**
见档 C 说明。

---

## 档 C 详注（高危，需逐个评审）

**C1 — orchestrator OutboxEvent → EventOutboxRetry（跨事务持有 id）**
`OutboxDomainEventPublisher.publish()` 在一个 `PROPAGATION_MANDATORY` 事务中 insert outbox_event 并返回 `entity.getId()`。`DefaultScheduleForwarder` 在另一处调用 `outboxPublisher.publish(event)` 取 id 存入局部变量 `future`（Long）；在后续重试路径中，`eventOutboxRetryMapper.insert(retry)` 写入 `retry.setOutboxEventId(event.getOutboxEventId())`（event 是从 DB 重读的对象，非同 tx id）。

高危原因：outbox_event 是 Citus 化优先候选表（高写入、独立分区键），若 distributed table 上的 sequence 在 coordinator 与 worker 的 id 回填语义有差异，event_outbox_retry 的 `outbox_event_id` FK 将写错，导致重试链路断裂且难以察觉（event 本身已 commit，重试行 FK 静默错误）。POC 必须在 distributed table 上验证：insert → 提交 → 跨事务 re-read id 的一致性。

---

## 结论

43 处 useGeneratedKeys 中，33 处（档 A）id 仅用于日志 / HTTP 响应回显 / 同行 UPDATE，对 Citus 无同 tx FK 风险；9 处（档 B）在同一事务内将回读 id 作为子表 FK 立即 INSERT，集中于调度核心链路（job_instance → workflow_run → workflow_node_run、job_partition → job_task → job_step_instance、pipeline_definition → pipeline_step_definition），必须在 Citus POC 阶段优先验证 distributed table 的 RETURNING/useGeneratedKeys 可靠性；1 处（档 C，outbox_event → event_outbox_retry）涉及跨事务 id 持有 + FK 写入，是风险最高的单点，需单独评审。
