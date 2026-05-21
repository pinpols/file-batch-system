# 后端测试统一性诊断报告 — 2026-05-21

**扫描范围**：9 个 backend module 下 `src/test/java/**`（不含 batch-e2e-tests）
**技术栈**：Java 21 / JUnit 5 / AssertJ / Mockito
**评分**：**6 / 10**

---

## 1. Mock 初始化方式漂移（最严重）

**现象**：三种风格并存，无统一约定。

| 风格 | 文件数 | 样本 |
|---|---|---|
| `@ExtendWith(MockitoExtension.class) + @Mock`（主流） | 52 | — |
| `MockitoAnnotations.openMocks(this)` + `@Mock` 手动初始化 | 18 | `batch-orchestrator/.../TaskDispatchOutboxServiceTest.java:47`<br>`batch-orchestrator/.../DefaultTaskAssignmentServiceTest.java:62`<br>`batch-orchestrator/.../DefaultWorkerRegistryServiceTest.java:44`<br>`batch-worker-core/.../DefaultHeartbeatServiceTest.java`<br>`batch-orchestrator/.../DefaultWorkflowNodeDispatchServiceTest.java` |
| `Mockito.mock(Class.class)` 字段直接实例化 | 7 | `batch-orchestrator/.../SensorStateMachineTest.java:36`<br>`batch-console-api/.../ConsoleLoginServiceTest.java:30`<br>`batch-console-api/.../ConsoleSecurityConfigurationTest.java:51`<br>`batch-console-api/.../ConsoleAuthControllerTest.java:36`<br>`batch-orchestrator/.../FileArrivalSensorPolicyTest.java:20` |

**建议**：统一到 `@ExtendWith(MockitoExtension.class) + @Mock + @InjectMocks`，删 `openMocks` 和裸 `Mockito.mock()`，顺带获得 Mockito strict 模式保护。

---

## 2. `@MockitoSettings(LENIENT)` 滥用

**现象**：10 个文件加 `@MockitoSettings(strictness = Strictness.LENIENT)`，4 个 StageExecutor 测试是模板复制带入的，并非真需要宽松 stubbing。

**偏离样本**：
- `batch-worker-dispatch/.../DefaultDispatchStageExecutorTest.java:38`
- `batch-worker-import/.../DefaultImportStageExecutorTest.java:36`
- `batch-worker-export/.../DefaultExportStageExecutorTest.java:39`
- `batch-worker-process/.../DefaultProcessStageExecutorTest.java:35`
- `batch-trigger/.../TriggerOutboxRelayTest.java:41`

**建议**：核查每个文件是否真有跨方法共享 stub 场景；无则删注解，让 strict 模式发现无用 stub。

---

## 3. `@DisplayName` 使用两极分化（无约定）

**现象**：250+ 测试文件中只有 21 个用 `@DisplayName`，集中在 batch-orchestrator/worker-core 两模块，其它 7 个模块几乎不用。

**有 DisplayName 的样本**：
- `batch-worker-core/.../DefaultHeartbeatServiceTest.java`（6 处）
- `batch-orchestrator/.../DefaultTaskAssignmentServiceTest.java`（14 处）
- `batch-orchestrator/.../TaskDispatchOutboxServiceTest.java`（12 处）
- `batch-orchestrator/.../DefaultResourceSchedulerTest.java`（7 处）
- `batch-worker-core/.../DefaultWorkerRegistryServiceTest.java`（7 处）

**建议**：写进 CLAUDE.md 测试规范——要么全推广（中文场景描述），要么统一靠方法名（`shouldXxxWhenYyy`），别让两种共存当默认。

---

## 4. 测试命名风格三分

**现象**：`shouldXxxWhenYyy` camelCase（主流，80+ 文件）/ `xxx_yyy_zzz` snake_case / 纯 camelCase 无语义结构混用。

**偏离样本（snake_case）**：
- `batch-orchestrator/.../TaskDispatchOutboxServiceTest.java:59` — `event_key_falls_back_to_tenant_task`
- `batch-common/.../BatchSecurityPropertiesTest.java:40` — `prodProfile_bypassModeTrue_throwsFatal`
- `batch-orchestrator/.../DefaultTaskAssignmentServiceTest.java:80` — `assign_task_missing_returns_null`
- `batch-orchestrator/.../DefaultWorkerRegistryServiceTest.java` — 全文件 snake_case
- `batch-worker-core/.../HttpTaskExecutionClientTest.java:31` — 混合风格

**建议**：新代码强制 `shouldXxxWhenYyy`；存量在下次改动顺带 rename。

---

## 5. `ReflectionTestUtils.setField` + 裸反射散落

**现象**：`ReflectionTestUtils.setField` 16 处 + `field.setAccessible(true)` 15 处。两类用途：① 注入 `@Value` 字段；② 注入 `@Lazy self` 循环引用。

**偏离样本**：
- `batch-trigger/.../QuartzPauseWhenWheelEnabledCustomizerTest.java:27,39,51,63` — 4 处注入 `schedulerImpl`
- `batch-worker-core/.../StaleTempFileCleanupTest.java:49` — 注入 `staleTempFileHours`
- `batch-console-api/.../ConsoleRequestContextFilterTest.java:34` — 注入 `applicationName`
- `batch-worker-import/.../ReceiveStepPayloadSizeLimitTest.java:36` — 注入 `maxPayloadSizeBytes`
- `batch-orchestrator/.../TaskDispatchOutboxServiceTest.java:51` — 裸反射注入 `self`

**建议**：① `@Value` 字段改 `@ConfigurationProperties`，测试直接传值；② `@Lazy self` 模式（9 处）统一用 `ReflectionTestUtils.setField(service, "self", service)`，不再裸反射 `setAccessible`。

---

## 6. 手工 `new XxxService(...)` vs `@InjectMocks`

**现象**：~7 个文件在 `@BeforeEach` 直接 `new XxxService(mockA, mockB, ...)`，没用 `@InjectMocks`。

**偏离样本**：
- `batch-console-api/.../ConsoleLoginServiceTest.java`（全手工）
- `batch-orchestrator/.../DefaultCompensationServiceTest.java:65`
- `batch-orchestrator/.../SensorStateMachineTest.java`
- `batch-orchestrator/.../DefaultApprovalWorkflowServiceTest.java:92`
- `batch-console-api/.../ConsoleSecurityConfigurationTest.java`

**建议**：统一 `@InjectMocks`；循环引用场景在 `@BeforeEach` 补 `setField(service, "self", service)`。

---

## 7. 集成测试 `@BeforeEach` 模板重复

**现象**：batch-console-api 12 个 `*MutationIntegrationTest` 各自维护几乎相同的 `WebTestClient.bindToServer().baseUrl(...).responseTimeout(60s).build()` 构造代码，已有 `AbstractIntegrationTest` 基类但没下沉。

**偏离样本**：
- `batch-console-api/.../ConsoleJobDefinitionMutationIntegrationTest.java:44`
- `batch-console-api/.../ConsoleAlertRoutingMutationIntegrationTest.java:34`
- `batch-console-api/.../ConsolePipelineDefinitionMutationIntegrationTest.java:41`
- `batch-console-api/.../ConsoleCalendarMutationIntegrationTest.java:41`
- `batch-console-api/.../ConsoleBatchWindowMutationIntegrationTest.java`

**建议**：把 `WebTestClient` 提升到 `AbstractIntegrationTest`（或新 `AbstractMutationIntegrationTest`），子类不再重复。

---

## 8. 集成测试 JSON 字符串硬编码

**现象**：上述 12 个文件各自一份 `private String body(...)`，用字符串拼接 JSON，字段名硬编码，无类型安全，跨文件字段集合有漂移。

**偏离样本**：
- `batch-console-api/.../ConsoleJobDefinitionMutationIntegrationTest.java:53`
- `batch-console-api/.../ConsoleAlertRoutingMutationIntegrationTest.java:43`
- `batch-console-api/.../ConsolePipelineDefinitionMutationIntegrationTest.java:50`
- `batch-console-api/.../ConsoleCalendarMutationIntegrationTest.java:49`
- `batch-console-api/.../ConsoleFileTemplateMutationIntegrationTest.java`

**建议**：用 Request DTO + `ObjectMapper.writeValueAsString()` 替字符串拼接；或抽 `IntegrationTestFixture` 工厂集中默认值。

---

## 9. 断言库混用（仅范围外）

9 个主模块**全用 AssertJ**，没混用。仅 `security-scan`（非主链）2 文件用 JUnit Jupiter Assertions：
- `security-scan/.../SecurityScanOptionsTest.java:5-7`
- `security-scan/.../SecurityScanOrchestratorTest.java:7-8`

**建议**：低优先，下次改 security-scan 顺带替换。

---

## 10. AssertJ 反模式 `assertThat(x.equals(y)).isTrue()`

扫描**未发现**，所有 AssertJ 断言都用 fluent 风格。

---

## Top 3 优先收敛项

1. **Mock 初始化统一（Item 1 + 6）**：~25 个文件，一次专项 PR 把 `openMocks` 和 `Mockito.mock()` 字段消灭，统一到 `@ExtendWith + @Mock + @InjectMocks`，顺带删 10 个 `@MockitoSettings(LENIENT)`（Item 2）。
2. **集成测试基类下沉 + JSON 工厂（Item 7 + 8）**：12 个 Mutation IT 的 `WebTestClient setUp` + JSON 拼接双重复制提到基类 + Fixture 工厂后，维护成本减半。
3. **测试命名 + DisplayName 约定写进 CLAUDE.md（Item 3 + 4）**：明确 `shouldXxxWhenYyy` 为命名，`@DisplayName` 可选用于中文场景描述，止血新代码出现。
