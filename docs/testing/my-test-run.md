# 测试运行记录 

---

## 第一轮（初始全量跑）

### 执行命令
```
mvn test -T 1C
```

### 总体结果
- 运行测试类：147 个（仅覆盖 `*Test.java`，`*IT.java` 被 Surefire 默认排除）
- 总用例数：约 600+
- **失败：4 个（全在 batch-e2e-tests）**
- BUILD FAILURE

### 失败测试

| # | 模块 | 测试类 | 方法 | 现象 |
|---|------|--------|------|------|
| 1 | batch-e2e-tests | `ImportPipelineE2eIT` | `importJobRunsThroughKafkaClaimAndReportsSuccess` | ConditionTimeout：等待 job status=FINISHED，超时时实际为 FAILED |
| 2 | batch-e2e-tests | `MultiTenantConcurrentE2eIT` | `concurrentTenantJobsAreIsolatedAndBothSucceed` | ConditionTimeout：两个租户并发 job 未在超时内完成 |
| 3 | batch-e2e-tests | `OutboxForwarderE2eIT` | `outboxSchedulerAutomaticallyPublishesAndWorkerReportsSuccess` | ConditionTimeout：等待 outbox status=PUBLISHED，超时时实际为 FAILED |
| 4 | batch-e2e-tests | `OutboxForwarderRetryE2eIT` | `transientFailure_thenRecovery_eventIsPublishedEventually` | ConditionTimeout：等待 outbox status=PUBLISHED，超时时实际为 NEW |

**共同特征**：全部是 ConditionTimeout，说明异步链路中某个环节卡住，需要看 E2E 的具体业务错误。

---

## 第二轮（统一命名 + 补跑集成测试）

### 变更说明

1. **命名统一**：所有 `*IT.java` 改名为 `*IntegrationTest.java`（e2e 测试例外，保留 `*E2eIT.java`）
2. **删除 Surefire 额外 includes**：改名后默认 `**/*Test.java` 已能匹配，无需额外配置
3. **修复测试 bug**：
   - `JobLaunchToFinishLifecycleIntegrationTest`：`resultSummary` 传入裸字符串 `"processed ok"`，改为合法 JSON `{"status":"processed ok"}`
   - `WorkerClaimProgressCompleteIntegrationTest`：`"100 records processed"` → `{"records":100,"status":"processed"}`
4. **修复 `@Import` 引用**（改名后内部类路径未同步）：
   - `QuartzLaunchJobIntegrationTest`：`QuartzLaunchJobIT.TestConfig` → `QuartzLaunchJobIntegrationTest.TestConfig`
   - `TriggerRegistrationStartupIntegrationTest`：同上
   - `TriggerServiceIntegrationTest`：`TriggerServiceIntegrationIT.TestConfig` → `TriggerServiceIntegrationTest.TestConfig`
   - `ShedLockConfigurationIntegrationTest`（batch-worker-dispatch）：`ShedLockConfigurationIT.DispatchTestDataSourceConfiguration` → `ShedLockConfigurationIntegrationTest.DispatchTestDataSourceConfiguration`
5. **补充 trigger 测试配置**：`batch-trigger/src/test/resources/application-test.yml` 缺少 Flyway baseline 配置，导致 trigger 所有集成测试 ApplicationContext 加载失败，补加：
   ```yaml
   spring:
     flyway:
       locations: classpath:db/migration
       baseline-on-migrate: true
       baseline-version: 32
   ```

### 执行命令
```bash
mvn test -pl batch-orchestrator,batch-trigger,batch-console-api,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-worker-core \
  -am -Dtest="*IntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress
```

### 结果：全绿 BUILD SUCCESS

| 模块 | 测试数 | 结果 |
|------|--------|------|
| batch-common | 0 | — |
| batch-trigger | 9 | 全绿 |
| batch-orchestrator | 92 | 全绿 |
| batch-worker-import | 6 | 全绿 |
| batch-worker-export | 12 | 全绿 |
| batch-worker-dispatch | 10 | 全绿 |
| batch-console-api | 41 | 全绿 |
| **合计** | **170** | **全绿** |

---

## 当前测试覆盖状态

### 正常运行（随 `mvn test` 自动跑）

#### batch-common（73 用例，全绿）
- RunModeSupportTest / ShedLockProviderFactoryTest
- TaskStatusTest / RetryScheduleStatusTest / PartitionStatusTest / JobInstanceStatusTest / WorkerRegistryStatusTest / DeadLetterReplayStatusTest / RetryPolicyTypeTest
- JsonUtilsTest / FileStateMachineTest / IdGeneratorTest / AlertFingerprintsTest / ContentMaskingUtilsTest
- JdbcMappedSqlValidatorTest / PageRequestTest / BatchObjectCryptoServiceTest

#### batch-console-api（41 用例，全绿）
- ConsoleSecurityConfigurationTest
- DefaultConsoleFile/Template/JobDefinition/Ops/Report/WorkflowExcelApplicationServiceTest
- AlertEventActionIntegrationTest / AlertEventIntegrationTest / ApprovalCommandQueryIntegrationTest
- ApprovalWorkflowIntegrationTest / ConsoleAiAuditServiceIntegrationTest / ConsoleRetryScheduleQueryIntegrationTest
- DeadLetterQueryIntegrationTest / FileErrorRecordIntegrationTest / FileGovernanceIntegrationTest / JobInstanceQueryIntegrationTest
- BatchConsoleApiApplicationIntegrationTest / ConsoleHttpIntegrationTest / DispatchChannelHealthServiceIntegrationTest
- AiPromptGateResultTest / ConsoleAiPromptGuardTest / ConsoleRequestContextFilterTest / ConsoleSecurityHeadersWriterTest
- ConsoleAlertControllerTest / ConsoleApprovalControllerTest / ConsoleConfigControllerTest
- ConsoleFileChannelExcelControllerTest / ConsoleFileControllerTest / ConsoleFileTemplateExcelControllerTest
- ConsoleJobControllerTest / ConsoleJobDefinitionExcelControllerTest / ConsoleOpsControllerTest
- ConsoleQueryControllerTest / ConsoleReportExcelControllerTest / ConsoleWorkerControllerTest / ConsoleWorkflowExcelControllerTest

#### batch-e2e-tests（10 类通过，4 类失败）
- 通过：DedupJobLaunchE2eIT / DispatchFailurePipelineE2eIT / DispatchPipelineE2eIT
- 通过：ExportContentVerificationE2eIT / ExportFailurePipelineE2eIT / ExportPipelineE2eIT / ExportStorageFailureE2eIT
- 通过：ImportFailureE2eIT / ImportFailurePipelineE2eIT / WorkerDrainE2eIT
- **失败（未修复）**：ImportPipelineE2eIT / MultiTenantConcurrentE2eIT / OutboxForwarderE2eIT / OutboxForwarderRetryE2eIT

#### batch-orchestrator（92 用例，全绿）
- 单元测试（~20 类）：DefaultSchedulePlanBuilderTest / DefaultApprovalWorkflowServiceTest / DefaultCompensationServiceTest
  DefaultRetryGovernanceServiceTest / DefaultWorkerDrainGovernanceServiceTest / DefaultWorkflowDagServiceTest
  WorkflowConditionEvaluatorTest / PartitionLeaseReclaimSchedulerTest / KafkaOutboxPublisherTest
  DefaultPipelineExecutorTest / DefaultStepRegistryTest / SequentialStepOrderResolverTest
  DefaultWorkerRoutingPolicyTest / BatchDaySettleSchedulerTest / JobSlaSchedulerTest
  DefaultStateMachineTest / DefaultPrioritySchedulerTest / QuotaResetPolicyTest / QuotaRuntimeStateServiceTest
  DefaultLaunchServiceTest
- 集成测试（~17 类）：ApprovalWorkflowIntegrationTest / BatchDaySqlMigrationsIntegrationTest
  BatchOrchestratorApplicationStartupIntegrationTest / ConcurrentPartitionPromoteIntegrationTest
  ConcurrentTaskClaimIntegrationTest / ConcurrentTaskFinishIntegrationTest
  FileErrorRecordIntegrationTest / FileGovernanceIntegrationTest / JobLaunchToFinishLifecycleIntegrationTest
  JobNodeDispatchIntegrationTest / JobRetryFlowIntegrationTest / JobTypeOutboxChainIntegrationTest
  LocalFlywayPlatformMigrationsIntegrationTest / MultiTenantIsolationIntegrationTest
  OutboxEventToKafkaDispatchIntegrationTest / OutboxPublishIntegrationTest
  QuotaResetSchedulerIntegrationTest / QuotaRuntimeStateIntegrationTest / RetryScheduleIntegrationTest
  SchedulingDecisionLaunchIntegrationTest / ShedLockConfigurationIntegrationTest
  SqlConsistencyIntegrationTest / StartupSelfCheckIntegrationTest
  TriggerTypeLaunchIntegrationTest / WorkerClaimProgressCompleteIntegrationTest / WorkerRegistryIntegrationTest
- Web 层：ApprovalControllerTest / DeadLetterControllerTest / LaunchControllerTest / TaskControllerTest / WorkerControllerTest

#### batch-trigger（9 用例，全绿）
- 单元测试：HttpOrchestratorTriggerAdapterTest / QuartzLaunchJobTest / TriggerSchedulerFacadeTest
  BatchDayCutoffSchedulerTest / CalendarBizDateResolverTest / DefaultLaunchAdapterServiceTest
  DefaultTriggerServiceTest / TriggerControllerTest
- 集成测试：BatchTriggerApplicationIntegrationTest / MisfireHandlerIntegrationTest / QuartzLaunchJobIntegrationTest
  TriggerRegistrationStartupIntegrationTest / TriggerServiceIntegrationTest

#### batch-worker-core（9 用例，全绿）
- DefaultTaskExecutionWrapperTest / DefaultWorkerLifecycleManagerTest / HttpTaskExecutionClientTest
- PlatformFileRuntimeRepositoryTest / AbstractTaskConsumerBackpressureTest / AbstractWorkerLoopTest
- ActiveTaskLeaseRegistryTest / PipelineStepFlowSupportTest / StaleTempFileCleanupTest

#### batch-worker-dispatch（10 用例，全绿）
- ChannelConfigMergeTest / DispatchReceiptPollSchedulerTest / DispatchChannelCircuitBreakerTest
- DispatchChannelGatewayTest / DispatchChannelHealthServiceIntegrationTest / DefaultDispatchStageExecutorTest
- BatchWorkerDispatchApplicationIntegrationTest / ShedLockConfigurationIntegrationTest

#### batch-worker-export（12 用例，全绿）
- MinioExportStorageIntegrationTest / JdbcMappedExportSpecTest / ExportDataPluginRegistryTest
- SqlTemplateExportDataPluginTest / SqlTemplateExportSpecTest / SqlTemplateExportSqlValidatorTest
- DefaultExportStageExecutorTest / GenerateStepTest / PrepareStepTest / RegisterStepTest / StoreStepTest
- BatchWorkerExportApplicationIntegrationTest / ShedLockConfigurationIntegrationTest

#### batch-worker-import（6 用例，全绿）
- ImportIngressScannerIntegrationTest / JdbcMappedImportSpecTest / ImportLoadPluginRegistryTest
- ImportPreprocessPipelineRsaTest / ImportPreprocessPipelineTest / PreprocessStepKmsDecryptTest
- ImportIngressScannerTest / ParseStepFixtureTest / ParseStepStreamingTest
- BatchWorkerImportApplicationIntegrationTest / ShedLockConfigurationIntegrationTest

---

### 不在常规 mvn test 范围的类

#### 支撑类（非测试类，正常不运行）
```
E2eScenarioFixture, E2eTestSql, E2eVerifier, DispatchReceiptVerifier, ExportFileVerifier
AbstractIntegrationTest, BatchIntegrationTest, IntegrationTestInfrastructure
MinIOContainer, OrchestratorWireMockSupport, PlatformTestdataSql, TestExcelFileBuilder
E2eOutboxPublishSupport, LaunchIntegrationFixture
E2eDispatchApplication, E2eExportApplication, E2eImportApplication
E2eKafkaProducerConfiguration, E2eImportWorkerDataSourceConfiguration, ...
```

#### 独立框架（需单独运行）
```
load-tests:     GatlingConfig, CapacityBaselineSimulation, ConsoleQuerySimulation, JobLaunchSimulation
security-scan:  SecurityScanOptionsTest, SecurityScanOrchestratorTest
```

---

## 待办
- [ ] 修复 4 个 E2E ConditionTimeout 失败（ImportPipelineE2eIT / MultiTenantConcurrentE2eIT / OutboxForwarderE2eIT / OutboxForwarderRetryE2eIT）
