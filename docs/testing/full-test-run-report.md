# 全量测试运行报告

生成时间：`2026-03-28 17:30:29 CST`

## 范围

本报告记录一次仓库级全量测试执行，包含：

- Reactor 默认测试：`mvn -fae -Dmaven.test.failure.ignore=true test`
- 显式集成 / E2E 套件：`mvn -fae -Dmaven.test.failure.ignore=true test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`

说明：

- 使用 `-fae` 与 `maven.test.failure.ignore=true` 以便收集完整失败列表。
- Maven 输出为 `BUILD SUCCESS`，但下列测试失败为真实失败。
- 临时日志写入：
  - `/tmp/file-batch-default-tests-20260328171219.log`
  - `/tmp/file-batch-it-suite-20260328172119.log`

## 摘要

- 失败测试类：`21`
- 失败测试方法：`45`
- 默认测试失败类：`7`
- 默认测试失败方法：`25`
- `*IT` 失败类：`14`
- `*IT` 失败方法：`20`

## 失败列表

### 默认测试失败

1. `batch-orchestrator`
   - 类：`com.example.batch.orchestrator.integration.QuotaResetSchedulerIntegrationTest`
   - 报告：`batch-orchestrator/target/surefire-reports/TEST-com.example.batch.orchestrator.integration.QuotaResetSchedulerIntegrationTest.xml`
   - 失败方法数：`1`
   - 现象：`expected: 0 but was: 7`
   - 相关断言：`QuotaResetSchedulerIntegrationTest.schedulerReconcileResetsExpiredSlidingWindowState`

2. `batch-worker-import`
   - 类：`com.example.batch.worker.imports.integration.ImportIngressScannerIntegrationTest`
   - 报告：`batch-worker-import/target/surefire-reports/TEST-com.example.batch.worker.imports.integration.ImportIngressScannerIntegrationTest.xml`
   - 失败方法数：`2`
   - 现象：
     - `shouldRegisterDiscoveredFileInPlatformDb`
     - `shouldNotRegisterAlreadyKnownFile`
   - 直接失败：`existsFileRecordByStoragePath(...)` 返回 `false`

3. `batch-worker-dispatch`
   - 类：`com.example.batch.worker.dispatchs.integration.DispatchChannelHealthServiceIntegrationTest`
   - 报告：`batch-worker-dispatch/target/surefire-reports/TEST-com.example.batch.worker.dispatchs.integration.DispatchChannelHealthServiceIntegrationTest.xml`
   - 失败方法数：`6`
   - 根因错误：`ERROR: relation "batch.file_channel_health" does not exist`

4. `batch-console-api`
   - 类：`com.example.batch.console.integration.AlertEventIntegrationTest`
   - 报告：`batch-console-api/target/surefire-reports/TEST-com.example.batch.console.integration.AlertEventIntegrationTest.xml`
   - 失败方法数：`5`
   - 根因错误：`ERROR: relation "batch.alert_event" does not exist`

5. `batch-console-api`
   - 类：`com.example.batch.console.integration.ConsoleAiAuditServiceIntegrationTest`
   - 报告：`batch-console-api/target/surefire-reports/TEST-com.example.batch.console.integration.ConsoleAiAuditServiceIntegrationTest.xml`
   - 失败方法数：`4`
   - 根因错误：`ERROR: relation "batch.console_ai_audit_log" does not exist`

6. `batch-console-api`
   - 类：`com.example.batch.console.integration.ConsoleRetryScheduleQueryIntegrationTest`
   - 报告：`batch-console-api/target/surefire-reports/TEST-com.example.batch.console.integration.ConsoleRetryScheduleQueryIntegrationTest.xml`
   - 失败方法数：`3`
   - 根因错误：PostgreSQL 驱动无法为 `java.time.Instant` 推断 SQL 类型

7. `batch-console-api`
   - 类：`com.example.batch.console.integration.JobInstanceQueryIntegrationTest`
   - 报告：`batch-console-api/target/surefire-reports/TEST-com.example.batch.console.integration.JobInstanceQueryIntegrationTest.xml`
   - 失败方法数：`4`
   - 根因错误：`column "biz_date" is of type date but expression is of type character varying`

### `*IT` 失败

本次运行中，非 E2E 的 `*IT` 类全部通过。

其余失败集中在 `batch-e2e-tests`：

1. 导入侧 E2E 上下文失败
   - `com.example.batch.e2e.DedupJobLaunchE2eIT`（`2`）
   - `com.example.batch.e2e.ImportFailureE2eIT`（`3`）
   - `com.example.batch.e2e.ImportFailurePipelineE2eIT`（`1`）
   - `com.example.batch.e2e.ImportPipelineE2eIT`（`1`）
   - `com.example.batch.e2e.MultiTenantConcurrentE2eIT`（`3`）
   - `com.example.batch.e2e.OutboxForwarderE2eIT`（`1`）
   - `com.example.batch.e2e.OutboxForwarderRetryE2eIT`（`2`）
   - `com.example.batch.e2e.WorkerDrainE2eIT`（`1`）

2. 导出侧 E2E 上下文失败
   - `com.example.batch.e2e.ExportContentVerificationE2eIT`（`1`）
   - `com.example.batch.e2e.ExportFailurePipelineE2eIT`（`1`）
   - `com.example.batch.e2e.ExportPipelineE2eIT`（`1`）
   - `com.example.batch.e2e.ExportStorageFailureE2eIT`（`1`）

3. 分发侧 E2E 上下文失败
   - `com.example.batch.e2e.DispatchFailurePipelineE2eIT`（`1`）
   - `com.example.batch.e2e.DispatchPipelineE2eIT`（`1`）

## 根因归类

### 1. 行为类失败

#### 配额重置行为与测试预期不一致

- 模块：`batch-orchestrator`
- 失败：
  - `QuotaResetSchedulerIntegrationTest.schedulerReconcileResetsExpiredSlidingWindowState`
- 直接现象：
  - 调用 `quotaRuntimeResetScheduler.reconcile()` 后，`peakBorrowedCount` 仍为 `7`
- 相关测试位置：
  - `batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/QuotaResetSchedulerIntegrationTest.java:55-67`

#### 导入入口扫描未创建平台文件记录

- 模块：`batch-worker-import`
- 失败：
  - `shouldRegisterDiscoveredFileInPlatformDb`
  - `shouldNotRegisterAlreadyKnownFile`
- 直接现象：
  - `runtimeRepository.existsFileRecordByStoragePath(...)` 始终为 `false`
- 相关测试位置：
  - `batch-worker-import/src/test/java/com/example/batch/worker/imports/integration/ImportIngressScannerIntegrationTest.java:73-115`

### 2. 测试库表结构落后于生产/测试预期

`AbstractIntegrationTest` 使用的测试启动库表缺少若干较新的表。

观测到的缺失表：

- `batch.file_channel_health`
- `batch.alert_event`
- `batch.console_ai_audit_log`

受影响类：

- `DispatchChannelHealthServiceIntegrationTest`
- `AlertEventIntegrationTest`
- `ConsoleAiAuditServiceIntegrationTest`

可能的修复方向：

- 在 `AbstractIntegrationTest` 共用的测试启动 DDL 路径中补全缺失表
- 或让这些模块完全切到与 `db/migration-integration` 相同的、由 Flyway 驱动的测试库表

### 3. 测试插入 SQL 的 JDBC / SQL 类型不当

#### `ConsoleRetryScheduleQueryIntegrationTest`

- 根因：
  - `JdbcTemplate.update(...)` 直接传入 `Instant.now().plusSeconds(60)`
  - 在该调用路径下 PostgreSQL 驱动无法为该参数推断 SQL 类型
- 相关源码：
  - `batch-console-api/src/test/java/com/example/batch/console/integration/ConsoleRetryScheduleQueryIntegrationTest.java`

#### `JobInstanceQueryIntegrationTest`

- 根因：
  - 测试将 `LocalDate.now().toString()` 插入 `DATE` 列
- 相关源码：
  - `batch-console-api/src/test/java/com/example/batch/console/integration/JobInstanceQueryIntegrationTest.java`

### 4. E2E ApplicationContext Bean 名称冲突

E2E 的主要根因是 Spring Bean 名称冲突：

- `com.example.batch.orchestrator.config.ShedLockConfiguration`
- `com.example.batch.worker.imports.config.ShedLockConfiguration`
- `com.example.batch.worker.exports.config.ShedLockConfiguration`
- `com.example.batch.worker.dispatchs.config.ShedLockConfiguration`

上述四个类均为普通 `@Configuration`，默认 Bean 名相同：`shedLockConfiguration`。

观测到的冲突示例：

- 导入 E2E：
  - `ConflictingBeanDefinitionException`
  - `batch.worker.imports.config.ShedLockConfiguration` 与 `batch.orchestrator.config.ShedLockConfiguration`
- 导出 E2E：
  - `batch.worker.exports.config.ShedLockConfiguration` 与 `batch.orchestrator.config.ShedLockConfiguration`
- 分发 E2E：
  - `batch.worker.dispatchs.config.ShedLockConfiguration` 与 `batch.orchestrator.config.ShedLockConfiguration`

相关源文件：

- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/config/ShedLockConfiguration.java`
- `batch-worker-import/src/main/java/com/example/batch/worker/imports/config/ShedLockConfiguration.java`
- `batch-worker-export/src/main/java/com/example/batch/worker/exports/config/ShedLockConfiguration.java`
- `batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/config/ShedLockConfiguration.java`

## 建议修复顺序

1. 补齐共用测试库表
   - 在共用测试启动路径中增加缺失表
   - 回归：
     - `DispatchChannelHealthServiceIntegrationTest`
     - `AlertEventIntegrationTest`
     - `ConsoleAiAuditServiceIntegrationTest`

2. 修正 Console 测试中的插入数据类型
   - `ConsoleRetryScheduleQueryIntegrationTest`
   - `JobInstanceQueryIntegrationTest`

3. 解决 E2E 中 `ShedLockConfiguration` Bean 名冲突
   - 有望一次性消除 `14` 个失败 E2E 类与 `20` 个失败方法

4. 处理剩余真实行为回归
   - `QuotaResetSchedulerIntegrationTest`
   - `ImportIngressScannerIntegrationTest`

## 原始失败清单

```text
失败类: 21
失败方法: 45

默认测试失败:
- batch-orchestrator: QuotaResetSchedulerIntegrationTest (1)
- batch-worker-import: ImportIngressScannerIntegrationTest (2)
- batch-worker-dispatch: DispatchChannelHealthServiceIntegrationTest (6)
- batch-console-api: AlertEventIntegrationTest (5)
- batch-console-api: ConsoleAiAuditServiceIntegrationTest (4)
- batch-console-api: ConsoleRetryScheduleQueryIntegrationTest (3)
- batch-console-api: JobInstanceQueryIntegrationTest (4)

IT / E2E 失败:
- batch-e2e-tests: DedupJobLaunchE2eIT (2)
- batch-e2e-tests: DispatchFailurePipelineE2eIT (1)
- batch-e2e-tests: DispatchPipelineE2eIT (1)
- batch-e2e-tests: ExportContentVerificationE2eIT (1)
- batch-e2e-tests: ExportFailurePipelineE2eIT (1)
- batch-e2e-tests: ExportPipelineE2eIT (1)
- batch-e2e-tests: ExportStorageFailureE2eIT (1)
- batch-e2e-tests: ImportFailureE2eIT (3)
- batch-e2e-tests: ImportFailurePipelineE2eIT (1)
- batch-e2e-tests: ImportPipelineE2eIT (1)
- batch-e2e-tests: MultiTenantConcurrentE2eIT (3)
- batch-e2e-tests: OutboxForwarderE2eIT (1)
- batch-e2e-tests: OutboxForwarderRetryE2eIT (2)
- batch-e2e-tests: WorkerDrainE2eIT (1)
```
