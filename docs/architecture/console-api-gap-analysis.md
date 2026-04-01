# Console Ab 

> 基于完整数据模型（31 张表）、现有 Console API、Orchestrator / Trigger 内部 API 的全面比对。
>
> 日期：2026-03-31 &nbsp;|&nbsp; 最后更新：2026-04-01 P3 + P4 实现完成

---

## 现状概览

| 类别 | 数量 | 说明 |
|------|------|------|
| 配置/定义表 | 14 张 | `resource_queue`、`tenant_quota_policy`、`batch_window`、`business_calendar`、`calendar_holiday`、`job_definition`、`workflow_definition`、`workflow_node`、`workflow_edge`、`pipeline_definition`、`pipeline_step_definition`、`file_channel_config`、`file_template_config`、`worker_registry` |
| 运行态表 | 17 张 | `trigger_request`、`job_instance`、`job_partition`、`job_task`、`workflow_run`、`workflow_node_run`、`file_record`、`pipeline_instance`、`pipeline_step_run`、`file_dispatch_record`、`file_audit_log`、`job_execution_log`、`retry_schedule`、`dead_letter_task`、`outbox_event` 等 |
| 原有读接口 | 35 | `ConsoleQueryController`（34）+ `ConsoleOpsController`（1） |
| 原有写接口 | 39 | 全部为 `@PostMapping`；无 `PUT` / `DELETE` / `PATCH` |
| **P0 新增接口** | **25** | 作业定义 CRUD（6）+ 工作流定义 CRUD（6）+ 实例操作（2）+ 触发器管理（6）+ 枚举元数据（5） |
| **P1 新增接口** | **25** | 资源队列 CRUD（4）+ 批次窗口 CRUD（4）+ 日历+假日（8）+ 调度器控制（3）+ 查询详情（6） |
| **P2 新增接口** | **19** | 配额策略 CRUD（4）+ 流水线定义 CRUD（5）+ 分区操作（2）+ 工作流运行操作（3）+ 仪表盘（5） |
| **P3 新增接口** | **10** | 文件通道 CRUD（5）+ 文件模板 CRUD（5） |
| **P4 新增接口** | **44** | 作业运维（9）+ 告警治理（3）+ Worker 运维（3）+ AI 对话（1）+ 调度快照（2）+ 文件下载（1）+ Excel 批量维护（16）+ 报表导出（9） |
| **当前接口总计** | **197** | 35 + 39 + 25 + 25 + 19 + 10 + 44 |

---

## P0 — 已实现 ✅

### 1.1 作业定义 CRUD（job_definition）— ✅ 已实现

控制器：`ConsoleJobDefinitionController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`（读取允许 `ROLE_AUDITOR` / `ROLE_CONFIG_ADMIN`）

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 单条详情 | GET | `/api/console/job-definitions/{id}?tenantId=` | 含全部字段：`param_schema`、`default_params`、`trigger_mode`、`timezone`、`dag_enabled`、`priority`、`version` 等 |
| ✅ | 新建 | POST | `/api/console/job-definitions` | 含 `schedule_type` / `schedule_expr` / `trigger_mode` / `timezone` 等全部字段；校验 `job_code` 唯一 |
| ✅ | 编辑 | PUT | `/api/console/job-definitions/{id}` | 部分更新，未传字段保留原值 |
| ✅ | 启用/禁用 | POST | `/api/console/job-definitions/{id}/toggle?tenantId=&enabled=` | |
| ✅ | 删除 | DELETE | `/api/console/job-definitions/{id}?tenantId=` | |
| ✅ | 克隆 | POST | `/api/console/job-definitions/{id}/copy?tenantId=&newJobCode=` | 复制定义，新作业默认 `enabled=false` |

**实现文件：**
- `batch-console-api/.../web/ConsoleJobDefinitionController.java`（新）
- `batch-console-api/.../web/request/JobDefinitionCreateRequest.java`（新）
- `batch-console-api/.../web/request/JobDefinitionUpdateRequest.java`（新）
- `batch-console-api/.../domain/entity/JobDefinitionEntity.java`（补 `bizType`、`timezone`、`priority`、`triggerMode`、`dagEnabled`、`version`、`createdBy`、`updatedBy`）
- `batch-console-api/.../web/response/ConsoleJobDefinitionResponse.java`（补 7 个字段，共 28 字段）
- `batch-console-api/.../mapper/JobDefinitionMapper.java`（增 `selectById`、`insert`、`deleteByTenantAndId`、`toggleEnabled`、`copyJobDefinition`）
- `batch-console-api/.../resources/mapper/JobDefinitionMapper.xml`（增 6 列 resultMap + 5 条 SQL）

### 1.2 工作流定义 CRUD（workflow_definition / workflow_node / workflow_edge）— ✅ 已实现

控制器：`ConsoleWorkflowDefinitionController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 详情（含节点+边） | GET | `/api/console/workflow-definitions/{id}?tenantId=` | 返回 `WorkflowDefinitionDetailResponse`（含 nodes + edges 完整 DAG） |
| ✅ | 新建 | POST | `/api/console/workflow-definitions` | `@Transactional`：一次提交定义 + 节点 + 边 |
| ✅ | 编辑 | PUT | `/api/console/workflow-definitions/{id}` | `@Transactional`：删旧节点/边 → 插新节点/边 |
| ✅ | 启用/禁用 | POST | `/api/console/workflow-definitions/{id}/toggle?tenantId=&enabled=` | |
| ✅ | 删除 | DELETE | `/api/console/workflow-definitions/{id}?tenantId=` | `@Transactional`：级联删除节点 + 边 |
| ✅ | DAG 校验 | POST | `/api/console/workflow-definitions/{id}/validate?tenantId=` | Kahn 拓扑排序检测环、START/END 节点校验、可达性检查 |

**实现文件：**
- `batch-console-api/.../web/ConsoleWorkflowDefinitionController.java`（新）
- `batch-console-api/.../web/request/WorkflowDefinitionSaveRequest.java`（新，含 `NodeItem` + `EdgeItem` 内部类）
- `batch-console-api/.../web/response/WorkflowDefinitionDetailResponse.java`（新）
- `batch-console-api/.../mapper/WorkflowDefinitionMapper.java`（增 `selectById`、`insert`、`updateWorkflowDefinition`、`deleteByTenantAndId`、`toggleEnabled`）
- `batch-console-api/.../mapper/WorkflowNodeMapper.java`（增 `deleteByWorkflowDefinitionId`）
- `batch-console-api/.../mapper/WorkflowEdgeMapper.java`（增 `deleteByWorkflowDefinitionId`）
- 对应 3 个 `Mapper.xml` 增 SQL

### 2.1 作业实例操作 — ✅ 已实现（cancel / terminate）

控制器：`ConsoleInstanceController`（Console 代理）→ `InstanceManagementController`（Orchestrator 内部）

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 取消 | POST | `/api/console/instances/{id}/cancel?tenantId=` | 仅允许 `CREATED` / `WAITING` / `READY` 状态 |
| ✅ | 强制终止 | POST | `/api/console/instances/{id}/terminate?tenantId=` | 仅允许 `RUNNING` 状态 |
| ❌ 移除 | ~~暂停~~ | — | — | DB 约束无 `PAUSED` 状态，不实现 |
| ❌ 移除 | ~~恢复~~ | — | — | 同上 |

> 状态机校验 + 乐观锁（`version`），并发安全。

**实现文件：**
- `batch-orchestrator/.../controller/InstanceManagementController.java`（新）
- `batch-orchestrator/.../mapper/JobInstanceMapper.java`（增 `updateStatus`）
- `batch-orchestrator/.../resources/mapper/JobInstanceMapper.xml`（增 SQL）
- `batch-console-api/.../web/ConsoleInstanceController.java`（新，RestClient 代理）

### 3.1 调度触发器管理 — ✅ 已实现

Trigger 端：`TriggerManagementController` &nbsp;|&nbsp; Console 端：`ConsoleTriggerController`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 触发器列表 | GET | `/api/console/triggers` | 返回已注册 Quartz Job 列表：`status`、`previousFireTime`、`nextFireTime` |
| ✅ | 注册/更新触发器 | POST | `/api/console/triggers/{jobCode}/register?tenantId=` | 从 DB 加载作业定义并注册到 Quartz |
| ✅ | 取消注册 | POST | `/api/console/triggers/{jobCode}/unregister?tenantId=` | 从 Quartz 移除 |
| ✅ | 暂停调度 | POST | `/api/console/triggers/{jobCode}/pause?tenantId=` | Quartz `pauseJob` |
| ✅ | 恢复调度 | POST | `/api/console/triggers/{jobCode}/resume?tenantId=` | Quartz `resumeJob` |
| ⏳ 待定 | 触发历史 | GET | `/api/console/triggers/{jobCode}/fire-history` | 需要 `trigger_request` 查询，暂未实现 |

**实现文件：**
- `batch-trigger/.../domain/TriggerStatusInfo.java`（新 record DTO）
- `batch-trigger/.../domain/TriggerRegistrationService.java`（增 `unregisterByJobCode`、`pauseByJobCode`、`resumeByJobCode`、`listRegisteredTriggers`）
- `batch-trigger/.../infrastructure/TriggerSchedulerFacade.java`（实现 4 个新方法）
- `batch-trigger/.../web/TriggerManagementController.java`（新）
- `batch-console-api/.../web/ConsoleTriggerController.java`（新，RestClient 代理）

### 5.1 枚举元数据 — ✅ 已实现

控制器：`ConsoleMetaController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_CONFIG_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 返回内容 |
|------|------|------|------|---------|
| ✅ | 全部枚举 | GET | `/api/console/meta/enums` | `triggerType`、`jobType`、`scheduleType`、`triggerMode`、`shardStrategy`、`retryPolicy`、`instanceStatus`、`workflowNodeType`、`channelType` |
| ✅ | 可选队列 | GET | `/api/console/meta/queues?tenantId=` | `queue_code` 简化列表 |
| ✅ | 可选日历 | GET | `/api/console/meta/calendars?tenantId=` | `calendar_code` 简化列表 |
| ✅ | 可选窗口 | GET | `/api/console/meta/windows?tenantId=` | `window_code` 简化列表 |
| ✅ | Worker 分组 | GET | `/api/console/meta/worker-groups?tenantId=` | 去重 `worker_group` 列表 |

**实现文件：**
- `batch-console-api/.../web/ConsoleMetaController.java`（新）

---

## P1 — 已实现 ✅

### 1.3 资源队列（resource_queue）— ✅ 已实现

控制器：`ConsoleResourceQueueController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 列表 | GET | `/api/console/queues` | 分页，支持 queueCode / queueType / enabled 筛选 |
| ✅ | 新建 | POST | `/api/console/queues` | 校验 `queue_code` 唯一，含并发上限、QPS、优先级策略 |
| ✅ | 编辑 | PUT | `/api/console/queues/{id}` | 部分更新，未传字段保留原值 |
| ✅ | 启用/禁用 | POST | `/api/console/queues/{id}/toggle` | |

**实现文件：**
- `batch-console-api/.../web/ConsoleResourceQueueController.java`（新）
- `batch-console-api/.../web/request/ResourceQueueCreateRequest.java`（新）
- `batch-console-api/.../web/request/ResourceQueueUpdateRequest.java`（新）
- `batch-console-api/.../mapper/ResourceQueueMapper.java`（新）
- `batch-console-api/.../resources/mapper/ResourceQueueMapper.xml`（新）

### 1.4 批次窗口（batch_window）— ✅ 已实现

控制器：`ConsoleBatchWindowController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 列表 | GET | `/api/console/batch-windows` | 分页，支持 windowCode / enabled 筛选 |
| ✅ | 新建 | POST | `/api/console/batch-windows` | 校验 `window_code` 唯一，起止时间、跨日策略、窗口外动作 |
| ✅ | 编辑 | PUT | `/api/console/batch-windows/{id}` | 部分更新 |
| ✅ | 启用/禁用 | POST | `/api/console/batch-windows/{id}/toggle` | |

**实现文件：**
- `batch-console-api/.../web/ConsoleBatchWindowController.java`（新）
- `batch-console-api/.../web/request/BatchWindowCreateRequest.java`（新）
- `batch-console-api/.../web/request/BatchWindowUpdateRequest.java`（新）
- `batch-console-api/.../mapper/BatchWindowMapper.java`（新）
- `batch-console-api/.../resources/mapper/BatchWindowMapper.xml`（新）

### 1.5 工作日历 + 假日管理（business_calendar / calendar_holiday）— ✅ 已实现

控制器：`ConsoleCalendarController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 日历列表 | GET | `/api/console/calendars` | 分页，支持 calendarCode / enabled 筛选 |
| ✅ | 新建日历 | POST | `/api/console/calendars` | 校验 `calendar_code` 唯一，时区、补跑策略、滚动规则 |
| ✅ | 编辑日历 | PUT | `/api/console/calendars/{id}` | 部分更新 |
| ✅ | 启用/禁用 | POST | `/api/console/calendars/{id}/toggle` | |
| ✅ | 假日列表 | GET | `/api/console/calendars/{id}/holidays` | 校验日历归属租户 |
| ✅ | 批量导入假日 | POST | `/api/console/calendars/{id}/holidays` | 支持多条批量写入 |
| ✅ | 编辑单条假日 | PUT | `/api/console/calendars/{id}/holidays/{holidayId}` | |
| ✅ | 删除单条假日 | DELETE | `/api/console/calendars/{id}/holidays/{holidayId}` | |

**实现文件：**
- `batch-console-api/.../web/ConsoleCalendarController.java`（新）
- `batch-console-api/.../web/request/CalendarSaveRequest.java`（新）
- `batch-console-api/.../web/request/HolidayImportRequest.java`（新）
- `batch-console-api/.../web/request/HolidaySaveRequest.java`（新）
- `batch-console-api/.../mapper/BusinessCalendarMapper.java`（增 6 个方法）
- `batch-console-api/.../resources/mapper/BusinessCalendarMapper.xml`（增 6 条 SQL）
- `batch-console-api/.../mapper/CalendarHolidayMapper.java`（新）
- `batch-console-api/.../resources/mapper/CalendarHolidayMapper.xml`（新）

### 3.2 调度器全局控制 — ✅ 已实现

Trigger 端：`TriggerManagementController` &nbsp;|&nbsp; Console 端：`ConsoleSchedulerController`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 调度器状态 | GET | `/api/console/scheduler/status` | 返回 STARTED / PAUSED / STANDBY / SHUTDOWN |
| ✅ | 暂停全部 | POST | `/api/console/scheduler/pause-all` | Quartz `scheduler.pauseAll()` |
| ✅ | 恢复全部 | POST | `/api/console/scheduler/resume-all` | Quartz `scheduler.resumeAll()` |

**实现文件：**
- `batch-trigger/.../domain/TriggerRegistrationService.java`（增 `pauseAll`、`resumeAll`、`schedulerStatus`）
- `batch-trigger/.../infrastructure/TriggerSchedulerFacade.java`（实现 3 个新方法）
- `batch-trigger/.../web/TriggerManagementController.java`（增 3 个端点）
- `batch-console-api/.../web/ConsoleSchedulerController.java`（新，RestClient 代理）

### 四、查询详情接口补齐 — ✅ 已实现

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | ~~作业定义详情~~ | ~~GET~~ | ~~`/api/console/query/job-definitions/{id}`~~ | 已由 P0 提供 |
| ✅ | ~~工作流定义详情~~ | ~~GET~~ | ~~`/api/console/query/workflow-definitions/{id}`~~ | 已由 P0 提供 |
| ✅ | 文件通道详情 | GET | `/api/console/query/file-channels/{channelCode}?tenantId=` | 按 channelCode 查询 |
| ✅ | 文件模板详情 | GET | `/api/console/query/file-templates/{templateCode}?tenantId=&version=` | 按 templateCode + version 查询 |
| ✅ | 文件记录详情 | GET | `/api/console/query/files/{id}?tenantId=` | 按 ID 查询 |
| ✅ | 流水线实例详情 | GET | `/api/console/query/file-pipelines/{id}?tenantId=` | 按 ID 查询 |
| ✅ | 配置发布单详情 | GET | `/api/console/config/releases/{releaseId}?tenantId=` | 按 ID 查询 |
| ✅ | 密钥版本详情 | GET | `/api/console/config/secrets/{secretVersionId}?tenantId=` | 按 ID 查询 |

> 注：配置发布单列表 / 密钥版本列表已由 `ConsoleConfigController` 原有接口提供，不重复实现。

**实现文件：**
- `batch-console-api/.../application/ConsoleQueryApplicationService.java`（增 4 个详情方法）
- `batch-console-api/.../infrastructure/DefaultConsoleQueryApplicationService.java`（实现 4 个详情方法）
- `batch-console-api/.../web/ConsoleQueryController.java`（增 4 个 `@GetMapping` 详情端点）
- `batch-console-api/.../application/ConsoleConfigApplicationService.java`（增 2 个详情方法）
- `batch-console-api/.../infrastructure/DefaultConsoleConfigApplicationService.java`（实现 2 个详情方法）
- `batch-console-api/.../web/ConsoleConfigController.java`（增 2 个 `@GetMapping` 详情端点）

---

## P2 — 已实现 ✅

### 1.6 租户配额策略（tenant_quota_policy）— ✅ 已实现

控制器：`ConsoleQuotaPolicyController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 列表 | GET | `/api/console/quota-policies` | 分页，支持 policyCode / enabled 筛选 |
| ✅ | 新建 | POST | `/api/console/quota-policies` | 校验 policy_code 唯一，含并发/QPS/公平份额配置 |
| ✅ | 编辑 | PUT | `/api/console/quota-policies/{id}` | 部分更新 |
| ✅ | 启用/禁用 | POST | `/api/console/quota-policies/{id}/toggle` | |

**实现文件：**
- `batch-console-api/.../web/ConsoleQuotaPolicyController.java`（新）
- `batch-console-api/.../web/request/QuotaPolicySaveRequest.java`（新）
- `batch-console-api/.../mapper/TenantQuotaPolicyMapper.java`（新）
- `batch-console-api/.../resources/mapper/TenantQuotaPolicyMapper.xml`（新）

### 1.7 流水线定义（pipeline_definition / pipeline_step_definition）— ✅ 已实现

控制器：`ConsolePipelineDefinitionController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 列表 | GET | `/api/console/pipeline-definitions` | 分页，支持 jobCode / pipelineType / enabled 筛选 |
| ✅ | 详情 | GET | `/api/console/pipeline-definitions/{id}` | 含步骤列表 `PipelineDefinitionDetailResponse` |
| ✅ | 新建 | POST | `/api/console/pipeline-definitions` | `@Transactional`：创建定义 + 步骤 |
| ✅ | 编辑 | PUT | `/api/console/pipeline-definitions/{id}` | `@Transactional`：更新定义，删旧步骤 + 插新步骤 |
| ✅ | 启用/禁用 | POST | `/api/console/pipeline-definitions/{id}/toggle` | |

**实现文件：**
- `batch-console-api/.../web/ConsolePipelineDefinitionController.java`（新）
- `batch-console-api/.../web/request/PipelineDefinitionSaveRequest.java`（新，含 `StepItem` 内部类）
- `batch-console-api/.../web/response/PipelineDefinitionDetailResponse.java`（新，含 `StepResponse` 内部记录）
- `batch-console-api/.../mapper/PipelineDefinitionMapper.java`（新）
- `batch-console-api/.../mapper/PipelineStepDefinitionMapper.java`（新）
- `batch-console-api/.../resources/mapper/PipelineDefinitionMapper.xml`（新）
- `batch-console-api/.../resources/mapper/PipelineStepDefinitionMapper.xml`（新）

### 2.2 分区级操作 — ✅ 已实现

Orchestrator 端：`InstanceManagementController`（扩展）&nbsp;|&nbsp; Console 端：`ConsoleInstanceController`（扩展）

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 取消分区 | POST | `/api/console/instances/partitions/{id}/cancel?tenantId=` | 仅允许 CREATED / WAITING / READY 状态，乐观锁 |
| ✅ | 重试分区 | POST | `/api/console/instances/partitions/{id}/retry?tenantId=` | 仅允许 FAILED 状态，retryCount + 1 |

**实现文件：**
- `batch-orchestrator/.../controller/InstanceManagementController.java`（增 `cancelPartition`、`retryPartition`，注入 `JobPartitionMapper`）
- `batch-console-api/.../web/ConsoleInstanceController.java`（增 2 个代理端点 + `proxyPartition` 方法）

### 2.3 工作流运行操作 — ✅ 已实现

Orchestrator 端：`WorkflowRunManagementController` &nbsp;|&nbsp; Console 端：`ConsoleWorkflowRunController`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 取消 | POST | `/api/console/workflow-runs/{id}/cancel?tenantId=` | 允许 CREATED / RUNNING → TERMINATED |
| ✅ | 强制终止 | POST | `/api/console/workflow-runs/{id}/terminate?tenantId=` | 允许 RUNNING → TERMINATED |
| ✅ | 跳过失败节点 | POST | `/api/console/workflow-runs/{id}/skip-node?tenantId=&nodeCode=` | 仅允许 FAILED 节点 → SKIPPED |

**实现文件：**
- `batch-orchestrator/.../controller/WorkflowRunManagementController.java`（新）
- `batch-orchestrator/.../mapper/WorkflowRunMapper.java`（增 `selectById`）
- `batch-orchestrator/.../resources/mapper/WorkflowRunMapper.xml`（增 SQL）
- `batch-console-api/.../web/ConsoleWorkflowRunController.java`（新，RestClient 代理）

### 六、仪表盘统计 — ✅ 已实现

控制器：`ConsoleDashboardController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_CONFIG_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 返回内容 |
|------|------|------|------|---------|
| ✅ | 作业执行统计 | GET | `/api/console/dashboard/job-stats?tenantId=&days=7` | 按状态分组计数 + 每日趋势 |
| ✅ | 触发统计 | GET | `/api/console/dashboard/trigger-stats?tenantId=&days=7` | 按 triggerType 分布 + 每日趋势 |
| ✅ | Worker 负载 | GET | `/api/console/dashboard/worker-load?tenantId=` | 按状态/分组统计 + 活跃分区分布 |
| ✅ | 告警趋势 | GET | `/api/console/dashboard/alert-trend?tenantId=&days=7` | 按严重级别分组 + 每日趋势 |
| ✅ | SLA 达标率 | GET | `/api/console/dashboard/sla-compliance?tenantId=&days=7` | 违约/准时数量 + 平均时长 + 每日趋势 |

**实现文件：**
- `batch-console-api/.../web/ConsoleDashboardController.java`（新，JdbcTemplate 聚合查询）

---

## P3 — ✅ 已完成

### 1.8 文件通道（file_channel_config）— ✅ 已实现

控制器：`ConsoleFileChannelController` &nbsp;|&nbsp; 权限：读取 `ROLE_ADMIN` / `ROLE_CONFIG_ADMIN` / `ROLE_AUDITOR`；写入 `ROLE_CONFIG_ADMIN`+；删除 `ROLE_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 列表 | GET | `/api/console/file-channels` | `@ModelAttribute FileChannelQueryRequest`，分页 |
| ✅ | 详情 | GET | `/api/console/file-channels/{id}` | 按 ID + tenantId 查询 |
| ✅ | 新建 | POST | `/api/console/file-channels` | `@RequestBody FileChannelCreateRequest` |
| ✅ | 编辑 | PUT | `/api/console/file-channels/{id}` | `@RequestBody FileChannelUpdateRequest` |
| ✅ | 启用/禁用 | POST | `/api/console/file-channels/{id}/toggle` | `?tenantId=&enabled=` |
| ✅ | 删除 | DELETE | `/api/console/file-channels/{id}` | `?tenantId=`，仅 `ROLE_ADMIN` |

**实现文件：**
- `batch-console-api/.../web/ConsoleFileChannelController.java`（新）
- `batch-console-api/.../application/ConsoleFileChannelApplicationService.java`（新）
- `batch-console-api/.../web/request/FileChannelCreateRequest.java`（新）
- `batch-console-api/.../web/request/FileChannelUpdateRequest.java`（新）
- `batch-console-api/.../web/query/FileChannelQueryRequest.java`（新）

### 1.9 文件模板（file_template_config）— ✅ 已实现

控制器：`ConsoleFileTemplateController` &nbsp;|&nbsp; 权限：同文件通道

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 列表 | GET | `/api/console/file-templates` | `@ModelAttribute FileTemplateQueryRequest`，分页 |
| ✅ | 详情 | GET | `/api/console/file-templates/{id}` | 按 ID + tenantId 查询 |
| ✅ | 新建 | POST | `/api/console/file-templates` | `@RequestBody FileTemplateCreateRequest` |
| ✅ | 编辑 | PUT | `/api/console/file-templates/{id}` | `@RequestBody FileTemplateUpdateRequest` |
| ✅ | 启用/禁用 | POST | `/api/console/file-templates/{id}/toggle` | `?tenantId=&enabled=` |
| ✅ | 删除 | DELETE | `/api/console/file-templates/{id}` | `?tenantId=`，仅 `ROLE_ADMIN` |

**实现文件：**
- `batch-console-api/.../web/ConsoleFileTemplateController.java`（新）
- `batch-console-api/.../application/ConsoleFileTemplateApplicationService.java`（新）
- `batch-console-api/.../web/request/FileTemplateCreateRequest.java`（新）
- `batch-console-api/.../web/request/FileTemplateUpdateRequest.java`（新）
- `batch-console-api/.../web/query/FileTemplateQueryRequest.java`（新）

---

## P4 — ✅ 已完成

### 七、作业运维操作 — ✅ 已实现

控制器：`ConsoleJobController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN`（全部接口）

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 手工触发 | POST | `/api/console/jobs/trigger` | 触发作业立即运行，返回 instanceNo |
| ✅ | 登记补偿命令 | POST | `/api/console/jobs/compensations` | 需审批或已有 approvalId；返回 instanceNo |
| ✅ | 执行补偿 | POST | `/api/console/jobs/compensate` | 直接提交补偿任务，无需审批 |
| ✅ | 重跑实例/分区 | POST | `/api/console/jobs/rerun` | 按 targetId / targetInstanceNo 重跑 |
| ✅ | 死信重放 | POST | `/api/console/jobs/dead-letters/replay` | 需审批或已有 approvalId |
| ✅ | 任务重放（task） | POST | `/api/console/jobs/tasks/replay` | job_task 粒度，复用 COMPENSATION+RETRY 审批链 |
| ✅ | 分区重放 | POST | `/api/console/jobs/partitions/replay` | job_partition 粒度 |
| ✅ | Catch-Up 审批 | POST | `/api/console/jobs/catch-up/approve` | 批量补跑审批通过入口 |
| ✅ | 批量日 Catch-Up | POST | `/api/console/jobs/batch-days/{bizDate}/catchup` | 按批量日发起补跑，支持多 jobCode |

> 所有写操作均需 `X-Idempotency-Key` 请求头；涉及审批的操作若 `approvalId` 为空则先登记审批、否则直接执行。

**实现文件：**
- `batch-console-api/.../web/ConsoleJobController.java`（新）
- `batch-console-api/.../application/ConsoleJobApplicationService.java`（新）
- `batch-console-api/.../infrastructure/DefaultConsoleJobApplicationService.java`（新）

### 八、告警治理 — ✅ 已实现

控制器：`ConsoleAlertController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN` / `ROLE_CONFIG_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 确认告警 | POST | `/api/console/alerts/{alertId}/ack` | 告警状态 → ACKED |
| ✅ | 静默告警 | POST | `/api/console/alerts/{alertId}/silence` | 告警状态 → SILENCED |
| ✅ | 关闭告警 | POST | `/api/console/alerts/{alertId}/close` | 告警状态 → CLOSED |

**实现文件：**
- `batch-console-api/.../web/ConsoleAlertController.java`（新）
- `batch-console-api/.../application/ConsoleAlertApplicationService.java`（新）

### 九、Worker 运维 — ✅ 已实现

控制器：`ConsoleWorkerController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN` / `ROLE_CONFIG_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 优雅排空 | POST | `/api/console/workers/{workerCode}/drain` | Worker 停止接新任务，等待已认领任务完成 |
| ✅ | 强制下线 | POST | `/api/console/workers/{workerCode}/force-offline` | 立即下线 Worker |
| ✅ | 已认领任务 | GET | `/api/console/workers/{workerCode}/claimed-tasks` | 查询 Worker 当前持有的 task 列表 |

**实现文件：**
- `batch-console-api/.../web/ConsoleWorkerController.java`（新）
- `batch-console-api/.../application/ConsoleWorkerApplicationService.java`（新）

### 十、AI 对话 — ✅ 已实现

控制器：`ConsoleAiController` &nbsp;|&nbsp; 权限：不限角色（由业务逻辑内部 gate 控制）

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | AI 聊天 | POST | `/api/console/ai/chat` | Spring AI 单轮对话，含审计落库；需 `X-Idempotency-Key` |

**实现文件：**
- `batch-console-api/.../web/ConsoleAiController.java`（新）
- `batch-console-api/.../application/ConsoleAiApplicationService.java`（新）
- `batch-console-api/.../infrastructure/DefaultConsoleAiApplicationService.java`（新）

### 十一、调度快照代理 — ✅ 已实现

控制器：`ConsoleSchedulerSnapshotController` &nbsp;|&nbsp; 权限：`ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_CONFIG_ADMIN`

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 当前快照 | GET | `/api/console/scheduler/snapshot?tenantId=` | 实时调度状态：队列、配额、Worker 分布 |
| ✅ | 快照历史 | GET | `/api/console/scheduler/snapshot/history?tenantId=&limit=` | 最近 N 条快照历史（默认 20） |

**实现文件：**
- `batch-console-api/.../web/ConsoleSchedulerSnapshotController.java`（新）
- `batch-console-api/.../application/ConsoleOrchestratorProxyService.java`（新）

### 十二、文件下载 — ✅ 已实现

控制器：`ConsoleFileDownloadController` &nbsp;|&nbsp; 权限：无注解（依赖全局安全配置）

| 状态 | 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|------|
| ✅ | 下载文件 | GET | `/api/console/files/{fileId}/download?tenantId=&approvalId=` | 流式返回对象存储文件二进制；`approvalId` 可选 |

**实现文件：**
- `batch-console-api/.../web/ConsoleFileDownloadController.java`（新）
- `batch-console-api/.../application/ConsoleFileDownloadApplicationService.java`（新）

### 十三、Excel 批量维护 — ✅ 已实现

> 四类配置共享相同的"导出 → 上传 → 预览 → 应用"四步流程，差异仅在数据类型。

#### 作业定义 Excel（`ConsoleJobDefinitionExcelController`）

路径前缀：`/api/console/config/job-definitions/excel`

| 状态 | 接口 | 方法 | 路径后缀 | 说明 |
|------|------|------|---------|------|
| ✅ | 导出模板 | GET | `/export` | 按查询条件导出 `.xlsx` |
| ✅ | 上传 | POST | `/upload` | `multipart/form-data`，返回 `uploadToken` |
| ✅ | 预览 | GET | `/preview/{uploadToken}` | 解析结果及校验问题，不写库 |
| ✅ | 应用 | POST | `/apply/{uploadToken}` | 幂等写库，需 `X-Idempotency-Key` |

#### 工作流定义 Excel（`ConsoleWorkflowExcelController`）

路径前缀：`/api/console/config/workflows/excel`（同上四个端点）

#### 文件模板 Excel（`ConsoleFileTemplateExcelController`）

路径前缀：`/api/console/config/file-templates/excel`（同上四个端点）

#### 文件通道 Excel（`ConsoleFileChannelExcelController`）

路径前缀：`/api/console/config/file-channels/excel`（同上四个端点）

**实现文件：**
- `batch-console-api/.../web/ConsoleJobDefinitionExcelController.java`（新）
- `batch-console-api/.../web/ConsoleWorkflowExcelController.java`（新）
- `batch-console-api/.../web/ConsoleFileTemplateExcelController.java`（新）
- `batch-console-api/.../web/ConsoleFileChannelExcelController.java`（新）
- 对应 4 个 `ApplicationService` 接口 + 4 个 `DefaultConsole*ExcelApplicationService` 实现

### 十四、报表 Excel 导出 — ✅ 已实现

控制器：`ConsoleReportExcelController` &nbsp;|&nbsp; 路径前缀：`/api/console/reports/excel` &nbsp;|&nbsp; 权限：`ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_CONFIG_ADMIN`

| 状态 | 接口 | 方法 | 路径后缀 | 说明 |
|------|------|------|---------|------|
| ✅ | 配置发布单 | GET | `/config-releases` | 按查询条件导出 |
| ✅ | 密钥版本 | GET | `/secrets` | |
| ✅ | 配置变更日志 | GET | `/change-logs` | |
| ✅ | 审计日志 | GET | `/audits` | |
| ✅ | 调度快照 | GET | `/scheduler-snapshot?tenantId=` | 当前租户快照 |
| ✅ | 快照历史 | GET | `/scheduler-history?tenantId=&limit=` | |
| ✅ | Worker 列表 | GET | `/workers` | |
| ✅ | Outbox 重试日志 | GET | `/outbox-retries` | |
| ✅ | Outbox 投递日志 | GET | `/outbox-deliveries` | |

**实现文件：**
- `batch-console-api/.../web/ConsoleReportExcelController.java`（新）
- `batch-console-api/.../application/ConsoleReportExcelApplicationService.java`（新）
- `batch-console-api/.../infrastructure/DefaultConsoleReportExcelApplicationService.java`（新）

---

## 统计

| 优先级 | 计划接口数 | 已实现 | 剩余 | 状态 |
|--------|-----------|--------|------|------|
| **P0** | 27 | **25** | 2（实例 pause/resume 因 DB 约束移除） | ✅ 完成 |
| **P1** | 28 | **25** | 1（配置发布单/密钥列表已有，详情查询路径微调 -2） | ✅ 完成 |
| **P2** | 19 | **19** | 0 | ✅ 完成 |
| **P3** | 10 | **10** | 0 | ✅ 完成 |
| **P4** | 44 | **44** | 0 | ✅ 完成 |
| **合计** | 128 | **123** | 0 | ✅ 全部完成 |
