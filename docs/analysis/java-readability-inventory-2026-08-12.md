# Java 可读性治理扫描快照

> 由 `python3 scripts/ci/report-java-readability-inventory.py` 生成。
> 本报告只列候选，不把行数、Map 或 suppression 数量直接判定为缺陷。

## 汇总

| 指标 | 数量 |
|---|---:|
| 生产 Java 源文件 | 2162 |
| CGLIB 自注入类 | 12 |
| `Map<String, Object>` 出现次数 | 2236 |
| 含 Map 的源文件 | 463 |
| public Map 契约候选 | 149 |
| public Map 契约候选文件 | 57 |
| `@SuppressWarnings` | 215 |
| 含 suppression 的源文件 | 162 |
| `@Configuration` 类 | 47 |
| 大于等于 700 行的源文件 | 13 |
| `PMD.ExcessiveParameterList` 显式例外 | 26 |

## 模块源文件

| 模块 | 生产 Java 文件 |
|---|---:|
| `batch-common` | 293 |
| `batch-console-api` | 856 |
| `batch-orchestrator` | 494 |
| `batch-trigger` | 59 |
| `batch-worker` | 363 |
| `sdk` | 88 |
| `security-scan` | 9 |

## CGLIB 自注入

| 文件 | 自注入类型 |
|---|---|
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java` | `DefaultConsoleTenantConfigInitApplicationService` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitApplyHandlers.java` | `TenantConfigInitApplyHandlers` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/TaskDispatchOutboxService.java` | `TaskDispatchOutboxService` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java` | `DefaultCompensationService` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskOutcomeService.java` | `DefaultTaskOutcomeService` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskControllerApplicationService.java` | `TaskControllerApplicationService` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java` | `DefaultWorkflowNodeDispatchService` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java` | `ApiKeyVerifier` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaRuntimeStateSnapshotScheduler.java` | `QuotaRuntimeStateSnapshotScheduler` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayReplayDispatcher.java` | `BatchDayReplayDispatcher` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/SensorPollScheduler.java` | `SensorPollScheduler` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java` | `DefaultWorkerRegistryService` |

## 大类候选（大于等于 700 行）

| 文件 | 行数 |
|---|---:|
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java` | 1074 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java` | 1042 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskOutcomeService.java` | 921 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | 903 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 832 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java` | 811 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractTaskConsumer.java` | 810 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 765 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/workflow/DefaultConsoleWorkflowDefinitionApplicationService.java` | 755 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java` | 738 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java` | 734 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidator.java` | 715 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java` | 711 |

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
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/observability/DefaultConsoleQueryApplicationService.java` | `L506: public Map<String, Object> fileChannelDetail`<br>`L511: public Map<String, Object> fileTemplateDetail`<br>`L517: public Map<String, Object> fileRecordDetail`<br>`L533: public List<Map<String, Object>> jobDefinitionCodes`<br>`L539: public List<Map<String, Object>> pipelineDefinitionCodes` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java` | `L47: public Map<String, Object> diagnose`<br>`L65: public Map<String, Object> shedLockStatus`<br>`L94: public Map<String, Object> workerConsistency`<br>`L137: public Map<String, Object> outboxHealth`<br>`L173: public Map<String, Object> terminalChildrenHealth`<br>`L191: public Map<String, Object> instanceDiagnosis` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileApplicationService.java` | `L176: public Map<String, Object> presignUpload` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java` | `L50: public Map<String, Object> get`<br>`L56: public Map<String, Object> create`<br>`L89: public Map<String, Object> update` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileTemplateApplicationService.java` | `L77: public Map<String, Object> get`<br>`L83: public Map<String, Object> create`<br>`L100: public Map<String, Object> update` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/query/ConsoleFileQueryService.java` | `L311: public Map<String, Object> fileChannelDetail`<br>`L318: public Map<String, Object> fileTemplateDetail`<br>`L327: public Map<String, Object> fileRecordDetail` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleNotificationApplicationService.java` | `L102: public List<Map<String, Object>> listChannels`<br>`L107: public Map<String, Object> getChannel`<br>`L209: public List<Map<String, Object>> listRules`<br>`L214: public Map<String, Object> getRule`<br>`L291: public List<Map<String, Object>> deliveryLogs`<br>`L305: public Map<String, Object> testChannel` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java` | `L225: public Map<String, Object> parsePayload` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/DefaultConsoleOrchestratorProxyService.java` | `L45: public Map<String, Object> instanceAction`<br>`L59: public Map<String, Object> partitionAction`<br>`L77: public Map<String, Object> retryFailedPartitions`<br>`L94: public Map<String, Object> workflowRunAction`<br>`L110: public Map<String, Object> workflowRunSkipNode`<br>`L255: public Map<String, Object> batchDayOperate`<br>`L285: public Map<String, Object> requestForensicExport` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/service/ConsoleKafkaLagQueryService.java` | `L39: public List<Map<String, Object>> consumerGroupLags` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleAlertRoutingApplicationService.java` | `L48: public Map<String, Object> create`<br>`L64: public Map<String, Object> update` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApprovalApplicationService.java` | `L68: public Map<String, Object> submit`<br>`L118: public Map<String, Object> detail`<br>`L134: public Map<String, Object> approve`<br>`L179: public Map<String, Object> reject` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java` | `L126: public static Map<String, Object> toExportRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/query/ConsoleQuerySupport.java` | `L74: public static Map<String, Object> requireRow` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ConfigChangeLogBuilder.java` | `L94: public Map<String, Object> build` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java` | `L18: public static Map<String, Object> mapOf` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleResponseFieldReader.java` | `L118: public static List<Map<String, Object>> mapList`<br>`L123: public static List<Map<String, Object>> mapList`<br>`L128: public static Map<String, Object> asMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java` | `L185: public Map<String, Object> createUploadSession` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/lineage/LineageEvidenceService.java` | `L31: public Map<String, Object> evidenceForResultVersion`<br>`L44: public Map<String, Object> evidenceForEffective` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/InstanceManagementApplicationService.java` | `L48: public Map<String, Object> cancel`<br>`L66: public Map<String, Object> terminate`<br>`L71: public Map<String, Object> pause`<br>`L76: public Map<String, Object> resume`<br>`L103: public Map<String, Object> cancelPartition`<br>`L119: public Map<String, Object> retryPartition`<br>`L131: public Map<String, Object> retryFailedPartitions` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java` | `L51: public Map<String, Object> effectiveParams` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java` | `L268: public Map<String, Object> nodeOutput`<br>`L273: public Map<String, Object> workflowRunFields`<br>`L302: public Map<String, Object> nodeOutput`<br>`L307: public Map<String, Object> workflowRunFields`<br>`L330: public static Map<String, Object> parsePayloadMap` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunManagementApplicationService.java` | `L72: public Map<String, Object> cancel`<br>`L82: public Map<String, Object> terminate`<br>`L95: public Map<String, Object> pause`<br>`L101: public Map<String, Object> resume`<br>`L134: public Map<String, Object> skipNode`<br>`L139: public Map<String, Object> skipNode` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/controller/FileGovernanceController.java` | `L52: public Map<String, Object> presignUpload`<br>`L94: public Map<String, Object> latencyMetrics` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/controller/InstanceManagementController.java` | `L21: public Map<String, Object> cancel`<br>`L27: public Map<String, Object> terminate`<br>`L33: public Map<String, Object> pause`<br>`L39: public Map<String, Object> resume`<br>`L45: public Map<String, Object> cancelPartition`<br>`L51: public Map<String, Object> retryPartition`<br>`L57: public Map<String, Object> retryFailedPartitions` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/controller/OrchestratorDrainController.java` | `L24: public Map<String, Object> status`<br>`L29: public Map<String, Object> enable`<br>`L35: public Map<String, Object> disable` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/controller/WorkflowRunManagementController.java` | `L21: public Map<String, Object> cancel`<br>`L27: public Map<String, Object> terminate`<br>`L33: public Map<String, Object> pause`<br>`L39: public Map<String, Object> resume`<br>`L45: public Map<String, Object> skipNode` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/OrchestratorGracefulShutdown.java` | `L91: public Map<String, Object> status` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java` | `L70: public Map<String, Object> loadFileRecord`<br>`L79: public Map<String, Object> loadTemplateSecurityForFile`<br>`L124: public Map<String, Object> loadLatestDispatchRecord`<br>`L155: public List<Map<String, Object>> selectArchivedFilesForCleanup`<br>`L166: public List<Map<String, Object>> selectOrphanUploadSessions`<br>`L183: public List<Map<String, Object>> selectArrivalGovernanceCandidates`<br>`L190: public List<Map<String, Object>> selectArrivalGroupSummaries`<br>`L196: public List<Map<String, Object>> selectArrivalGroupFiles`<br>`L200: public List<Map<String, Object>> selectArrivalGroupFiles`<br>`L228: public List<Map<String, Object>> selectArrivalDelaySamples`<br>`L263: public List<Map<String, Object>> selectProcessingDelaySamples`<br>`L462: public Map<String, Object> operationDetail` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` | `L164: public Map<String, Object> loadLatencyMetrics` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java` | `L36: public Map<String, Object> getNullableResult`<br>`L42: public Map<String, Object> getNullableResult`<br>`L47: public Map<String, Object> getNullableResult` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/FileGovernanceMetricsCacheService.java` | `L33: public Map<String, Object> load`<br>`L57: public Map<String, Object> compute` |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerGracefulShutdown.java` | `L134: public Map<String, Object> status` |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatus.java` | `L44: public Map<String, Object> asMap` |
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
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | `L454: public Map<String, Object> progressSnapshot` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java` | `L63: public Map<String, Object> toOutput` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java` | `L71: public Map<String, Object> toOutputMap` |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClient.java` | `L44: public Map<String, Object> register`<br>`L49: public Map<String, Object> heartbeat`<br>`L55: public Map<String, Object> deactivate`<br>`L61: public Map<String, Object> claim`<br>`L67: public Map<String, Object> report`<br>`L73: public Map<String, Object> renew` |
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
