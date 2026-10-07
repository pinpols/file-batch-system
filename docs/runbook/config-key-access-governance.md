# 程序内配置 Key 读取治理

本文约束生产代码中通过字符串 key 读取配置的写法，包括直接读取和 `@Value` 注入，例如：

```java
environment.getProperty("batch.xxx")
environment.getRequiredProperty("spring.xxx")
System.getProperty("batch.xxx")
@Value("${batch.xxx:default}")
```

它不替代 [配置治理与生效契约](./config-governance.md)，而是补充说明：哪些字符串 key 读取场景应该收敛，哪些允许保留，以及后续 CI 如何逐步拦截。

## 1. 治理目标

目标不是消灭所有 `getProperty` 或 `@Value`，而是减少以下风险：

- 配置 key 分散在业务代码里，改名时无法统一发现；
- 默认值散落，文档、Helm、Compose 与代码口径不一致；
- 敏感配置通过字符串 key 多处读取，审计和轮换边界不清；
- 新增配置绕过 `@ConfigurationProperties` 登记表，CI 无法识别生命周期与敏感级别。

## 2. 适用范围

纳入治理：

- `src/main/java` 生产代码中的 `Environment#getProperty(...)`、`Environment#getRequiredProperty(...)`、`System#getProperty(...)`；
- `src/main/java` 生产代码中的项目自定义 `@Value("${batch.*}")`；
- 读取项目自定义配置的字符串 key，尤其是 `batch.*`；
- 读取敏感配置、生产守护配置、功能开关、容量阈值、调度间隔、批大小和后端类型选择。

暂不纳入治理：

- `src/test`、`batch-test-support`、`batch-e2e-tests`、`load-tests` 中用于测试或压测的 `-Dxxx` 参数；
- `java.io.tmpdir`、`user.home` 等 JVM 系统属性；
- `local.server.port` 这类 Spring Boot 运行期绑定结果（Worker 注册也使用该属性，不仅用于测试）；
- 框架桥接场景，例如仅用于诊断标签、连接池名或 actor 名的 `spring.application.name`；
- 需要读取 Spring 基础设施实际生效值的启动守护，例如 `spring.datasource.url`、`spring.data.redis.*`。这类可以后续封装 runtime inspector，但不作为第一批阻断项。

`src/test` 的口径（2026-10-06 明确）：

- **不拦截、不进基线**。测试本来就要用 `MockEnvironment#withProperty(...)`、
  `@SpringBootTest(properties = ...)` 设置 key，强行阻断只会逼出大量豁免；
- **只做可见性**。`check-direct-config-key-access.py --report` / `--json` 附带一份 TEST_SOURCE 快照，
  按读取方式汇总并逐条列出命中，让「测试里新硬编码了哪个 key」被看见；
- **有归属类就从归属类取常量**。精确读取点（`System.getProperty(...)`、`@Value("${...}")`、
  `withProperty("key", ...)`）若该 key 有明确归属类，一律引用其公开常量，不再在测试里重复字面量
  （清单见 §4.1「第三批」）；
- **`properties = {"key=value"}` 夹具字面量保留**。这类字面量数量大、多为一次性覆盖，收敛收益低于
  改动成本；key 改名会静默失效的风险由 TEST_SOURCE 聚合段暴露，不强行常量化的另一个理由是
  `@SpringBootTest` 的 `properties` 数组以可读性优先，拼接常量会显著降低夹具可扫读性。

## 3. 分类规则

| 分类 | 示例 | 建议 |
|---|---|---|
| 项目自定义静态配置 | `batch.storage.backend`、`batch.console.instance-id` | 优先收敛到 `@ConfigurationProperties` |
| 项目自定义敏感配置 | `batch.console.read-replica.*.password` | 通过 properties 或专用 secret resolver 读取，避免散落 key |
| 生产守护条件 | `batch.worker.executors.guard.enforce-profiles` | 收敛到守护配置类，并登记生命周期 |
| 多字段同前缀 `@Value` | `batch.console.pipeline-progress-dirty.*` | 提取成独立 properties 类，避免默认值散落 |
| 单字段低风险 `@Value` | 单个内部实现开关或历史桥接字段 | 可以暂缓；新增时优先 properties |
| Spring 基础设施配置 | `spring.datasource.url`、`spring.data.redis.host` | 允许保留；如多处重复再封装只读 inspector |
| 运行期绑定端口 | `local.server.port` | 允许保留，用于实际端口注册、测试或懒解析客户端；不得用配置端口替代绑定结果 |
| JVM 系统路径 | `java.io.tmpdir`、`user.home` | 允许保留，不强行 Spring 化 |
| 测试/压测参数 | `batch.test.*`、`users.peak` | 允许保留在测试和压测模块 |
| 构建 / 测试工具属性 | `maven.multiModuleProjectDirectory`、`boundedContext.report` | 允许保留，归 `TEST_ONLY`（由 Maven 或测试自身注入，无应用侧归属类） |

分类的判据是「有没有应用侧归属类」：有归属类的 key（含 `batch.test.storage.backend` 这类测试基础设施
属性）应引用归属类常量；没有归属类的工具属性按上表归 `TEST_ONLY`，不进拦截范围。

## 4. 当前优先治理清单

第一批治理生产代码里项目自定义配置的直接 key 读取，以及高风险或多字段同前缀的 `@Value`。不扩大到 `spring.*`、JVM 系统属性和测试/压测参数。

> 状态：P1 7 条 + P2 3 条均已收敛（✅）。收敛后由 `scripts/ci/check-config-governance.py` 维护登记表；直接 key 读取清单由 `scripts/ci/check-direct-config-key-access.py` 报告。

| 优先级 | 文件 | key | 处理建议 | 状态 |
|---|---|---|---|---|
| P1 | `batch-worker/atomic/.../AtomicExecutorProductionGuard.java` | `batch.worker.executors.guard.enforce-profiles` | 新增或扩展 executor guard properties | ✅ `AtomicExecutorGuardProperties` |
| P1 | `batch-console-api/.../ConsoleRealtimeInstanceIdProvider.java` | `batch.console.instance-id` | 收敛到 Console realtime / observability properties | ✅ `ConsoleInstanceIdProperties`（保留原 key） |
| P1 | `batch-console-api/.../ConsolePipelineProgressDirtyPublisher.java` | `batch.console.pipeline-progress-dirty.*` | 5 个同前缀 `@Value` 提取为 properties 类 | ✅ `ConsolePipelineProgressDirtyProperties` |
| P1 | `batch-console-api/.../ReplicaLagMonitor.java` | `batch.console.replica.*` | 2 个同功能 `@Value` 提取为 properties 类 | ✅ `ReplicaLagMonitorProperties` |
| P1 | `batch-common/.../ProductionRuntimeConfigurationGuard.java` | `batch.storage.backend` | 注入 storage backend properties | ✅ `StorageBackendProperties` |
| P1 | `batch-common/.../ProductionRuntimeConfigurationGuard.java` | `batch.console.read-replica.enabled` | 注入 read-replica properties 或专用只读配置对象 | ✅ `ConsoleReadReplicaProperties` |
| P1 | `batch-common/.../BatchSecurityProperties.java` | `batch.console.read-replica.primary.password` / `replica.password` | 通过 read-replica secret/properties 统一读取 | ✅ `ReadReplicaCredentialGuard` + `ConsoleReadReplicaProperties` |
| P2 | `batch-orchestrator/.../StaleCompensationCommandReconciler.java` | `batch.compensation.stale-running-reconciler.*` | 2 个同前缀 `@Value` 提取为 properties 类 | ✅ `StaleCompensationReconcilerProperties` |
| P2 | `batch-orchestrator/.../StaleCreatedLaunchRecoveryScheduler.java` | `batch.trigger.launch.created-recovery.*` | 2 个同前缀 `@Value` 提取为 properties 类 | ✅ `StaleCreatedLaunchRecoveryProperties` |
| P2 | `batch-common/.../BatchRuntimeStatusEndpoint.java` | `batch.storage.backend` | 复用 storage properties，避免端点内散读 | ✅ `StorageBackendProperties` |

第二批再评估（2026-10-06 全部收敛 ✅）：

| 项 | 处理建议 | 状态 |
|---|---|---|
| `spring.data.redis.*`、`spring.datasource.url` 是否需要封装为只读 runtime inspector | 启动守护去重读取 | ✅ `RuntimeInfrastructureInspector`（batch-common） |
| `spring.application.name` 是否统一通过 `ApplicationNameProvider` 读取 | 收敛为单一读取入口 | ✅ `ApplicationNameProvider`（batch-common） |
| worker `maxConcurrentTasks` 的重复 `@Value(WorkerRuntimeConfiguration.MAX_CONCURRENT_TASKS_PLACEHOLDER)` | 收敛到统一 runtime properties 注入路径 | ✅ `WorkerConcurrencyProperties`（batch-worker/core） |
| worker 本地路径默认值是否需要集中成临时目录策略组件 | 复用既有临时目录工具 | ✅ `PrivateTempFiles.tempRoot()` / `resolveUnderTempRoot()`（batch-common） |

第二批与第一批的取舍一致：只做**去重与集中**，不新增业务配置语义，也不迁移 key。

Spring 生命周期收口：Worker consumer 的多个 `spring.kafka.*` 注入已集中为
`WorkerKafkaProperties`，保留现有配置键、默认值、毫秒单位和背压校验，不引入 Kafka 自动配置模块。
副本采样、Worker 并发与续租参数在绑定期校验；五类 Worker 删除固定端口兜底，只读主服务
`local.server.port`。具体零值语义、初始化责任与验证边界见 [启动期配置](./config-governance.md#2-启动期配置)。

第三批：测试源码字面量收敛（2026-10-06，只动测试与归属类常量，不改任何 key）：

| 测试里的裸 key | 归属类（常量 / 入口） | 模块 |
|---|---|---|
| `java.io.tmpdir` | `PrivateTempFiles.TEMP_ROOT_PROPERTY` | batch-common |
| `java.io.tmpdir`（SDK 侧不依赖 batch-common，本地持同一份 key） | `ShellAtomicHandler.TEMP_ROOT_PROPERTY` | sdk/java/core |
| `local.server.port` | `ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY` / `LOCAL_SERVER_PORT_PLACEHOLDER` | batch-console-api |
| `spring.kafka.bootstrap-servers` | `TriggerKafkaProducerConfiguration.BOOTSTRAP_SERVERS_KEY` | batch-trigger |
| `batch.test.storage.backend` | `AbstractIntegrationTest.s3BackendActive()`（判定与既有 `storageBackend()` 同源） | batch-test-support |

保留不改的（无应用侧归属类，按 §3 归 `TEST_ONLY` / `JVM_SYSTEM`）：`user.dir`、
`maven.multiModuleProjectDirectory`、`boundedContext.report`。

常量一律放在**拥有该 key 的类**上，并保持模块内单一副本：`spring.kafka.bootstrap-servers` 只在
batch-trigger 集中一份，`local.server.port` 只在 batch-console-api 集中一份。不跨模块抽公共常量——
为字符串复用新增制品耦合，收益低于成本（与本节开头对 `batch-worker-sdk` 的取舍同理）。

- `RuntimeInfrastructureInspector` 只读 `spring.datasource.url` / `spring.data.redis.*` 的生效值，供
  `QuotaRuntimeBackendGuard`、`WorkerReportOutboxBackendGuard` 计算后端身份。它**不**把 `spring.*`
  复制成 `@ConfigurationProperties`，也不作为业务规则来源（§5.3 / §7）。
- `ApplicationNameProvider` 只读 `spring.application.name`，默认值仍由各调用方按自身语义传入，避免出现
  第二份事实来源。
- `WorkerConcurrencyProperties`（key `batch.worker.max-concurrent-tasks`，保留原 key 与默认值）替代原
  `@Value` 占位符常量：`AbstractTaskConsumer` 及其 5 个子类、5 个 `*WorkerLoop`、
  `KafkaConsumerConfiguration`、`TaskExecutionPool`、`WorkerStartupRuntimeAudit` 统一从该 properties
  读取。收敛时一并覆盖了原先用常量 key 读取、未被扫描器字符串字面量规则命中的两处（`TaskExecutionPool`、
  `WorkerStartupRuntimeAudit`）。
- 临时目录策略集中在 `PrivateTempFiles`：`GenerateStep`、`LocalOutboxDispatchSupport`、
  `StaleTempFileCleanup`、`ShellExecutorProperties`、`ForensicExportProperties` 不再各自读取
  `java.io.tmpdir`。`java.io.tmpdir` 仍是 JVM 系统属性（§3 允许保留），**不**包装成业务配置项（§7）。
  `sdk/java/core` 的 `ShellAtomicHandler` 有意不接入：`batch-worker-sdk` 是对外发布的独立制品，刻意不依赖
  `batch-common`，为单个调用点复用工具类反而引入跨制品耦合。

### 4.1 存量清零（2026-10-06）

第一批收敛后，`docs/governance/direct-config-key-access-baseline.txt` 仍留有 11 条高风险
`batch.*` 读取。本轮按明确授权把它们全部收敛，基线归零。

这是一次有意识的**范围决定**，不是对 §4 路线的改写：§5.4 允许“极窄范围、没有复用需求的历史
单字段内部开关”暂缓，但既然决定清零，就不再区分宽窄，一律提取为类型安全配置。上面
“第二批再评估”的路线保持原样，其条目随后也已全部收敛（见上表）。

| key | 收敛去向 | 模块 |
|---|---|---|
| `batch.shedlock.auto-create`、`batch.shedlock.redis.key-prefix-env` | `BatchShedLockProperties` | batch-common |
| `batch.metrics.backlog.outbox-duplicate-window-hours` | `BacklogMetricsProperties` | batch-orchestrator |
| `batch.quota.redis.failure-mode` | `QuotaProperties.Redis`（复用既有前缀类） | batch-orchestrator |
| `batch.scheduler.snapshot-persist-enabled` | `SchedulerSnapshotProperties` | batch-orchestrator |
| `batch.worker.audit.capability-tags-log-sample-limit` | `WorkerCapabilityTagsAuditProperties` | batch-orchestrator |
| `batch.trigger.readiness-gate.enabled` | `ReadinessGateProperties` | batch-trigger |
| `batch.worker.batch-claim.enabled` | `WorkerBatchClaimProperties`（复用既有类） | batch-worker/core |
| `batch.worker.graceful-shutdown.timeout-seconds` | `WorkerGracefulShutdownProperties` | batch-worker/core |
| `batch.worker.registry.fail-fast-on-startup` | `WorkerRegistryStartupProperties` | batch-worker/core |
| `batch.worker.stale-temp-file-hours` | `WorkerTempFileProperties` | batch-worker/core |

`batch-trigger` 没有 `@ConfigurationPropertiesScan`，因此 `ReadinessGateProperties` 由消费方
`UpstreamReadinessChecker` 用 `@EnableConfigurationProperties` 显式登记（沿用 `BatchTimezoneProvider`
的既有先例）。其余模块靠既有 scan 生效。

所有条目保留原有 key，不改前缀，避免 Helm / Compose / 文档三处漂移。

### 4.2 全工程字面量收敛（2026-10-06）

§4.1 之后，仓库里仍有大量「同一个字符串概念，有的地方用常量、有的地方写裸字面量」的写法。典型是同一个方法里
旁边已经引用了 `PipelineRuntimeKeys.TRACE_ID`，紧挨着的 `detailSummary.put("externalRequestId", ...)` 却仍是
字面量。本节把这批收敛补齐，口径如下。

**收敛判据**：字符串常量在代码里**充当键或状态值**，且**已有归属类**（该概念已存在公开常量 / 枚举）。
两条同时满足才改；缺归属类的先登记为待办，不新建常量。

| 批次 | 范围 | 归属类 | 处数 |
|---|---|---|---|
| A | 审计字段 `operationType` / `operationResult` / `operatorType` | `FileAuditOperationType` / `OperationResult` / `AuditLogConstants` | 23 |
| B | dispatch 链路键（attributes + 出站 payload + 审计明细） | 新建 `DispatchRuntimeKeys`（batch-worker/dispatch） | 29 |
| C | `batch-worker/*` 的 pipeline attributes 键 | `PipelineRuntimeKeys` | 243 |
| D | 枚举码值（`FileStatus` / `FileReceiptStatus` / `OperationResult` / `FileAuditOperationType`） | 对应枚举的 `code()` | 84 + 手工 18 |
| E | 审计操作者 `"API"` / `ACTOR_SYSTEM` | `AuditLogConstants.OPERATOR_TYPE_API` / `OPERATOR_TYPE_SYSTEM` | 4 |
| F | 缺归属类补齐：node outputs 产出键、`payload` / `stepCode`、import / export / process 私有键（见下方批次 F） | 新建 `NodeOutputKeys` / `ImportRuntimeKeys` / `ExportRuntimeKeys`，扩 `PipelineRuntimeKeys` / `ProcessRuntimeKeys` | 74 |

**归属决策（沿用 §3「有没有归属类」的同一判据）**：

- `PipelineRuntimeKeys` 是「worker 侧 pipeline 运行时属性键」的**跨模块**共享表。批次 C 的键都在
  `batch-worker/*` 内流通，模块可达，因此一律归它，不新开同义别名。
- `DispatchRuntimeKeys` 是**模块内**键表：`dispatchPayload`、`receiptCode`、`channelCode` 等只在 dispatch
  链路内流通（写方各 stage step，读方后续 step 与 receipt watcher）。为字符串复用给 `batch-worker/core`
  引入 dispatch 专属概念不划算，与 §4 里 `ShellAtomicHandler` 自持 `java.io.tmpdir` 同一取舍。
  跨模块的 `dryRunSkipped`（dispatch 与 process 都写）仍登记在 `PipelineRuntimeKeys`。
- MDC 键归 `StructuredLogField`（batch-common/logging）。`MDC.get("traceId")` 与
  `attributes.get("traceId")` 值相同但契约不同，分别归 `StructuredLogField.TRACE_ID` 与
  `PipelineRuntimeKeys.TRACE_ID`。
- `FileReceiptStatus` 补齐 `NONE`：DDL 的 `ck_file_dispatch_receipt_status` 允许
  `('NONE','PENDING','SUCCESS','FAILED')` 且默认 `'NONE'`，枚举此前只有 3 个值，导致
  `DispatchInvocationSupport.receiptStatusOf()`、`CompleteDispatchStep`、`DeliverDispatchStep` 只能写裸
  `"NONE"`。补值后这三处一并收敛。

**明确不收敛的类别（写下来是为了下次不必重新论证）**：

| 类别 | 例子 | 理由 |
|---|---|---|
| MyBatis mapper 参数名 | `params(TENANT_ID, tenantId, "fileCode", fileCode)`、`@Param("channelCode")` | 与 SQL 的 `#{...}` 绑定，是持久化契约而非 attributes 键；无归属类 |
| `@JsonAlias` / `@JsonProperty` 值 | `@JsonAlias("runMode")` | 反序列化契约 |
| 出站响应体字段名 | 原子执行器 dry-run 结果里的 `plannedAction` | 尚无归属类，登记待办 |
| 同名不同域的字面量 | `retryPolicy` 的 `"NONE"`、`receipt_policy` 的 `"NONE"`/`"SYNC"`、`result_version.status` 的 `"ARCHIVED"` | 与 `FileReceiptStatus` / `FileStatus` 同名但属不同取值域，**不能**复用同一常量 |
| 无归属类的枚举码值 | `sourceType("GENERATED")`、`"ARCHIVED"`（`result_version`） | 对应枚举不存在，新建枚举属语义变更，另议 |
| `output_summary` 展示字段 | 各 `Default*StageExecutor.buildOutputSummary` 的 `stepCode` / `stage` / `implCode` / `tenantId` / `workerId` / `success` / `code` / `message` | 属 `pipeline_step_run.output_summary` 展示契约，与 attributes 键不总是同义（`stepCode` 另有 `PipelineRuntimeKeys.STEP_CODE` 语义）；本批只收口与产出键同义的 `batchKey`，其余待 output_summary 契约单独评估 |
| 构建 / 测试工具属性 | 见 §3 表末行 | 归 `TEST_ONLY` |

`PlatformRuntimeValues`（batch-worker/core）持有一份与 `PipelineRuntimeKeys` /
`StructuredLogField` 同值的私有常量表（`TENANT_ID` / `FILE_ID` / `PIPELINE_INSTANCE_ID` 等），
但它服务的是 MyBatis 参数构造，属于上表第一行；是否合并需要单独评估，本轮只登记不动。

**改既有行会触发 diff 增量守护**：`check-empty-checks.py` 以 `git diff HEAD` 判定「新增行」，本批只是把裸键换成
常量、行内容变了，就被当成新增行。dispatch 侧 `AckDispatchStep` / `CompensateDispatchStep` /
`CompleteDispatchStep` / `DeliverDispatchStep` 的 `context == null ? null : ...`、`DispatchManifestSupport` 的
`command.payload() == null ? ...`、`DefaultTaskExecutionWrapper` 的 `task.getJobCode() == null ? ...`、
`CompleteDispatchStep` 的 `attrs.get(RECEIPT_CODE) != null` 共 7 处，统一改用 `EmptyChecks.isNull` /
`isNotNull`（守护指定的统一入口，零行为变更）；`ReadReplicaCredentialGuard`（非本批文件）同因被扫出 2 处，一并收敛。
这里**不能**用 `// empty-check: allow` 豁免——GJF 会把超长行折行，豁免标记落到另一行即失效。

**批次 F：缺归属类补齐（2026-10-06，已收敛）**：

批次 E 之后剩下的键**无归属类**，按判据只登记不动。本轮按明确授权补齐归属类，全部收敛（74 处引用点，另删除 3 个同义别名常量）：

| 类别 | 补齐的归属 | 收敛点 | 处数 |
|---|---|---|---|
| ADR-009 节点产出键 / ADR-041 count 信封 | 新建 `NodeOutputKeys`（batch-common） | `ImportStepExecutionAdapter`(2) / `ExportStepExecutionAdapter`(2) / `ProcessStepExecutionAdapter`(3) / `CountContinuityOutboxService`(2) / `WorkflowGraphValidator`(1) / `DefaultProcessStageExecutor`(2) / `ProcessPublishedCountVerifier`(2) | 14 |
| 跨模块 pipeline attributes 键 `payload` / `stepCode` | 扩 `PipelineRuntimeKeys.PAYLOAD` / `STEP_CODE` | `AbstractPipelineStepExecutionAdapter`(4) / `DefaultStepExecutionAdapter`(1 使用 + 删私有同义别名 `CONTEXT_PAYLOAD`) / `DefaultTaskExecutionWrapper`(1 写入点) | 6 |
| import 私有 attributes / file_record metadata 键 | 新建 `ImportRuntimeKeys`（batch-worker/import） | `PreprocessStep`(9) / `ImportRecordGovernanceService`(10) / `LoadStep`(10 使用 + 删 2 个私有同义常量) / `ValidateStep`(2) | 31 |
| export 跨 stage 私有键 | 新建 `ExportRuntimeKeys`（batch-worker/export） | `PrepareStep`(2) / `GenerateStep`(7) / `RegisterStep`(10) / `CompleteStep`(1) / `ExportStepExecutionAdapter`(2) | 22 |
| process 私有键 `processStagingMode` | 扩 `ProcessRuntimeKeys.PROCESS_STAGING_MODE` | `SqlTransformComputePlugin`(1) | 1 |

**归属决策（沿用 §3「有没有归属类」的同一判据，并保持模块内单一副本）**：

- `NodeOutputKeys` 放 **batch-common** 而不是任一 worker 模块：产出键的生产方是各 worker adapter，消费方是
  orchestrator（`CountContinuityOutboxService` 读 `inputCount` / `outputCount`、`WorkflowGraphValidator` 校验
  `batchKey`），worker 模块内的 `PipelineRuntimeKeys` 对 orchestrator 不可达。本表只收**没有 attributes 归属类**
  的产出键（`inputCount` / `outputCount` / `batchKey`）；与 attributes 键同名同义的产出键（`fileId` /
  `recordCount` / `bizDate` / `highWaterMarkOut` / `processedCount` / `stagedCount` / `publishedCount` /
  `objectName` 等）**不新建别名**，继续复用各自键表常量（沿用 `DispatchRuntimeKeys` 的 detailSummary / payload 口径）。
  orchestrator 侧 `KNOWN_OUTPUT_CONTRACT_BY_JOB_TYPE` 里的其余键名对 orchestrator 同样不可达，仍以字面量固化，
  改动必须与 worker 产出侧同步（该表 javadoc 已写明）。
- `ImportRuntimeKeys` / `ExportRuntimeKeys` 是**模块内**键表，与 `DispatchRuntimeKeys` / `ProcessRuntimeKeys`
  同构：收「本模块多个类按名字读写」的键；只在单个类内流通的键（`parseSkippedCount` / `validateFailedCount`、
  `snapshotMode` / `totalAmount` 等）继续留在该类的私有常量，不让模块键表变成垃圾桶。
- `payload` / `stepCode` 归 `PipelineRuntimeKeys`（worker 侧 pipeline 运行时属性键的跨模块共享表）：
  `DefaultTaskExecutionWrapper` 写入、adapter 读取，并删掉 `DefaultStepExecutionAdapter` 的私有同义别名
  `CONTEXT_PAYLOAD`。
- `SqlTransformComputeSpec.RESERVED_PARAMS` 与 `SqlTransformComputePlugin.PARAM_BATCH_KEY` 里的
  `stepCode` / `batchKey` 是 **SQL 参数名**（与 `#{...}` 绑定），属上方不收敛表第一行，保持字面量。
- `DefaultProcessStageExecutor` 的 `batchKey`（`buildInputSummary` / `buildOutputSummary`）与
  `ProcessPublishedCountVerifier` 的 evidence 键与产出键同名同义，一并改引 `NodeOutputKeys.BATCH_KEY`；
  同一 map 里的 `stepCode` / `stage` / `implCode` 等展示字段按上方不收敛表保留字面量。

**批次 F 同样触发 diff 增量守护**：改既有行后，`AbstractPipelineStepExecutionAdapter#extractFromPayloadJson`、
`DefaultStepExecutionAdapter#extractParameters`、`GenerateStep#execute` 的 `context == null ? null : ...` 共 3 处，
`RegisterStep` 的 `attrs.get(EXPORT_LINE_SEPARATOR) != null` / `EXPORT_WITH_BOM != null` 共 2 处，统一改用
`EmptyChecks.isNull` / `isNotNull`（零行为变更）。

**批次 F 后仍缺归属类（登记待评估，本轮不动）**——`file_record.metadata` 键族：

| 键 | 写方 | 已确认的按名读取方 |
|---|---|---|
| `badRecordCount` | `LoadStep`(2) / `ParseStep` / `ValidateStep` / `ImportRecordGovernanceService` | `scripts/sim/sql/select-stage2d-file-records.sql` 等运维 SQL |
| `preprocessed` / `preprocessFormat` / `errorOutputPath` / `qualityChecks` / `validatedRecordsPath` / `loadTargetRef` / `scanner` / `detectedAt` / `bundleJobCode` / `manifestSchemaVersion` 等 | 各 import / export stage 与 `ImportIngressScanner` | 目前只有测试按名断言与少量运维 SQL（生产 Java 侧未见按名读取） |

这些键跨 worker 模块、写方分散，且 `badRecordCount` 被非 Java 制品（`scripts/sim/sql/**`）按名读取，符合 §3「缺归属类先
登记」口径。补齐需要新建 `FileRecordMetadataKeys` 之类的**跨模块**归属类（batch-common 或 batch-worker/core），并同步
SQL 脚本，属下一批的语义决策。

## 5. 推荐改法

### 5.1 静态配置

优先写成类型安全配置：

```java
@ConfigurationProperties(prefix = "batch.console")
public class ConsoleInstanceIdProperties {
  private String instanceId = "";
  // getters / setters
}
```

消费方注入配置对象：

```java
public ConsoleRealtimeInstanceIdProvider(ConsoleInstanceIdProperties properties) {
  this.instanceId = properties.getInstanceId();
}
```

> 收敛时默认保留原有 key（如 `batch.console.instance-id`），不为了改造而迁移前缀，避免 Helm / Compose / 文档三处漂移。

配置类必须进入现有配置治理登记表，由 `scripts/ci/check-config-governance.py` 维护。

### 5.2 敏感配置

敏感配置不要在多个类中直接写 key。优先通过已绑定的 properties 或专用 resolver 读取，并确保：

- 生产环境缺失时 fail-close；
- 文档说明 Secret / 环境变量 / Helm value 注入路径；
- 不在日志、异常和诊断端点输出明文。

### 5.3 框架配置读取

如果必须读取 `spring.*`，保持用途窄且只读：

- 用于启动守护、诊断、连接池命名、运行时实际值检查；
- 不把读取结果当作业务规则；
- 多处重复时封装为 `RuntimeInfrastructureProperties` 或 inspector，但不要把 Spring 内置配置完整复制一份。

### 5.4 `@Value` 使用边界

`@Value` 不是推荐的新配置入口。新增项目自定义配置时，默认使用 `@ConfigurationProperties`。

以下 `@Value` 应提取配置类：

- 同一个类中存在 2 个及以上同前缀配置；
- key 属于 `batch.*` 且是功能开关、容量阈值、调度间隔、批大小、超时或生产守护参数；
- key 含 `password`、`secret`、`token`、`credential`、`private-key`、`kms`、`signing`；
- 同一个 key 被多个类注入；
- 默认值需要被文档、Helm、Compose 或 Console 展示复用。

以下 `@Value` 可以暂时保留：

- `spring.*` 框架配置桥接；
- `local.server.port` 或测试运行期属性；
- 仅用于极窄范围、没有复用需求的历史单字段内部开关；但新增代码不应继续采用这种方式。

## 6. CI 拦截策略

需要做 CI 控制，但治理应分阶段推进，避免一次性阻断历史技术债。当前推荐策略是“先报告、再增量拦截、最后按清理进度收紧”，不做全仓立即失败。

不建议在第一版 CI 中直接全量失败，原因是历史代码里同时存在合理例外和待治理项：`spring.*` 框架桥接、`local.server.port`、JVM 系统属性、测试/压测 `-Dxxx` 参数不应与生产 `batch.*` 业务配置散读混为一谈。

### 阶段 0：报告模式

新增脚本只输出 inventory，不失败：

```bash
python3 scripts/ci/check-direct-config-key-access.py --report
```

报告至少包含：

- 文件、行号、读取方式、key；
- 读取类型：`DIRECT_GET_PROPERTY`、`SYSTEM_PROPERTY`、`VALUE_INJECTION`；
- 分类建议：`PROJECT_CONFIG`、`SECRET_CONFIG`、`SPRING_INFRA`、`JVM_SYSTEM`、`TEST_ONLY`；
- 是否命中白名单。

适合先接入 Full Gate 或 nightly，作为治理看板。

同一入口还会附带 TEST_SOURCE 段（`src/test/java`），读取类型额外含 `TEST_PROPERTY_FIXTURE`
（`withProperty("key", ...)`）与 `TEST_PROPERTY_ENTRY`（`@SpringBootTest(properties = ...)` /
`@TestPropertySource` 里的 `"key=value"` 字面量）。该段**只做可见性**：不进基线、不参与
`--check-baseline`、恒不影响退出码（§2）；`--json` 对应 `testSourceSummary` / `testSourceFindings`。

`@Value("${" + SomeProperties.KEY + "}")` 这类「已收敛到常量」的写法**不计**为字面量命中：
`VALUE_INJECTION` 的 key 只取标识符形态，`${` 之后紧跟引号即判为拼接表达式。收敛到常量正是本治理的
目标状态，把它报成违规等于惩罚正确做法。

本阶段目标是让团队看到新增趋势和分类，不要求立刻清空存量。报告输出应稳定、机器可读，便于后续生成 baseline。

> 已落地：`scripts/ci/check-direct-config-key-access.py` 已登记 `scripts/ci/README.md`，并在 Full Gate `static-checks` job 中以报告模式运行（`run-gate.sh FULL_DIRECT_CONFIG_KEY_ACCESS`），同时输出 `build/config-key-access.json` 并作为 artifact 上传（保留 30 天）。脚本支持 `--json <path>` 输出机器可读报告、`--write-baseline` / `--check-baseline` 生成与比对基线，为阶段 1 做准备。

### 阶段 1：新增增量拦截

> 已落地：`docs/governance/direct-config-key-access-baseline.txt` 已生成；
> `--check-baseline` 已接入 PR Gate（`PR_DIRECT_CONFIG_KEY_BASELINE`，java/ci 变更触发）和
> Full Gate（`FULL_DIRECT_CONFIG_KEY_BASELINE`，main push 回退）。只对相对基线新增的高风险
> 命中失败，历史存量不阻断。
>
> 基线规模变化：建立时 12 项 → 第一批收敛后 11 项 → §4.1 存量清零后 **0 项**。基线为空意味着
> 此后任何新增高风险 `batch.*` 读取都会被 PR Gate 直接拦下。

PR Gate 只拦截新增违规，不要求一次清完历史：

- 新增 `environment.getProperty("batch.*")` 或 `System.getProperty("batch.*")`，且不在白名单，失败；
- 新增 `@Value("${batch.*}")` 时，如果是敏感 key、同前缀多字段或高风险生产守护配置，失败；
- 新增敏感 key 直接读取，失败；
- 新增 `spring.*` 读取只告警，除非命中密码、secret、token。

历史存量放入基线文件，例如：

```text
docs/governance/direct-config-key-access-baseline.txt
```

CI 比对当前扫描结果与基线，只对新增项失败。

本阶段的失败条件应保持克制：只拦新增高风险项，不阻断合理例外，也不因历史存量让无关 PR 失败。

### 阶段 2：P1 存量清零

清理本文 P1 清单后，将脚本升级为：

- `batch.*` 直接读取默认失败；
- `batch.*` 高风险 `@Value` 默认失败，低风险历史单字段必须登记白名单；
- 明确允许项必须在白名单中写明原因、owner 和复查条件；
- `spring.*`、JVM 系统属性继续采用报告模式。

> 已落地（脚本侧）：本阶段由 `--check-baseline` 同一入口承担，无需新增 Gate。
>
> - 前两条与第四条在阶段 1 已实际生效：`classify()` 把所有 `batch.*` key 归为 `PROJECT_CONFIG`
>   （敏感 key 归 `SECRET_CONFIG`），二者同属拦截范围，因此 `batch.*` 直接读取与 `@Value` 都不会
>   因“低风险”而漏拦；`spring.*`（`SPRING_INFRA`）与 JVM 系统属性（`JVM_SYSTEM`）不在拦截范围，
>   继续只报告。
> - 第三条：`ALLOWLIST` 条目必须同时写明 `reason` / `owner` / `review`，缺项或空值即失败。
>   当前 `ALLOWLIST` 登记 1 条（`BatchSecurityProperties.java#spring.datasource.password`，owner
>   为平台/基础设施组，复查条件为封装只读 runtime inspector 后复核）。
> - 脚本同时补上了“注释不是代码”的口径：匹配前先剥离 `//` 与 `/* */` 注释
>   （`strip_comments`）。迁移说明里写的 `@Value("${batch.xxx}")` 只是文档，若计入命中，检查就会
>   逼作者删掉解释文字，属于口径压过事实。剥离保持等长与换行，行号不受影响。
> - 存量已于 §4.1 全部收敛，`docs/governance/direct-config-key-access-baseline.txt` 为空；白名单
>   是“明确允许的例外”，不把存量包装成例外（见 §9）。

### 阶段 3：文档治理联动

当新增或删除 `@ConfigurationProperties` 时，继续由现有脚本维护：

```bash
python3 scripts/ci/check-config-governance.py --write
```

当新增直接 key 读取白名单时，PR 描述必须说明：

- 为什么不能用 `@ConfigurationProperties`；
- 是否涉及敏感信息；
- 是否影响生产启动、滚动重启或多实例一致性；
- 后续是否要清理。

## 7. 不建议的做法

- 不建议把所有 `getProperty` 一刀切改成 `@Value`。`@Value` 仍然是字符串 key，只是换了注入方式。
- 不建议继续新增项目自定义 `@Value("${batch.*}")`。确实要保留时必须说明为什么不用 properties。
- 不建议把 `spring.*` 全部复制成自定义 properties，会制造第二份事实来源。
- 不建议把测试、压测和本地调试 `-Dxxx` 参数纳入生产配置治理。
- 不建议为了消除扫描数字，把 `java.io.tmpdir`、`user.home` 这类 JVM 属性包装成业务配置。

## 8. 本地复查命令

快速查看生产代码直接 key 读取：

```bash
rg -n '(System\.getProperty\("|environment\.getProperty\("|environment\.getRequiredProperty\(")' \
  --glob '!target/**' \
  --glob '!**/src/test/**' \
  --glob '!load-tests/**' \
  --glob '!batch-e2e-tests/**' \
  --glob '*.java'
```

查看生产代码中的项目自定义 `@Value`：

```bash
rg -n '@Value\("\$\{batch\.' \
  --glob '!target/**' \
  --glob '!**/src/test/**' \
  --glob '!load-tests/**' \
  --glob '!batch-e2e-tests/**' \
  --glob '*.java'
```

查看项目自定义 `batch.*` 直接读取：

```bash
rg -n '(System\.getProperty\("batch\.|environment\.getProperty\("batch\.|environment\.getRequiredProperty\("batch\.)' \
  --glob '!target/**' \
  --glob '!**/src/test/**' \
  --glob '!load-tests/**' \
  --glob '!batch-e2e-tests/**' \
  --glob '*.java'
```

查看测试源码里仍硬编码的 key（TEST_SOURCE 段，含夹具字面量，按读取方式汇总）：

```bash
python3 scripts/ci/check-direct-config-key-access.py --report
python3 scripts/ci/check-direct-config-key-access.py --json build/config-key-access.json
```

## 9. 验收口径

一次治理 PR 通过以下条件即可认为收口：

- 新增或修改的配置读取不再散落字符串 key；
- 新增 `@ConfigurationProperties` 已进入配置治理登记表；
- 敏感配置没有新增明文日志或诊断输出；
- CI 至少能报告直接 key 读取清单；若已进入阶段 1，则新增违规可被拦截；
- 测试源码里的精确读取点（`System.getProperty(...)` / `@Value("${...}")` / `withProperty("key", ...)`）
  若 key 有归属类，已改为引用其公开常量，不再重复字面量（§2 / §4.1 第三批）；
- 文档说明允许保留的例外，不把例外包装成已治理。
