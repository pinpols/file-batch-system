# Java 可读性治理当前机器清单

> 由 `bash scripts/python.sh scripts/ci/report-java-readability-inventory.py` 生成。
> 本报告只列候选，不把行数、Map 或 suppression 数量直接判定为缺陷。

## 汇总

| 指标 | 数量 |
|---|---:|
| 生产 Java 源文件 | 3011 |
| CGLIB 自注入类 | 0 |
| `Map<String, Object>` 出现次数 | 2074 |
| 含 Map 的源文件 | 410 |
| public Map 契约候选 | 60 |
| public Map 契约候选文件 | 44 |
| `@SuppressWarnings` | 237 |
| 含 suppression 的源文件 | 175 |
| `@Configuration` 类 | 51 |
| 大于等于 700 行的源文件 | 36 |
| `PMD.ExcessiveParameterList` 显式例外 | 29 |

## 模块源文件

| 模块 | 生产 Java 文件 |
|---|---:|
| `batch-common` | 363 |
| `batch-console-api` | 1204 |
| `batch-orchestrator` | 772 |
| `batch-trigger` | 124 |
| `batch-worker` | 445 |
| `sdk` | 94 |
| `security-scan` | 9 |

## CGLIB 自注入

| 文件 | 自注入类型 |
|---|---|

## 大类候选（大于等于 700 行）

| 文件 | 行数 |
|---|---:|
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java` | 1531 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java` | 1357 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java` | 1253 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | 1245 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 1238 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java` | 1227 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleOrchestratorProxyService.java` | 1079 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | 1060 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java` | 1033 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplayPreviewService.java` | 985 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java` | 976 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java` | 934 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 929 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 916 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskAssignmentService.java` | 903 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java` | 877 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java` | 856 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | 835 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java` | 832 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleOpsQueryService.java` | 826 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java` | 817 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java` | 813 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/query/ConsoleFileQueryService.java` | 799 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java` | 791 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java` | 789 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java` | 771 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationService.java` | 769 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java` | 768 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/DefaultWorkflowDefinitionService.java` | 765 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java` | 758 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 732 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/observability/DefaultConsoleQueryApplicationService.java` | 726 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileTemplateApplicationService.java` | 712 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java` | 712 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelWorkbookWriter.java` | 704 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java` | 704 |

## Public Map 契约候选

> 此处只做词法候选。插件参数、metadata、JSONB、动态聚合和外部扩展字段应保留 Map。

| 文件 | 候选方法 |
|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchKafkaProducerSupport.java` | `L36: public static Map<String, Object> stringProducerConfig` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/context/RunModeSupport.java` | `L17: public static Map<String, Object> copyWithDefault` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/diagnostics/BatchRuntimeStatusEndpoint.java` | `L51: public Map<String, Object> status` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/page/CursorCodec.java` | `L45: public static Map<String, Object> decode` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/JsonUtils.java` | `L91: public static Map<String, Object> toMap` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java` | `L65: public static Map<String, Object> maskSensitiveKeys`<br>`L71: public static Map<String, Object> maskSensitiveKeys` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SensitiveEvidenceMasking.java` | `L22: public static Map<String, Object> maskMetadata` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileChannelConfigQueryView.java` | `L26: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileTemplateConfigQueryView.java` | `L61: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/view/ConsoleJobConfigViews.java` | `L42: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java` | `L525: public Map<String, Object> parsePayload` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/view/ResourceQueueRow.java` | `L43: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java` | `L133: public static Map<String, Object> toExportRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java` | `L18: public static Map<String, Object> mapOf` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java` | `L55: public Map<String, Object> effectiveParams` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java` | `L299: public Map<String, Object> nodeOutput`<br>`L304: public Map<String, Object> workflowRunFields`<br>`L333: public Map<String, Object> nodeOutput`<br>`L338: public Map<String, Object> workflowRunFields`<br>`L362: public static Map<String, Object> parsePayloadMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java` | `L584: public Map<String, Object> dispatchSecretBindings` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java` | `L99: public Map<String, Object> toCacheValues`<br>`L119: public Map<String, Object> toCacheValues` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/SensorFileArrivalView.java` | `L18: public Map<String, Object> toProbeOutput` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java` | `L37: public Map<String, Object> getNullableResult`<br>`L43: public Map<String, Object> getNullableResult`<br>`L48: public Map<String, Object> getNullableResult` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java` | `L41: public Map<String, Object> pin`<br>`L166: public Map<String, Object> pinWorkflowReplay` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchSecretSnapshotResolver.java` | `L34: public Map<String, Object> pin` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java` | `L41: public Map<String, Object> pin`<br>`L90: public Map<String, Object> pinWorkflowReplay` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java` | `L31: public Map<String, Object> loadFileRecord`<br>`L48: public Map<String, Object> loadFileRecordByStoragePath` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileTemplateRepository.java` | `L18: public Map<String, Object> loadLatestTemplateConfig` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformPipelineRunRepository.java` | `L102: public Map<String, Object> loadLatestSucceededStepOutputSummary` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRecordView.java` | `L40: public Map<String, Object> toPluginValues` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileTemplateConfigView.java` | `L61: public Map<String, Object> toPluginValues` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java` | `L31: public Map<String, Object> resolve` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java` | `L106: public static Map<String, Object> merge` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java` | `L36: public Map<String, Object> loadFile`<br>`L44: public Map<String, Object> loadFile`<br>`L52: public Map<String, Object> loadChannel` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/mapper/DispatchChannelConfigView.java` | `L25: public Map<String, Object> toPluginValues` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java` | `L19: public static Map<String, Object> toMap` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java` | `L83: public Map<String, Object> loadBatch` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java` | `L91: public Map<String, Object> loadBatch` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java` | `L20: public Map<String, Object> toMap`<br>`L42: public Map<String, Object> firstMap`<br>`L52: public Map<String, Object> payloadToMap` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationRuleSetMerger.java` | `L32: public Map<String, Object> merge` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java` | `L62: public Map<String, Object> parseHints`<br>`L76: public Map<String, Object> readJsonObject` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | `L618: public Map<String, Object> progressSnapshot` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java` | `L63: public Map<String, Object> toOutput` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java` | `L71: public Map<String, Object> toOutputMap` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/ProgressReporter.java` | `L29: public Map<String, Object> latest` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskStoppedException.java` | `L24: public Map<String, Object> breakPosition` |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java` | `L233: public List<Map<String, Object>> registrations` |

## 宽参数显式例外

| 文件 | 例外数 |
|---|---:|
| `batch-common/src/main/java/io/github/pinpols/batch/common/kafka/TaskDispatchMessage.java` | 2 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/view/dashboard/ExecutionProgressView.java` | 1 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 1 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/view/ConsoleWorkerRegistryResponse.java` | 1 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/excel/TenantConfigPackageExcelImportStore.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplaySubmitCommand.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/command/CompensationSubmitCommand.java` | 2 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/BatchDayInstanceEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/BatchDayReplayEntryEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/BatchDayReplaySessionEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/BusinessCalendarEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/JobDefinitionEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/ResultVersionEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/WorkerRegistryEntity.java` | 3 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/JobMonitoringAlertCandidate.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java` | 1 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicWorkerLoop.java` | 1 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 1 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java` | 2 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatchMessage.java` | 1 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java` | 4 |
