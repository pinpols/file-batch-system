# 分支、数据结构与设计模式审查（2026-11-12）

## 范围与结论

- 仓库 `file-batch-system`，`main` @ `1db0c5c5e`，工作区干净。审查本身只读；整改在 `chore/branching-pattern-remediation`（基于 `3ff888db3`）实施，见文末「整改记录」。
- 入口 `project-engineering-review`，覆盖工作包 3b（Service 与设计模式）、16/19（条件）、23（枚举/常量）、24（数据结构、策略与工厂）。依据 [编码规约](../coding-conventions.md) §1.5、§1.7、§4、§22。
- 范围：全部模块 `src/main/java` 生产 Java 3139 个。先用脚本扫出候选（去掉注释后），再人工逐条核对。脚本只产生候选，不代表结论。
- 结论：总体健康。98 个 `switch` 里 97 个是箭头式；枚举里的 `switch (this)` 没有 `default`，由编译器检查是否穷尽；循环里的成员判断全部用 `Set`；没有可变的静态集合字段。登记 8 项 P3、5 项 P4（含设计模式盘点里的 CONV-11、CONV-12）。其中 CONV-0 已经造成用户可见的漂移；其余是潜在漂移或维护性问题，没有发现运行时故障。

## 候选扫描基数

| 维度 | 候选数 | 人工判定 |
|---|---|---|
| 同一主体 ≥3 分支的 `else if` 链 | 1 | `ExcelSheetWriter.writeTypedCell` 解析失败要统一回落到文本，`if` 链合理，保留 |
| `if-return` 阶梯（≥3 级） | 2 | `KafkaOutboxPublisher` 记为 P4；`ImportEventArrivalService` 是 guard，保留 |
| 冒号式 `switch` | 1 | `TaskConsumerRoutingPolicy` 记为 P4 |
| 没有 `default` 的 `switch` | 28 | 26 个是枚举 switch 表达式，由编译器保证穷尽；`ThrottledLogger`、`TriggerApiAdmissionGuard` 是枚举语句 switch，已覆盖全部取值 |
| 字符串 `switch` | 39 | 其中 4 组与已有枚举或已有描述表重复，见 CONV-0、CONV-3、CONV-4、CONV-5 |
| 循环里的 `contains` | 15 | 15 个都是 `Set`，或者是小规模的列名 `List`，保留 |
| 旧集合 / `StringBuffer` | 1 | `SparkSubmitTaskExecutor.StreamCollector` 跨线程写入，有注释说明，保留 |
| 可变的静态集合字段 | 0 | — |
| `instanceof` 链 | 1 | `FrozenDispatchChannelSnapshot` 记为 P4 |

## 代码规约整改台账

### CONV-0 / P3：Excel 说明页的平行 switch 已漂移（有当前证据）

- 位置：[ConfigPackageWorkbookSupplementWriter.java:289–318](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageWorkbookSupplementWriter.java)、[ConfigPackageSheetSpecs.java:493、538](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java)。
- 现状：`SheetDef` 描述表已经存在（第 339 行起），但每个 sheet 的填写场景、填写原因、必填列和投影列分散在两个类的 4 个 `switch (sheetName)` 里。
- 证据：说明页遍历 `SheetDef`（第 167–168 行）。`JOB_MONITORING_POLICY_SHEET` 和 `WORKFLOW_MONITORING_POLICY_SHEET` 两个 sheet 不在 `fillScenarioFor` / `fillReasonFor` 里，会落到 `default`，导出的文字是「按业务需要填写。」，填写原因为空。`PIPELINE_SHEET` 有 case，但没有对应的 `SheetDef`，是死分支。
- 建议：把这些逐 sheet 的属性收进 `SheetDef`，或者放进同包里一张以 sheet 名为键的描述表；新增 sheet 只改一处。补一个测试：每个 `SheetDef` 都要有非默认的说明文字。文案可以一起改走 i18n，但这是另一个决定。
- 行为保持：列集合、列顺序、默认列推导不变。

### CONV-1 / P3：配置读穿缓存模板复制了 5 份

- 位置：[OrchestratorConfigCacheService.java:145–350](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigCacheService.java)。
- 现状：作业定义、工作流定义、日历、窗口、配额 5 个 `findEnabled*` 方法，各自重复同一段流程：快照短路 → 本地缓存 → Redis → 负缓存 → 查库 → 事务内不回填。其中日历和窗口没有本地正缓存，其余 3 个有。`evictLocalByType` / `evictLocalByKey`（第 418、431 行）用字符串 `if-else` 选择要失效的本地缓存，`"job-definition"`、`"business-calendar"`、`"batch-window"` 这些字面量也和常量混着用。
- 风险：缓存一致性的修复（例如 R3-P2-9 负缓存清理）需要逐份同步，漏改其中一份不会被编译器发现。
- 建议：抽一个私有的通用读穿方法（`loader`、`Class<T>`、可选的本地缓存），按 §22.2 的模板方法写法；缓存类型到本地缓存的映射改成构造期 `Map`，按 §22.1。每类配置是否带本地缓存保持原样。
- 验收：现有缓存测试，加上事务内不回填、负缓存失效、epoch 竞争三个场景的定向回归。

### CONV-2 / P3：条件表达式的引号/括号扫描写了 4 份

- 位置：[WorkflowConditionEvaluator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java) 中的 `splitListItems`:235、`splitTopLevel`:270、`stripOuterParentheses`:388、`AtomicOperator.indexOfTopLevel`:488。
- 现状：4 处各自维护 `inSingleQuote`、`inDoubleQuote`、`depth` 三个状态，`else if` 结构基本一样。`SqlTaskExecutor.SplitScanner` 已经示范过把这种状态收成一个扫描器。
- 建议：提取同类内私有的顶层扫描器，只表达各处的差异动作。可以和 §22.5 一起看。必须保留现有转义和括号失衡时的行为，回归用现有的条件求值测试。

### CONV-3 / P3：工作流 DAG 判定用状态字面量，没有用 `WorkflowNodeRunStatus`

- 位置：[DefaultWorkflowDagService.java:575–584、603–606、620–624](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java)。
- 现状：边类型已经用 `WorkflowEdgeType` 枚举穷尽 switch；节点状态却用 `"SKIPPED"` / `"FAILED"` 字面量加 `equalsIgnoreCase`。`isTerminal` 自己又定义了一遍终态集合。
- 建议：解析成 `WorkflowNodeRunStatus` 之后再判断。终态口径是 DAG 自己的业务规则（SKIPPED 算终态，`OPERATOR_SKIPPED` 例外），不要直接换成通用的 `lifecycle()`，要加专项测试固定这个口径。写入的状态字符串（第 508、535 行）可以继续用 `.code()`。

### CONV-4 / P3：Console 渲染和通知文案用字符串 switch，重复了枚举里已有的取值域和文案

- 位置：[WorkflowMermaidRenderer.java:62、85](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/web/support/WorkflowMermaidRenderer.java)（`batch-common` 已有 `WorkflowNodeType` / `WorkflowEdgeType`）；[JobPushIntakeService.java:107](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/JobPushIntakeService.java)、[ConsolePushApprovalNotifier.java:146、156](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) 写死了中文状态和类型文案。
- 风险：枚举新增取值后，这些 switch 静默走 `default`；推送文案和 i18n、枚举 `label` 是两份来源。
- 建议：先解析成枚举，再做穷尽 switch；文案走 MessageSource 或枚举 label，要和 [i18n 规范](../design/i18n.md) 一起裁定。外部协议里的事件类型字符串保持不变。

### CONV-5 / P3：导出格式到后缀的映射有两张表，XML 的结果不一致（潜在问题）

- 位置：[ExportFormatStrategy.java:22–47](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/ExportFormatStrategy.java)、[ExportFileNameResolver.java:47–61](../../batch-common/src/main/java/io/github/pinpols/batch/common/file/ExportFileNameResolver.java)。
- 证据：`fileSuffixFor` 没有 XML 分支，XML 会得到 `.json`；但 `contentTypeFor` 有 XML 分支。`ExportFileNameResolver.extension` 把 XML 映射为 `.xml`。目前没有注册 XML 导出策略，`ExportFormatStrategyRegistry.require` 会直接拒绝 XML，所以现在不会触发。
- 建议：后缀和 Content-Type 只保留一处来源，例如放到 `FileTemplateFormat` 上，或者统一委托 `ExportFileNameResolver`。等将来接入 XML 导出时再补这一项也可以，但这个前提要在计划里写明。

### CONV-6 / P3：SDK 原子处理器和平台 Worker 的 SQL 切分、类型表不一致（契约漂移，属于已知限制）

- 位置：[SqlAtomicHandler.java:127](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java) 和 [SqlTaskExecutor.java:258](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java)；`StoredProcAtomicHandler.toSqlType`（7 种类型）和 `StoredProcTaskExecutor.toSqlType`（21 种类型）。
- 现状：平台 Worker 已经修好 PG dollar-quote 体内的 `;` 误切（注释写明原问题会让 `maxStatementsPerJob` 和只读判定出偏差）；Java SDK 和 Python SDK 的注释都写着「不处理 dollar-quote」。所以同一段 SQL 在 SDK 自托管和平台托管两种方式下，语句数和执行结果可能不同。
- 判定：SDK 有意识地保持了跨语言一致，因此不登记为新 Bug。是否要让 SDK 和平台对齐，交给 `sdk-contract-governance` 裁定（影响 Java、Python 等多个 SDK 和 conformance fixture）。

## 设计模式盘点

按类名后缀统计（生产代码）：Support 48、Handler 39、Resolver 39、Adapter 26、Policy 22、Provider 15、Registry 13、Builder 11、Factory 8、Strategy 5、Facade 2、Template 1。除 `XxxService` / `DefaultXxxService` 配对外，只有 1 个实现的接口有 66 个；子类不超过 1 个的抽象类有 16 个。

| 类别 | 判定 |
|---|---|
| Console 的 `*Store`、`*Port`（Redis 会话、限流、幂等、实时事件等，约 25 个） | 应用端口。只有一个 Redis 实现，但隔离了基础设施，测试也依赖它，符合 §4.1.1，保留 |
| Orchestrator 调度层的接口-默认实现配对（`ConcurrencyLimiter`、`PartitionThrottle`、`WorkerSelector`、`ResourceScheduler`、`WorkerRouter` + `WorkerRoutingPolicy` 等） | 沿用既有配对。规约明确不借治理改动重构调用图，保留 |
| `ExportFormatStrategy` + Registry + `AbstractExportFormat`（4 个格式实现） | Strategy 加 Template 用对了；问题只在格式到后缀/Content-Type 那两张平行表（CONV-5） |
| SDK `SdkAbstract*Handler` / `SdkTyped*` | 提供给租户扩展的 SPI 模板，仓内只有 Echo 示例子类是正常的，保留 |
| 测试基类 `Base*ArchTest`、`AbstractIntegrationTest` | 子类在测试源码里，脚本没有统计，保留 |
| `DefaultCompensationService` 路由表、`SpecHandler` 模板、`DispatchChannelGateway` 适配器注册表 | 已落地的 §22 样板，没有退化 |

### CONV-11 / P3：`AbstractSingleSheetExcelService` 是没有子类的模板方法基类

- 位置：`batch-console-api/.../infrastructure/excel/AbstractSingleSheetExcelService.java`（审查时 376 行，`<R, T>` 泛型模板，有 protected 构造器）。
- 证据：全仓没有类继承它。只有 4 个 `*ExcelRowParser`（日历、窗口、文件模板、资源队列）通过 `import static` 使用它的 `normalize`、`optionalText`、`optionalEnum`、`optionalBoolean` 这类静态方法。模板方法部分实际是死代码，静态工具却放在抽象类名下。
- 建议：先确认实例部分确实没有调用方，再删除它们；静态解析工具移到同包的 `*ExcelCellSupport`，或者并入已有的 `ConsoleSingleSheetExcelImportSupport`。解析语义和错误码保持不变，回归用 4 个 parser 的现有测试。

### CONV-12 / P4：`AuditableEntity` 没有子类

- 位置：`batch-common/.../common/model/AuditableEntity.java`。`AuditFieldsInterceptor` 的注释写明按字段名匹配、不要求继承它，全仓也没有子类。
- 建议：删除，或者在注释里说明它保留给什么用途。[实体边界审查](backend-controller-service-entity-boundary-review-2026-11-01.md) 只提到了它的位置，没有说明它的用途。

## P4 可选项（低收益，等相邻改动时顺手处理）

| ID | 位置 | 建议 |
|---|---|---|
| CONV-7 | `KafkaOutboxPublisher.resolveDedicatedTopic`:209 | 4 级字符串 `if-return` 改成 `Map.of` 路由表（§22.1） |
| CONV-8 | `TaskConsumerRoutingPolicy`:73 | 全仓唯一的冒号式 switch，`return`、`break` 和可变的 `suffixAlt` 混用；改成 switch 表达式 |
| CONV-9 | `ExportKeysetRangePlanner.toMap`:105 | 与同模块的 `ExportConfigValueSupport.toMap` 重复，直接复用后者 |
| CONV-10 | `FrozenDispatchChannelSnapshot.copyObject` / `copyList` | 两处 Map/List/标量分派重复，收成一个 `copyValue(Object)` |

## 合法保留（不整改）

- 枚举内 `switch (this)`（`JobStatus`、`TaskStatus` 等 11 处）没有 `default`，新增常量时由编译器提示补分支，写法正确。
- `DefaultLifecycleEventMapper.resolveToState` 用多标签 case 表达事件别名，`default` 走自转换或抛错，清楚，保留。
- `SqlTaskExecutor`、`SqlAtomicHandler`、`WorkflowConditionEvaluator` 里的字符级 `else if` 是词法状态机，不适合改成路由表（CONV-2 只处理重复问题）。
- `DefaultConsoleApprovalApplicationService.executeApprovedAction` 的 8 路 switch：最长的分支约 12 行，略高于 §22.4 的 10 行阈值，阅读负担不大，等相邻改动时再考虑提取。

## 系统问题台账

本范围未发现。CONV-0 是用户可见的文案缺失，不影响导入语义，所以没有升级为系统问题。

## 治理控制

现有门禁没有覆盖「同一个键的平行 switch」和「字符串 switch 与已有枚举取值域重复」。按 [Skill 指南](../standards/agent-skills-guide.md) 的顺序，先按现行规约完成上面的整改，再决定是否需要新增守卫；本轮不新增规则。

## 验证边界

- 实际执行：候选扫描脚本（临时，不入库）、`grep`、源码人工核对。
- 未运行：编译、单测、集成测试、ArchUnit。所有结论都是静态证据；CONV-0 是从源码推出的输出结果，没有实际生成工作簿来确认。

## 整改记录（2026-11-12）

分支 `chore/branching-pattern-remediation`，独立工作树；先基于 `origin/main` `3ff888db3`（含 #1358），2026-11-12 晚快进到 `da24538cd`（含 #1360），与新主干只有生成的代码行数快照重叠，已重新生成。未提交。

| ID | 实施 | 新增/调整测试 |
|---|---|---|
| CONV-0 | 两个 `switch (sheetName)` 合并为 `FILL_GUIDES` 描述表，补齐两个监控策略 sheet 的文案，删除不存在的 `PIPELINE_SHEET` 分支。`ConfigPackageSheetSpecs` 里的两个 switch 的 `default` 是「按列推导」规则，不是兜底，保留 | `ConfigPackageWorkbookGuidanceTest.shouldDescribeEverySheet_whenBuildingFillOrderSheet`：修复前失败（列出 2 个监控 sheet），修复后通过 |
| CONV-1 | 5 个 `findEnabled*` 收敛为 `readThrough(ConfigCacheSlot, localCache, loader)`；本地缓存失效改用 `localPositiveCachesByType` 映射；缓存类型字面量收成常量。单作业 Workflow 关系缓存的形状不同，保留原方法 | 原有 `OrchestratorConfigCacheServiceTest` 23 项保护行为 |
| CONV-2 | 4 处引号状态收成 `QuoteState.outsideAfter` | `shouldTreatOperatorsInsideQuotesAsLiterals`：在原实现上先跑通过，作为行为基线；新实现同样通过 |
| CONV-3 | DAG 判定解析为 `WorkflowNodeRunStatus`，终态口径固定为 `DAG_TERMINAL_STATUSES`，写入的状态改用 `.code()`；跳过原因码保留字面量 | `shouldReleaseOnlyOperatorSkippedPredecessor`、`shouldUseDagTerminalStatusesCaseInsensitively`：原实现与新实现均通过 |
| CONV-4 | Mermaid 图形按 `WorkflowNodeType` / `WorkflowEdgeType` 穷尽 switch；推送标题按枚举解析。审批类型改用字典标签（`SELF_SERVICE` 由原始码改为「自助补救」）；状态和作业结果保留面向推送的措辞 | `shouldLabelApprovalTypeFromDictionary` |
| CONV-5 | `fileSuffixFor` 补 XML，并以 `ExportFileNameResolver` 为一致性基准；并行分支正在修改该文件，本次未改它 | `ExportFormatStrategySuffixTest`：修复前 XML 失败，修复后 6 项通过 |
| CONV-6 | Java SDK、Python SDK 的 SQL 切分支持 PG dollar-quote，规则与 `SqlTaskExecutor` 一致；Python 改成状态类结构，没有放宽 ruff。Java SDK 存储过程补齐平台的标量 OUT 类型；`REF_CURSOR` / `OTHER` 需要另行读取，SDK 仍拒绝。Python 的 `outParams` 是保留字段，没有类型表 | Java 新增 4 个切分用例（修复前失败 2 个）、类型映射用例；Python 新增 4 个参数化用例（修复前失败 2 个） |
| CONV-7 | 专用 topic 改成 `DEDICATED_TOPICS_BY_EVENT_TYPE` | 由模块单测覆盖 |
| CONV-8 | 冒号式 switch 改成提前返回加 switch 表达式；租户白名单拼接提取为 `tenantScopedSuffix` | 由模块单测覆盖 |
| CONV-9 | 撤回，不改。`ExportKeysetRangePlanner.optedIn` 当前不可达：`resolve` 在多分片时提前返回，`compute` 在单分片时直接返回；类注释说明这段区间规划代码是为后续共享计划有意保留的。CI 差异覆盖率门禁也指出这几行无法覆盖。等这段代码重新启用时再复用 `toMap` | — |
| CONV-10 | 新增 `copyValue` 模式 switch，标量规则不变 | 由快照服务相关测试覆盖 |
| CONV-11 | 删除 `AbstractSingleSheetExcelService`；4 个 RowParser 使用的解析函数原样移到包内 `SingleSheetExcelCellParsers`；删除 `PositionalArgsConventionTest` 中失效的 `ImportAuditContext` 白名单项；同步更新设计文档引用 | 4 个 parser 相关测试 |
| CONV-12 | 删除 `AuditableEntity`，同步修改拦截器注释 | 编译验证 |

### 验证（基线 `da24538cd` + 本分支工作区，本机 macOS，Docker 29.8 + Testcontainers）

| 命令 | 结果 |
|---|---|
| `./mvnw -o -ntp -pl batch-common,batch-test-support,sdk/java/core -am test`（作为第一次全量重跑的一部分） | PASS：batch-common、batch-test-support、Java SDK 均为 SUCCESS |
| `./mvnw -o -ntp -pl batch-orchestrator test -Dtest='!**/integration/**'` | PASS：2429 项，0 失败 |
| `./mvnw -o -ntp -pl batch-orchestrator test -Dtest='**/integration/**'` | 1000 项，1 失败：`ArchiveDiagnosticCadenceProbeIT`（其他测试遗留实例 13 个，期望 0）。该探针默认不在 surefire 的选择范围内，按[WDI 验证记录](../verifications/wdi-local-closure-2026-10-25.md)应单独运行，这里是因为选择器把它带进来才失败 |
| `./mvnw -o -ntp -pl batch-orchestrator -Dtest=ArchiveDiagnosticCadenceProbeIT test`（单独运行） | PASS：2/2 |
| `./mvnw -o -ntp -pl batch-console-api,batch-worker/core,batch-worker/export,batch-worker/dispatch test` | PASS：2996 / 287 / 261 / 450 项，0 失败 |
| `uv run --locked pytest tests/handler/atomic/`、`ruff check`、`ruff format --check`、`mypy src/batch_worker_sdk`（sdk/python） | PASS：31 项；lint、格式、类型检查均通过 |
| `check-empty-checks.py`、`check-java-readability.py`、`check-test-conventions.py`、`check-code-doc-references.py`、`check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-comment-language.py --base-ref origin/main`、`check-java-logging-governance.py`、`check-mapof-null-values.py`、`check-dependency-boundaries.py`、`check-java-suppression-registry.py`、`check-loc-snapshot.py`、`check-changelog-sync.py` | PASS |

说明与限制：
- 第一次全量重跑中，orchestrator 因单个 fork 总时长超过 1800 秒被 surefire 终止。当时机器负载约 54（8 核），另有会话占用大量 CPU；终止前 2321 项 0 失败。因此改为上表两段运行。在快进之前的基线上，同一命令曾整模块通过（23 分 46 秒）。
- 本分支的 `batch-common` 等快照已 install 到本机 `~/.m2`。
- 未运行：`batch-e2e-tests`、全仓 Full Gate、Sonar、远端 CI。
