# Java 可读性治理当前机器清单

> 由 `bash scripts/python.sh scripts/ci/report-java-readability-inventory.py` 生成。
> 本报告只列候选，不把行数、Map 或 suppression 数量直接判定为缺陷。

## 汇总

| 指标 | 数量 |
|---|---:|
| 生产 Java 源文件 | 3124 |
| CGLIB 自注入类 | 0 |
| `Map<String, Object>` 出现次数 | 2111 |
| 含 Map 的源文件 | 418 |
| public Map 契约候选 | 60 |
| public Map 契约候选文件 | 44 |
| `@SuppressWarnings` | 237 |
| 含 suppression 的源文件 | 175 |
| `@Configuration` 类 | 53 |
| 大于等于 700 行的源文件 | 31 |
| `PMD.ExcessiveParameterList` 显式例外 | 29 |

## 模块源文件

| 模块 | 生产 Java 文件 |
|---|---:|
| `batch-common` | 367 |
| `batch-console-api` | 1261 |
| `batch-orchestrator` | 798 |
| `batch-trigger` | 135 |
| `batch-worker` | 458 |
| `sdk` | 96 |
| `security-scan` | 9 |

## CGLIB 自注入

| 文件 | 自注入类型 |
|---|---|

## 大类候选（大于等于 700 行）

| 文件 | 行数 |
|---|---:|
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 1593 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java` | 1447 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | 1297 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java` | 1276 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java` | 1226 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 1178 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java` | 1170 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleOrchestratorProxyService.java` | 1063 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | 1034 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplayPreviewService.java` | 976 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java` | 945 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java` | 936 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskAssignmentService.java` | 916 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java` | 906 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 888 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java` | 872 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | 855 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java` | 844 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleOpsQueryService.java` | 825 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java` | 797 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationService.java` | 775 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java` | 771 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java` | 769 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/query/ConsoleFileQueryService.java` | 760 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java` | 760 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java` | 759 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java` | 758 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java` | 739 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/DefaultWorkflowDefinitionService.java` | 723 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 723 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/observability/DefaultConsoleQueryApplicationService.java` | 711 |

## Public Map 契约候选

> 此处只做词法候选。插件参数、metadata、JSONB、动态聚合和外部扩展字段应保留 Map。

| 文件 | 候选方法 |
|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchKafkaProducerSupport.java` | `L35: public static Map<String, Object> stringProducerConfig` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/context/RunModeSupport.java` | `L18: public static Map<String, Object> copyWithDefault` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/diagnostics/BatchRuntimeStatusEndpoint.java` | `L51: public Map<String, Object> status` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/page/CursorCodec.java` | `L46: public static Map<String, Object> decode` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/JsonUtils.java` | `L91: public static Map<String, Object> toMap` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java` | `L58: public static Map<String, Object> maskSensitiveKeys`<br>`L64: public static Map<String, Object> maskSensitiveKeys` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SensitiveEvidenceMasking.java` | `L26: public static Map<String, Object> maskMetadata` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileChannelConfigQueryView.java` | `L25: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileTemplateConfigQueryView.java` | `L60: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/view/ConsoleJobConfigViews.java` | `L41: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java` | `L523: public Map<String, Object> parsePayload` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/view/ResourceQueueRow.java` | `L43: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java` | `L132: public static Map<String, Object> toExportRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java` | `L18: public static Map<String, Object> mapOf` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java` | `L54: public Map<String, Object> effectiveParams` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java` | `L290: public Map<String, Object> nodeOutput`<br>`L295: public Map<String, Object> workflowRunFields`<br>`L324: public Map<String, Object> nodeOutput`<br>`L329: public Map<String, Object> workflowRunFields`<br>`L352: public static Map<String, Object> parsePayloadMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java` | `L561: public Map<String, Object> dispatchSecretBindings` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java` | `L98: public Map<String, Object> toCacheValues`<br>`L118: public Map<String, Object> toCacheValues` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/SensorFileArrivalView.java` | `L29: public Map<String, Object> toProbeOutput` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java` | `L37: public Map<String, Object> getNullableResult`<br>`L42: public Map<String, Object> getNullableResult`<br>`L47: public Map<String, Object> getNullableResult` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java` | `L36: public Map<String, Object> pin`<br>`L151: public Map<String, Object> pinWorkflowReplay` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchSecretSnapshotResolver.java` | `L29: public Map<String, Object> pin` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java` | `L41: public Map<String, Object> pin`<br>`L89: public Map<String, Object> pinWorkflowReplay` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java` | `L33: public Map<String, Object> loadFileRecord`<br>`L49: public Map<String, Object> loadFileRecordByStoragePath` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileTemplateRepository.java` | `L17: public Map<String, Object> loadLatestTemplateConfig` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformPipelineRunRepository.java` | `L140: public Map<String, Object> loadLatestSucceededStepOutputSummary` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRecordView.java` | `L40: public Map<String, Object> toPluginValues` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileTemplateConfigView.java` | `L61: public Map<String, Object> toPluginValues` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java` | `L31: public Map<String, Object> resolve` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java` | `L105: public static Map<String, Object> merge` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java` | `L35: public Map<String, Object> loadFile`<br>`L43: public Map<String, Object> loadFile`<br>`L51: public Map<String, Object> loadChannel` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/mapper/DispatchChannelConfigView.java` | `L25: public Map<String, Object> toPluginValues` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java` | `L18: public static Map<String, Object> toMap` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java` | `L84: public Map<String, Object> loadBatch` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java` | `L90: public Map<String, Object> loadBatch` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java` | `L20: public Map<String, Object> toMap`<br>`L42: public Map<String, Object> firstMap`<br>`L52: public Map<String, Object> payloadToMap` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationRuleSetMerger.java` | `L31: public Map<String, Object> merge` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java` | `L61: public Map<String, Object> parseHints`<br>`L75: public Map<String, Object> readJsonObject` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | `L631: public Map<String, Object> progressSnapshot` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java` | `L63: public Map<String, Object> toOutput` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java` | `L71: public Map<String, Object> toOutputMap` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/ProgressReporter.java` | `L29: public Map<String, Object> latest` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskStoppedException.java` | `L24: public Map<String, Object> breakPosition` |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java` | `L230: public List<Map<String, Object>> registrations` |

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
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/ResultVersionEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/WorkerRegistryEntity.java` | 3 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/JobDefinitionView.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/JobMonitoringAlertCandidate.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java` | 1 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicWorkerLoop.java` | 1 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 1 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java` | 2 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatchMessage.java` | 1 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java` | 4 |
