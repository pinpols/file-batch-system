# SonarQube 治理修复日志 — 2026-05-02

> 基准：本地 sonar-scan.sh 跑出 `reports/sonar/2026-05-02_18-31-55/sonar-report.csv`，共 **1024 条** issue
> 范围：P1（BLOCKER + 高优 NPE）+ S112 真边界 + S3776 高复杂度方法 + 抽通用 Excel 校验 helper + 标注脚本
> 验证：跨模块 `mvn compile test-compile` 通过，所有受影响测试 51/51 绿
> 已合并到 `main`：`59d37ef6` / `8510f690` / `301c5699`

---

## 一、整体处置分布

| action | 数量 | 说明 |
|---|---:|---|
| **FIXED** | **31** | 本次代码已改 |
| **ANNOTATION** | **29** | `Texts.hasText` / `Guard.require*` 加 `@Contract`，下次扫描应自动消除（FP 治理） |
| **DEFERRED** | 2 | 业务关键路径但 CC 中段，下次迭代到此功能时"经过即重构" |
| **KEEP** | 22 | Worker 内部 helper（顶层 stage `execute()` 已 catch）/ abstract 模板基类 |
| **KEEP_DOMAIN** | 3 | 自然边界（CSV / XML / FixedWidth format parser 状态机） |
| **SKIP_FP** | 1 | Spring Security `SecurityFilterChain` `throws Exception` 框架契约 |
| **SKIP_SPI** | 5 | Plugin SPI 接口设计上需要宽泛 throws |
| **SKIP_THRESHOLD** | 46 | S3776 CC 16-21 边缘越限，建议 Quality Profile 把阈值放宽到 20 |
| **SKIP_BULK** | 887 | 低 ROI 批量噪音（S1192 字符串常量化、S6213 `_` 命名、S5838 等） |

**输出**：`reports/sonar/latest/sonar-report-annotated.csv` 每条 issue 带 `action` + `note` 中文说明。

---

## 二、P1 修复（commit `59d37ef6`）

### 2.1 · BLOCKER S2699 缺断言测试（4 处）

| 测试 | 修复 |
|---|---|
| [RedisQuotaRuntimeStateIntegrationTest:86](batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/RedisQuotaRuntimeStateIntegrationTest.java) | `reconcileExpiredStates` 包成 `assertThatCode().doesNotThrowAnyException()` |
| [CronExpressionAdapterTest:77](batch-trigger/src/test/java/com/example/batch/trigger/wheel/CronExpressionAdapterTest.java) | `evict()` 同上 |
| [AbstractWorkerLoopTest:112](batch-worker-core/src/test/java/com/example/batch/worker/core/support/AbstractWorkerLoopTest.java) | `doHeartbeat` 异常路径同上 |
| [ProcessMetricsTest:13](batch-worker-process/src/test/java/com/example/batch/worker/processes/metrics/ProcessMetricsTest.java) | `noopFactory` 4 行调用包成 lambda |

### 2.2 · BLOCKER S3516 死分支（1 处）

[ConsoleWebhookService.normalizeEventTypes:105](batch-console-api/src/main/java/com/example/batch/console/service/ConsoleWebhookService.java)：恒等 `if Objects.equals(normalized, "*") return normalized; return normalized;` 删第一分支。

### 2.3 · S2259 真 NPE（5 处）

| 位置 | 修复手法 | 风险等级 |
|---|---|---|
| [DatabaseQuotaRuntimeStateService:270](batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) `policy.isRuntimeManaged()` | 引入 `effectivePolicy = policy == null ? NONE : policy`，全方法替换 | 真 NPE，`policy` 来自 `QuotaResetPolicy.from()` 可能返回 null |
| 同文件 :104 `persist(state)` | `refreshState` 返回 null 守卫 + 早返 | refreshState 可返 null |
| 同文件 :111 `state.peakBorrowedCount()` | 同上守卫 | 同上 |
| [RetryDispatchStep:51](batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/stage/RetryDispatchStep.java) | `context==null` 早返 + 失败结果 | 三元运算符让 context 可 null 后裸用 `getAttributes()` |
| [ConsoleAuthController:60](batch-console-api/src/main/java/com/example/batch/console/web/ConsoleAuthController.java) | `getPrincipal()` 强转改 pattern-match `instanceof ConsolePrincipal principal`，配套新增 i18n key `error.auth.principal_missing`（zh+en） | `Authentication.getPrincipal()` 可返非 ConsolePrincipal 类型 |

### 2.4 · S112 Controller 边界（3 处）

[TriggerManagementController:98/103/109](batch-trigger/src/main/java/com/example/batch/trigger/web/TriggerManagementController.java) drain 三接口的 `throws Exception` 收窄为 `throws SchedulerException`（实际抛出的就是 Quartz 的 SchedulerException）。

其余 28 条 S112 经分析后保留：
- Spring Security `SecurityFilterChain` bean — 框架契约（FP）
- 4 个 Plugin SPI 接口 — SPI 设计契约
- 24 个 Worker 阶段内部 helper — 顶层 stage `execute()` 已统一 catch 包成 `BizException` / `XxxStageResult.failure`

### 2.5 · S2259 噪音治理（29 处批量）

`Texts.hasText` / `Guard.require` / `Guard.requireText` / `Guard.requireFound` 加 `@org.jetbrains.annotations.Contract` 注解（jar 已通过 spring-core 传递存在）：

```java
@Contract("null -> false")
public static boolean hasText(String str) { ... }

@Contract("false, _ -> fail")
public static void require(boolean condition, String message) { ... }

@Contract("null, _ -> fail; !null, _ -> param1")
public static <T> T requireFound(T entity, String message) { ... }
```

SonarJava 识别此契约后，`if (Texts.hasText(x))` 和 `Guard.require(x != null && ...)` 之后调用 `x.someMethod()` 不再触发 S2259。**此前一次错误尝试**逐点重写为 `x == null || x.isBlank()` 已被回滚（30 处机械改动属于"为讨好 Sonar"的噪音），改用契约注解一次性覆盖全仓。

---

## 三、S3776 高复杂度重构

### 3.1 · Tier 1（CC ≥ 30，8 个方法）— commit `8510f690`

| # | 方法 | 原 CC | 拆法 |
|---|---|---:|---|
| 1 | `SqlTemplateExportSqlValidator.checkNoSelectStar` | 35 | 拆 `collectInitialBodies` / `rejectStarItems` / `enqueueNestedBodies` |
| 2 | `SqlTransformComputeSqlValidator.checkNoSelectStar` | 33 | 同上模式 |
| 3 | `BatchStartupSelfCheck.onApplicationReady` | 37 | 每检查项独立 method（flyway / required-versions / schemas / tables / columns / quartz / report），主方法变线性串接 |
| 4 | `ConfigPackageExcelValidator.validateStepRows` | 31 | 抽 `validateStepRow` + `validateStageCode` / `validateRetryPolicy` / `validatePipelineLink` |
| 5 | `WorkflowNodePayloadBuilder.mergeUpstreamPartitionOutputs` | 38 | 拆 `findLatestSuccessPartition` / `mergeWhitelistedOutputFields` / `fallbackFileIdLookup` |
| 6 | `DefaultConsoleFileTemplateApplicationService.buildUpdateParam` | 38 | 抽 `buildBasic/Format/Query/Runtime/Security` 子方法 + `coalesceString/Boolean/Int` 工具 |
| 7 | `LaunchBatchDayService.doUpsertBatchDayInstance` | 49 | 拆 `insertNewBatchDay` / `updateExistingBatchDay` + `BatchDayUpsertContext` / `BatchDayUpdatePlan` record |
| 8 | `DefaultConsoleJobDefinitionExcelApplicationService.validate` | 51 | 拆 `validateRow` + `validateIdentity/Existing/Enum/Numeric/FK/Json` |

### 3.2 · Tier 2（CC 19-28，10 个方法）— commit `8510f690`

| # | 方法 | 原 CC | 拆法 |
|---|---|---:|---|
| 1-7 | `ConfigPackageExcelValidator.validate{Job,Channel,Routing,Pipeline,WfDef,WfNode,WfEdge}Rows` | 19-28 | 各自抽 `validateXxxRow`，公共 helper 上抽到 `SheetValidationHelpers` |
| 8 | `LaunchBatchDayService.upsertBatchDayInstance` | 23 | 抽 `logUpsertRetry` 消除 8 个 `request==null?...` ternary |
| 9 | `DefaultPartitionDispatchService.dispatch` | 26 | 拆 `dispatchInitialDagNodes` / `dispatchByPlan` / `transitionInstanceToRunning/Waiting` + `DispatchOutcome` record |
| 10 | `ConfigPackageExcelWorkbookWriter.createFieldGuideSheet` | 27 | 拆 `setGuideColumnWidths` / `buildGuideStyles` / `writeGuideHeader` / `writeGuideRow` + `GuideStyles` record |

### 3.3 · 重构手法分类总结

| 类型 | 例子 | 套路 |
|---|---|---|
| BFS 遍历状态机 | 两个 `SqlValidator` | 抽 `collectXxx` / `rejectXxx` / `enqueueXxx` 三段式 |
| 顺序检查清单 | `BatchStartupSelfCheck` | 每检查项独立 method，主方法变线性串接 |
| 行级校验循环 | 两个 Excel Validator | 抽 `validateRow(row, ctx, ri)` + 多个 `validate{Identity,Enum,...}Fields` |
| INSERT/UPDATE 双分支 | `LaunchBatchDayService` | 拆双方法 + 共享 `XxxContext` / `XxxPlan` record 携带 |
| 多字段 ternary 合并 | `DefaultConsoleFileTemplateApplicationService` | 抽 `coalesceXxx` 工具 + 4 个 sub-section build 方法 |
| 多步状态填充 | `WorkflowNodePayloadBuilder` | 拆 find / merge / fallback 三段 |
| 配置/数据混合循环 | `ConfigPackageExcelWorkbookWriter` | 把"styles 构建"和"row 写入"分离 + `Styles` record |

---

## 四、Plan A — 抽 SheetValidationHelpers（commit `8510f690`）

新建 [SheetValidationHelpers.java](batch-console-api/src/main/java/com/example/batch/console/support/excel/SheetValidationHelpers.java) — 5 个静态工具方法：

```java
requireField(ri, value, fieldName)
requiredEnum(value, fieldName, allowedSet, ri)
optionalEnum(value, fieldName, allowedSet, ri)
requireIntField(value, fieldName, ri)
validateJsonField(value, fieldName, required, ri)
```

**迁移**：[ConfigPackageExcelValidator](batch-console-api/src/main/java/com/example/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java) + [WorkflowExcelRowValidator](batch-console-api/src/main/java/com/example/batch/console/infrastructure/excel/WorkflowExcelRowValidator.java) 共用，都改成 static import。

**未迁移**：[DefaultConsoleBusinessCalendarExcelApplicationService](batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleBusinessCalendarExcelApplicationService.java) 和 [DefaultConsolePipelineDefinitionExcelApplicationService](batch-console-api/src/main/java/com/example/batch/console/infrastructure/workflow/DefaultConsolePipelineDefinitionExcelApplicationService.java) 各有一组 6 个 `requireText/optionalText/requireEnum/requireInteger/optionalBoolean/{requireDate|optionalJson}` 帮助方法，**两文件 5/6 重叠** —— 是另一个抽象（"读 + 解析 + 返回值"模式，签名 `Map values + key + maxLength/min + issues`），不在 Plan A 范围。建议下次动这块功能时抽成 `ExcelMapRowReader` 类似工具。

**未做**：
- 方案 B（`SheetValidator` 接口 + 8 个 sheet validator 实现） — 改动面大，跨 sheet ctx 依赖（step→pipeline、wfNode→wfDef）需要设计 SheetValidationContext，无功能驱动不主动做
- 方案 C（`WorkflowConditionEvaluator` op 拆 op class） — 表达式 op 间共享类型转换，DSL 语法稳定无新 op 需求

---

## 五、CSV 标注脚本（commit `301c5699`）

新增 [scripts/dev/annotate-sonar-report.py](scripts/dev/annotate-sonar-report.py)：读 `sonar-report.csv`，按"精确表 + 规则默认表 + S112/S3776 特殊分级"为每条 issue 加两列 `action` + `note`，输出 `sonar-report-annotated.csv`。

**精确表**记录本次 31 处 FIXED 的 `(rule, basename, line)` 三元组 + 中文说明；**规则默认表**收口剩余 batch-level 决策（每个 rule 一条）。后续每次扫描后跑一次脚本就能继承当前的归类决策。

下一步可在 [sonar-scan.sh](scripts/dev/sonar-scan.sh) 末尾自动接一行 `python3 scripts/dev/annotate-sonar-report.py`，让标注流水化。

---

## 六、关于 Sonar 阈值

**S3776 阈值建议放宽**：当前默认 15 偏严，对项目实际复杂度过敏感。建议在 SonarQube Quality Profile 把 S3776 `Threshold` 参数从 15 调到 **20**：

- 边缘越限的 46 条（CC 16-19）一次性消化
- 剩余 ~20 条都是真正需要关注的（CC ≥ 22）
- 不影响新代码的"复杂度引入预警"作用

> 注：scanner 端 `-Dsonar.java.S3776.threshold=20` 参数在 SonarQube 8.x+ 不生效，必须在 server side Quality Profile 配。

---

## 七、跳过的批量噪音（说明 + 不修原因）

### S1192 字符串常量化（256 条 / SKIP_BULK）

噪音最大单一规则。项目核心字符串已按场景收口：DB 列名 / JSON key 在 `BatchFileConstants`、`PipelineRuntimeKeys`、`RESERVED_PARAMS` 中；剩余 256 条多为日志模板（`"tenantId={}, jobCode={}"`）、测试 fixture ID、error message detail —— 提常量后命名困难，可读性下降。

### S6213 `_` 命名变量（56 条 / SKIP_BULK）

机械改名，不影响运行。建议下次代码格式化统一时 IDE 批量改。

### S5838 / S5778 / S6068 / S5853 等测试断言增强（合计 ~200 条 / SKIP_BULK）

`assertThat` 链式增强、`assertThatThrownBy` 应只断言抛异常的语句、JSR-305 一致性等。批量改风险高于收益。

### S6813 字段注入改构造器（24 条 / SKIP_BULK）

项目已大量使用 Lombok `@RequiredArgsConstructor`。剩余 24 条多为测试夹具，按 Spring 测试惯例不强求构造器注入。

### S125 注释掉的代码（8 条 / SKIP_BULK）

均为故意保留的"历史脉络注释"（如 ADR-009 / ADR-010 的旧实现样例）。

---

## 八、未尽事宜（DEFERRED / 后续迭代）

### 未修但记录的 S3776 残余 ~20 条（CC ≥ 22 不在 Tier 1/2 名单）

按"下次迭代到此功能时'经过即重构'"原则保留。包括：

- `DefaultConsoleFileTemplateExcelApplicationService` / `DefaultConsoleBatchWindowExcelApplicationService` 等 Excel 子方法
- `WorkflowConditionEvaluator` 的三个 evaluate 方法（CC 17-21）
- `DefaultTaskOutcomeService` 的 outcome dispatcher 方法（CC 18）

### 未做的潜在 helper 抽取

- 方案 B（`SheetValidator` 接口 + 实现注册表）
- 方案 C（`WorkflowConditionOps` 表达式 op 类）
- `ExcelMapRowReader`（Calendar / PipelineDefinition Excel service 两份重复的"读+解析+返回"helper）

---

## 九、commits

```
301c5699 chore(dev): 新增 annotate-sonar-report.py — sonar 报告本次处置标注脚本
8510f690 refactor(sonar): S3776 Tier 1+2 复杂度重构 + 抽 SheetValidationHelpers 工具
59d37ef6 fix(sonar): P1 BLOCKER + 真 NPE + S112 controller 收窄 + helper 加 @Contract
```

---

*验证：`mvn compile test-compile --projects '!batch-e2e-tests'` 全模块通过；受影响测试 `RedisQuotaRuntimeStateIntegrationTest` / `CronExpressionAdapterTest` / `AbstractWorkerLoopTest` / `ProcessMetricsTest` / `ConsoleWebhookServiceTest` / `SqlTemplateExportSqlValidatorTest` / `SqlTransformComputeSqlValidatorTest` / `ConfigPackageExcelValidatorTest` / `DefaultConsoleJobDefinitionExcelApplicationServiceTest` / `DefaultLaunchServiceTest` / `DefaultPartitionDispatchServiceTest` / `ConsoleFileTemplateControllerTest` / `DefaultConsoleWorkflowExcelApplicationServiceTest` 共 51/51 绿。*
