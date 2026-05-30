# P1-A Stage 1:console-api 子包归一迁移规划(2026-05-30)

> 目标:在不引入部署变化(单模块、单 jar)前提下,把 `batch-console-api` 内的 controller / application / infrastructure / mapper / entity / DTO 按 9 个有界上下文(bounded context)+ 1 个 `shared` 横切包重组到 `com.example.batch.console.domain.<context>.*` 之下,为后续 P1-A Stage 2(独立模块拆分)与 ArchUnit 包依赖约束铺路。
>
> 本文档只规划,不动代码。配套 ArchUnit 规则与脚本化迁移由后续 agent 处理。

---

## 1. 当前包结构盘点

源根:`batch-console-api/src/main/java/com/example/batch/console/`

| 顶层包 | 文件数 | 备注 |
|---|---:|---|
| `web/` | 260 | 56 个 Controller(直接位于 `web/`)+ `query/`(36)+ `realtime/`(8)+ `request/`(76)+ `response/`(81)+ 其他 |
| `domain/` | 93 | `entity/`(30)+ `param/`(15)+ `query/`(26)+ `view/`(13)+ `command/`(2)+ 其他 |
| `infrastructure/` | 75 | 按 `job` / `workflow` / `file` / `ops` / `config` / `monitor` / `report` / `realtime` / `ai` / `excel` / `query` 分包(已经是初步上下文化) |
| `mapper/` | 62 | MyBatis Mapper 接口与查询 mapper,扁平 |
| `support/` | 55 | 横切:`auth/`(13)`cache/`(4)`web/`(7)`excel/`(8)`ratelimit/`(2)`push/`(5)`audit/`(3)`maintenance/`(2)`naming/`(1)`querymap/`(4)+ 5 个根级 util |
| `application/` | 35 | 按 `job` / `workflow` / `file` / `config` / `ops` / `monitor` / `audit` / `ai` / `report` 分包 |
| `service/` | 22 | 扁平,新旧混杂的 service / factory / webhook 子集 |
| `config/` | 23 | Spring 配置类(CORS / Security / 读写分离 / Kafka / Async / 各 properties) |
| Application | 1 | `BatchConsoleApiApplication.java` |
| **合计** | **626** | |

测试源:`src/test/java` 约 160 个文件,Stage 1 同步迁移(命名跟随生产代码包)。

观察:
- `application/` 与 `infrastructure/` 已 **接近** 按上下文分,工作量主要集中在 `web/`、`mapper/`、`domain/`、`support/` 的重新归类。
- `service/` 是历史遗留,需要逐个判定上下文(部分会进入 `shared/`,如 `ConsoleResponseFactory`)。
- `domain/` 现按 *技术类别*(entity/param/query/view)切分,迁移后应改为 *按上下文切分*,内部再按 entity/query/view 分。

---

## 2. 9 个 bounded context 归类表

> 命名约定:`com.example.batch.console.domain.<ctx>.{web,application,infrastructure,domain,mapper}`(单模块包内层级)。
> 数字为粗略估算(controller + service + mapper + entity + request + response + param + query 合计),不含 `shared` 与 `config`。

### 2.1 job(任务定义 / 实例 / 调度)
约 **78** 个文件
- Controller:`ConsoleJobController`、`ConsoleJobDefinitionController`、`ConsoleJobBundleController`、`ConsoleInstanceController`、`ConsoleSelfServiceJobController`、`ConsoleSchedulerController`、`ConsoleSchedulerSnapshotController`、`ConsoleTriggerController`、`ConsoleBatchDayController`、`ConsoleBatchDayReplayController`、`ConsoleBatchWindowController`、`ConsoleCalendarController`、`ConsoleDryRunPlanController`、`ConsoleResultVersionController`
- Application:`application/job/*`(6)
- Infrastructure:`infrastructure/job/*`(6)
- Service:`ConsoleSelfServiceJobService`
- Mapper:`JobDefinitionMapper`、`JobInstanceMapper`、`JobPartitionMapper`、`JobStepInstanceMapper`、`BatchDayMapper`、`BatchWindowMapper`、`BusinessCalendarMapper`、`CalendarHolidayMapper`、`PendingCatchUpMapper`、`StepRegistryQueryMapper`
- Entity:`JobDefinitionEntity`、`JobInstanceEntity`、`JobStepInstanceEntity`、`JobPartitionEntity`、`PendingCatchUpEntity`
- 实时:`ConsoleJobInstanceRealtimeController`
- Request:`web/request/job/*`(14)、`web/request/batchday/*`(1)
- Response:`web/response/job/*`(5)
- Query / Param:`Job*Query`、`BatchDay*Query`、`PendingCatchUpQuery`、`Job*UpsertParam`、`BatchWindowUpsertParam`、`BusinessCalendarUpsertParam`

### 2.2 workflow(DAG / 节点 / 触发)
约 **35** 个文件
- Controller:`ConsoleWorkflowDefinitionController`、`ConsoleWorkflowRunController`、`ConsolePipelineDefinitionController`
- Application:`application/workflow/*`(2)
- Infrastructure:`infrastructure/workflow/*`(3)含 `WorkflowMermaidRenderer`
- Mapper:`WorkflowDefinitionMapper`、`WorkflowEdgeMapper`、`WorkflowNodeMapper`、`WorkflowNodeRunMapper`、`WorkflowRunMapper`、`PipelineDefinitionMapper`、`PipelineStepDefinitionMapper`
- Entity:`WorkflowDefinitionEntity`、`WorkflowEdgeEntity`、`WorkflowNodeEntity`、`WorkflowNodeRunEntity`
- 实时:`ConsoleWorkflowDefinitionRealtimeController`、`ConsoleWorkflowRunRealtimeController`、`ConsolePipelineDefinitionRealtimeController`
- Request:`web/request/workflow/*`(2)
- Response:`web/response/workflow/*`(9)
- Query/Param:`Workflow*Query`、`Workflow*UpsertParam`

### 2.3 file(文件传输 / 渠道 / 模板)
约 **48** 个文件
- Controller:`ConsoleFileController`、`ConsoleFileChannelController`、`ConsoleFileDownloadController`、`ConsoleFileTemplateController`、`ConsoleFilePipelineObservabilityController`(注:观测口径,但聚焦 file pipeline,放 file)
- Application:`application/file/*`(4)
- Infrastructure:`infrastructure/file/*`(4)
- Mapper:`FileRecordMapper`、`FileChannelConfigMapper`、`FileDispatchRecordMapper`、`FileErrorRecordMapper`、`FilePipelineMapper`、`FilePipelineStepRunMapper`、`FileTemplateConfigMapper`、`FileArrivalGroupMapper`
- Entity:`FileRecordEntity`、`FileErrorRecordEntity`、`FileArrivalGroupEntity`
- Request:`web/request/file/*`(17)
- Response:`web/response/file/*`(20)
- Query/Param:`File*Query`、`File*Param`

### 2.4 ops(运维 / 触发器代理 / outbox 工具 / 集群诊断)
约 **40** 个文件
- Controller:`ConsoleOpsController`、`ConsoleApprovalController`、`ConsoleConfigApprovalController`、`ConsoleWorkerController`、`ConsoleClusterDiagnosticController`、`ConsoleForensicController`、`ConsoleAdminMaintenanceController`、`ConsoleAdminTestDataController`
- Application:`application/ops/*`(6,含 OrchestratorProxy / TriggerProxy / OutboxOps / Worker / Approval / Ops)
- Infrastructure:`infrastructure/ops/*`(8)+ `infrastructure/query/ConsoleOpsQueryService`、`ConsoleJobOpsSupport`
- Service:`ConsoleClusterDiagnosticService`、`ConsoleAdminTestDataCleanupService`、`ConsoleKafkaLagQueryService`
- Mapper:`OutboxEventMapper`、`OutboxDeliveryLogMapper`、`OutboxRetryLogMapper`、`ApprovalCommandMapper`、`ConfigApprovalMapper`、`WorkerRegistryMapper`、`ResourceQueueMapper`、`RetryScheduleMapper`、`ConsoleClusterDiagnosticMapper`
- Entity:`ApprovalCommandEntity`、`WorkerRegistryEntity`、`RetryScheduleEntity`、`ResourceTagEntity`
- 实时:`ConsoleOutboxRealtimeController`、`ConsoleOpsRealtimeController`、`ConsoleWorkerRealtimeController`
- Request:`web/request/ops/*`(11)、`web/request/forensic/*`(1)
- Response:`web/response/ops/*`(21)
- Query:`OutboxDeliveryLogQuery`、`OutboxRetryLogQuery`、`ApprovalCommandQuery`、`WorkerRegistryQuery`、`RetryScheduleQuery`

### 2.5 governance(死信 / 重试 / 补偿 / 数据质量)
约 **8** 个文件
- Controller:`ConsoleGovernanceController`
- Mapper:`DeadLetterTaskMapper`
- Entity:`DeadLetterTaskEntity`
- Query:`DeadLetterTaskQuery`
- 注:此 context 内容偏少,重试 / 补偿主体在 `ops`(`RetryScheduleMapper`)与 `job`(`compensate` 操作)。Stage 1 暂仅承载 **死信清单 / 数据质量入口**;重试与补偿的合并由 P1-B 再行讨论。Stage 1 暂可以与 ops 合并,**保留独立包**以便后续拆分(零文件成本)。

### 2.6 notification(告警 / 渠道 / webhook / push)
约 **35** 个文件
- Controller:`ConsoleAlertController`、`ConsoleAlertRoutingController`、`ConsoleNotificationController`、`ConsolePushController`、`ConsoleWebhookController`、`ConsoleEventCatalogController`、`ConsoleSubscriptionController`(若存在)
- Application:`application/monitor/ConsoleAlertApplicationService`、`ConsoleNotificationApplicationService`(注:`monitor` 名义,实质是 alert + notification)
- Infrastructure:`infrastructure/monitor/Default*`(2)+ `infrastructure/realtime/ConsoleWebhookDomainEventListener`
- Service:`ConsolePushSubscriptionService`、`ConsoleWebhookService`、`WebhookDeliveryRelay`、`WebhookDeliveryResult`、`WebhookDispatcher`、`WebhookEventPayload`
- Support:`support/push/*`(5)
- Mapper:`AlertEventMapper`、`AlertRoutingConfigMapper`、`NotificationChannelMapper`、`NotificationDeliveryLogMapper`、`SubscriptionRuleMapper`、`ConsolePushApprovalNotificationMapper`、`ConsolePushJobNotificationMapper`、`ConsolePushSubscriptionMapper`、`ConsoleWebhookDeliveryLogMapper`、`ConsoleWebhookSubscriptionMapper`
- Entity:`WebhookSubscriptionEntity`、`WebhookDeliveryLogEntity`、`ConsolePushSubscriptionEntity`、`ConsolePushApprovalNotificationEntity`、`ConsolePushJobNotificationEntity`
- 实时:`ConsoleAlertRealtimeController`
- Request:`web/request/push/*`(2)
- Response:`web/response/auth/AiAuditLogResponse?` 否,放 audit;`web/response/push/*`(1)

### 2.7 audit(操作审计 / AI 审计)
约 **15** 个文件
- Controller:`ConsoleAiController`(用户态 + 审计入口)
- Application:`application/audit/OperationAuditQueryService`、`application/ai/ConsoleAiApplicationService`
- Infrastructure:`infrastructure/ai/Default*`(2)
- Service:`ConsoleAiAuthorizationService`、`ConsoleAiPromptGuard`
- Support:`support/audit/*`(3)、`support/ConsoleAiAuditService`、`support/auth/AiPromptGateResult`
- Mapper:`OperationAuditMapper`、`AuditLogQuery`(query)、`ConsoleAiAuditLogMapper`、`ConsoleAiAuditLogQuery`
- Entity:`ConsoleAiAuditLogEntity`
- Domain command:`AiChatCommand`、`AiAuditCommand`
- Response:`web/response/auth/AiAuditLogResponse`、`AiChatResponse`(实际属于 audit/ai,需要从 `auth/` 子包搬走)

### 2.8 rbac(用户 / 角色 / 权限 / 菜单 / session)
约 **30** 个文件
- Controller:`ConsoleAuthController`、`ConsoleUserAccountController`、`ConsoleApiKeyController`、`ConsoleTenantController`、`ConsoleTenantSelfServiceController`、`ConsoleTenantConfigInitController`、`ConsoleMetaController`
- Application:`application/config/ConsoleTenantConfigInitApplicationService`(部分,租户初始化)
- Service:`ConsoleAuthApplicationService`、`ConsoleUserAccountService`、`ConsoleApiKeyService`、`ConsoleMetaQueryService`、`ConsoleTenantApplicationService`、`ConsoleResourceTagService`
- Support:`support/auth/*`(13,不含 `AiPromptGateResult`)、`support/ConsoleMenuRegistry`、`SseTicketService`(放 rbac 还是 observability?见 §3)
- Mapper:`ConsoleUserAccountMapper`、`ConsoleApiKeyMapper`、`TenantMapper`、`ConsoleMetaQueryMapper`、`SecretVersionMapper`、`ConsoleResourceTagMapper`
- Entity:`ConsoleUserAccountEntity`、`ApiKeyEntity`、`SecretVersionEntity`、`ResourceTagEntity`(注:资源标签现状归 rbac,语义上偏 governance,可议)
- Request:`web/request/auth/*`(9)
- Response:`web/response/auth/*`(剔除 ai-* 后约 7)

### 2.9 observability(dashboard / SLA / 指标 / SSE 实时)
约 **30** 个文件
- Controller:`ConsoleDashboardController`、`ConsoleTelemetryController`、`ConsoleQueryController`、`ConsoleReportExcelController`、`ConsoleSystemController`、`ConsoleSystemParameterController`
- Application:`application/report/*`(2)
- Infrastructure:`infrastructure/report/*`(2)+ `infrastructure/query/ConsoleQuerySupport`、`ConsoleFileQueryService`、`ConsoleJobQueryService`、`ConsoleWorkflowQueryService`(注:query support 多上下文聚合,Stage 1 保留在 observability,标记为跨域热点)
- Infrastructure realtime:`infrastructure/realtime/*`(13,SSE / 实时事件中枢)
- Service:`ConsoleDashboardQueryService`、`ConsoleSystemParameterService`
- Mapper:`ConsoleDashboardQueryMapper`、`ConsoleSystemParameterMapper`
- Domain view:`domain/view/dashboard/*`(13)、`domain/view/cluster/*`(2)、`domain/view/meta/*`(1)
- 实时:`web/realtime/*`(8 个 controller)— 各自归对应上下文,SSE 基础设施(EventHub/Publisher/Bridge)放 observability
- Query:`PageQueryRequest`(若仅做泛型 → `shared/`)

---

## 3. shared 横切判定

放入 `com.example.batch.console.shared.*`(或保持 `support.*` 包名,语义即 shared),判定理由如下。

| 文件 / 包 | 归类 | 判定理由 |
|---|---|---|
| `service/ConsoleResponseFactory.java` | `shared/web` | 所有 controller 共用响应封装,无业务语义 |
| `support/web/*`(7) | `shared/web` | 过滤器、异常处理、幂等拦截器、请求上下文 — 跨全部 controller |
| `support/cache/*`(4) | `shared/cache` | 通用查询缓存 + 失效切面,被多上下文 application 共用 |
| `support/ratelimit/*`(2) | `shared/ratelimit` | 全局限流过滤器 |
| `support/maintenance/*`(2) | `shared/maintenance` | 维护模式过滤器,全局开关 |
| `support/naming/ReservedPrefixGuard` | `shared/naming` | 命名前缀守卫,被多个 controller 校验 |
| `support/excel/*`(8) | `shared/excel` | Excel 通用读写工具,被 config + file + report 共用;**注**:`AbstractSingleSheetExcelService`、`ConfigPackageExcelSchema/Validator/WorkbookWriter`(在 `infrastructure/excel`)留在 `infrastructure/excel`,因为是 config 上下文的实现;`support/excel` 是工具底座 |
| `support/querymap/*`(4) | `shared/querymap` | MyBatis ResultMap 映射工具,被 file/job/ops/workflow 四个上下文 query service 共用 — 是显式的跨上下文 read-model 适配层 |
| `support/ConfigChangeLogBuilder.java` | `shared/audit-helper`(或 `domain/audit/...`) | 仅 config 上下文使用,可放 audit;若仅 config 在用,**应归 config 而非 shared**。本规划:**归 audit**(因为 ConfigChangeLog 是审计制品) |
| `support/CallbackUrlValidator.java` | `shared/web` 或 `domain/notification` | 仅 notification(webhook + push)使用 → **归 notification**,不放 shared |
| `support/SseTicketService.java` | `shared/realtime` 或 `observability/realtime` | SSE 票据被所有实时 controller 用 → **归 observability/realtime**,因为是 SSE 基础设施一部分 |
| `support/ConsoleMenuRegistry.java` | `shared/web` 或 rbac | 菜单元数据,跟随 rbac;现状被 ConsoleAuthController 与 ConsoleMetaController 注入 → **归 rbac** |
| `support/ConsoleAiAuditService.java` | audit | 已在 §2.7 |
| `config/*`(23) | 保留 `config/` 包(Spring 启动配置) | 启动期框架配置不属于业务上下文。Stage 1 不动 |
| `BatchConsoleApiApplication.java` | 保留根包 | 不动 |
| `web/query/PageQueryRequest.java` | `shared/web` | 纯分页参数泛型 |
| `web/response/excel/*`(2) | `shared/web/excel` 或 `shared/excel` | 通用 Excel 行问题响应 |
| `domain/view/meta/SimpleOptionView.java` | `shared/view` | 通用下拉项视图 |

**shared 总计约 35–40 个文件**(含 `config/` 23 个保留,真正搬迁仅 ~15–17 个)。

---

## 4. 跨上下文耦合热点

通过 `grep "import com.example.batch.console.mapper\." infrastructure/<ctx>/*.java`、`grep "console\.application\." support/` 排查。下列条目按耦合严重程度从高到低排列:

1. **`infrastructure/config/DefaultConsoleTenantConfigCopyService` → 14 个 mapper(跨 5+ context)**
   引用 `AlertRoutingConfig`、`BatchWindow`、`BusinessCalendar`、`CalendarHoliday`、`FileChannelConfig`、`FileTemplateConfig`、`JobDefinition`、`PipelineDefinition`、`PipelineStepDefinition`、`ResourceQueue`、`TenantQuotaPolicy`、`WorkflowDefinition`、`WorkflowEdge`、`WorkflowNode`。
   → 租户配置复制本质是跨上下文事务,Stage 1 维持单进程内直接依赖,**显式标注为跨域工件**;P1-B 需要引入应用服务编排或 outbox。

2. **`infrastructure/config/TenantConfigInitApplyHandlers` → 14 mapper**(同上,逐字段写入)
   与上一项配对。

3. **`infrastructure/config/DefaultConsoleTenantConfigPackageExcelApplicationService` → 13 mapper**
   Excel 导入,跨 file / workflow / job / config / ops。

4. **`infrastructure/ops/DefaultConsoleOpsApplicationService` → AlertEventMapper + ApprovalCommandMapper + JobInstanceMapper + OutboxDeliveryLogMapper + OutboxRetryLogMapper + WorkerRegistryMapper**
   ops 概览聚合 5 个上下文的状态 — 健康摘要;**建议拆分为只读 query service**,经 observability 收口或保留为 ops 自治。

5. **`infrastructure/query/ConsoleQuerySupport`(及 `ConsoleJobOpsSupport`、`ConsoleFileQueryService`、`ConsoleJobQueryService`、`ConsoleWorkflowQueryService`、`ConsoleOpsQueryService`)**
   这一组 `infrastructure/query/*` 是面向 dashboard 的横切只读层,**几乎一定要拆**,但拆法影响下游;Stage 1 保留集中,标 **高耦合**。

6. **`web/ConsoleJobController` → 同时依赖 `web/request/job/*` 与 `web/request/ops/*`**(`BatchDayCatchUpRequest`、`ConsoleCatchUpApprovalRequest`、`DeadLetterReplayRequest`)以及 `web/response/file/ConsoleBatchDayCatchUpResponse`
   注:这些 request 类的归属本身有歧义 — batch-day catch up 既是 job 又是 ops 操作。**建议归 job**,删除 `web/request/ops/BatchDay*Request` 至 `web/request/job/`。

7. **`infrastructure/config/DefaultConsoleConfigApplicationService` → ConsoleDashboardQueryMapper**
   config 上下文反查 dashboard 数据(配置依赖度)。属于 read-model 反向引用,Stage 1 保留,标 **中**。

8. **`web/response/auth/AiAuditLogResponse.java` 与 `AiChatResponse.java` 在 `auth/` 子包下**,实际属 audit/ai。**包路径错位**,移动到 `domain/audit/web/response/`。

9. **`infrastructure/monitor/DefaultConsoleNotificationApplicationService` → SubscriptionRuleMapper / NotificationChannelMapper / NotificationDeliveryLogMapper**
   `monitor` 命名 + notification 实质 — 名实不符。**全部归 notification**;`monitor` 包名删除。

10. **`infrastructure/realtime/ConsoleWebhookDomainEventListener`**
    realtime 基础设施 + notification 业务订阅 — 属 notification(订阅域事件并 webhook 派发)。

11. **`support/push/*`(5)** 被 `application/job` + `application/ops`(审批通知)+ `infrastructure/monitor` 三处注入 — 实质是 notification 的子能力(push 通道)。**整组归 notification**。

12. **`support/querymap/*`(4)** 分别对应 file / job / ops / workflow 的 read-model 行映射;**每个 mapper 跟随其上下文**,而非集中放 `shared/`。**重新分配** 4 个文件到 4 个上下文。

13. **`infrastructure/query/ConsoleJobOpsSupport`** — 名字 + 内容双重跨域(job × ops),归 ops。

14. **`infrastructure/excel/CronExpressionFormatRule` 与 `WorkflowExcelTextUtils` / `WorkflowExcelKeys` / `WorkflowExcelColumnMetadata` / `WorkflowNodeStartEndCodeRule`**
    Workflow 专用的 Excel 规则被 `config` 包导入(tenant package excel),实际是 workflow 的导入策略 — 应归 workflow,由 config 反向依赖。Stage 1 暂保留 `infrastructure/excel/` 集中,标为跨域,P1-B 解耦。

15. **`web/realtime/*` 8 个 controller** 每个跟随其上下文(job 实例、worker、outbox、workflow run/def、pipeline def、alert、ops summary);但都依赖 `infrastructure/realtime` 同一组 hub/publisher → realtime 基础设施归 observability,业务 SSE controller 各归各家。

---

## 5. 包路径映射(示例 30+)

> 规则:`com.example.batch.console.{web,application,infrastructure,service,mapper,domain.entity}.X` → `com.example.batch.console.domain.<ctx>.{web,application,infrastructure,mapper,entity}.X`。

| # | 旧路径 | 新路径 |
|---:|---|---|
| 1 | `web.ConsoleJobController` | `domain.job.web.ConsoleJobController` |
| 2 | `web.ConsoleJobDefinitionController` | `domain.job.web.ConsoleJobDefinitionController` |
| 3 | `web.ConsoleInstanceController` | `domain.job.web.ConsoleInstanceController` |
| 4 | `web.ConsoleBatchDayController` | `domain.job.web.ConsoleBatchDayController` |
| 5 | `web.ConsoleSchedulerController` | `domain.job.web.ConsoleSchedulerController` |
| 6 | `web.ConsoleWorkflowDefinitionController` | `domain.workflow.web.ConsoleWorkflowDefinitionController` |
| 7 | `web.ConsoleWorkflowRunController` | `domain.workflow.web.ConsoleWorkflowRunController` |
| 8 | `web.ConsolePipelineDefinitionController` | `domain.workflow.web.ConsolePipelineDefinitionController` |
| 9 | `web.ConsoleFileController` | `domain.file.web.ConsoleFileController` |
| 10 | `web.ConsoleFileChannelController` | `domain.file.web.ConsoleFileChannelController` |
| 11 | `web.ConsoleFileTemplateController` | `domain.file.web.ConsoleFileTemplateController` |
| 12 | `web.ConsoleFilePipelineObservabilityController` | `domain.file.web.ConsoleFilePipelineObservabilityController` |
| 13 | `web.ConsoleOpsController` | `domain.ops.web.ConsoleOpsController` |
| 14 | `web.ConsoleApprovalController` | `domain.ops.web.ConsoleApprovalController` |
| 15 | `web.ConsoleWorkerController` | `domain.ops.web.ConsoleWorkerController` |
| 16 | `web.ConsoleClusterDiagnosticController` | `domain.ops.web.ConsoleClusterDiagnosticController` |
| 17 | `web.ConsoleGovernanceController` | `domain.governance.web.ConsoleGovernanceController` |
| 18 | `web.ConsoleAlertController` | `domain.notification.web.ConsoleAlertController` |
| 19 | `web.ConsoleAlertRoutingController` | `domain.notification.web.ConsoleAlertRoutingController` |
| 20 | `web.ConsoleNotificationController` | `domain.notification.web.ConsoleNotificationController` |
| 21 | `web.ConsolePushController` | `domain.notification.web.ConsolePushController` |
| 22 | `web.ConsoleWebhookController` | `domain.notification.web.ConsoleWebhookController` |
| 23 | `web.ConsoleAiController` | `domain.audit.web.ConsoleAiController` |
| 24 | `web.ConsoleAuthController` | `domain.rbac.web.ConsoleAuthController` |
| 25 | `web.ConsoleUserAccountController` | `domain.rbac.web.ConsoleUserAccountController` |
| 26 | `web.ConsoleApiKeyController` | `domain.rbac.web.ConsoleApiKeyController` |
| 27 | `web.ConsoleTenantController` | `domain.rbac.web.ConsoleTenantController` |
| 28 | `web.ConsoleMetaController` | `domain.rbac.web.ConsoleMetaController` |
| 29 | `web.ConsoleDashboardController` | `domain.observability.web.ConsoleDashboardController` |
| 30 | `web.ConsoleTelemetryController` | `domain.observability.web.ConsoleTelemetryController` |
| 31 | `web.ConsoleReportExcelController` | `domain.observability.web.ConsoleReportExcelController` |
| 32 | `web.ConsoleSystemController` | `domain.observability.web.ConsoleSystemController` |
| 33 | `web.realtime.ConsoleJobInstanceRealtimeController` | `domain.job.web.realtime.ConsoleJobInstanceRealtimeController` |
| 34 | `web.realtime.ConsoleAlertRealtimeController` | `domain.notification.web.realtime.ConsoleAlertRealtimeController` |
| 35 | `web.realtime.ConsoleOutboxRealtimeController` | `domain.ops.web.realtime.ConsoleOutboxRealtimeController` |
| 36 | `application.job.ConsoleJobApplicationService` | `domain.job.application.ConsoleJobApplicationService` |
| 37 | `application.workflow.ConsoleWorkflowDefinitionApplicationService` | `domain.workflow.application.ConsoleWorkflowDefinitionApplicationService` |
| 38 | `application.monitor.ConsoleAlertApplicationService` | `domain.notification.application.ConsoleAlertApplicationService` |
| 39 | `application.monitor.ConsoleNotificationApplicationService` | `domain.notification.application.ConsoleNotificationApplicationService` |
| 40 | `application.audit.OperationAuditQueryService` | `domain.audit.application.OperationAuditQueryService` |
| 41 | `application.ai.ConsoleAiApplicationService` | `domain.audit.application.ConsoleAiApplicationService` |
| 42 | `application.report.ConsoleQueryApplicationService` | `domain.observability.application.ConsoleQueryApplicationService` |
| 43 | `infrastructure.job.DefaultConsoleJobApplicationService` | `domain.job.infrastructure.DefaultConsoleJobApplicationService` |
| 44 | `infrastructure.ops.DefaultConsoleOpsApplicationService` | `domain.ops.infrastructure.DefaultConsoleOpsApplicationService` |
| 45 | `infrastructure.realtime.ConsoleRealtimeEventHub` | `domain.observability.infrastructure.realtime.ConsoleRealtimeEventHub` |
| 46 | `infrastructure.realtime.ConsoleWebhookDomainEventListener` | `domain.notification.infrastructure.ConsoleWebhookDomainEventListener` |
| 47 | `mapper.JobDefinitionMapper` | `domain.job.mapper.JobDefinitionMapper` |
| 48 | `mapper.JobInstanceMapper` | `domain.job.mapper.JobInstanceMapper` |
| 49 | `mapper.WorkflowDefinitionMapper` | `domain.workflow.mapper.WorkflowDefinitionMapper` |
| 50 | `mapper.FileRecordMapper` | `domain.file.mapper.FileRecordMapper` |
| 51 | `mapper.OutboxEventMapper` | `domain.ops.mapper.OutboxEventMapper` |
| 52 | `mapper.DeadLetterTaskMapper` | `domain.governance.mapper.DeadLetterTaskMapper` |
| 53 | `mapper.AlertEventMapper` | `domain.notification.mapper.AlertEventMapper` |
| 54 | `mapper.OperationAuditMapper` | `domain.audit.mapper.OperationAuditMapper` |
| 55 | `mapper.ConsoleUserAccountMapper` | `domain.rbac.mapper.ConsoleUserAccountMapper` |
| 56 | `mapper.ConsoleDashboardQueryMapper` | `domain.observability.mapper.ConsoleDashboardQueryMapper` |
| 57 | `domain.entity.JobDefinitionEntity` | `domain.job.entity.JobDefinitionEntity` |
| 58 | `domain.entity.WorkflowDefinitionEntity` | `domain.workflow.entity.WorkflowDefinitionEntity` |
| 59 | `domain.entity.FileRecordEntity` | `domain.file.entity.FileRecordEntity` |
| 60 | `domain.entity.ApprovalCommandEntity` | `domain.ops.entity.ApprovalCommandEntity` |
| 61 | `domain.entity.DeadLetterTaskEntity` | `domain.governance.entity.DeadLetterTaskEntity` |
| 62 | `domain.entity.WebhookSubscriptionEntity` | `domain.notification.entity.WebhookSubscriptionEntity` |
| 63 | `domain.entity.ConsoleAiAuditLogEntity` | `domain.audit.entity.ConsoleAiAuditLogEntity` |
| 64 | `domain.entity.ConsoleUserAccountEntity` | `domain.rbac.entity.ConsoleUserAccountEntity` |
| 65 | `service.ConsoleResponseFactory` | `shared.web.ConsoleResponseFactory` |
| 66 | `support.web.ConsoleApiExceptionHandler` | `shared.web.ConsoleApiExceptionHandler` |
| 67 | `support.cache.ConsoleQueryCacheService` | `shared.cache.ConsoleQueryCacheService` |
| 68 | `support.auth.ConsoleJwtService` | `domain.rbac.support.ConsoleJwtService` |
| 69 | `support.push.ConsolePushSender` | `domain.notification.support.ConsolePushSender` |
| 70 | `support.audit.AuditAspect` | `domain.audit.support.AuditAspect` |
| 71 | `support.querymap.ConsoleFileQueryMappers` | `domain.file.support.ConsoleFileQueryMappers` |
| 72 | `support.querymap.ConsoleJobQueryMappers` | `domain.job.support.ConsoleJobQueryMappers` |
| 73 | `support.querymap.ConsoleOpsQueryMappers` | `domain.ops.support.ConsoleOpsQueryMappers` |
| 74 | `support.querymap.ConsoleWorkflowQueryMappers` | `domain.workflow.support.ConsoleWorkflowQueryMappers` |
| 75 | `support.SseTicketService` | `domain.observability.support.SseTicketService` |
| 76 | `web.response.auth.AiAuditLogResponse` | `domain.audit.web.response.AiAuditLogResponse` |
| 77 | `web.response.auth.AiChatResponse` | `domain.audit.web.response.AiChatResponse` |
| 78 | `web.query.PageQueryRequest` | `shared.web.PageQueryRequest` |
| 79 | `domain.view.dashboard.SlaStatsView` | `domain.observability.view.SlaStatsView` |
| 80 | `domain.view.meta.SimpleOptionView` | `shared.view.SimpleOptionView` |

> 完整 626 文件映射表可通过脚本 `scripts/p1a-stage1-rename.py` 生成(后续 agent 实施)。

---

## 6. 迁移顺序建议(分批)

> 原则:**先低耦合上下文做包路径搬迁,跑通完整构建 + ArchUnit,再做下一批**。每批一次性提交一个 PR,避免长开分支。
> Stage 1 不变更类内行为,仅 package + import + MyBatis namespace 调整。MyBatis XML 的 `namespace` 与 `resultType` 全限定名需同步,**注意 `application.yml` / `mybatis-plus` 配置中 `mapper-locations` 若按 wildcard 没问题;`type-aliases-package` 必须更新**。

### 批次 1:基础低耦合(目标 1–2 天)
- **governance**(8 文件)— 最少,先打通 “建包 + 改 import + MyBatis namespace + 单元测试” 流水线
- **rbac**(~30)— 自包性强,几乎不与业务上下文双向依赖

### 批次 2:中等耦合业务(目标 3–4 天)
- **audit**(~15)— 单向被引用,内部 ai/operation 拆分清晰
- **workflow**(~35)— application/infrastructure 已分,主要是 mapper + 实体 + request/response 搬迁
- **file**(~48)— request/response 已分子包,可批量搬

### 批次 3:中高耦合(目标 3–4 天)
- **job**(~78)— 与 ops 有双向(catch-up、approval、recovery)
- **notification**(~35)— 涉及 `monitor` 包改名 + `support/push` 拆分,触面广

### 批次 4:跨域聚合(目标 2–3 天)
- **ops**(~40)— 聚合多上下文 mapper(见 §4#4)。搬迁后保留耦合,标注 `// XXX: cross-context until P1-B`
- **observability**(~30)— infrastructure/realtime 13 个文件搬迁;realtime controller 跟随业务 ctx 走;`infrastructure/query/*` 子模块整体平移

### 批次 5:shared 与收尾(目标 1–2 天)
- 抽出 `shared/web`、`shared/cache`、`shared/ratelimit`、`shared/maintenance`、`shared/excel`、`shared/view`
- 删除空旧包(`web/`、`mapper/`、`domain/entity` 旧路径)
- 同步 `src/test/java` 包路径 + ArchUnit 基线测试落地
- `config/` 与 `BatchConsoleApiApplication` 保持原位

每批必做的固定检查清单:
- `mvn -pl batch-console-api -am clean test` 全绿
- IDE inspect: unused imports / cyclic packages
- MyBatis-Plus `type-aliases-package` & XML `namespace` 全部更新
- Swagger / 路径不变(只动包,不动 `@RequestMapping`)
- 数据库 / Redis key 不变

---

## 7. 风险 + 工程量预估

### 风险表(按上下文)

| Context | 风险 | 主要原因 |
|---|:---:|---|
| governance | 低 | 文件少,耦合面窄 |
| rbac | 低 | 包内自洽,仅被横切引用 |
| audit | 低 | 单向依赖(被注入,不反向 fan-out) |
| workflow | 中 | infrastructure/excel 中的 workflow 规则被 config 反向引用 |
| file | 中 | request/response/Mapper 数量大;file pipeline observability controller 上下文模糊 |
| job | 中 | 与 ops 双向(catch-up、approval),与 file 共享 batchday 视图 |
| notification | 中 | 现有 `monitor` 包名实质是 notification,需要包重命名;`support/push` 散在 3 处 |
| ops | **高** | 概览聚合 5+ mapper(Alert/Approval/JobInstance/Outbox/Worker);`infrastructure/query/ConsoleOpsQueryService` 与 `ConsoleJobOpsSupport` 跨域 |
| observability | **高** | `infrastructure/realtime`(13)+ `infrastructure/query`(6)是横向聚合层,SSE 票据 + 事件中枢牵涉全部业务 controller |
| shared | 中 | querymap / push 的拆分判定有歧义,需要二次评审 |

### 工程量预估

| 项目 | 数量 |
|---|---:|
| 总迁移生产文件数(扣除 `config/` 与 Application) | **~600** |
| 同步迁移测试文件数 | **~160** |
| 新增包目录 | 9 contexts × 平均 4 层 + `shared` ≈ **40 个新目录** |
| 需要更新的 MyBatis XML(全限定名 / namespace) | **~62** |
| 需要修订的 Spring/MyBatis 配置项 | `type-aliases-package`、`mapper-scan` 至少 **2 处** |
| 跨上下文已知耦合点(需 `// XXX:` 标注,P1-B 解耦) | **~15** |
| ArchUnit 规则数(后续 agent) | 9 contexts × allowed-targets + 1 shared 规则 ≈ **10–12 条** |
| **总工时**(单工程师全时) | **8–10 人天**(批次 1+2 约 4 天,批次 3 约 3 天,批次 4+5 约 3 天,含返工与 review) |
| **建议节奏** | 2 周内完成 5 批 PR,每 PR 控制在 < 80 个文件 / < 1500 行 import-only diff |

### 实施前置条件

1. **ArchUnit 测试先落地**(空规则即可),便于每批 PR 增量收紧约束。
2. **CI 强制跑** `mvn verify` + ArchUnit。
3. **冻结期**:迁移期间 console-api 暂缓非紧急新功能,避免 merge 冲突放大。
4. **回滚预案**:每批 PR 独立 revert;由于不动行为,revert 安全。
5. **脚本化**:`git mv` + sed 批量改 package + import 的脚本应在批次 1 跑通后固化为 `scripts/p1a-stage1-rename.{py,sh}`。

---

## 附录 A:统计口径
- 文件扫描时间:2026-05-30
- 源根:`batch-console-api/src/main/java/com/example/batch/console`
- 工具:`find … -name '*.java'`、`grep -rE 'import com\.example\.batch\.console\.…'`
- 数据未含 `src/test`、`src/main/resources`

## 附录 B:遗留议题(给 P1-B / P1-C)
1. `infrastructure/config` 跨 14 个 mapper 的租户配置复制 / Excel 导入 — 需要事务边界 + outbox 协议化(P1-B)。
2. `infrastructure/query/ConsoleQuerySupport` 与 `ConsoleJobOpsSupport` 集中的只读聚合层 — Stage 2 模块化前考虑是否独立为 `console-readmodel` 子模块。
3. `ResourceTagEntity` 归 rbac 还是 governance,待业务侧确认(目前归 rbac)。
4. `governance` 上下文文件数 < 10,是否在 Stage 2 与 `ops` 合并或保留独立,P1-B 评估。
5. `support/excel/` 工具与 `infrastructure/excel/` 规则的分层:Stage 1 暂保留;P1-B 评估是否把 workflow / file / config 各自的 Excel 规则下沉到对应 context 的 infrastructure。
