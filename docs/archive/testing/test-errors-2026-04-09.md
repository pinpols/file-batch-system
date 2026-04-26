# 测试失败清单

更新时间：2026-04-11

---

## 已解决（2026-04-11）

### 1. 单元测试 — dispatch 阶段 `NoClassDefFoundError`

**影响测试：**
- `AckDispatchStepTest` / `RetryDispatchStepTest` / `PrepareDispatchStepTest`
- `CompensateDispatchStepTest` / `CompleteDispatchStepTest` / `DeliverDispatchStepTest`

**根因：** mvnd 将 `batch-worker-core` 从 `~/.m2` 解析为旧版 JAR，
导致 `StageExecutionResult`（新接口）和 `FileAuditParam$FileAuditParamBuilder`（Lombok 生成类）在运行时找不到。

**修复：** `run-tests.sh` 在 `clean test` 前新增 `install -DskipTests -pl batch-common,batch-orchestrator,batch-worker-core`，
确保 `~/.m2` 中的 `batch-worker-core` JAR 是最新版。

---

### 2. 单元测试 — `DefaultDispatchStageExecutorTest` Mockito 异常

**根因：** 同上，`batch-worker-core` 旧版 JAR 导致 `DispatchStageResult`（实现 `StageExecutionResult`）
在类型层次解析时失败，Mockito 无法为 `DispatchStageStep` 接口创建动态代理，
抛出 `MockitoException: Mockito cannot mock this class`。

**修复：** 同上，`batch-worker-core` 预装后联动解决。

---

### 3. 集成测试 — `batch-console-api` ApplicationContext 加载失败

**影响测试（全部 9 个）：**
- `AlertEventIntegrationTest` / `AlertEventActionIntegrationTest`
- `BatchConsoleApiApplicationIntegrationTest` / `ConsoleHttpIntegrationTest`
- `ApprovalCommandQueryIntegrationTest` / `ConsoleAiAuditServiceIntegrationTest`
- `ConsoleRetryScheduleQueryIntegrationTest` / `DeadLetterQueryIntegrationTest`
- `JobInstanceQueryIntegrationTest`

**根因：** `batch-console-api` 依赖 `batch-orchestrator`。mvnd 将 `batch-orchestrator`
从 `~/.m2` 解析为旧版 JAR，导致 `batch-console-api` 编译时缺少最新类
（`AlertEventMapper`、`BatchWindowUpsertParam`、`InMemoryAlertRoutingExcelImportStore` 等），
`target/classes` 不完整，测试运行时出现 `NoClassDefFoundError` / `ClassNotFoundException` 级联失败。

**修复：** 同上，新增 `batch-orchestrator` 到预装列表解决。

---

### 4. 集成测试 — `batch-worker-export` ApplicationContext 加载失败

**影响测试：**
- `BatchWorkerExportApplicationIntegrationTest`
- `MinioExportStorageIntegrationTest`

**根因：** `ExportStepExecutionAdapter` 继承 `AbstractPipelineStepExecutionAdapter`（`batch-worker-core` 新增类）。
旧版 `batch-worker-core` JAR 中该类不存在，Spring 在扫描配置类时抛出
`BeanDefinitionStoreException: FileNotFoundException`。

**修复：** 同上，`batch-worker-core` 预装后解决。

---

### 5. E2E 测试 — `InvalidUrlException: Bad authority`（全部 14 个测试类）

**根因：** `HttpWorkerRegistryClient.resolveBaseUrl()` 缺少对未解析占位符的处理：
当 `batch.orchestrator.base-url` 配置为 `http://127.0.0.1:${local.server.port}` 时，
`@ConfigurationProperties` 绑定发生在 `local.server.port` 被 Spring 写入之前，
导致返回含字面量 `${...}` 的 URL，Spring 6.2 的严格 `RfcUriParser` 拒绝该 URL 并抛出异常。

**修复：** 参照 `HttpTaskExecutionClient.resolveBaseUrl()` 的模式，
为 `HttpWorkerRegistryClient` 注入 `Environment`，并在 URL 含 `${` 时
回退到 `environment.getProperty("local.server.port")` 读取实际端口。

---

## 未解决（需跟进）

### 1. E2E — ConditionTimeout（4 个测试类）

| 测试类 | 现象 |
|--------|------|
| `ImportPipelineE2eIT` | 等待 job status=FINISHED 超时，实际为 FAILED |
| `MultiTenantConcurrentE2eIT` | 两个租户并发 job 未在超时内完成 |
| `OutboxForwarderE2eIT` | 等待 outbox status=PUBLISHED 超时，实际为 FAILED |
| `OutboxForwarderRetryE2eIT` | 等待 outbox status=PUBLISHED 超时，实际为 NEW |

**共同特征：** 全部是异步等待超时，说明链路中某个环节卡住，需深入查 E2E 业务侧错误日志。

---

## 历史记录（2026-04-06，已过时）

> 以下条目来自 2026-04-06 版本，相关代码已发生变化，以上列表为准。
> 保留仅供参考：OutboxPollSchedulerTest NPE（`outbox()` 返回 null）、
> KafkaOutboxPublisherTest CompletableFuture 未完成、
> batch-trigger 集成测试 JVM crash（Docker 未就绪）。
