# Java 可读性治理扫描快照

> 由 `python3 scripts/ci/report-java-readability-inventory.py` 生成。
> 本报告只列候选，不把行数、Map 或 suppression 数量直接判定为缺陷。

## 汇总

| 指标 | 数量 |
|---|---:|
| 生产 Java 源文件 | 2213 |
| CGLIB 自注入类 | 0 |
| `Map<String, Object>` 出现次数 | 2045 |
| 含 Map 的源文件 | 442 |
| public Map 契约候选 | 65 |
| public Map 契约候选文件 | 34 |
| `@SuppressWarnings` | 211 |
| 含 suppression 的源文件 | 161 |
| `@Configuration` 类 | 47 |
| 大于等于 700 行的源文件 | 8 |
| `PMD.ExcessiveParameterList` 显式例外 | 26 |

## 模块源文件

| 模块 | 生产 Java 文件 |
|---|---:|
| `batch-common` | 293 |
| `batch-console-api` | 868 |
| `batch-orchestrator` | 519 |
| `batch-trigger` | 65 |
| `batch-worker` | 369 |
| `sdk` | 90 |
| `security-scan` | 9 |

## CGLIB 自注入

| 文件 | 自注入类型 |
|---|---|

## 大类候选（大于等于 700 行）

| 文件 | 行数 |
|---|---:|
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | 1106 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java` | 1070 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java` | 775 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 765 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 740 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidator.java` | 715 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java` | 711 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 709 |

## Public Map 契约候选

> 此处只做词法候选。插件参数、metadata、JSONB、动态聚合和外部扩展字段应保留 Map。

| 文件 | 候选方法 |
|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchKafkaProducerSupport.java` | `L36: public static Map<String, Object> stringProducerConfig` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/context/RunModeSupport.java` | `L17: public static Map<String, Object> copyWithDefault` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/diagnostics/BatchRuntimeStatusEndpoint.java` | `L46: public Map<String, Object> status` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/page/CursorCodec.java` | `L45: public static Map<String, Object> decode` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/JsonUtils.java` | `L91: public static Map<String, Object> toMap` |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java` | `L57: public static Map<String, Object> maskSensitiveKeys` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java` | `L225: public Map<String, Object> parsePayload` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java` | `L126: public static Map<String, Object> toExportRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/query/ConsoleQuerySupport.java` | `L74: public static Map<String, Object> requireRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ConfigChangeLogBuilder.java` | `L94: public Map<String, Object> build` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java` | `L18: public static Map<String, Object> mapOf` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleResponseFieldReader.java` | `L118: public static List<Map<String, Object>> mapList`<br>`L123: public static List<Map<String, Object>> mapList`<br>`L128: public static Map<String, Object> asMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/lineage/LineageEvidenceService.java` | `L31: public Map<String, Object> evidenceForResultVersion`<br>`L44: public Map<String, Object> evidenceForEffective` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java` | `L51: public Map<String, Object> effectiveParams` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java` | `L268: public Map<String, Object> nodeOutput`<br>`L273: public Map<String, Object> workflowRunFields`<br>`L302: public Map<String, Object> nodeOutput`<br>`L307: public Map<String, Object> workflowRunFields`<br>`L330: public static Map<String, Object> parsePayloadMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java` | `L70: public Map<String, Object> loadFileRecord`<br>`L79: public Map<String, Object> loadTemplateSecurityForFile`<br>`L124: public Map<String, Object> loadLatestDispatchRecord`<br>`L193: public List<Map<String, Object>> selectArrivalGovernanceCandidates`<br>`L200: public List<Map<String, Object>> selectArrivalGroupSummaries`<br>`L206: public List<Map<String, Object>> selectArrivalGroupFiles`<br>`L210: public List<Map<String, Object>> selectArrivalGroupFiles`<br>`L238: public List<Map<String, Object>> selectArrivalDelaySamples`<br>`L273: public List<Map<String, Object>> selectProcessingDelaySamples`<br>`L472: public Map<String, Object> operationDetail` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java` | `L36: public Map<String, Object> getNullableResult`<br>`L42: public Map<String, Object> getNullableResult`<br>`L47: public Map<String, Object> getNullableResult` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/FileGovernanceMetricsCacheService.java` | `L33: public Map<String, Object> load`<br>`L57: public Map<String, Object> compute` |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRuntimeRepository.java` | `L32: public Map<String, Object> loadFileRecord`<br>`L41: public Map<String, Object> loadFileRecordByStoragePath`<br>`L46: public Map<String, Object> loadLatestTemplateConfig`<br>`L51: public Map<String, Object> loadChannelConfig`<br>`L104: public Map<String, Object> loadLatestSucceededStepOutputSummary`<br>`L155: public List<Map<String, Object>> loadFileErrorRecords` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java` | `L103: public static Map<String, Object> merge` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java` | `L29: public Map<String, Object> loadFile`<br>`L36: public Map<String, Object> loadFile`<br>`L45: public Map<String, Object> loadChannel`<br>`L54: public Map<String, Object> loadLatestDispatchRecord`<br>`L185: public List<Map<String, Object>> listPendingReceiptPolls` |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthRepository.java` | `L30: public List<Map<String, Object>> findEnabledProbeChannels` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java` | `L18: public static Map<String, Object> toMap` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java` | `L57: public Map<String, Object> loadBatch` |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java` | `L79: public Map<String, Object> loadBatch` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java` | `L21: public Map<String, Object> toMap`<br>`L41: public Map<String, Object> firstMap`<br>`L49: public Map<String, Object> payloadToMap` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationRuleSetMerger.java` | `L31: public Map<String, Object> merge` |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java` | `L61: public Map<String, Object> parseHints`<br>`L75: public Map<String, Object> readJsonObject` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | `L449: public Map<String, Object> progressSnapshot` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java` | `L63: public Map<String, Object> toOutput` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java` | `L71: public Map<String, Object> toOutputMap` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/ProgressReporter.java` | `L29: public Map<String, Object> latest` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskStoppedException.java` | `L24: public Map<String, Object> breakPosition` |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java` | `L192: public List<Map<String, Object>> registrations` |

## 宽参数显式例外

| 文件 | 例外数 |
|---|---:|
| `batch-common/src/main/java/io/github/pinpols/batch/common/dto/LaunchRequest.java` | 2 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/kafka/TaskDispatchMessage.java` | 1 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/mapper/OperationAuditMapper.java` | 2 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/view/dashboard/ExecutionProgressView.java` | 1 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/mapper/ConsoleApiKeyMapper.java` | 1 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/command/CompensationSubmitCommand.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/BatchDayInstanceEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/BusinessCalendarEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/JobDefinitionEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/ResultVersionEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/WorkerRegistryEntity.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/mapper/BatchDayReplayEntryMapper.java` | 1 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/mapper/BatchDayReplaySessionMapper.java` | 1 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 1 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java` | 2 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatchMessage.java` | 1 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java` | 4 |
