# 前后端 Bug 审查与验证汇总（2026-10-27）

## 2026-10-31：补跑日方法拆分以通过 NCSS

`d23a51483` 的 pr-gate 里，`DefaultConsoleJobApprovalService.catchUpBatchDay` 的 NCSS 为 60，达到 PMD `NcssCount` 方法阈值。请求作业码的清洗抽到 `sanitizedRequestedJobCodes`。空列表仍返回 null；非空时仍过滤空白、按 128 截断并去重。`request.getJobCodes()` 仍只调用一次。同一提交刷新 `docs/stats/java-readability-inventory.md`，行数变化来自本分支已经落地的判空收敛。

验证：`batch-console-api`、`batch-orchestrator`、`batch-trigger`、`batch-worker/process`、`batch-worker/export` 及 reactor 依赖的 `pmd:check` 通过。`DefaultConsoleJobApprovalServiceWorkflowTest` 14 项通过。未改 SQL、接口或迁移。

## 2026-10-31：检查通过后复用的访问器只取一次

接收方和字段不是同一表达式时，判空仍然保留。本轮只处理检查通过后还会再次调用同一 getter 的 16 个生产文件：先确认接收方，再绑定字段，后续使用这份局部变量。空请求、空集合和失败分支仍按原顺序返回，不提前调用被短路的 getter。`quartzIdentity` 的租户标识仍只按 null 拒绝。一次调用的私有薄包装和前端包装候选仍按原记录保留。

验证：触及文件定向 Spotless 通过。定向测试合计 297 项、0 失败。覆盖 `CalendarBizDateResolverTest` 9、`DefaultTriggerServiceTest` 32、`DefaultTaskCreationServiceTest` 25、`WorkflowRunReplayPreviewServiceTest` 40、`ChildJobLaunchSupportTest` 5、`DefaultPartitionDispatchServiceTest` 14、`DefaultPartitionLifecycleServiceTest` 19、`DefaultTaskAssignmentServiceTest` 30、`TaskOutcomeDagProgressorTest` 2、`TaskOutcomeSummaryBuilderTest` 8、`DefaultWorkflowRunLaunchServiceReplayTest` 11、`WorkflowRunReportLockTest` 2、`SqlTemplateExportSqlValidatorTest` 41、`SqlTransformComputeSqlValidatorTest` 25、`ConsoleFilePipelineProgressBridgeTest` 10、`WorkflowDagValidatorTest` 24。未跑全 reactor 测试，未跑增量 Sonar，也未改 SQL、接口或迁移。

## 2026-10-31：同一取值的重复判空再收敛

上一节只处理简单标识符。本轮处理同一 getter 或访问器被判空两次的 20 处：先取一次局部变量，再交给 `isBlank` 或 `isEmpty`。通过检查后还会使用该值时，后续也用这份局部变量。`WorkflowRunExecutionSnapshot.from`、启动请求校验、`quartzIdentity` 和冻结图 `nodes()` 仍先确认接收方非空，再调用 getter。`quartzIdentity` 的租户标识仍只按 null 拒绝，空白字符串保持原口径。

另外 10 处已经是同一简单值或已绑定 getter 的 `isNotNull(x) && isNotBlank(x)`、`isNotNull(x) && !isBlank(x)`，收成 `isNotBlank(x)`。接收方和字段不是同一表达式的组合保持原样。一次调用的私有薄包装和前端包装候选仍按原记录保留。

验证：触及文件定向 Spotless 通过。定向测试合计 291 项、0 失败；`PlatformHttpClientTest` 另有 1 项跳过。覆盖 `NamespacedObjectStoreTest` 3、`QuartzLaunchJobTest` 12、`TriggerSchedulerFacadeTest` 25、`PlatformHttpClientTest` 15、`BatchDayReplayServiceTest` 31、`DefaultPartitionLifecycleServiceTest` 19、`NodePartitionProgressCalculatorTest` 3、`DefaultWorkflowRunLaunchServiceReplayTest` 11、`WorkflowRunExecutionSnapshotTest` 11、`DefaultLaunchValidationServiceTest` 16、`DefaultWorkerRegistryServiceTest` 35、`JobDependencyWaitingLaunchServiceTest` 69、`BatchManifestTest` 9、`DefaultConsoleJobApprovalServiceWorkflowTest` 14、`ConsoleSelfServiceJobServiceTest` 5、`WorkflowExecutionGraphAssemblerTest` 9、`TenantConfigPackageRowProjectionsMonitoringPolicyTest` 4。同名 Worker Core 的 `DefaultWorkerRegistryServiceTest` 7 项也通过，该类未改。未跑全 reactor 测试，未跑增量 Sonar，也未改 SQL、接口或迁移。

## 2026-10-31：简单变量重复判空收敛

2026-10-28 扫描列出的 71 处 `EmptyChecks.isNull(x) || isBlank/isEmpty(x)` 都是同一简单标识符。`isBlank` 与 `isEmpty` 的各重载已经把 null 视为空，两段判断结果相同。现只保留后一段调用。null 集合仍在进入 `size()` 或 `stream()` 之前返回，短路顺序保持。

本轮改动落在当前 `main` 的 45 个生产文件。方法调用形式仍会求值两次，包括 `request.traceId()`、`request.getJobCodes()`、`run.getTenantId()` 与 `node.getNodeCode()`，本轮未改。一次调用的私有薄包装和前端包装候选仍按原记录保留。

验证：触及文件定向 Spotless 通过。`EmptyChecksTest`、`SdkTaskContextTest`、`TaskDispatchExecutorTest`、`PipelineProgressQueryServiceTest` 合计 25 项通过（batch-common 3、Java SDK 13、Worker Core 6、Console 3）。随后 Trigger、Orchestrator、Import、Export、Process、Dispatch 生产编译通过。未跑全 reactor 测试，也未改 SQL、接口或迁移。

## 2026-10-30：批量日补跑根请求修复复核

沿 `BUG-20261030-BATCHDAY-CATCHUP-ROOT` 及 `BUG-20261030-BATCHDAY-PARENT-IDENTITY` 核对当前源码：批日调度器生成 `tenant:batch-day-catchup:calendar:date:job` 键；根请求读取及失败日扫描都按租户、该键、`CATCH_UP` 类型和空父 Workflow 限定，避免同键子 Job 遮蔽根请求。V369 部分唯一索引覆盖该键前缀的同租户 `CATCH_UP` 根请求，不限制子请求。Trigger 通用按键回查也排除子请求，但保留别名匹配和原有排序/单条选择；其业务范围比批日根请求查询更广，不能把两条 SQL 简化为同一口径。

本次仅核对现有 SQL、索引和直接调用链，未重复运行此前成功的真实数据库、启动服务及独立进程回查测试；具体证据和测试环境限制见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)。这不证明所有 Trigger 请求类型或目标环境已完成验证。

## 2026-10-30：通知投递日志直接消费者复核

核对 `BUG-20261030-NOTIFICATION-JSONB-MASK` 与 `BUG-20261030-NOTIFICATION-LOG-UI`：后端以 snake_case 返回已遮罩的 JSON 值，OpenAPI 和前端生成类型将两个动态字段声明为 `unknown`；前端 API 适配器原样传递 JSON，并将渠道、尝试次数和错误摘要映射为页面使用的字段。当前通知投递日志页面展示渠道、事件、状态、次数和错误摘要，筛选使用事件与渠道，并不展示或字符串化这两个 JSON 字段。租户切换时页面用请求作用域排除过期响应。

本次为当前源码与直接消费者静态复核，前端适配器、页面及适配器测试的 SHA-256 前 12 位分别为 `039b3ceeffba`、`a5e1a19f639b`、`d21c0add56fb`；未重复运行此前成功的测试。隔离数据库与真实 HTTP 遮罩验证、前端模拟客户端和组件测试的范围分别见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)相应条目；真实浏览器与真实后端同次联验及生产权限仍未验证。

## 2026-10-30：文件身份解析修复的直接调用链核对

针对 `BUG-20261029-FILE-ID-NUMERIC` 核对当前启动、任务创建和结果回报代码：三处身份转换均先转文本并以 `Long.parseLong` 解析，只接受正整数；小数、溢出及无效值不会通过数值截断关联到其他文件。启动时有效父实例按租户查询，未解析时沿既有空值分支处理；文件身份由引用校验器检查。任务创建对同一解析结果执行引用校验并写入步骤，结果回报将解析结果传入步骤进度更新。Workflow 启动的父实例也使用同一启动参数解析器和租户查询。

本次保留现有优先级：普通任务按正值候选回退；DISPATCH 遇到已提供的 `bundleSourceFileId` 或 `fileId` 时使用相应字段的解析结果；启动参数则先选择首个非空字段再解析。没有更改无效值应否拒绝及候选回退语义。三处生产源码 SHA-256 前 12 位分别为 `4e7fbafa3e76`、`973507789425`、`89d034f9f401`，对应启动解析、任务创建、结果载荷解析。此为源码与直接调用链复核，未重复运行先前成功的 51 项定向测试及两个真实数据库测试；历史执行证据和跨进程未验证边界见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)。

## 2026-10-30：四项证据新鲜度核对

本次核对下节四份来源：Trigger Relay 与 Mapper 的 SHA-256 仍分别为 `619c7405d0cb`、`9c67d71e95f7`；配置初始化集成测试和前端生成类型已变化，当前摘要分别为 `44a5fab394c5`、`9b6e3423cb63`。当前测试仍包含真实数据库 savepoint 回滚首项、提交第二项及统计断言；这只是源码核对，不能单凭它认定变化后的整类测试已运行。前端类型随后因 Workflow 节点名称契约变更重新生成并做过生成漂移及类型检查，见[治理记录](../audit/backend-25-package-governance-2026-10-26.md#已执行验证与未关闭项)的 `BUG-20261030-WORKFLOW-NODE-NAME`；不推断全部页面交互已验证。本次未重复运行业务测试或前端检查。

## 2026-10-28：剩余四项定向核查

本次核查以下当前源码和直接契约，未发现新增确认缺陷，未修改生产代码：

1. `TenantConfigInitCommitOutcomeIntegrationTest` 新增 savepoint 反例：实际回滚首项、提交第二项，并检查数据库与 created/failed 统计；`TenantConfigInitApplyHandlers.applySpecs` 在嵌套事务完成后才记录成功。已有真实 PostgreSQL 先红后绿及六分支证据见[治理记录](../audit/backend-25-package-governance-2026-10-26.md#已执行验证与未关闭项)，该证据对应当时快照，本次未重跑。
2. `TriggerOutboxRelay` 与 `TriggerOutboxEventMapper.xml`：当前批次领取生成 UUID，成功、失败和放弃按状态及 token 围栏更新，超时回收清除 token；写入型 RETURNING 声明刷新缓存。V361 同步主表与归档镜像。历史真实 PostgreSQL 重领反例与 Kafka 定向证据见同一治理记录的 BUG-20261027-TRG-02；未复验双实例锁失效和存量升级。
3. `src/types/api.generated.ts`：对配对后端本地 OpenAPI 执行 `npm run gen:api:check`，生成比较通过（`/private/tmp/wdi-four-api-drift-20261028.log`）。这是契约生成一致性检查，不代表完整前端交互验收。

读取时四份来源 SHA-256 依次为 `615a6e16452ff016fc90b71182329cae3271a4ec6523c351e0fa8a4c33efde18`、`619c7405d0cb42bf18c4d61c2a72892e517a47cf92a88b0ac5a7ea19eb59c4a6`、`9c67d71e95f78f91473f8b17bcd24f3574be992cae469021d082b69864dee80b`、`fee525808716d9c3dfc39609b30dcf90e1f6fcef4e681b645d2046b8d15d47b6`。按用户要求，仅针对新发现问题的类运行测试；本次未发现新增确认问题，未重跑业务类测试。下方10月27日待复核状态为当时记录，以本节为最新核查结论。

## 2026-10-28：前后端及未跟踪源码模式扫描

按用户要求只记录，本次未修改业务或测试代码，未运行测试。通过 Git 已跟踪与未跟踪且未忽略的文件清单扫描后端 4,548 份、前端 1,044 份 Java/TypeScript/JavaScript/Vue 源码，排除依赖与构建产物；该分母是文件枚举，不等于逐文件语义审核。以下行号为扫描快照，后续变动时应重新定位。

- **重复判空**：后端71处、45个文件符合 `isNull(x) || isBlank/isEmpty(x)` 模式；已核对工具对 null 的处理，简单局部变量的重复分支可作为精简候选。逐处仍须检查类型重载、求值顺序、上下文及实际维护状态。
- **一次调用的私有薄包装**：后端110处语法候选，仅以单文件出现次数和单行 return 初筛；有领域命名、递归、方法引用、反射、接口/代理或职责边界时可能合理，不等同110项缺陷。
- **前端包装**：84处语法候选；Vue 模板、事件绑定、导出和测试调用可能不以脚本中的普通调用表达式出现，数量不等于无用包装，必须核对模板及外部引用。只针对单行函数形态筛选，其他防御与抽象尚未语义审完。

### 重复判空候选

| 文件 | 候选位置 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineDefinition.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineDefinition.java) | 10、19 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java) | 33 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | 126、130 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java) | 192 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleAiProperties.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleAiProperties.java) | 227 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java) | 26 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java) | 183 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/entity/JobDefinitionEntity.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/entity/JobDefinitionEntity.java) | 27、33 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertNotificationReceiptService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertNotificationReceiptService.java) | 27 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java) | 200 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | 134 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowDefinitionSaveRequest.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowDefinitionSaveRequest.java) | 67、79 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowScheduleUpdateRequest.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowScheduleUpdateRequest.java) | 56、84、96 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java) | 125、249 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java) | 40 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java) | 168 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadService.java) | 41 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/BatchDayCatchupPreviewService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/BatchDayCatchupPreviewService.java) | 46、48、61、63 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/WorkflowNodeReplayTargetResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/WorkflowNodeReplayTargetResolver.java) | 33 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySourceResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySourceResolver.java) | 45 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | 361、376 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 516、622、970 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/JobDependencyRule.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/JobDependencyRule.java) | 13 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRouter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRouter.java) | 29 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java) | 471 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleScheduler.java) | 145、381 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/JobDependencyWaitingLaunchReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/JobDependencyWaitingLaunchReconciler.java) | 61 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 269、404 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java) | 69、73、84、377、403、411、468、502 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolver.java) | 89 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java) | 50 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/QuartzMisfireRecoveryConfiguration.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/QuartzMisfireRecoveryConfiguration.java) | 40 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java) | 422 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) | 43、86、88 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) | 196、198、232 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java) | 70 |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java) | 172 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ControlTotalEvaluator.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ControlTotalEvaluator.java) | 41 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/TrailerControlRecord.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/TrailerControlRecord.java) | 59 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/BatchManifest.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/BatchManifest.java) | 83 |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingGenerationCleanupFence.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingGenerationCleanupFence.java) | 48 |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessStagingGenerationCoordinator.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessStagingGenerationCoordinator.java) | 90 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java) | 586 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java) | 22 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java) | 78 |

### 后端薄包装候选

| 文件 | 候选位置 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java) | 42 `sanitizeTopicSegment` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java) | 90 `upper` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java) | 255 `matches` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java) | 406 `syntheticEtag` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | 125 `physicalMarker`、129 `logicalMarker` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java) | 335 `isTerminal` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java) | 126 `cacheSegment` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java) | 143 `observeReplicaConnection` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java) | 252 `unpriced` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java) | 853 `resolveSoftRuntimeSeconds` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java) | 106 `normalizeStatus` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java) | 275 `notification` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | 165 `nullToDash`、169 `truncate` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | 281 `normalizeStream`、285 `normalizeFilter` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java) | 101 `isAscii` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java) | 223 `isPublicAuthPath` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java) | 90 `invalidCredentials` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java) | 184 `sessionStateTtl` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | 832 `requiredHeaders`、1029 `fileNameOrDefault` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) | 212 `first` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java) | 514 `resolveSoftRuntime` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java) | 1111 `resourceQueueExists` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java) | 85 `isAscii` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java) | 184 `validBrowserBinding` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java) | 160 `hasText` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java) | 153 `isExpensivePath` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java) | 116 `truncate` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java) | 268 `clampMaxPartitionCount` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java) | 203 `configSupplierFor` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java) | 246 `emptyBacklog` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java) | 116 `defaultParamsHas` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) | 1244 `firstText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java) | 741 `safeUrlQuery` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) | 721 `stringValue` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | 873 `failedStatuses`、1182 `isCancellable` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java) | 309 `readProtectedDispatchChannelSnapshots` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | 93 `buildContext`、220 `initialPartitionStatus` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java) | 194 `buildBusinessKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | 664 `stringValue` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 921 `isJobNode`、925 `isWaitNode` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java) | 324 `findAtomicOperator` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java) | 668 `readProtectedDispatchChannelSnapshots` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java) | 226 `verifyCacheKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java) | 64 `text` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java) | 476 `defaultText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java) | 550 `defaultText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java) | 316 `sanitizeTrace` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java) | 232 `stripTrailingSlash` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java) | 118 `sameHost` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) | 292 `normalizePolicy` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java) | 54 `notApplicable` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java) | 230 `eventKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java) | 165 `loadWorkflowRun` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java) | 254 `isWaitingRetry` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java) | 182 `currentLoadOrZero` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java) | 38 `hasHardCap` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java) | 164 `resolveErrorCode` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java) | 101 `hasValidSignatureShape` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 354 `firstHeartbeat` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | 423 `isMissingLaunchContext`、427 `isIncompleteBatchDay` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java) | 201 `eqInt` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java) | 178 `snapshot` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java) | 401 `bizDate` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java) | 258 `selections` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java) | 189 `mockWorkerSelector` |
| [batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java) | 111 `withoutComments` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java) | 301 `isDedicatedRecoveryJob` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java) | 126 `unknown` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | 88 `resolveCutoffTime`、105 `previousWorkday`、109 `nextWorkday` |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java) | 960 `jdbcUrlWithApplicationName` |
| [batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java) | 113 `isProdProfile` |
| [batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java) | 455 `isLocalPath` |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | 537 `startExecution`、541 `lastConfirmedNanosValue` |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java) | 192 `escapeRegex` |
| [batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java) | 830 `actualReplayCapability` |
| [batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java) | 145 `port` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java) | 135 `digestToHex` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java) | 109 `firstNonBlank` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | 225 `isParameterStart`、229 `isParameterPart` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java) | 233 `defaultText` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java) | 142 `asString` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java) | 466 `stringValue` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) | 610 `isManifestMode` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java) | 217 `hasTemplateConfigSnapshot` |
| [batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java) | 353 `mockPayload` |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java) | 390 `values` |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java) | 77 `upper` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | 109 `fixturesDir`、113 `openapiPath` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java) | 51 `fixturesDir` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java) | 60 `yamlPath` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java) | 49 `mockConsumer` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java) | 79 `objectMap` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java) | 48 `ctxNoSql` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java) | 499 `attemptedAddresses` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java) | 51 `objectMap` |
| [sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java](../../sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java) | 185 `registeredHandlers` |
| [sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java) | 473 `asMap` |

### 前端薄包装候选

| 文件 | 候选位置 |
|---|---|
| [e2e/c-plus-coverage.spec.ts](../../../batch-console/e2e/c-plus-coverage.spec.ts) | 22 `ts` |
| [scripts/lib/docs-publication.mjs](../../../batch-console/scripts/lib/docs-publication.mjs) | 114 `escapeLabel` |
| [src/components/table/ProTable.vue](../../../batch-console/src/components/table/ProTable.vue) | 389 `scheduleRecompute` |
| [src/composables/useTenantReload.ts](../../../batch-console/src/composables/useTenantReload.ts) | 27 `onErr` |
| [src/views/approvals/components/CatchUpApprovalsTab.vue](../../../batch-console/src/views/approvals/components/CatchUpApprovalsTab.vue) | 210 `onSearch`、219 `onReset` |
| [src/views/approvals/components/GeneralApprovalsTab.vue](../../../batch-console/src/views/approvals/components/GeneralApprovalsTab.vue) | 424 `selectableRow`、482 `onSearch`、489 `reset` |
| [src/views/config/ConfigReleaseList.vue](../../../batch-console/src/views/config/ConfigReleaseList.vue) | 640 `onSearch`、647 `reset` |
| [src/views/file-center/ArrivalGroupList.vue](../../../batch-console/src/views/file-center/ArrivalGroupList.vue) | 224 `onSearch`、233 `onReset` |
| [src/views/file-center/FileList.vue](../../../batch-console/src/views/file-center/FileList.vue) | 934 `applyStatusFilter`、956 `applyTodayFilter`、1041 `onSearch`、1057 `reset` |
| [src/views/file-center/FilePipelineObservability.vue](../../../batch-console/src/views/file-center/FilePipelineObservability.vue) | 777 `onSearch`、785 `onReset` |
| [src/views/file-center/FileTemplateList.vue](../../../batch-console/src/views/file-center/FileTemplateList.vue) | 982 `onChannelSearch`、986 `resetChannels`、994 `onSearch`、1001 `reset` |
| [src/views/governance/QueueConfig.vue](../../../batch-console/src/views/governance/QueueConfig.vue) | 819 `onTabSearch`、827 `onTabReset` |
| [src/views/governance/QuotaPanel.vue](../../../batch-console/src/views/governance/QuotaPanel.vue) | 268 `onQuotaSearch`、275 `onQuotaReset`、284 `onQuotaRefresh` |
| [src/views/job/JobDefinitionList.vue](../../../batch-console/src/views/job/JobDefinitionList.vue) | 786 `onSearch`、792 `reset`、805 `onRefreshDefinitions` |
| [src/views/monitor/JobInstanceList.vue](../../../batch-console/src/views/monitor/JobInstanceList.vue) | 757 `resetQuery`、775 `searchInstances` |
| [src/views/monitor/JobStepInstanceList.vue](../../../batch-console/src/views/monitor/JobStepInstanceList.vue) | 140 `onQuerySearch`、147 `onQueryReset` |
| [src/views/monitor/PartitionView.vue](../../../batch-console/src/views/monitor/PartitionView.vue) | 236 `applyFilter`、244 `resetFilter` |
| [src/views/monitor/WorkflowRunDetail.vue](../../../batch-console/src/views/monitor/WorkflowRunDetail.vue) | 696 `applyNodeFilter`、705 `resetNodeFilter` |
| [src/views/monitor/WorkflowRunList.vue](../../../batch-console/src/views/monitor/WorkflowRunList.vue) | 318 `search`、325 `resetQueryBar` |
| [src/views/observability/AlertList.vue](../../../batch-console/src/views/observability/AlertList.vue) | 525 `search`、538 `reset` |
| [src/views/observability/AlertRoutingPanel.vue](../../../batch-console/src/views/observability/AlertRoutingPanel.vue) | 283 `onSearch`、292 `onReset`、303 `onRefresh` |
| [src/views/observability/AuditList.vue](../../../batch-console/src/views/observability/AuditList.vue) | 230 `search`、237 `reset` |
| [src/views/observability/OperationAuditList.vue](../../../batch-console/src/views/observability/OperationAuditList.vue) | 268 `search`、275 `reset` |
| [src/views/observability/components/ChannelReceiptsTab.vue](../../../batch-console/src/views/observability/components/ChannelReceiptsTab.vue) | 204 `applyFilter`、213 `resetFilter` |
| [src/views/observability/components/DeadLettersTab.vue](../../../batch-console/src/views/observability/components/DeadLettersTab.vue) | 380 `applyFilter`、389 `resetFilter` |
| [src/views/observability/components/RetriesTab.vue](../../../batch-console/src/views/observability/components/RetriesTab.vue) | 188 `applyFilter`、197 `resetFilter` |
| [src/views/scheduler/BatchDayList.vue](../../../batch-console/src/views/scheduler/BatchDayList.vue) | 459 `onSearch`、466 `reset` |
| [src/views/system/AiChat.vue](../../../batch-console/src/views/system/AiChat.vue) | 487 `onAuditSearch`、497 `onAuditReset` |
| [src/views/system/ApiKeyList.vue](../../../batch-console/src/views/system/ApiKeyList.vue) | 327 `onSearch`、334 `onReset` |
| [src/views/system/SystemParameterList.vue](../../../batch-console/src/views/system/SystemParameterList.vue) | 189 `onSearch`、195 `onReset` |
| [src/views/system/TenantList.vue](../../../batch-console/src/views/system/TenantList.vue) | 328 `onSearch`、337 `onReset` |
| [src/views/system/TriggerList.vue](../../../batch-console/src/views/system/TriggerList.vue) | 211 `onSearch`、218 `onReset` |
| [src/views/system/UserAccountList.vue](../../../batch-console/src/views/system/UserAccountList.vue) | 455 `onSearch` |
| [src/views/system/components/NotificationChannelsTab.vue](../../../batch-console/src/views/system/components/NotificationChannelsTab.vue) | 414 `applyChannelFilter`、422 `resetChannelFilter` |
| [src/views/system/components/NotificationDeliveryLogsTab.vue](../../../batch-console/src/views/system/components/NotificationDeliveryLogsTab.vue) | 171 `applyLogFilter`、179 `resetLogFilter` |
| [src/views/system/components/NotificationRulesTab.vue](../../../batch-console/src/views/system/components/NotificationRulesTab.vue) | 366 `applyRuleFilter`、374 `resetRuleFilter` |
| [src/views/system/components/NotificationWebhooksTab.vue](../../../batch-console/src/views/system/components/NotificationWebhooksTab.vue) | 391 `applyWebhookFilter`、399 `resetWebhookFilter` |
| [src/views/worker/MyWorkers.vue](../../../batch-console/src/views/worker/MyWorkers.vue) | 89 `onRefresh` |
| [src/views/worker/WorkerManagement.vue](../../../batch-console/src/views/worker/WorkerManagement.vue) | 707 `onWorkerSearch`、713 `resetWorkers`、948 `onChannelSearch`、955 `resetChannels` |
| [src/views/workflow/WorkflowDefinitionList.vue](../../../batch-console/src/views/workflow/WorkflowDefinitionList.vue) | 992 `onSearch`、999 `reset` |

## 2026-10-28：过度实现初筛

本次对相对当前 HEAD 有差异的 1,060 份已跟踪后端生产 Java 文件做定向模式初筛，关注重复空值判断和仅使用一次的私有薄包装；这是候选筛选，不代表全量语义审查，也不包括未跟踪文件。配对前端本次未做同等范围扫描。

- **重复空值判断**：`isNull(x) || isBlank(x)` 与 `isNull(x) || isEmpty(x)` 命中61处、37份文件。`EmptyChecks.isBlank` 和集合 `isEmpty` 已处理 null；例如 `NamespacedObjectStore.physicalMarker/logicalMarker`、`TaskDispatchExecutor` 的批量入参判断中的额外 `isNull` 可省。这里的判空本身仍有业务意义，应仅去掉重复部分；61处是机械候选，修改前须逐处核对重载、求值与调用现场。
- **一次性薄包装候选**：`DefaultTenantConfigPackageExcelService.requiredHeaders` 仅转发 `ConfigPackageSheetSpecs.requiredImportHeaders`，只有一处调用，调用点可直接使用后者。是否精简还应结合邻近代码可读性决定；不按单次调用机械删除具有领域含义或代理边界的方法。
- 本次只扫描、未修改业务代码、未跑业务测试；其他防御校验、前端、测试 mock、未跟踪源码和更复杂的抽象尚未完成审查，不能据此声称过度实现已全部找出。

## 2026-10-30：测试必要性初步核查

在配对 `fix/wdi-current` 工作树，对 Git 已跟踪及未跟踪、未忽略测试文件做字节级重复检查：后端 1,434 份 `*Test.java`，前端 410 份 `.test.ts` / `.spec.ts`，未发现完全相同的文件。此项不能识别不同写法的行为重复；不包含其他命名、脚本及所有测试资源，不代表全量语义审查。

- **建议合并，不能直接删除**：[UpdateBatchDayReplayEntryStatusParamTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/domain/param/UpdateBatchDayReplayEntryStatusParamTest.java) 的 `shouldBuildWhenRequiredFieldsArePresent` 与后续 `shouldBuildLegalStateShapesFromNamedFactories` 都以 `succeededWithResult` 验证成功状态和时间；但前者独有 ID 断言。可将该断言移入后者并保留必填字段负例，再考虑移除前者；当前未修改测试，也未验证合并。
- **保留**：前端 [storageKeys.test.ts](../../../batch-console/src/constants/storageKeys.test.ts) 以独立预期保护已发布持久化键兼容；不是简单重复常量。后端 Worker DTO 测试中的上限、集合快照及参数工厂测试中的必填字段/状态形态均有独立契约作用，不能仅凭 getter 断言判断无用。

本次只读代码、登记结果，未运行测试；尚未逐项比较全部测试的行为覆盖，不能推断其他测试均有必要或存在多少可删除项。

## 短断言候选复核进展

对原321项短断言候选检查了索引，已核实以下代表性场景；尚未逐项完成全部上下文审查，不能据此认定其余项可删或应保留。本轮仅记录，未修改、删除或运行测试。

- `iconResolve.test.ts` 的 `toBeDefined()` 和 `not.toBeNull()` 分别排除 `undefined` 和 `null`，并非重复；此前将其称为重复候选的口头判断已更正。该测试对具体图标身份的证明仍弱于其标题，是否加强应另评估。
- `lastApiMeta.test.ts` 对 `null` 和 `undefined` 分别测试输入边界，`metaEnumPick.test.ts` 同理；虽然当前实现统一处理，两个公开输入不应仅因输出相同而删除。
- `HttpTaskExecutorIpBlockTest` 不同地址涉及 IPv4、IPv6、映射地址、元数据地址与公网反例，应保留各自安全边界；`PreprocessRangeSliceTest` 的无尾换行、空行和 CRLF 也分别测试不同文件格式。
- `RetryScheduleStatusTest.shouldContainSixValues` 锁定枚举总数，六个编码断言只证明已列举值的编码，新增第七项时不会失败，因此不能认定数量断言与编码断言重复。
- `resourceCodeBuilder.test.ts` 中空值、最小/最大长度及首字符规则为不同边界；`theme.test.ts` 的默认值、已保存值和旧值迁移为不同状态，不因代码短而合并。

## 测试候选上下文复核续记

**10组后端相同正文**：第1组分别实例化 Job 与 Workflow 通知消费者，验证各自无效身份不产生业务动作；第2组分别执行 HTTP 与 Shell 执行器的日志脱敏；第3、4、5、7、8组的 ArchUnit 规则分别导入各模块的生产类；第9组分别启动五类 Worker 并验证各自端口，以上保留各自覆盖。第6组 Workflow DAG 两个 FILE_STEP 拒绝用例与第10组 Readiness 两个空结果用例的重复结论见下文；保留其更完整断言或按真实业务状态补充反例后再考虑合并，不直接删除。

**9组前端相同正文**：第1、2、3、4、6、7、8组分别处于不同路由、页面或功能区域；第9组处于资源标签和搜索标签两个不同 tab，不能仅凭正文相同删除。第5组两条 Outbox stats 200 检查的请求相同，但分别处于 Trigger 链和 Outbox 故障恢复的串行场景，且后者先创建故障数据；可讨论合并通用存活检查，但需先确认单独运行、失败定位和阶段前置条件，暂不判为可删。多处 UI 测试在控件不可见时提前返回，或仅断言容器存在；应作为低有效性改进候选，不能把它们的成功视为实际操作成功。

**仍未完成**：以上针对已有19组相同正文候选核对业务上下文；另有后端208、前端113个短断言语法候选，尚未逐个核实，不应报告整个候选集已完成。未修改、删除或执行测试；以上仅基于当前代码静态核对。

## 2026-10-30：测试重复性全量索引与上下文复核

只读前后端 Git 已跟踪及未跟踪、未忽略的 `*Test.java`、`.test.ts`、`.spec.ts`：后端 1,434 个文件、前端 410 个文件。简单解析器识别后端 8,646 个、前端 2,011 个测试体，按去除空白的正文计算相同组；未识别测试、其他命名和动态生成测试不在测试体计数内。相同正文未必测试相同对象，短断言也未必无效；以下为筛选和有限上下文核查，**不代表全量测试已逐项语义审核**。没有删除或修改测试，也未执行测试。

### 已核实的重复及处置建议

1. `WorkflowDagValidatorTest.shouldFail_whenFileStepPipelineCodeMissing` 与 `shouldFail_whenFileStepPipelineNotFound`：同类、相同输入与断言，且不存在不同的类级 fixture；可合并为一个退役节点拒绝用例。后者标题称旧 Pipeline 存在，但该测试没有建立这种状态；如该场景仍需保障，应补真实可区分前提，而不是将当前测试视为证据。
2. `ReadinessServiceTest.shouldNotBeReady_whenRerunNotEffective` 与 `shouldNotBeReady_whenNoAssetPartition`：相同 mock 返回空、相同调用与断言；同类 `shouldNotBeReady_whenAssetPartitionNotEffective` 覆盖该结果且断言更多。可考虑保留覆盖更完整的用例；若要证明“重跑未生效”，应另用能表达重跑状态的真实场景。
3. 上一轮登记的 `UpdateBatchDayReplayEntryStatusParamTest.shouldBuildWhenRequiredFieldsArePresent` 与具名工厂测试部分重复；先迁移独有 ID 断言，再评估合并。

### 不应按正文重复直接删除

- ArchUnit 的相同规则在各模块以不同 `CLASSES` 包范围执行；五种 Worker 的端口测试分别启动不同应用，均不能因正文相同而合并删除。
- 前端 `tag-ops.spec.ts` 两个 Tag Key 测试的正文相同，但 `beforeEach` 分别进入资源标签与搜索标签，作用于不同 tab，应保留独立业务覆盖。其他跨页面 E2E 相同正文同样须先确认路由和上下文。
- 前端页面操作测试存在条件不满足直接 return、只断言容器存在的低信息候选；这属于测试真实性改进线索，不能直接作为删除依据，也不能当作真实业务链通过证据。

### 相同正文的候选组

后端10组、前端9组，以下保留全部初筛位置供逐项复核。行号是扫描快照；除上文明确说明外，均未认定可删除。

**后端**

1. [JobTerminalNotificationConsumerTest.java:72](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/notification/infrastructure/JobTerminalNotificationConsumerTest.java) `shouldDropInvalidIdentityWithoutBusinessAction`；[WorkflowTerminalNotificationConsumerTest.java:72](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/notification/infrastructure/WorkflowTerminalNotificationConsumerTest.java) `shouldDropInvalidIdentityWithoutBusinessAction`
2. [HttpTaskExecutorTest.java:96](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorTest.java) `shouldMaskUnexpectedErrorInLog`；[ShellTaskExecutorTest.java:63](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutorTest.java) `shouldMaskUnexpectedErrorInLog`
3. [CodingConventionsArchTest.java:20](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidDefaultTimezoneReads_whenOutsideTimezoneInfrastructure`；[OrchestratorCodingConventionsArchTest.java:18](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoJvmDefaultTimeZoneUsage_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:18](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `zoneIdSystemDefault_isForbidden`；[ConsoleCodingConventionsArchTest.java:24](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidJvmDefaultTimeZone_whenRuleChecked`
4. [CodingConventionsArchTest.java:26](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidEncodingLookupByName_whenOutsideEncodingUtils`；[OrchestratorCodingConventionsArchTest.java:24](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoNameBasedCharsetConstruction_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:24](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `charsetForName_isForbidden`；[ConsoleCodingConventionsArchTest.java:30](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidCharsetLookupByName_whenRuleChecked`
5. [CodingConventionsArchTest.java:32](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidRecordSuffix_whenNamingPersistentOrDomainClasses`；[OrchestratorCodingConventionsArchTest.java:30](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoRecordSuffixTypeName_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:30](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `recordSuffix_isForbidden`；[ConsoleCodingConventionsArchTest.java:36](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidRecordSuffixTypeName_whenRuleChecked`
6. [WorkflowDagValidatorTest.java:168](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidatorTest.java) `shouldFail_whenFileStepPipelineCodeMissing`；[WorkflowDagValidatorTest.java:179](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidatorTest.java) `shouldFail_whenFileStepPipelineNotFound`
7. [OrchestratorCodingConventionsArchTest.java:36](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoTransactionalEventListener_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:36](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `transactionalOnEventListener_isForbidden`；[ImportCodingConventionsArchTest.java:23](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/arch/ImportCodingConventionsArchTest.java) `shouldRejectTransactional_whenEventListenerMethodAnnotated`；[ConsoleCodingConventionsArchTest.java:42](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidTransactionalOnEventListener_whenRuleChecked`
8. [OrchestratorCodingConventionsArchTest.java:42](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoTransactionalScheduledMethod_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:42](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `transactionalOnScheduled_isForbidden`；[ImportCodingConventionsArchTest.java:29](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/arch/ImportCodingConventionsArchTest.java) `shouldRejectTransactional_whenScheduledMethodAnnotated`；[ConsoleCodingConventionsArchTest.java:48](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidTransactionalOnScheduledMethod_whenRuleChecked`
9. [BatchWorkerAtomicApplicationIntegrationTest.java:35](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerDispatchApplicationIntegrationTest.java:31](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerProcessApplicationIntegrationTest.java:50](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerImportApplicationIntegrationTest.java:32](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerExportApplicationIntegrationTest.java:31](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`
10. [ReadinessServiceTest.java:92](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessServiceTest.java) `shouldNotBeReady_whenRerunNotEffective`；[ReadinessServiceTest.java:107](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessServiceTest.java) `shouldNotBeReady_whenNoAssetPartition`

**前端**

1. [scheduler-governance-ops.spec.ts:153](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `状态筛选 → 查询`；[alert-outbox-ops.spec.ts:154](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `重试 tab — 状态筛选`
2. [scheduler-governance-ops.spec.ts:199](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `刷新`；[scheduler-governance-ops.spec.ts:291](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `刷新`；[alert-outbox-ops.spec.ts:178](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `刷新`；[trigger-ops.spec.ts:29](../../../batch-console/e2e/trigger-ops.spec.ts) `刷新`；[file-ops.spec.ts:54](../../../batch-console/e2e/file-ops.spec.ts) `刷新按钮`；[tag-ops.spec.ts:58](../../../batch-console/e2e/tag-ops.spec.ts) `刷新`；[log-ops.spec.ts:19](../../../batch-console/e2e/log-ops.spec.ts) `刷新按钮可用`
3. [scheduler-governance-ops.spec.ts:204](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `关键字筛选 → 查询`；[alert-outbox-ops.spec.ts:146](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `重试 tab — 关键字搜索`；[file-ops.spec.ts:215](../../../batch-console/e2e/file-ops.spec.ts) `关键字搜索 → 查询`
4. [scheduler-governance-ops.spec.ts:212](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `启用 / 停用切换 → 确认 → toast`；[scheduler-governance-ops.spec.ts:272](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `启用 / 停用切换 → 确认 → toast`
5. [08-outbox-stuck-republish.spec.ts:92](../../../batch-console/e2e/flows/08-outbox-stuck-republish.spec.ts) `1. 查 outbox stats — 通道存活`；[01-trigger-instance.spec.ts:57](../../../batch-console/e2e/flows/01-trigger-instance.spec.ts) `4. outbox stats 接口可访问 → 发布通道存活`
6. [user-account-ops.spec.ts:28](../../../batch-console/e2e/user-account-ops.spec.ts) `刷新`；[worker-ops.spec.ts:47](../../../batch-console/e2e/worker-ops.spec.ts) `刷新按钮`
7. [ops-shard-catalog-crud.spec.ts:29](../../../batch-console/e2e/ops-shard-catalog-crud.spec.ts) `页可达 + 新建入口可见`；[ops-tenant-placement-crud.spec.ts:23](../../../batch-console/e2e/ops-tenant-placement-crud.spec.ts) `页可达 + 新建入口可见`
8. [monitor-ops.spec.ts:150](../../../batch-console/e2e/monitor-ops.spec.ts) `分片列表加载`；[worker-ops.spec.ts:118](../../../batch-console/e2e/worker-ops.spec.ts) `文件渠道列表加载`；[log-ops.spec.ts:15](../../../batch-console/e2e/log-ops.spec.ts) `页面正常打开并展示表格`
9. [tag-ops.spec.ts:42](../../../batch-console/e2e/tag-ops.spec.ts) `Tag Key 输入 → 查询`；[tag-ops.spec.ts:73](../../../batch-console/e2e/tag-ops.spec.ts) `Tag Key 搜索`

后端另有208个、前端113个短断言语法候选，尚未逐一确认；短小不等于无效，边界、状态机、持久化键及独立协议预期应保留。下一步应优先核对这些候选的具体行为和相邻测试，而非按数字批量删除。

## 当前核查结论

本报告为本轮前后端审查与验证的唯一记录，合并原后端审查、后端验证和前端整改三份10月27日报告，并删除被替代文件；问题、首红、命令、历史覆盖数字和环境限制保留在下文。10月26日独立历史记录保留。当前执行范围以[差异审查清单](../plans/todo-master.md#当前差异审查清单)为准，下文历史“继续全仓”安排不再作为本轮执行指令。

- **工作位置**：配对 `wdi-current` 工作树，分支均为 `fix/wdi-current`；后端HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，前端HEAD `662d254e2f8b97f93021de548815470d2291c997`。未修改main或冻结参考树，未暂存、提交或发布。
- **本次完成**：原14项待复核来源的读取快照已核对，未发现新增的已确认Bug；收尾时13项仍与读取内容一致，1份测试被并发追加用例，新增内容待复核。未修改生产或测试代码。此结论限于下表关注点，不将这些文件全文或其所在模块标为全部已审。
- **验证边界**：按“只跑有问题的类”执行。此次没有确认新的代码缺陷，也没有因此启动Java/前端测试、编译或全模块回归。下文运行结果为历史源码快照的证据，本次没有重新产生业务测试通过数。
- **比较限制**：原14项中10项当前摘要不同、4项与待复核台账摘要相同；4项相同只说明自登记后未继续漂移，不等于此前已通过。旧摘要未附完整旧文件正文，不能宣称已恢复并逐字证明所有旧新变换等价；本次依据当前源码、只读冻结参考内容、原问题及反例核对所列不变量。
- **复用范围**：已核对日切原首红/修复后命令与日志、Worker生命周期/领取CAS、配置事务及前端静态路由历史证据的归属；没有将旧编译、旧全量测试或文件哈希一致当作当前完整运行保证。

### 原14项风险片段复核

表中摘要固定本次读取内容，完整旧摘要仍保留在原CSV。该CSV继续作为历史覆盖记录，不重写为本轮全文件审核完成。

| 来源 | 本次核对结论 | 本次读取快照SHA-256前12位 |
|---|---|---|
| [DefaultConsoleConfigSyncApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java) | 目标身份先验拒绝；原键查询受租户约束；typed审计行、独立审计事务及收据核对仍衔接 | `026a63680ceb` |
| [TenantConfigCopyTypeLocaleTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigCopyTypeLocaleTest.java) | 两种默认语言与三类协议值断言保留，finally恢复Locale | `8a1f9d1927c5` |
| [TenantConfigInitCommitOutcomeIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitCommitOutcomeIntegrationTest.java) | 原提交后确认异常路径已核对；收尾并发新增savepoint回滚统计用例，新增内容待复核 | `d0345c6979d4` |
| [TenantConfigInitOutcomeTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitOutcomeTest.java) | 未知提交停止扇出并恢复上下文；已知回滚继续；非法目标零执行断言保留 | `c3f8f370f5b2` |
| [TenantConfigBundleCompletenessIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBundleCompletenessIntegrationTest.java) | 201队列、最高版本及合法150节点/296边断言保留，SQL参数顺序与本租户清理核对 | `8848586855c8` |
| [BatchDayCutoffMultiTenantIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayCutoffMultiTenantIntegrationTest.java) | 多租户各自审计、版本推进一次及未来/冻结项保持断言保留 | `6170b2a2449a` |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | 按实际Instant比较日切；重叠取首次、缺口取跳时边界；原DST及等于日切反例仍在 | `3b4e5429a8c3` |
| [TriggerAdmissionDrainTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/config/TriggerAdmissionDrainTest.java) | 排队后再次检查排水；三入口各有拒绝零调用与正常接纳反例，线程收尾仍在 | `ccb24198c9c7` |
| [TriggerSchedulingOwnershipTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerSchedulingOwnershipTest.java) | 实际组件扫描有非空正例，并断言Trigger不注册日切状态任务；不等同全部调度实现证明 | `e2eef600fe29` |
| [ExternalEventApiKeyHttpIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ExternalEventApiKeyHttpIntegrationTest.java) | 认证/来源绑定、固定业务日、原事件重投及范围外Workflow拒绝、请求/Outbox计数断言保留 | `61f49716eea4` |
| [TaskDispatchExecutor.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) | 空输入仍短路；仅认领成功项执行；批末清理未执行项，不重跑已执行业务 | `158dbab885cd` |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | 清理仅匹配tenant/worker/invocation且未启动项；判断与移除共用写锁，已启动项保留 | `f1e6261ab2c4` |
| [WorkerRuntimeState.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerRuntimeState.java) | computeIfPresent内仍用current != expected判实例身份，迟到心跳不能替换新注册 | `f77787a08a3a` |
| [WorkerReportOutboxCoordinator.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinator.java) | 成功/失败/损坏处理传递完整领取行；凭据CAS仍由Repository约束，计数仅在生效后增加 | `e8af342dbed1` |

### 新变化与未验证边界

`TenantConfigInitCommitOutcomeIntegrationTest.java` 在本次读取后新增 `shouldCountOnlyCommittedItems_whenNestedItemRollsBackAtCompletion`，不能沿用读取快照的核查结论覆盖新用例。表中保留本次读取摘要，原确认异常用例结论仍限其当时内容；该文件最新新增内容及对应运行验证单列待复核，未覆盖其他工作的修改或并行启动编译。

对原已审/部分审查来源只做摘要新鲜度检查，后端278项、前端140项；此操作不是新增418项语义审核。另发现以下3项与原登记摘要不同，不沿用旧通过结论：

- 后端 `TriggerOutboxRelay.java` 与 `TriggerOutboxEventMapper.xml`：当前变化内容待专项核对，未据文件变化提前认定Bug，也未额外启动Trigger模块测试。
- 前端 `src/types/api.generated.ts`：生成契约当前摘要变化，未重跑生成漂移或前端全量测试；未据此宣称已有组件证据覆盖新契约。

上述3项及收尾新增测试内容均仅登记变化，共4处待复核，不自动扩大当前核查为新的全仓扫描。URI/JDBC/计量/Nginx等原候选仍按主清单判定与本轮修复的直接关系后安排，不计为已修复问题。

### 合并与轻量检查

本次迁移仅重组文档标题、链接与当前状态；三份原报告的正文证据及命令保留，后端命令在后端根目录、前端命令在前端根目录执行。合并检查不与业务运行验证混计。

- `bash scripts/python.sh scripts/ci/check-docs-structure.py`：后端文档结构通过。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：文档日期命名通过。
- 前端 `npm run check:docs`：75份当前文档通过。
- 迁移完整性核对：原报告225行非标题正文、7个代码块和70个原章节映射均保留；三份原文件已删除，已迁移链接/锚点核对通过。
- 收尾来源核对：13份与本次读取内容一致；1份测试的新用例和另外3份变化保留待复核，未新增业务测试通过结论。

- [后端问题与历史审查](#backend-review-record)
- [后端历史运行验证](#backend-validation-record)
- [前端问题与历史运行验证](#frontend-review-record)

<a id="backend-review-record"></a>

<a id="backend-review-后端代码-bug-续审2026-10-27"></a>

## 后端问题与历史审查

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：全工程逐文件续审已停止，后续以[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)为准，优先核对已有修复及直接影响。本报告保留各批次当时的发现、覆盖数字与验证；正文中的继续审查安排属于原计划，不代表当前执行范围。代码审查仍暂停，本次仅调整清单。

<a id="backend-review-范围与状态"></a>

### 范围与状态

继续[原逐文件任务](../plans/todo-master.md#前后端全工程逐文件-bug-续审2026-10-26)，只审查Bug与运行风险。既有配对 `wdi-current` 工作树均为 `fix/wdi-current`，后端HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，前端HEAD `662d254e2f8b97f93021de548815470d2291c997`。保留共享暂存/未暂存/未跟踪改动，未提交或发布。

本日截至后端定位与产物验收批次确认并修复十六项，运行证据见[当日验证](#backend-validation-record)。全工程逐文件审查仍未完成，持续清单沿用[原任务内容摘要台账](backend-bug-file-coverage-2026-10-26.csv)，日期为任务起点而非当前源码冻结日期；源文件变化后需复核。

<a id="backend-review-确认问题"></a>

### 确认问题

| ID | 触发与旧行为 | 修复与证据 |
|---|---|---|
| BUG-20261027-01 / P2 | 配置复制服务用 `PageRequest(1,5000)` 读取完整包，但公共请求将页大小限制为200，且只读取第一页；同步导出、复制/差异和矩阵依赖同一入口，会漏配置，Workflow边也被截断 | 复用既有Mapper空分页全量读取契约，保留租户与只读重复读快照、普通列表200条限制；真实PG验证201条队列及合法150节点/296边完整读取 |
| BUG-20261027-02 / P2 | 矩阵显式指定展示列表外的基准时，只读取展示租户，未加载基准，所有行可能得到空 `driftFields` | 额外读取基准供比较，响应仍只包含请求租户；基准缺少作业按缺失事实计算存在性差异。两个新增用例先红后绿，原默认/显式列表内基准用例保留 |
| BUG-20261027-03 / P1 | 源租户同时作为复制目标时，UPSERT将去除环境字段的渠道包回写源租户；真实认证HTTP返回200且原端点/配置标记均被清空 | 服务在读取源包前拒绝源目标重合，HTTP400且原配置不变；直接内部调用同样拒绝，中英文错误键验证通过。前端更换源时移除重合项，FE-BUG-42记录同一风险链的页面守卫 |
| BUG-20261027-04 / P2 | 复制、预览、矩阵及作业包导入的目标/作业元素缺少非空白约束；前者可返回200/500，作业包旧HTTP返回200且已写入前一合法租户 | 租户/作业元素增加NotBlank，初始化应用服务在任何目标事务前校验全部身份；类型null原已在HTTP反序列化层拒绝，NotNull仅补齐Bean声明，不计独立运行时Bug；可选空集合原语义保留 |
| BUG-20261027-05 / P2 | 配置类型大小写转换依赖JVM默认语言，土耳其语/阿塞拜疆语环境拒绝合法小写类型 | 使用Locale.ROOT；两环境回归先出现非法类型异常，修复后解析与兼容简写一致，默认Locale在测试后恢复 |
| BUG-20261027-06 / P1 | Workflow导入使用模糊编码搜索的第一条结果定位写入，UPSERT可改名并改图到相似编码，SKIP_EXISTING误跳过应创建的编码 | 增加精确编码最高版本查询；两种模式的真实PG回归均先红，修复后创建正确编码并保留原相似定义及图 |
| BUG-20261027-07 / P2 | 创建去重只查版本1；当前行升版后同码创建返回200并新增独立定义 | 创建按精确编码检查当前最高版本，真实认证HTTP返回409且仍一条定义；没有删除历史快照或修改迁移 |
| BUG-20261027-08 / P2 | 配置包导出同编码多版本物理定义为多条无版本Spec，旧定义可在目标重复应用并覆盖当前意图 | 按租户/编码只读最高版本及其图；当前版本停用仍保留停用，不回退旧启用版本。真实PG单项导出回归先红后绿 |
| BUG-20261027-09 / P2 | 执行池强停或停机线程被中断后，shutdownNow只移走排队任务，Future未终结，监听方继续等待到任务执行超时 | 取消shutdownNow返回的未启动Future，两条强停路径先红后绿；保留运行中业务的协作中断和原有宽限，不将Future取消等同于业务线程结束 |
| BUG-20261027-10 / P2 | 心跳请求在途时下线移除注册，迟到响应无条件put恢复旧注册；同标识新注册也被覆盖，并执行旧平台指令 | 缓存按原注册实例原子更新，已删除或替换时忽略回包及其指令；真实线程交错两项先红后绿，结构相等的新对象同样受保护 |

<a id="backend-review-影响路径"></a>

### 影响路径

- [复制与配置包服务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java) → 同步导出/预览、租户复制/差异和作业矩阵。九类当前配置以及Workflow节点/边完整读取；首批复用既有空分页，后续增加精确编码和每编码最高版本两条查询，未修改Flyway。
- [作业矩阵构建器](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) → [矩阵请求](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/contract/request/config/TenantConfigMatrixRequest.java) 的显式基准语义。OpenAPI描述与配对生成类型同步，无结构字段或路由新增。
- 前端复制面板的校验、写入及三类预览异步边界另记[前端FE-BUG-41/42](#frontend-review-record)，不把后端数据完整性与前端状态修复混成一个问题。

<a id="backend-review-排除与剩余范围"></a>

### 排除与剩余范围

首个图夹具使用203节点，超过现行DAG的200节点上限；虽然数据库可保存，其失败不作为正常业务缺陷证明。现使用实际DAG校验器通过的150节点/296边图。节点上限继续有效，不能据此限制边数或放宽节点准入。

新增原始队列/图测试先红，切换完整读取后旧Mock严格匹配非空分页的三项回归首轮失败；改为严格匹配空分页契约，业务断言不变。没有通过扩大页大小、取消公共上限或忽略失败解决问题。

源版本选择、源目标重合及请求元素由本批关闭；完整配置读取的目标容量/N+1、各配置类别的字段覆盖、目标环境字段保留、选项数据变化及旧复制入口持久未知结果恢复继续追查。真实浏览器跨账号联验、目标部署、所有SDK/脚本和全工程逐文件审查未完成。

<a id="backend-review-排除的候选与验证边界"></a>

### 排除的候选与验证边界

`appendCreatedVersionSnapshot`没有给临时请求设置nodes/edges，不等于快照丢图：实际消费者`WorkflowDefinitionWriteSupport.appendVersionSnapshot`在同一事务中重新读取节点/边并序列化。沿消费者取证后排除，未据此改代码。

配置身份阶段合跑140项，其中真实PostgreSQL61项；租户XML守卫、格式、432路由及配对类型漂移另列验证记录。现有物理历史行未被清理，普通列表的模糊搜索与历史版本接口保留原语义；本轮只改配置定位与当前版本读取。

<a id="backend-review-当前覆盖快照"></a>

### 当前覆盖快照

后端6,369项：157已审、59部分、6,152待审、1待复核；前端1,255项：95已审、30部分、1,130待审。新增Locale回归计入分母。并发改动的`BatchDayCutoffMultiTenantIntegrationTest`保留原证据与旧摘要，未替其他修改者销项。本批未提交、暂存或清理现有改动；共享索引内容不保证静止。

<a id="backend-review-配对作业包与向导续审"></a>

### 配对作业包与向导续审

前端FE-BUG-43至47继续沿作业包调用方核对异步反馈、关联选项、步骤校验和管理员权限入口，首红与最新227文件/1,417项等证据见[配对当日报告](#frontend-review-作业包与向导续审fe-bug-43至47)。后端本批只维护API/OpenAPI和运维说明；没有放宽ADMIN_ONLY或新增Java行为，因此不增加后端Bug计数或将旧140项重算为新运行结果。

最新前端清单1,256项：99已审、31部分、1,126待审；后端仍6,369项：157已审、59部分、6,152待审、1待复核。前端清单新增一份组件测试，权限store及路由库存测试实际全文核对，主路由与大组件仍部分。

<a id="backend-review-worker执行租约与心跳续审"></a>

### Worker执行、租约与心跳续审

两项新增问题分别定位于`TaskExecutionPool`的强停队列处理，以及`DefaultHeartbeatService`/`WorkerRuntimeState`的注册身份。排队Future的取消只针对shutdownNow实际移出的任务；`DefaultTaskExecutionWrapper`已有CancellationException路径负责返回取消结果，没有自动重跑业务或把运行中线程标为已退出。

下线与重新注册的回归使用真实线程、Latch和真实并发缓存，远程renew由可控替身阻塞；证明本地交错，未据此宣称真实Orchestrator网络、Pod强杀或全部停机组合已验证。Worker Core全模块370项通过，上游755项通过、5项环境条件跳过；完整命令及首红见当日验证记录。

续租HTTP明确404/409拒绝与其他错误传播已沿实现核对；原注释将所有4xx概括成不可认领并不符合代码，已修正说明，不计新增运行时Bug。注册缓存snapshot的消费者当前只读，没有仅因其返回视图而改集合语义。大执行包装器、完整HTTP客户端、抽象循环及共享关闭流程仍按实际片段留部分范围；五类Worker的全部业务执行、SDK与脚本继续审查。

<a id="backend-review-worker批次覆盖快照"></a>

#### Worker批次覆盖快照

后端6,369来源：177已审、61部分、6,127待审、4待复核；前端1,256来源：99已审、31部分、1,126待审。Worker核心新增23项全文审查完成，其他读取片段继续标部分。没有将Core370项测试通过自动记为该模块所有文件已审，原并发变化的日切测试仍待复核。本批未新增代码文件，未修改前端语义来源，未提交或暂存。

<a id="backend-review-收尾来源变化"></a>

#### 收尾来源变化

`TriggerAdmissionDrainTest`、`TriggerSchedulingOwnershipTest`及`ExternalEventApiKeyHttpIntegrationTest`在收尾核对时被并发修改，原已审内容摘要保留并转待复核，未覆盖改动；原日切测试待复核保留。最新后端177已审、61部分、6,127待审、4待复核，共6,369；前端99已审、31部分、1,126待审，共1,256。本轮Worker源码摘要未变，测试结果不外推到新Trigger内容。共享E2E变化只更新待审摘要。

<a id="backend-review-worker回报队列的执行身份与领取所有权"></a>

### Worker回报队列的执行身份与领取所有权

**BUG-20261027-11 / P1，已修复并验证。** 回报领取只携带行ID和载荷，确认删除及失败更新未绑定领取者。相同执行再次入队，或陈旧回收后由新轮询领取，旧HTTP响应仍可删除新报告、消耗新领取者重试预算或将其标为GIVE_UP。PostgreSQL和SQLite真实SQL均复现。现在每次领取生成claim_token，读取尝试次数、删除、重试、损坏载荷与耗尽放弃都要求ID、PUBLISHING及凭据一致；重新入队和退出领取时使旧凭据失效。写入返回行的MyBatis语句声明affectData/flushCache，确认属于事务写操作。放弃计量只在CAS实际生效后累加，同时覆盖耗尽路径，迟到失败不再产生虚假的重试/放弃告警。

**BUG-20261027-12 / P1，已修复并验证。** 原唯一键为租户/任务，旧执行的迟到回报会覆盖新执行的结果，反向顺序同样丢失一份已完成结果。现按租户/任务/partition_invocation_id去重；同执行仍更新一行，不同执行各自保存并独立确认。无执行身份统一以空串作为存储键，JSON载荷原有null不变。回报重投只提交结果，不重跑业务；过期执行仍由Orchestrator拒绝，不绕过服务端围栏。

新增V360仅用于ADR-048全新数据库、配套版本停旧启新。非空旧PG队列在任何结构修改前拒绝迁移并保留原数据；SQLite不自动升级、清空或搬运旧文件，校验不兼容结构后失败关闭。同版本文件重启保留待发结果。此前自动升级SQLite的草案已撤销，没有将兼容旧运行队列纳入实现。

核心实现为WorkerReportOutboxRepository、两个Mapper/XML及Coordinator，真实PG/SQLite参数化回归覆盖旧确认、陈旧回收、失败预算、损坏放弃、乱序执行、空身份去重及合法重试/放弃。同版本SQLite重开与两种旧存储拒绝另有正反例。Worker Core当前393项通过；上游755项通过、5项环境跳过；全新PG完整Flyway测试4项通过。首红、夹具错误和命令见当日验证记录。

载荷为合法JSON但业务身份缺失、极端重试配置溢出、BackendGuard完整切换组合及更大范围关闭组合尚未完成验证；前两项目前只列候选，不能计为已确认Bug。Repository和Coordinator本批在清单保留部分审查，不因定向回归而关闭全部输入与配置边界。平台迁移快照只更新实测发生变化的列、约束、索引三个摘要类别，未重生成种子或其他快照。

<a id="backend-review-bug-20261027-13--p2主机语言导致后端守卫误判"></a>

#### BUG-20261027-13 / P2：主机语言导致后端守卫误判

WorkerReportOutboxBackendGuard将存储枚举按默认语言转小写。土耳其语/阿塞拜疆语把SQLITE转为sqlıte，与原sqlite不相等；共享StatefulBackendGuard按已登记backend/identity精确比较，因此相同本地库在换语言重启后会要求不存在的后端切换。已改Locale.ROOT，保留原路径、功能键和切换策略。新增4项覆盖两语言及两存储，SQLite两项先失败、PG两项原本通过；原3项继续保留，定向7项和最终Worker Core397项通过。当前验证没有宣称真实多副本后端迁移已演练。

<a id="backend-review-本批范围盘点修正"></a>

#### 本批范围盘点修正

清单补入此前扩展名过滤漏掉的60份后端、8份前端自有文本来源，包括本批4份新源码/迁移，以及Spring服务注册、环境/HCL、schema摘要/种子、测试数据、包声明、Nginx模板、PWA清单和治理辅助资产。法律声明等辅助文本也显式列入，不隐含已审；二进制、依赖/构建/IDE产物、文档/Skill不计代码分母。当前后端6,429项：194已审、70部分审查、6,157待审、8待复核；前端1,264项的最新状态见配对报告。新增盘点不计审查完成，旧报告中分母为其当时快照。全工程目标仍未完成。

<a id="backend-review-下一批候选初查"></a>

#### 下一批候选初查

沿后端身份消费者初读StatefulBackendIdentity，发现对完整数据库位置、Redis Sentinel主名等统一小写，可能将大小写有业务含义的资源合并；尚未完成真实消费者及切换契约取证，不计为确认Bug。初读PipelineVerifierHook的默认语言转换，尚须核对内置及扩展适配器是否会传入合法小写类型；不能只凭toUpperCase无Locale就认定生产可绕过验证。这两份来源按实际阅读片段标部分，后续继续验证。

回报批次最终内容摘要核对又发现TenantConfigCopyTypeLocaleTest、TenantConfigInitCommitOutcomeIntegrationTest、TenantConfigInitOutcomeTest及TenantConfigBundleCompletenessIntegrationTest已被共享修改，保留旧摘要转待复核，与既有4份合计8份。没有覆盖这些测试或沿用配置批次旧通过结论；Outbox本批来源摘要未变。

<a id="backend-review-有状态后端与产物验收续审"></a>

### 有状态后端与产物验收续审

<a id="backend-review-bug-20261027-14--p2资源名折叠大小写绕过切换保护"></a>

#### BUG-20261027-14 / P2：资源名折叠大小写绕过切换保护

StatefulBackendIdentity原先把整个数据库地址、Sentinel主名、文件系统桶名和对象存储接入地址统一转小写。资源名仅大小写不同的两个目标因此产生相同身份；真实PostgreSQL登记后，四种无凭据切换都错误返回已验证。现在仅归一化协议与主机名，资源名称、路径和原始百分号编码保留；数据库认证段、查询及片段仍按原规则剥离。不同位置再次受既有一次性凭据和审计约束，失败拒绝保留原登记与唯一基线历史，不自动修改历史绑定。

单元四个反例先失败，真实PG四个拒绝反例也先失败，修复后均通过。此处验证的是定位序列化与持久守卫，不是四类远端存储的数据搬迁或容量演练。S3接入查询/片段的敏感值处理仍列进一步审查，不以本批宣称所有URI输入已完整关闭。

<a id="backend-review-bug-20261027-15--p1致命失败被可空错误码软化"></a>

#### BUG-20261027-15 / P1：致命失败被可空错误码软化

PipelineVerifierHook以firstFatalCode非空代替致命失败事实。合法失败元数据缺少错误码时，真实注册器仍返回失败，但Hook返回NO_FATAL；使用真实Hook/注册器的适配器回归实际返回成功。若后面还有一个带码的致命失败，首个失败信息也会被覆盖。

现在用独立boolean保留首个致命失败，其错误码和消息仍可空，由适配器原有VERIFIER_FATAL兜底处理。修复后适配器发出失败落库调用、返回失败且不发成功调用；这是单元级仓储替身验证，没有把该断言写成真实Pipeline数据库收敛。原有软告警、通过、未注册和未知类型反例继续保留。

<a id="backend-review-bug-20261027-16--p2redis生效配置未进入后端身份"></a>

#### BUG-20261027-16 / P2：Redis生效配置未进入后端身份

RuntimeInfrastructureInspector仅从Environment读取独立host/port/database和扁平Sentinel字符串，忽略URL优先级、索引列表、Cluster、MasterReplica及DataRedisConnectionDetails覆盖。客户端可连接新位置，守卫却继续拿旧属性或localhost身份比较；真实PG对五种配置来源变化均错误放行。

探针现使用与仓库当前Boot客户端相同的DataRedisConnectionDetails，依框架顺序选择Sentinel、Cluster、MasterReplica或Standalone；只读取定位，不读取用户名密码或建立连接。无有效定位时失败关闭。ComponentScan与AutoConfiguration回退均更新，Quota守卫直接消费该身份，原坐标DTO和重复属性解析移除。当前独立节点/Sentinel身份格式保留，新增两类拓扑有明确前缀；未增加环境变量或默认值，未改业务Redis连接、Quota算法或导入语义。

本地锁定Boot 4.1.1的真实配置绑定10项及真实PG守卫15项验证此边界；后者含五类来源变化、四类大小写变化和原六项。真实Redis配额6项、真实数据库配额12项及Quota守卫7项通过。没有验证真实Sentinel/Cluster/MasterReplica迁移、多副本切换或容量；JDBC自定义连接覆盖与手写客户端工厂仍待核对。

<a id="backend-review-bug-20261027-13扩展与排除的候选"></a>

#### BUG-20261027-13扩展与排除的候选

同一默认语言根因还影响对象存储FILESYSTEM和Quota REDIS两处后端标识。两组各7项中2个合法大写配置先抛不支持类型，S3/DATABASE及原分支为通过反例；两处仅改Locale.ROOT，沿用13号，不重复计Bug。

Pipeline类型转换的语言候选已排除：DictEnum.fromCode按equalsIgnoreCase匹配，土耳其语/阿塞拜疆语下小写import仍匹配并执行致命校验。两个真实注册器反例通过，未机械修改toUpperCase；已核对内置四类文件Worker的固定类型来源。后续继续审查注册器计量异常与失败返回的隔离、URI查询敏感值及JDBC覆盖，不把候选提前算修复。

<a id="backend-review-本批最新内容摘要快照"></a>

### 本批最新内容摘要快照

后端6,430来源：206已审、72部分、6,138待审、14待复核；前端1,265来源：102已审、38部分、1,125待审。本批各新增一份验证来源。ConfigSync应用服务、Calendar业务日解析器、TaskDispatchExecutor、ActiveTaskLeaseRegistry、WorkerRuntimeState、OutboxCoordinator六份共享生产来源又发生变化，保留旧摘要转待复核，与原八份测试合计14份；未替其他修改者销项。已核对WorkerRuntimeState仍按注册对象身份比较，Outbox的领取凭据和CAS仍在，其他片段继续审查。

上述分母是当前清单，不回写前段历史批次数字。整仓仍未完成逐文件Bug审核；Source盘点、配置/注入门禁和局部测试通过均不替代人工审查。当前专题继续保留URI查询敏感值、JDBC覆盖、计量异常及Nginx代理/日志候选。无提交、暂存、发布或主工作树修改。

<a id="backend-validation-record"></a>

<a id="backend-validation-后端-bug-续审验证2026-10-27"></a>

## 后端历史运行验证

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：后续执行范围见[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)。以下验证仍限于各自源码与环境快照；本次重新制定清单没有重跑编译或测试，也没有将旧结果升级为当前全工程通过。

本记录对应[当日确认问题](#backend-review-record)，延续原配对 `fix/wdi-current` 工作树。只使用测试容器、随机端口和自有场景数据，没有修改运行中的业务数据库或冻结工作树。

<a id="backend-validation-首个失败与根因"></a>

### 首个失败与根因

- `/private/tmp/wdi-config-bundle-completeness-red.log`：真实PostgreSQL的资源队列预期201条、实际200条。最初图夹具203节点也被截断，但超过当前节点准入上限，未将其计为正常业务证据；随后改为DAG校验器实际通过的150节点、296条边。
- `/private/tmp/wdi-config-bundle-completeness-green.log`：真实PG两个用例通过，旧复制Mock中的三个用例因仍匹配非空分页而失败。改为严格匹配空分页，保留依赖、日历、渠道与去凭据的原断言。
- `/private/tmp/wdi-config-matrix-baseline-red.log`：基准在展示范围外的两项新增用例失败，原列表内基准两项通过；旧代码实际返回空差异。

<a id="backend-validation-首批业务验证"></a>

### 首批业务验证

首批 **39项通过、0失败/错误/跳过**，其中 **22项真实PostgreSQL**：配置完整性2项、同步收据/认证HTTP7项、严格初始化与缓存事务13项。日志 `/private/tmp/wdi-tenant-copy-backend-final.log`。完整集合阶段另有17项通过，与39项重叠，不累计。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBundleCompletenessIntegrationTest,DefaultConsoleTenantConfigCopyServiceTest,TenantConfigMatrixBuilderTest,DefaultConsoleConfigSyncBundleMappingTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实PG验证201条队列全部进入配置包和差异Overlay，合法图保留150节点及296条边。矩阵新增测试验证外部基准加载与缺失作业事实，输出不额外加入基准行。

前端定向17项及全量 **226文件/1,389项通过**，实际命令、首红、构建与边界见[配对报告](#frontend-review-record)。前端组件/API替身、后端真实数据库和认证HTTP分别取证，未将其称为真实浏览器完整旅程。

<a id="backend-validation-限制"></a>

### 限制

没有执行容量、生产网络故障或目标部署验收，没有修改迁移。全量集合的内存/延迟规模、其余配置类别全部真实数据矩阵、当批尚待审的源版本选择与源目标重合已由后续批次修复；旧入口未知结果恢复继续审查。全工程内容摘要台账只更新实际读过的范围；盘点和本地全量单测不是全工程语义审查。

<a id="backend-validation-补充门禁与来源边界"></a>

### 补充门禁与来源边界

- `./mvnw -ntp -pl batch-console-api -am spotless:check`通过（`/private/tmp/wdi-tenant-copy-spotless-final.log`）。
- `python3 scripts/ci/check-console-openapi-paths.py`通过，432条路由一致（`/private/tmp/wdi-tenant-copy-openapi-paths.log`）。
- 配对生成类型按当前OpenAPI描述重生成，漂移检查通过。前端最终226文件/1,389项、构建和受影响文件lint通过；维护预览的初始组件假设已按服务端POST冻结契约更正，首红与最终结果见配对报告，未修改后端维护白名单。
- 首批清单快照后端6,368（146已审、55部分、6,167待审）、前端1,255（95已审、28部分、1,132待审）。新来源和并发变化只盘点；实际只读过分页片段的Mapper、请求元素和源版本仍标部分。全工程未完成。

<a id="backend-validation-后续批次源目标保护集合元素类型语言与workflow身份"></a>

### 后续批次：源目标保护、集合元素、类型语言与Workflow身份

<a id="backend-validation-首红及根因"></a>

#### 首红及根因

- `/private/tmp/wdi-copy-self-target-red.log`：认证HTTP/PG及内部调用两项均失败。真实响应200，原渠道`target_endpoint`与配置标记均变为null，预期400且两项保留；这不是仅静态推断。修复后读取源包前拒绝重合，新增中英文错误解析用例通过。
- `/private/tmp/wdi-copy-request-elements-red.log`：23项中15失败、8通过，包括9项Bean元素校验及14项认证HTTP。旧HTTP有200/500，也有已在其他边界返回400的反例；未把原本已拒绝的分支计新缺陷。现为租户/作业元素增加NotBlank、类型元素增加NotNull，另补可选集合省略/为空仍合法的正例。
- `/private/tmp/wdi-copy-type-locale-red.log`：土耳其语及阿塞拜疆语两项均因合法小写类型抛出IllegalArgumentException而错误退出。使用Locale.ROOT后通过；测试使用资源锁并恢复默认Locale，没有改变宿主机配置。
- `/private/tmp/wdi-workflow-definition-identity-red.log`：真实PG三项先失败；UPsert改写相似编码，SKIP_EXISTING跳过应建编码，升版后创建返回200/两条定义，预期409/一条。
- `/private/tmp/wdi-workflow-current-definition-red.log`：补齐有效原图夹具并加入同码多版本导出后，四项均失败。修复精确编码查询、全版本去重及最高版本导出后通过；停用的最高版本不会回退到旧启用版本。

独立阶段51项（公共10＋Console41）、集合/语言阶段78项、Workflow阶段92项均按各自快照保留日志；与最终合跑重叠，不累计相加。

<a id="backend-validation-workflow身份阶段合跑"></a>

#### Workflow身份阶段合跑

**129项通过、0失败/错误/跳过**，为`batch-common`11项加`batch-console-api`118项；其中真实PostgreSQL55项（认证/收据23、严格事务15、完整配置包3、Workflow变更10、版本写契约4）。日志 `/private/tmp/wdi-copy-workflow-governance-final.log`。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBundleCompletenessIntegrationTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,WorkflowDefinitionWriteContractIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,ConfigSyncTargetIdentityValidationTest,TenantConfigCopyTypeLocaleTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigMatrixBuilderTest,BizMessageResolverTest,MessagesPropertiesParityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

原Mock改用精确编码/最高版本查询，保留正常创建、跳过、托管单作业目标和版本CAS断言；创建后精确版本读取夹具直接返回刚保存实体，避免旧的多次返回序列提前截断后续图/快照路径。未删测试、扩大白名单或改变普通列表模糊搜索。

<a id="backend-validation-门禁及配对边界"></a>

#### 门禁及配对边界

- `MapperXmlTenantGuardArchTest`通过（`/private/tmp/wdi-workflow-identity-tenant-guard.log`），实际SQL租户谓词与参数绑定纳入检查。
- 受影响reactor `spotless:check`通过（`/private/tmp/wdi-copy-identity-spotless.log`）。
- `python3 scripts/ci/check-console-openapi-paths.py`通过，432路由一致（`/private/tmp/wdi-copy-identity-openapi.log`）。
- OpenAPI说明更新后生成配对类型，`npm run gen:api:check`通过（`/private/tmp/wdi-copy-identity-api-drift.log`）。
- 前端新增三项先红、修复后20项及全量226文件/1,392项通过；构建、类型、i18n与受影响文件ESLint通过，详见配对FE-BUG-42记录。

本批未修改Flyway、没有自动清理已有同码物理行或历史快照。没有验证实际部署容量、完整浏览器旅程、所有配置字段及环境覆盖策略，全工程逐文件审查继续进行。

<a id="backend-validation-类型null反例的后续复核"></a>

#### 类型null反例的后续复核

补测复制、差异、同步导出和同步预览四个真实HTTP入口，未加同步DTO NotNull时均已返回400；日志是反序列化层的InvalidNull错误。`/private/tmp/wdi-sync-type-elements-red.log`共5项仅1项Bean声明预期失败，4项HTTP通过，不是新API缺陷。四个选择器补齐NotNull声明属于契约元数据一致性，不计新增Bug；保留真实HTTP反例，并更正了早期可能成功的静态推断。集合元素Bug仍由那些已复现的租户/作业200/500及写入行为证明。

<a id="backend-validation-作业包导入的整组目标校验"></a>

#### 作业包导入的整组目标校验

`/private/tmp/wdi-job-bundle-target-elements-red.log`：6项中5失败、1通过。两个真实认证HTTP反例在目标列表为“合法目标＋null/空白”时返回200，且查询确认第一个目标已写入资源队列；空串已被原Size约束拒绝，不能算新失败。三个应用层用例原本未抛参数异常。

作业包DTO补齐NotBlank，初始化应用服务在任何目标事务前检查整个列表；未扩大事务到全批，合法目标的业务失败隔离及未知提交结果向上传播保持原语义。`/private/tmp/wdi-job-bundle-target-elements-green.log`定向8项通过（真实HTTP/PG3项，应用层5项）；最终关联结果另列，不与早期计数累加。

<a id="backend-validation-配置身份阶段最终合跑与清单"></a>

### 配置身份阶段最终合跑与清单

`/private/tmp/wdi-config-identity-transaction-final.log`完整命令退出0：**140项通过，0失败、错误、跳过**（公共模块11项、Console129项）。其中真实PostgreSQL **61项**：认证/审计/请求28、严格事务15、Workflow变更10、版本写契约4、完整配置包3、未知提交1。这个合跑包含前述129/131项和定向8项，不将重叠批次相加。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBundleCompletenessIntegrationTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,WorkflowDefinitionWriteContractIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,ConfigSyncTargetIdentityValidationTest,TenantConfigCopyTypeLocaleTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigMatrixBuilderTest,BizMessageResolverTest,MessagesPropertiesParityTest,TenantConfigInitOutcomeTest,TenantConfigInitCommitOutcomeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

当前清单后端6,369项（157已审、59部分、6,152待审、1待复核），前端1,255项（95已审、30部分、1,130待审）；以CSV内容摘要为准。只新增一份Locale测试；并发变化的日切测试保留旧摘要待复核。大服务、Mapper XML及作业包面板按已读取片段继续标部分，没有把测试通过当作全文审查。前端本批语义代码的全量单测仍为226文件/1,392项；本次后续仅更新OpenAPI生成说明。

<a id="backend-validation-收尾检查"></a>

#### 收尾检查

以下命令均观察到退出0，日志前缀为`/private/tmp/wdi-config-identity-`：

| 实际命令 | 结果/日志后缀 |
|---|---|
| `./mvnw -ntp -pl batch-console-api -am spotless:check` | 受影响reactor通过，`final-spotless.log` |
| `./mvnw -ntp -pl batch-console-api -am -Dtest=MapperXmlTenantGuardArchTest -Dsurefire.failIfNoSpecifiedTests=false test` | 2项通过，`final-tenant-guard.log` |
| `python3 scripts/ci/check-console-openapi-paths.py` | 432路由通过，`doc-routes.log` |
| `python3 scripts/ci/check-docs-structure.py` | 通过，`doc-structure-final.log` |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过，`doc-date.log` |
| `python3 scripts/ci/check-code-doc-references.py` | 通过，`doc-references.log` |
| `python3 scripts/ci/check-changelog-sync.py` | 通过，`doc-changelog-final.log` |
| 前端 `npm run typecheck` | 当前生成类型通过，`final-frontend-typecheck.log` |
| 前端 `npm run check:docs`、`npm run check:changelog` | 通过，`frontend-docs.log`、`frontend-changelog.log` |

作业包说明更新后再次执行前端`npm run gen:api`及`npm run gen:api:check`，两项退出0，日志为`/private/tmp/wdi-job-bundle-identity-api-gen.log`及`/private/tmp/wdi-job-bundle-identity-api-drift.log`。两仓`git diff --check`退出0；CSV行尾提示不代表运行缺陷，没有重写其他治理清单。全量后端业务测试、真实浏览器旅程和目标部署未在本批执行。

<a id="backend-validation-配对作业包向导继续验证"></a>

### 配对作业包/向导继续验证

后续FE-BUG-43..47仅改前端行为与后端接口说明，本批未重新执行Java测试；前一140项/真实PG61项保留原作用范围。前端最终227文件/1,417项、构建、定向lint及重新生成API类型漂移通过，独立首红和命令见[当日前端报告](#frontend-review-record)。全工程内容摘要清单以最新CSV和TODO为准，不沿用前段1,255项前端分母。

<a id="backend-validation-worker生命周期续审验证"></a>

### Worker生命周期续审验证

<a id="backend-validation-首红和定向恢复"></a>

#### 首红和定向恢复

- `./mvnw -ntp -pl batch-worker/core -am -Dtest=TaskExecutionPoolConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：首红`/private/tmp/wdi-worker-pool-shutdown-red.log`共5项，新增2项失败、原3项通过。正常强停和调用线程已中断两条路径的排队Future都未取消；实际排队业务没有执行。修复后同命令5项通过（`/private/tmp/wdi-worker-pool-shutdown-green.log`）。
- `./mvnw -ntp -pl batch-worker/core -am -Dtest=DefaultHeartbeatServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：首红`/private/tmp/wdi-worker-heartbeat-late-red.log`9项中新增2项失败、原7项通过。真实线程让移除/替换发生在renew已发出而响应未返回时，旧put复活注册或覆盖新对象；修复后9项通过（`/private/tmp/wdi-worker-heartbeat-late-green.log`）。新注册字段相等但实例不同，不能用Data生成的equals替代所有权身份。
- 期间15类关联回归102项通过（`/private/tmp/wdi-worker-lifecycle-regression.log`），该快照早于心跳修复；与最终完整模块重叠，不加总。

<a id="backend-validation-完整受影响模块"></a>

#### 完整受影响模块

```sh
./mvnw -ntp -pl batch-worker/core -am test
```

命令退出0，日志`/private/tmp/wdi-worker-core-all-final.log`：**Worker Core 47份报告/370项全部通过，无失败、错误、跳过**，包含真实PG的Report Outbox 3项。上游`batch-common`692项中690通过、2跳过；`batch-test-support`68项中65通过、3跳过。整个reactor记录1,130项，其中**1,125通过、5跳过，零失败/错误**。没有将其他模块旧XML报告混入本次统计，模块聚合与命令日志一致。

5项均为已有条件化活体验证：2项缺少`BIZ_SHARD_0_URL`，3项缺少`S3_COMPAT_POC_ENDPOINT`。本轮没有设置环境变量绕过条件、删除测试或放宽断言；多分片路由及外部S3供应商活体契约仍未验证。

后续只修正claim/renew接口和实现中的注释：明确404/409才是业务拒绝，其他HTTP错误传播；修正非幂等起点方法的复制错误说明。行为未变，当前`./mvnw -ntp -pl batch-worker/core -am spotless:check`退出0（`/private/tmp/wdi-worker-lifecycle-spotless-final.log`）。没有执行全部五类Worker、全后端业务测试、真实网络故障/Pod滚动或目标环境验收；不可中断业务线程仍沿原看门狗与运维处理。

<a id="backend-validation-worker批次清单与剩余范围"></a>

#### Worker批次清单与剩余范围

当前后端6,369来源：177已审、61部分、6,127待审、4待复核；前端1,256来源：99已审、31部分、1,126待审。清单逐项使用当前文件摘要；未读完的HTTP客户端、执行包装器、抽象循环和跨组件关闭组合继续标部分。未执行另外四类Worker的全模块测试，也未执行完整前端测试：本批前端源码摘要未变，沿用前批227文件/1,417项限定快照。全工程目标继续进行。

<a id="backend-validation-收尾来源变化"></a>

#### 收尾来源变化

`TriggerAdmissionDrainTest`、`TriggerSchedulingOwnershipTest`及`ExternalEventApiKeyHttpIntegrationTest`在收尾核对时被并发修改，原已审内容摘要保留并转待复核，未覆盖改动；原日切测试待复核保留。最新后端177已审、61部分、6,127待审、4待复核，共6,369；前端99已审、31部分、1,126待审，共1,256。本轮Worker源码摘要未变，测试结果不外推到新Trigger内容。共享E2E变化只更新待审摘要。

<a id="backend-validation-worker批次文档检查"></a>

#### Worker批次文档检查

`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`全部退出0；日志前缀`/private/tmp/wdi-worker-lifecycle-doc-`分别接`structure/timestamp/references/changelog.log`。配对前端`npm run check:docs`退出0，日志`/private/tmp/wdi-worker-lifecycle-frontend-docs.log`。当前`git diff --check`退出0。最初收尾脚本在发现已审来源漂移时主动中止，未据旧状态销项；随后显式转待复核并更新上述清单。

<a id="backend-validation-回报队列的领取cas与执行身份验证"></a>

### 回报队列的领取CAS与执行身份验证

<a id="backend-validation-首红与夹具问题分别保留"></a>

#### 首红与夹具问题分别保留

| 日志（均位于`/private/tmp/`） | 实际结果与归因 |
|---|---|
| `wdi-report-outbox-ownership-red.log` | 初始11项/8失败，其中4个陈旧回收失败含时间夹具错误，不能直接作为有效首红 |
| `wdi-report-outbox-ownership-valid-red.log` | 时间改为实际领取时刻后，11项中8项失败、3通过，证实两存储的迟到确认/失败/放弃覆盖 |
| `wdi-report-outbox-invocation-identity-red.log` | 15项中2失败，PG及SQLite不同执行互相覆盖，其他13项通过 |
| `wdi-report-outbox-stale-metric-red.log` | SimpleMeterRegistry不支持try-with-resources导致测试编译失败，仅夹具错误 |
| `wdi-report-outbox-giveup-metrics-valid-red.log` | 5项中2失败，失效领取的损坏指标误增、合法重试耗尽指标漏增 |
| `wdi-report-outbox-nonempty-cutover-red.log` | 非空旧PG队列拒绝的单项失败；为新迁移补上ADR-048切换前置守卫，不作为新增运行时Bug编号 |
| `wdi-report-outbox-core-all-final.log` | 22个PG/SQLite夹具错误，Spring SQL分隔器无法执行DO块；改用真实Flyway及隔离测试资源目录，未删迁移或跳过负例 |
| `wdi-report-outbox-guarded-focused-final.log` | 最终定向27项通过：Repository22（PG12、SQLite10）＋Coordinator5，无失败/错误/跳过 |
| `wdi-report-outbox-fresh-schema-red.log` | 全新数据库迁移成功，结构摘要3类别与旧预期不同；核对本次列/唯一键DDL后仅更新这3行，保留首红 |

<a id="backend-validation-最终受影响模块及全新数据库"></a>

#### 最终受影响模块及全新数据库

以下顺序执行且均观察到退出0，不与早期定向或生命周期批次重复加总：

```sh
./mvnw -ntp -pl batch-worker/core -am test
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=LocalFlywayPlatformMigrationsIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

日志分别为`/private/tmp/wdi-report-outbox-core-all-verified.log`和`/private/tmp/wdi-report-outbox-fresh-migrations-verified.log`。当前Worker Core **393项全部通过**；上游common692项中690通过/2跳过，test-support68项中65通过/3跳过。reactor共1,153项，**1,148通过、5跳过**，零失败/错误。跳过仍为已有多业务分片2项及外部S3活体3项，未伪造环境或取消条件。

平台真实PostgreSQL完整Flyway迁移测试 **4项全部通过**，执行当前全链到V360，并核对结构/种子快照。仅更新fresh-platform-schema-manifest.sha256的COLUMN（4,958→4,959）、CONSTRAINT和INDEX三个实测摘要；未使用批量重写快照开关。Worker测试资源只复制V96/V360到独立fixture目录，通过Flyway执行，避免测试目录被当作完整迁移链自动扫描。

V360非空旧队列负例、SQLite旧结构拒绝及原载荷保留、同版本文件重开均由定向测试验证；本轮没有改共享数据库或尝试旧库升级。没有验证真实网络/多Pod故障、全五类Worker、容量或目标部署；配对前端无本批语义修改，旧227文件/1,417项结果只保留原快照。

<a id="backend-validation-后端身份语言复核后的最终结果"></a>

#### 后端身份语言复核后的最终结果

`./mvnw -ntp -pl batch-worker/core -am -Dtest=WorkerReportOutboxBackendGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`首红日志为`/private/tmp/wdi-report-outbox-backend-locale-red.log`：7项中SQLite两语言2失败、5通过，实际得到sqlıte而非sqlite。Locale.ROOT修复后同命令7项通过，日志`wdi-report-outbox-backend-locale-green.log`。

再次执行`./mvnw -ntp -pl batch-worker/core -am test`，日志`/private/tmp/wdi-report-outbox-core-locale-verified.log`，退出0：**Worker Core397项全部通过**，上游仍755通过/5环境跳过，总1,157项中**1,152通过、5跳过**，零失败/错误。此为当前完整模块证据，覆盖前一393项，不累计相加；此前全新迁移4项仍对应相同V360与schema摘要。当前`./mvnw -ntp -pl batch-worker/core -am spotless:check`退出0，日志`wdi-report-outbox-last-spotless.log`。

补齐漏盘文本来源后分母后端6,429、前端1,264，不将新的待审资产算完成。当前状态及源码摘要以两仓CSV和TODO为准；当前PG/SQLite测试不替代全工程逐文件审查。

<a id="backend-validation-回报批次收尾门禁"></a>

#### 回报批次收尾门禁

以下检查均观察到退出0：后端`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`（日志前缀`/private/tmp/wdi-outbox-docs-`，后缀分别为`structure/timestamp/references/changelog-final.log`）；`check-schema-governance-assets.py`通过。仓库锁定Squawk对V360单文件检查0问题（`/private/tmp/wdi-outbox-final-migration.log`）。前端`npm run check:docs`检查75份现行文档通过（`/private/tmp/wdi-outbox-frontend-docs-final.log`）。两仓`git diff --check`退出0；配对旧治理CSV有CRLF提示，未重写该文件。

测试后只修正PG/SQLite测试中的过期缓存说明注释，不改变测试体；继续核对全文后该测试来源标为已审。最新后端6,429项：194已审、70部分审查、6,157待审、8待复核；前端1,264项：101已审、35部分审查、1,128待审。已审/部分来源摘要均与当前文件一致；8份并发变动测试继续保留旧摘要待复核。本批全部写入既有配对wdi-current工作树，没有提交、暂存或修改main工作树。

<a id="backend-validation-后端定位与致命验收结果验证"></a>

### 后端定位与致命验收结果验证

<a id="backend-validation-独立首红与合法反例"></a>

#### 独立首红与合法反例

| 日志（`/private/tmp/`） | 实际结果 |
|---|---|
| `wdi-backend-resource-identity-red.log` | 身份单元6项中4失败/2通过；Maven快速失败导致下游未运行，未算真实PG证据 |
| `wdi-backend-resource-guard-red.log` | 单独执行真实PG守卫10项，新增4个大小写切换均未拒绝而失败，原6项通过 |
| `wdi-object-backend-locale-red.log`、`wdi-quota-backend-locale-red.log` | 两组各7项，2个配置解析错误、其余5项通过；错误是实际不支持fılesystem/redıs，不是环境问题 |
| `wdi-pipeline-fatal-null-code-red.log` | Hook/真实注册器9项中2失败，分别为致命事实丢失、首个失败信息被覆盖 |
| `wdi-pipeline-fatal-result-red.log` | 适配器5项中1失败，实际success=true；修复后验证失败调用、无成功调用及原兜底码 |
| `wdi-redis-effective-identity-red.log` | 真实Boot绑定10项中8失败，默认与扁平Sentinel两项为通过反例；临时入口等价委托原坐标读取，未把缺符号编译错误当首红 |
| `wdi-redis-effective-guard-red.log` | 真实PG15项中新增五类配置变化均未被拒绝，其他10项通过 |

原坐标DTO及重复解析在最终实现中移除，框架连接信息供探针与客户端共用。绑定测试禁用真实Redis客户端类加载，只验证框架属性、优先级、覆盖和凭据不进入身份；真实Redis业务结果另由集成类验证。

<a id="backend-validation-最终运行结果"></a>

#### 最终运行结果

```sh
./mvnw -ntp -pl batch-worker/core -am test
```

`/private/tmp/wdi-identity-core-current-final.log`退出0：**Worker Core402项全部通过**；common711项中709通过/2跳过；test-support77项中74通过/3跳过。共1,190项，**1,185通过、5环境跳过**，零失败/错误。5项仍为多业务分片2项及外部S3供应商活体3项；未删除或绕过条件。此为本批最新完整模块结果，覆盖前一400项及旧397/393项，不能重复加总。

```sh
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=QuotaRuntimeBackendGuardTest,RedisQuotaRuntimeStateIntegrationTest,QuotaRuntimeStateIntegrationTest,StatefulBackendIdentityTest,StatefulBackendGuardIntegrationTest,RuntimeInfrastructureInspectorTest,ObjectStorageBackendGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`/private/tmp/wdi-identity-quota-runtime-final.log`退出0：common24、test-support15、Orchestrator25项全部通过；Orchestrator为守卫7＋真实PG配额12＋真实Redis配额6。上游39项与完整模块重叠，未累计为额外通过数。之后AutoConfiguration回退与Component路径定向39项通过（`wdi-identity-auto-wiring-final.log`），最终完整模块包含更新后的配置和夹具。

<a id="backend-validation-共享来源与格式首错"></a>

#### 共享来源与格式首错

`/private/tmp/wdi-identity-spotless-final.log`首次失败于共享修改后的WorkerReportOutboxRepository：null判定已换为EmptyChecks.isNull，但三元表达式排版不符合格式器。保存当时文件到`/private/tmp/wdi-shared-outbox-repository-format-before.java`，仅应用排版，保留该判定；已读isNull实现等价于引用==null。Outbox定向34项随后通过（`wdi-identity-outbox-shared-recheck.log`），最终完整模块也包含这些用例。

当前`./mvnw -ntp -pl batch-worker/core,batch-orchestrator -am spotless:check`退出0（`wdi-identity-spotless-current-final.log`）。没有扩大格式目标到无关来源或改白名单。新增PG测试SQL使用独立命名文本块，回读结果先承接再断言；fixture命名空间由本类共享，契约预期独立保留。

<a id="backend-validation-证据限制与后续"></a>

#### 证据限制与后续

本批未修改Flyway、API或SDK协议，也未执行全部Orchestrator/Console/五类Worker、真实Sentinel/Cluster迁移、容量和目标部署。前轮全新Flyway4项是相同迁移来源的独立证据；本批不能代替发布验收。URI查询敏感值、JDBC覆盖与注册器计量故障继续列候选。配对Nginx缺失静态制品的真实HTTP回归见当日前端记录，鉴权替身不能证明真实认证。

<a id="backend-validation-定位批次清单与来源变化"></a>

#### 定位批次清单与来源变化

当前后端6,430项（206已审、72部分、6,138待审、14待复核）、前端1,265项（102已审、38部分、1,125待审）。新增的两份验证代码进入原清单。六份共享生产来源在本批核对时发生内容变化，转待复核且保留原摘要；不能用本批测试结论为这些新内容整体销项。已读到的Worker注册身份与Outbox CAS关键条件仍保留，后续全文复核继续进行。

配置治理、默认值同步、Java注入与测试配置来源四项实际命令退出0，日志依次为`/private/tmp/wdi-identity-config-governance.log`、`wdi-identity-config-defaults.log`、`wdi-identity-injection.log`、`wdi-identity-test-config-sources.log`。默认值同步中的8项网络拓扑差异和既有1项实例命名白名单按原设计报告，未修改白名单。它们是静态门禁，不是全工程语义审查。

<a id="frontend-review-record"></a>

<a id="frontend-review-前端-bug-续审租户复制上下文2026-10-27"></a>

## 前端问题与历史运行验证

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：原全工程逐文件执行方式已停止，改按[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)核对已有修复及直接影响。本文保留历史批次及其证据，原继续审查安排不再是当前执行指令。代码审查仍暂停，本次只调整清单。

<a id="frontend-review-范围与基线"></a>

### 范围与基线

继续[原前后端逐文件任务](../plans/todo-master.md#前后端全工程逐文件-bug-续审2026-10-26)。现有 `wdi-current/batch-console`、`fix/wdi-current`，HEAD `662d254e2f8b97f93021de548815470d2291c997`；后端配对HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`。已有改动保留，未提交或发布。[26日原始证据](../../../batch-console/docs/reports/2026-10-26-frontend-bug-remediation.md)继续保留。

<a id="frontend-review-fe-bug-41--p2复制操作与预览异步失效"></a>

### FE-BUG-41 / P2：复制操作与预览异步失效

[复制面板](../../../batch-console/src/views/system/components/TenantCopyConfigDialog.vue)原先在校验完成后才设置忙碌状态，重复点击能排出多笔写请求；关闭、编辑、账号/权限或维护变化后，旧校验仍继续复制。旧复制响应可关闭重开的面板，三类预览也能恢复旧输入的结果并覆盖最新读取。

修复绑定面板、输入、主体和读取代次，写操作额外绑定写权限代次。校验前即占用本次操作；输入、账号或面板变化立即清空旧预览，旧读取收尾不能解除新请求的忙碌状态。发送使用独立目标与类型数组，迟到写结果不作用于新面板。这些预览端点虽为业务只读，但均使用POST，因此与正式复制/试运行一起按服务端HTTP方法策略冻结。已发送业务不因关闭面板撤销。

<a id="frontend-review-证据"></a>

### 证据

- 首红 `/private/tmp/wdi-tenant-copy-scope-red.log`：14项中13失败、1正常复制正例通过。
- 修复后17项通过（`/private/tmp/wdi-tenant-copy-scope-green2.log`），包括旧读取不解除新忙碌及矩阵无作业不发送的反例；维护边界在后续服务端复核中修正，见下。
- `npm run test:unit` 全量 **226文件/1,389项通过**（`/private/tmp/wdi-tenant-copy-all-unit-final.log`）；较早1,386项快照不代替补充反例后的结果。
- 类型、受影响文件ESLint和生产构建按实际命令执行，日志 `/private/tmp/wdi-tenant-copy-typecheck.log`、`/private/tmp/wdi-tenant-copy-lint-final.log`、`/private/tmp/wdi-tenant-copy-build.log`。后续协议说明及生成类型同步另保留最终构建/漂移结果。

后端配置包完整集合读取和外部矩阵基准为两项独立修复，见[后端当日报告](#backend-review-record)和[真实数据库验证](#backend-validation-record)。没有把组件替身称为真实浏览器跨账号联验。

<a id="frontend-review-未覆盖与下一步"></a>

### 未覆盖与下一步

旧复制入口没有同步导入的持久原键与目标收据恢复能力；未知结果仍需人工核对。源目标重合在下段FE-BUG-42处理，后端源版本选择另见当日续审；选项数据变化、配置字段和环境覆盖等继续追查。组件标记部分审查，全工程逐文件任务保持进行，不将本轮定向/全量单测通过作为全部人工审核完成。

<a id="frontend-review-fe-bug-41维护契约复核与当批证据"></a>

#### FE-BUG-41维护契约复核与当批证据

初版组件用例认为业务只读预览可在维护期间发送，但没有核对服务端。随后通读 `MaintenanceModeFilter`，确认其按HTTP方法统一冻结POST，三个预览并无旁路；组件替身的放行不能证明真实系统可用。修正预期后先红1项/原16项通过（`/private/tmp/wdi-tenant-copy-maintenance-red.log`），再让前端遵循服务端冻结契约，未改变过滤器或扩大维护白名单。

最终 `npm run test:unit` **226文件/1,389项通过，无失败/跳过**（`/private/tmp/wdi-tenant-copy-all-unit-verified.log`）；`npm run build`及受影响文件ESLint通过（`/private/tmp/wdi-tenant-copy-build-verified.log`、`/private/tmp/wdi-tenant-copy-lint-verified.log`）。OpenAPI新增外部基准描述后重新生成类型，`npm run gen:api:check`通过（`/private/tmp/wdi-config-matrix-api-drift.log`），后端432条路由核对通过。纯契约描述变更没有增加接口结构或权限。

<a id="frontend-review-fe-bug-42--p2更换源后保留重合目标"></a>

### FE-BUG-42 / P2：更换源后保留重合目标

选项列表虽过滤源租户，原表单目标值并未随源变化移除重合项；再次点击复制可把处理后的包回写源。现更换源时只移除重合项，保留其他目标，提交前再复核；不会通过更改已有在途请求的目标数组实现保护。本项是同一风险链的前端守卫，后端实际数据破坏和读写前拒绝见BUG-20261027-03，不重复称为两次生产事故。

首红 `/private/tmp/wdi-copy-source-target-ui-red.log`：新增3项失败、原17项通过；修复后20项通过（`/private/tmp/wdi-copy-source-target-ui-green.log`），覆盖目标移除、异常重合状态拒绝和其余目标保留。

最终 `npm run test:unit` **226文件/1,392项通过、0失败/跳过**（`/private/tmp/wdi-copy-source-target-front-all.log`），`npm run build`及受影响组件/测试/语言包ESLint通过（`/private/tmp/wdi-copy-identity-front-build.log`、`/private/tmp/wdi-copy-identity-front-lint.log`）；构建含类型与i18n检查。OpenAPI说明重新生成类型后漂移检查通过。

当前用户说明、API协议和运行手册已同步。后端最终关联140项、真实PG61项为独立层级证据；未进行真实浏览器跨账号旅程或目标部署验证。旧复制未知结果持久恢复、目标环境字段保留与完整字段覆盖仍待继续审查。

<a id="frontend-review-fe-bug-42阶段清单与契约说明收尾"></a>

### FE-BUG-42阶段清单与契约说明收尾

当批1,255个来源：95已审、30部分、1,130待审。作业包列表与向导只初读提交/目标/结果片段，异步上下文及完整映射仍待验证，不因接口单测通过升级为全文已审。

后端新增作业包整组目标校验说明后重新执行`npm run gen:api`与`npm run gen:api:check`均退出0（日志`/private/tmp/wdi-job-bundle-identity-api-gen.log`、`/private/tmp/wdi-job-bundle-identity-api-drift.log`）；`npm run typecheck`退出0（`/private/tmp/wdi-config-identity-final-frontend-typecheck.log`）。后续只更新生成注释，未改变组件语义，沿用上段1,392项当前语义快照，不重复计数。

<a id="frontend-review-作业包与向导续审fe-bug-43至47"></a>

### 作业包与向导续审：FE-BUG-43至47

继续同一`fix/wdi-current`配对工作树，本批后端只同步接口和运维说明，未变更Java或数据库行为。原140项后端结果只属于上一批源码快照；本批前端单测与构建单独取证。

| ID | 触发与旧行为 | 修复范围 |
|---|---|---|
| FE-BUG-43 / P2 | 作业包导入发出后切租户、账号、权限/维护代次、改输入或关闭重开，旧成功仍提示、关闭新面板并刷新新上下文；完成时还读取当前试运行开关 | 绑定输入/面板修订与租户生命周期，反馈只作用于发起范围；请求及结果提示使用发送时模式 |
| FE-BUG-44 / P2 | 打包新建等待时修改作业编码，旧成功用新编码导航；切换上下文或卸载后仍提示/导航，处理器缺少在途重复守卫 | 固定提交编码，绑定输入/步骤/主体/写权限修订与租户生命周期；旧反馈失效，在途提交零重复发送 |
| FE-BUG-45 / P2 | 元数据或关联渠道的旧租户响应覆盖当前队列/选项，切租户后旧选择保留到新读取完成 | 整个元数据→关联读取共享范围代次；切租户/操作者同步清空旧选项和选择，旧请求不能续发读取、覆盖结果或解除新读取忙碌 |
| FE-BUG-46 / P2 | 异步步骤校验期间多次点击叠加推进；旧租户/操作者的校验继续推进当前步骤 | 校验期间锁定一次推进，校验完成核对输入/上下文及原步骤；输入变化需重新校验 |
| FE-BUG-47 / P2 | 前端允许租户管理员进入打包向导，普通读取角色也看到可用导出动作；服务端控制器当前为ADMIN_ONLY | 向导路由、写入及导出动作对齐已有ROLE_ADMIN契约；普通单作业创建仍按原配置写权限提供，不放宽服务端授权 |

<a id="frontend-review-首红与回归"></a>

#### 首红与回归

- `/private/tmp/wdi-job-bundle-context-red.log`：16项中14失败、2正常路径通过，真实组件配合可控Promise复现旧响应和重复提交，旧导航实际携带JOB_B而提交的是JOB_A。
- `/private/tmp/wdi-job-wizard-associations-red.log`：新增3项失败、原16通过，旧渠道/队列覆盖和旧选择残留均由组件可观察结果证明。
- `/private/tmp/wdi-job-wizard-step-red.log`：新增3项失败、原19通过，重复点击实际叠加推进两格，旧上下文校验仍推进一格。
- `/private/tmp/wdi-job-bundle-permission-red.log`：组件入口与路由各1项失败，原25项通过。导出单项首红`/private/tmp/wdi-job-bundle-export-permission-red.log`按名称只执行新增1项并失败，其余23项被过滤，不算通过或删除测试。
- `/private/tmp/wdi-job-bundle-permission-green.log`：最终定向2文件/28项通过、无跳过（作业包组件24项、路由库存4项）。权限依据服务端`ConsoleJobBundleController`的现有ADMIN_ONLY与前端路由消费者，不把standalone MockMvc当作真实方法授权验证。

当前用户指南、角色矩阵、根Changelog、API协议与运维手册同步。前端的失效判断不会取消已发送的服务端业务；响应不确定仍需核对原目标，旧复制/作业包入口尚未具有配置同步的完整持久原键/收据恢复。关联类型完整字段映射、容量与真实浏览器/跨账号/目标环境验证继续开放。

<a id="frontend-review-本批最终验证与覆盖"></a>

#### 本批最终验证与覆盖

- `npm run test:unit`：**227文件/1,417项通过，无失败/跳过**，日志`/private/tmp/wdi-job-bundle-context-all-final.log`。前一1,414项快照在补齐角色/导出反例之前，不作为最终计数；各阶段用例重叠，不累加。
- `npm run build`：类型、i18n及生产/PWA构建通过，日志`/private/tmp/wdi-job-bundle-context-build-final.log`。
- `npx eslint src/views/job/JobDefinitionList.vue src/views/job/JobDefinitionWizard.vue src/views/job/JobBundleContextRisks.test.ts src/router/index.ts src/router/routeInventory.test.ts`退出0，日志`/private/tmp/wdi-job-bundle-context-lint-all-final.log`。
- 更新OpenAPI说明后执行`npm run gen:api`及`npm run gen:api:check`均退出0，日志`/private/tmp/wdi-job-bundle-context-api-gen.log`、`/private/tmp/wdi-job-bundle-context-api-drift.log`。

最新清单**1,256来源：99已审、31部分、1,126待审**。新增一份24项组件测试；路由库存测试、权限store及其测试完成全文核对。大列表、向导和路由主文件仍按实际片段标部分。全工程逐文件审查保持进行，当前前端单测不等同于真实后端、浏览器或全部文件审查。

<a id="frontend-review-文档与契约收尾"></a>

#### 文档与契约收尾

`npm run check:docs`及`npm run check:changelog`均退出0，日志`/private/tmp/wdi-job-bundle-context-docs.log`、`/private/tmp/wdi-job-bundle-context-changelog.log`。配对后端实际执行`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`与`check-console-openapi-paths.py`，全部退出0；日志前缀`/private/tmp/wdi-job-bundle-context-be-doc-`分别接`structure/date/references/changelog/routes.log`。路由一致性为432条。两仓源码摘要复核均无漂移，已有待复核项仍保留；两仓`git diff --check`退出0。当前无新增编译或运行失败，不将尚未执行的浏览器/真实账号边界、全部后端测试及剩余逐文件审查记为通过。

<a id="frontend-review-部署文本与清单补漏续审"></a>

### 部署文本与清单补漏续审

补入此前扩展名过滤遗漏的8份文本资产：Nginx模板、E2E声明、PWA清单、示例数据及辅助治理文本。当前1,264项：101已审、35部分审查、1128待审；新增盘点不计语义审查。声明已逐项对照运行配置导出，PWA入口及7个图标引用核对；Nginx模板和片段、E2E配置仍按实际读取范围标部分。

当前没有新增已确认前端Bug或语义修改，未重跑组件测试/构建；原227文件/1,417项及构建仅保留原来源快照。静态资源不存在时的Nginx全局404回退列为待运行验证候选，不能仅凭模板推断计为已确认Bug；真实代理、长连接、浏览器PWA安装/离线及目标部署仍未验。后端当前回报队列批次见[配对验证](#backend-validation-回报队列的领取cas与执行身份验证)。

<a id="frontend-review-静态制品错误状态续审"></a>

### 静态制品错误状态续审

<a id="frontend-review-fe-bug-48--p2缺失静态制品错误返回成功入口"></a>

#### FE-BUG-48 / P2：缺失静态制品错误返回成功入口

真实Nginx复验确认，缺失JS、字体、图片和version.json先被各location判404，却被server级error_page重定向为200的SPA HTML；icons/manifest则因通用history回退返回同样的错误内容。脚本/版本读取因此得到内容类型和内容不符的成功响应，不能据HTTP200判断制品可用。

删除全局404转入口，只由普通前端路径承担history回退。icons前缀和manifest精确路径独立检查存在性，固定文件名仍使用no-cache；已存在资源内容、hash资源缓存、相对/docs跳转和API转发保留。对新指令显式包含原安全头片段，未更改登录、权限或后端契约。Dockerfile和模板注释同步实际BACKEND_UPSTREAM_HOST变量名，镜像版本和构建指令未改。

<a id="frontend-review-首红最终回归及排除的推断"></a>

#### 首红、最终回归及排除的推断

- `/private/tmp/wdi-nginx-routing-red.log`最初9场景中2失败，实际/assets缺失JS和缺失version.json均200/SPA HTML。
- 扩展后的`wdi-nginx-routing-extended-red.log`及仓库回归`wdi-nginx-routing-contract-red.log`覆盖15个HTTP子场景，6失败/9通过。父测试随子失败，不能把父层再计一个业务缺陷。
- `npm run test:nginx-routing`最终退出0，`/private/tmp/wdi-nginx-routing-contract-final.log`记录15个HTTP子场景全通过；Node连父层共16条，无失败/取消/跳过。使用Dockerfile声明的不可变Nginx 1.30.5/Alpine镜像`sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94`，实际加载仓库nginx.conf、模板和片段，启动后另执行nginx -t。
- 已登录文档缺页原本就是404，API404也原本正确；初始关于文档会落入SPA的静态推断已由运行反例排除，没有算作新修复。相对/docs的301、正常文档/脚本/图标/清单及普通前端路径作为正例保留。

测试脚本只生成独占临时制品和鉴权204替身，发布随机回环端口，清理先核对本例容器标签。缺失清单仅删除生成的临时文件；未写共享应用环境或删除生产制品。它验证真实Nginx HTTP路由，不证明真实认证、根用户限制、完整应用镜像、PWA安装、浏览器交互或目标部署。本批没有新增Vue/TS行为修改，未重跑组件全集或生产bundle；旧227文件/1,417项保留原快照，不重算为本批结果。

<a id="frontend-review-剩余范围"></a>

#### 剩余范围

Nginx上游Host/转发端口与OIDC、访问日志中的查询数据、SSE/上传/完整代理链仍待追查；模板及相关配置继续按实际范围标部分。新增HTTP回归来源计入原逐文件清单，不把某一服务器回归当全前端审核完成。配对后端定位与产物致命失败的证据见[后端当日记录](#backend-validation-后端定位与致命验收结果验证)。

<a id="frontend-review-静态制品批次清单与轻量检查"></a>

#### 静态制品批次清单与轻量检查

最新前端1,265来源：102已审、38部分、1,125待审；新增一份Node/Docker HTTP回归，模板、核心配置、构建与代理相关入口仍按实际阅读范围标部分。后端清单6,430来源中14份共享变动待复核，不将旧证据套到新内容。

`npx eslint scripts/tests/nginx-routing.test.mjs`、`npx prettier --check scripts/tests/nginx-routing.test.mjs`、`npm run check:env`、`npm run check:test-fixture-sources`均退出0；日志前缀为`/private/tmp/wdi-nginx-routing-`，分别为`eslint.log`、`prettier.log`、`env.log`和`fixture-sources.log`。未新增默认CI/发布阻断策略，专用回归由独立npm命令运行。
