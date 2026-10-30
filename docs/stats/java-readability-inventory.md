# Java 可读性治理当前机器清单

> 由 `bash scripts/python.sh scripts/ci/report-java-readability-inventory.py` 生成。
> 本报告只列候选，不把行数、Map 或 suppression 数量直接判定为缺陷。

## 汇总

| 指标 | 数量 |
|---|---:|
| 生产 Java 源文件 | 2861 |
| CGLIB 自注入类 | 0 |
| `Map<String, Object>` 出现次数 | 2353 |
| 含 Map 的源文件 | 461 |
| public Map 契约候选 | 76 |
| public Map 契约候选文件 | 49 |
| `@SuppressWarnings` | 244 |
| 含 suppression 的源文件 | 177 |
| `@Configuration` 类 | 50 |
| 大于等于 700 行的源文件 | 34 |
| `PMD.ExcessiveParameterList` 显式例外 | 29 |

## 模块源文件

| 模块 | 生产 Java 文件 |
|---|---:|
| `batch-common` | 359 |
| `batch-console-api` | 1122 |
| `batch-orchestrator` | 738 |
| `batch-trigger` | 119 |
| `batch-worker` | 420 |
| `sdk` | 94 |
| `security-scan` | 9 |

## CGLIB 自注入

| 文件 | 自注入类型 |
|---|---|

## 大类候选（大于等于 700 行）

| 文件 | 行数 |
|---|---:|
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java` | 1446 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java` | 1291 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/DefaultTriggerService.java` | 1278 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | 1244 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java` | 1227 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java` | 1168 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 1088 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | 1062 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitApplyHandlers.java` | 1062 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleOrchestratorProxyService.java` | 1045 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplayPreviewService.java` | 985 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java` | 974 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java` | 969 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 929 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java` | 917 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java` | 904 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationService.java` | 837 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java` | 833 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java` | 813 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | 813 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskAssignmentService.java` | 807 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java` | 791 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleOpsQueryService.java` | 786 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/DefaultWorkflowDefinitionService.java` | 763 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java` | 761 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java` | 758 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java` | 752 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java` | 746 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 732 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 729 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/observability/DefaultConsoleQueryApplicationService.java` | 726 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java` | 723 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java` | 709 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java` | 700 |

## Public Map 契约候选

> 此处只做词法候选。插件参数、metadata、JSONB、动态聚合和外部扩展字段应保留 Map。

| 文件 | 候选方法 |
|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchKafkaProducerSupport.java` | `L36: public static Map<String, Object> stringProducerConfig` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/context/RunModeSupport.java` | `L17: public static Map<String, Object> copyWithDefault` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/diagnostics/BatchRuntimeStatusEndpoint.java` | `L51: public Map<String, Object> status` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/page/CursorCodec.java` | `L46: public static Map<String, Object> decode` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/JsonUtils.java` | `L91: public static Map<String, Object> toMap` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java` | `L64: public static Map<String, Object> maskSensitiveKeys`<br>`L70: public static Map<String, Object> maskSensitiveKeys` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileChannelConfigQueryView.java` | `L26: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileTemplateConfigQueryView.java` | `L61: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/view/ConsoleJobConfigViews.java` | `L42: public Map<String, Object> toMap`<br>`L96: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java` | `L525: public Map<String, Object> parsePayload` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/view/ConfigApprovalRow.java` | `L29: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/view/ResourceQueueRow.java` | `L45: public Map<String, Object> toMap` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java` | `L140: public static Map<String, Object> toExportRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ConfigChangeLogBuilder.java` | `L94: public Map<String, Object> build` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java` | `L18: public static Map<String, Object> mapOf` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleResponseFieldReader.java` | `L152: public static List<Map<String, Object>> mapList`<br>`L157: public static List<Map<String, Object>> mapList`<br>`L162: public static Map<String, Object> asMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java` | `L55: public Map<String, Object> effectiveParams` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java` | `L295: public Map<String, Object> nodeOutput`<br>`L300: public Map<String, Object> workflowRunFields`<br>`L329: public Map<String, Object> nodeOutput`<br>`L334: public Map<String, Object> workflowRunFields`<br>`L358: public static Map<String, Object> parsePayloadMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java` | `L584: public Map<String, Object> dispatchSecretBindings` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java` | `L15: public Map<String, Object> toRepositoryValues`<br>`L33: public Map<String, Object> toRepositoryValues`<br>`L47: public Map<String, Object> toRepositoryValues`<br>`L128: public Map<String, Object> toCacheValues`<br>`L148: public Map<String, Object> toCacheValues` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/SensorFileArrivalView.java` | `L18: public Map<String, Object> toProbeOutput` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java` | `L79: public Map<String, Object> loadFileRecord`<br>`L88: public Map<String, Object> loadTemplateSecurityForFile`<br>`L134: public Map<String, Object> loadLatestDispatchRecord`<br>`L270: public List<Map<String, Object>> selectArrivalDelaySamples`<br>`L309: public List<Map<String, Object>> selectProcessingDelaySamples` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java` | `L37: public Map<String, Object> getNullableResult`<br>`L43: public Map<String, Object> getNullableResult`<br>`L48: public Map<String, Object> getNullableResult` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/FileGovernanceMetricsCacheService.java` | `L33: public Map<String, Object> load`<br>`L57: public Map<String, Object> compute` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java` | `L40: public Map<String, Object> pin`<br>`L109: public Map<String, Object> pinWorkflowReplay` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchSecretSnapshotResolver.java` | `L33: public Map<String, Object> pin` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java` | `L41: public Map<String, Object> pin`<br>`L90: public Map<String, Object> pinWorkflowReplay` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java` | `L35: public Map<String, Object> loadFileRecord`<br>`L54: public Map<String, Object> loadFileRecordByStoragePath` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileTemplateRepository.java` | `L19: public Map<String, Object> loadLatestTemplateConfig` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformPipelineRunRepository.java` | `L142: public Map<String, Object> loadLatestSucceededStepOutputSummary` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRecordView.java` | `L40: public Map<String, Object> toPluginValues` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileTemplateConfigView.java` | `L61: public Map<String, Object> toPluginValues` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java` | `L31: public Map<String, Object> resolve` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java` | `L106: public static Map<String, Object> merge` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java` | `L39: public Map<String, Object> loadFile`<br>`L47: public Map<String, Object> loadFile`<br>`L56: public Map<String, Object> loadChannel` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthRepository.java` | `L33: public List<Map<String, Object>> findEnabledProbeChannels` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/mapper/DispatchChannelConfigView.java` | `L25: public Map<String, Object> toPluginValues` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java` | `L19: public static Map<String, Object> toMap` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java` | `L83: public Map<String, Object> loadBatch` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java` | `L91: public Map<String, Object> loadBatch` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java` | `L20: public Map<String, Object> toMap`<br>`L42: public Map<String, Object> firstMap`<br>`L52: public Map<String, Object> payloadToMap` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationRuleSetMerger.java` | `L32: public Map<String, Object> merge` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java` | `L62: public Map<String, Object> parseHints`<br>`L76: public Map<String, Object> readJsonObject` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | `L596: public Map<String, Object> progressSnapshot` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java` | `L63: public Map<String, Object> toOutput` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java` | `L71: public Map<String, Object> toOutputMap` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/ProgressReporter.java` | `L29: public Map<String, Object> latest` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskStoppedException.java` | `L24: public Map<String, Object> breakPosition` |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java` | `L207: public List<Map<String, Object>> registrations` |

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
