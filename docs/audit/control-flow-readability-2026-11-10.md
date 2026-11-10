# 控制流可读性审查（2026-11-10）

## 结论

阶段 8 批次 0–4 已在当前工作区落地，尚未提交。2026-11-10 核查闭合的 14 处嵌套选择已按原来的短路改完。批次 2–4 的原有单测 88 项失败数为 0。这不代表全仓 `if` / `catch` 清零，也不代表真实存储或集成验收。

方案正文在 [可读性路线图 §6](../plans/java-readability-refactoring-roadmap-2026-08-12.md#control-flow-density-2026-11-10)。

## 基线与范围

- 仓库：`file-batch-system`。结论针对当前工作区，不表示已经合入 `main`。
- 生产 Java 3100 个。去掉注释、字符串和泛型 `?` 后，同一语句里两个三元运算符 16 处：并列缺省 3 处保留，同一行嵌套选择 13 处。另有 1 处跨行嵌套，合计 14 处。
- 并列缺省保留在 `ConsoleTriggerStatsResponse`、`ConsoleWorkerLoadResponse`、`ConfigPackageExcelWorkbookWriter`。
- 调度器三层 catch、已有失败翻译边界、持久化阶段字符串、按行数拆类，都不在本轮改动里。

## 剩下 8 处的短路

| 位置 | 原短路 |
|---|---|
| `TriggerGracefulShutdown.schedulerStatus` | 先 `isShutdown`，再 `isInStandbyMode`，再 `isStarted`，否则 `STOPPED` |
| `TaskDispatcher.diagnosticMessage` | 文案已含类名则返回文案；空文案只返回类名；否则 `文案 (类名)` |
| `DispatchDeliveryMetrics.deliveryResult` | 熔断拒绝得到 `circuit_open`，否则成功为 `success`，否则 `failure` |
| `LocalOutboxDispatchSupport` | 只有传输桩才把 null 明细收成空串并写入桩文案 |
| `ExportRegionResolver.firstNonBlank` | 第一项有文本则不再检查第二项 |
| `DatasetRuleEvaluator.expectedRowCount` | 规则精确行数优先；否则仅在清单参与时返回清单期望行数 |
| `ConsoleApiExceptionHandler.bizExceptionMessage` | 无解析器时，空 message key 返回原文，否则返回错误码标签；有解析器才调用 `resolve` |
| `TenantConfigPackageExcelApplyService.singleJobDefinitionId` | 有单个作业只读它的标识；没有作业且已有定义时才读定义上的标识 |

## 验证

2026-11-10 本轮命令，失败数均为 0：

```bash
./mvnw -o -ntp -pl batch-trigger -am -Dtest=TriggerGracefulShutdownTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl sdk/java/core -am -Dtest=TaskDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl batch-worker/dispatch -am -Dtest=DispatchDeliveryMetricsTest,LocalOutboxDispatchSupportTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl batch-worker/export -am -Dtest=ExportRegionResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl batch-worker/import -am -Dtest=DatasetRuleEvaluatorManifestRowCountTest,DatasetRuleEvaluatorControlRecordTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl batch-console-api -am -Dtest=ConsoleApiExceptionHandlerTest,TenantConfigPackageExcelMonitoringPolicyApplyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`TriggerGracefulShutdownTest` 7、`TaskDispatcherTest` 15、`DispatchDeliveryMetricsTest` 6、`LocalOutboxDispatchSupportTest` 4、`ExportRegionResolverTest` 8、`DatasetRuleEvaluatorManifestRowCountTest` 4、`DatasetRuleEvaluatorControlRecordTest` 4、`TenantConfigPackageExcelMonitoringPolicyApplyTest` 15、`ConsoleApiExceptionHandlerTest` 25。`LocalOutboxDispatchSupport` 的 Spotless apply 为 clean。

批次 0、1 的证据仍以路线图 §6.1 为准。删除只转发一次的 `readyToRegister` 之后，`ImportIngressScannerTest` 14 与 `ImportIngressScannerPaginationTest` 2 已重跑，合计 16 项失败数为 0。`ImportIngressScanner` 与 `LocalOutboxDispatchSupport` 的 Spotless check 为 clean。

## 薄壳与封装

审查范围是阶段 8 改过的生产类，加上创建运行时已经看到的跨行选择。不按私有方法个数扫完全仓。依据是 [`coding-conventions.md` §1.4、§1.5、§1.7](../coding-conventions.md#14-命令与上下文的调用现场)。本段只记录，不授权再改代码。

本轮已经去掉的壳：

- `stubDetailText` 只做 null 收成空串，已回到传输桩分支内部。
- `readyToRegister` 只使用一次，且只读已经准备好的字段，已收成 `commitPreparedObject` 里的局部布尔。登记动作仍在版本确认分支。

留下的方法都有自己的决定或边界，不是一行转发：

| 方法 | 为什么留下 |
|---|---|
| `schedulerStatus` | 按停机、待机、已启动依次询问调度器 |
| `deliveryResult`、`expectedRowCount`、`firstNonBlank`、`singleJobDefinitionId`、`nextPhaseAfterApply` | 固定优先级，提前返回后不再看下一项 |
| `bizExceptionMessage` | 只有解析器存在时才调用 `resolve` |
| `specError` | 8 处规格失败共用同一个错误码和参数形状 |
| `cleanPage`、`persistIndexedObject`、`prepareObject`、`commitPreparedObject`、`recordRevisitLane`、`recordForwardLane` | 扫描周期里的独立阶段，围栏和短路仍在原处 |

`ImportIngressScanner` 的一行方法里，`isManifestMode` 用了 3 处，`validResourceCode` 用了 2 处，`sanitizeTrace` 用了 4 处，都是多处共用的命名事实。三参数 `putBundleMetadata` 把缺省的空 metadata 和 `false` 收在一处，两个调用点不用各写一遍。

## 全仓私有方法与辅助类

2026-11-10 用词法统计扫了 `batch-common`、`batch-console-api`、`batch-orchestrator`、`batch-trigger`、`batch-worker`、`sdk/java`、`security-scan` 和根 `src/main/java` 的生产 Java，排除测试源码。私有方法不少于 15 个的文件有 69 个：单入口（公共方法不超过 3 个）29 个，中等入口 19 个，多入口（公共方法不少于 8 个）21 个。这是签名计数，一行一个方法声明，用来找候选，不是拆分名单。规则见 [`coding-conventions.md` §4.1.1](../coding-conventions.md#411-service-层职责与入口约定)。本段不授权拆类。

实际打开入口后的判断：

| 类 | 入口现在怎么读 | 辅助类 |
|---|---|---|
| `ImportIngressScanner` | `scan` 到 `scanCycle` 已经是发现、索引、登记、清理 | 登记、清单校验和 metadata 回写叠在这条流程下面，是以后可以单独命名的一块。扫描周期留在主类 |
| `LoadStep` | `execute` 只决定演练、流式装载或缺少载荷 | 流式装载和插件协议在 `executeStreaming` 下面。同形的 `GenerateStep`、`ParseStep`、`ValidateStep` 只核对了公共方法是 `stage` 与 `execute`，没有逐个打开方法体 |
| `TenantConfigPackageExcelApplyService` | `applyAll` 按资源队列、日历、窗口、模板、渠道、作业、工作流往下写，然后失效缓存 | 主类已经是流程。各 sheet 的写入方法留在原处 |
| `DefaultLaunchService`、`DefaultWorkflowRunLaunchService` | `launch` 按校验、重放或容量、准备、派发往下走 | 阶段方法留在主类 |
| `DefaultCompensationService` | `submit` 开头仍把校验、指纹、冲突和插入写在一起，后面还有约 48 个私有方法 | 先把提交路径收成阶段，再决定有没有单独的执行簇。不按个数切开 |
| `ConfigPackageSheetSpecs` | 表单列和 sheet 的声明 | 不拆。声明式清单不是被淹没的流程 |
| 多入口服务，例如控制台 AI、回放、作业定义、任务分配 | 每个公共方法是一个用例 | 不因为私有方法多就拆成辅助类 |

`DefaultWorkflowNodeDispatchService` 只看到按等待节点和任务节点分支，没有把 31 个私有方法逐个归类。

创建运行的三处跨行选择已收成 `preferLaunchSnapshot`。优先级仍是容量快照、否则重放快照、否则当前定义。`buildExecutionSnapshot` 仍只在前两支都为空时调用。

## 全仓四类封装

同一批 3100 个生产 Java 文件。私有方法按“声明和左花括号在同一行、方法体没有 `if` / `for` / `while` / `try` / `switch`、只有一句、全文件最多再用一次”筛选。这样的方法有 111 个，分布在 91 个文件。这是候选，不是要删的名单。命名谓词和声明式登记没有收回。

留下的占多数。一句里表达业务事实的谓词留下，例如 `isExpectedUnavailableTaskClaim`、`isCancellable`、`isGatewayNode`。带方向或错误码的包装留下，例如 `previousWorkday`、`invalidCredentials`。`ConfigPackageSheetSpecs` 的 `build*Guides`、回放范围和调度漂移用 `Map.of` 登记策略，属于声明，不收回调用点。十几个字段的 `toResponse` / `builder` 是映射，不是一行转发。

方法体只有一句转调、没有额外决定的，才是薄壳。2026-11-10 已把这些纯转调收回调用点：`recordSuccessfulAudit`、`createOwnerOnlyFile`、`queryLag`、`doPoll`、`markRetryFailed`、`loadWorkflowRun`、两处 `readProtectedDispatchChannelSnapshots`、`sessionStateTtl`、`lastConfirmedNanosValue`、`clearStrictEncoderCache`、`finish`、`firstHeartbeat`、`markExecutedRemote`。`ExcelSheetWriter.writeHeaderBlock` 打开后还有分组表头和逐列写入，留下。`extractTenantId`、`resolveUsername`、`resolveWorkerPoolCode` 内部还有类型或空值分支，不是薄壳。

私有方法个数仍按上一节：69 个文件不少于 15 个。主类已经能顺着读的不拆。

应当再收成方法的入口，2026-11-10 已按原顺序拆开，原来的 `try` / `catch` 仍包住同一段调用：`GenerateStep.execute` 只保留载荷检查和失败收口，生成放进 `generateExport`；`WorkflowRunReplayPreviewService.submitForApproval` 分成已有操作、锁定预览令牌和插入待审批；`DefaultCompensationService.submit` 的插入前准备放进 `prepareBeforeInsert`，失败审计仍读到中途已经写下的命令号和追踪号。创建运行的三处跨行选择收成 `preferLaunchSnapshot`，容量、重放、定义三条只在轮到自己时取值，`buildExecutionSnapshot` 仍只在前两支都为空时调用。定向单测失败数为 0。`ImportIngressScanner` 的登记、清单和 metadata 仍留在扫描器里，因为它们和发现、索引共用租约与对象存储，这次没有整段搬走。

## 未纳入本轮

嵌套 `if`、三项以上复合条件、`try/catch` 边界、阶段字符串、700 行以上的类，维持路线图 §6.6 的裁定，不另开清零批次。

## 追加复核与整改方案（2026-11-10）

### 当前范围与证据

- 本次以 `main` 提交 `c0f7c92ed480269a914f38559060d2dbb7000e21` 和其上的未提交工作区改动为基线，保留在同一工作树的 `codex/control-flow-readability-review` 分支。盘点时有 33 个生产 Java 文件、6 个已修改文档和本审查文档；暂存区为空，前端工作区无改动。数量是本轮盘点快照，后续以实际 diff 为准。
- 上文 3100 个文件是历史生产扫描口径，不代表每个方法、判断和异常分支均已进行人工语义复核。当前 `rg --files -g '*.java' | rg '(^|/)src/main/java/' | wc -l` 得到 3134 个路径；其中 `batch-test-support` 23 个及 `examples` 11 个不属于这 3100 个生产文件。本轮对 33 个生产 Java 文件的现有差异逐文件初查，并重点跟踪文件等待、扫描、导出、回放、补偿和停机相关调用，其他调用路径尚需针对性验证，不声称全工程逐方法审完。
- 上文列出的转发壳和阶段抽取已有未提交实现，不能再次作为全部待整改项。上文 88 项及其他测试数是原记录的历史证据；该项记载的是最初追加复核时的状态；后续本工作区定向验证结果见下方“已有改动的顺序核对与定向验证”，不将历史测试数当作当前结果。

### 本次确认的规约问题与复核项

| 级别 | 位置和当前事实 | 建议与语义边界 |
|---|---|---|
| 已修正并定向验证 | `TriggerGracefulShutdown.status` 曾先读取排水状态，再读取调度器状态，与原求值顺序不同。 | 现已恢复先读取调度器、再读取排水字段；读取期间状态改变的定向测试通过。 |
| 已修正并定向验证 | `WorkflowRunReplayPreviewService.insertPendingReplayOperation` 曾有 8 个参数，超过 [参数规约](../coding-conventions.md#1-方法参数约束) 的 7 个上限。 | 现用内聚的提交身份承接操作键及摘要，降为 6 个参数；静态复核了事务、令牌锁定、消费与关联顺序，对应单测及调用顺序断言通过。 |
| 已调整并定向验证 | `FileArrivalSensorPolicy` 曾以含 7 个 `null` 的结果对象表达规格拒绝。 | 现按原顺序在入口返回规格错误，仅为有效规格构造查询参数；日期错误和查询异常翻译边界保持原样，单测及渠道匹配定向集成测试通过。 |
| 已对照并定向验证 | `ImportIngressScanner` 已把观察和围栏内提交分阶段，并用 `PreparedObject` 承接观察状态；登记布尔判断已恢复到原版本确认分支。 | 已对照清单重查、版本比较、租约和 `fenced` 内写入、进度顺序，并运行相应单测和 3 个真实数据库场景；不按私有方法数量拆类。 |
| 已对照并定向验证 | `GenerateStep.execute` 已提取 `generateExport` 并用可变 `GenerationAttempt` 向失败路径传递状态。 | 已对照原路径和运行相应生成、续跑测试；现有阶段具备明确职责，保留 checkpoint、生成文件及失败清理边界，不再只因方法行数继续拆。 |
| 可读性取舍 | `DefaultWorkflowRunLaunchService` 三处使用多个 Supplier 表达快照优先级；`AuditAspect` 内联成功审计后在调用点暴露两个 `null`；`ConsoleAiController` 将命名状态转换内联为字段赋值。 | 结合调用点评估表达清晰度，不以方法数量为目标；保留快照惰性求值及审计/结束状态行为。 |
| 可读性候选 | `DefaultCompensationService.prepareBeforeInsert` 仍有两层 traceId 选择。 | 如确有阅读收益再命名或展开，保持前两项均为空时才生成新值，以及异常审计读取中间状态的时机。 |
| 保留边界 | `CrossDayDependencyReconciler.scheduledReconcile` 对每个条目单独捕获异常；`ImportIngressScanner.readBatchManifest` 分别返回 JSON 无效与读取失败原因，`readAndVerifyManifest` 包含资源关闭。 | 前者保证单项故障隔离；后两者涉及错误分类、资源关闭和主异常/抑制异常。不能为了减少 `catch` 或嵌套直接合并、缩小或挪动边界。 |

调度器读取顺序变化已由源码确认并在工作区修正；其他事项主要是规约、可读性或待验证项，不把潜在风险报告为已复现故障。全工程复审深度与后续候选见下文。

### 分四批执行

1. **先稳定现有差异**：33 个生产 Java 差异已作静态初查，调度器读取顺序、8 参数和多空值状态已调整；继续补齐调用方和行为验证，列出已保持、需调整、待验证及不适用，不改变用户可观察行为。
2. **方法封装与调用**：只在复用、明确独立职责或显著降低阅读成本时提取；只转发且无边界价值的方法可合并；保留命名谓词、错误转换、事务代理入口和简单 getter。核对局部变量、调用次数、求值时机与副作用。
3. **长条件和嵌套调用**：从现有候选中按业务含义逐项判定，命名有独立含义的条件或值；保留短路、顺序、空值保护与懒加载。简单判断、流式链调用不机械拆分。
4. **异常与资源边界**：按实际失败路径检查 `try/catch` 和资源关闭；保留逐项隔离、错误码翻译、事务/锁边界、异常传播与回滚、资源关闭顺序及被抑制异常。确认可以安全合并时才简化，不按嵌套深度或 catch 数量验收。

每批维护有限的文件与方法清单、结论和未核查数量；不按命中数批量改写。每批结束对照修改前后的行为及适用代码规约，实际影响范围的测试通过后再进入下一批。

### 验证与参考

- 第一批优先复用 `TriggerGracefulShutdownTest`、`WorkflowRunReplayPreviewServiceTest`、`DefaultCompensationServiceTest`、`GenerateStepTest`、`GenerateStepCheckpointTest`、`FileArrivalSensorPolicyTest` 和 `ImportIngressScannerTest` / `ImportIngressScannerPaginationTest`；涉及数据库事务、扫描围栏和清单身份的调整，另选对应真实数据库或对象存储集成场景。仅审查不提前运行或宣称通过。
- 参考 [Google 代码审查指南](https://google.github.io/eng-practices/review/) 综合判断功能、复杂度、测试和可维护性；结合 [Oracle try-with-resources 文档](https://docs.oracle.com/javase/8/docs/technotes/guides/language/try-with-resources.html) 核对关闭资源及异常抑制的语义。项目现行规约优先于通用建议。

### 已有改动的顺序核对与定向验证（2026-11-10）

- 在同一工作树保留已有改动，对 33 个生产 Java 文件差异重新对照 `main`；恢复停机状态的原读取顺序，回放提交参数从 8 个收敛为 6 个，文件等待规格校验恢复直接返回，并将扫描登记条件放回对象存在和版本确认分支之后。异常范围、短路、数据库围栏和既有业务调用次序按原实现核对。静态核对不能证明所有运行时组合均已覆盖。
- 受影响模块运行 37 个定向测试类，共 481 项通过、0 失败、0 错误、0 跳过，日志 `/tmp/control-flow-existing-changes-tests-20261110.log`。命令：

```bash
./mvnw -o -ntp -pl batch-common,batch-console-api,batch-orchestrator,batch-trigger,batch-worker/core,batch-worker/dispatch,batch-worker/export,batch-worker/import,sdk/java/core -am '-Dtest=PrivateTempFilesTest,DefaultConsoleApprovalApplicationServiceTest,AuditAspectTenantFallbackTest,ConsoleAiControllerTest,ConsoleSessionRegistryTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,ConsoleApiExceptionHandlerTest,DataQualityCheckExecutorTest,DefaultCompensationServiceTest,DefaultRetryGovernanceServiceTest,LineageEvidenceServiceTest,WorkflowRunReplayPreviewServiceTest,WorkflowRunReplaySnapshotResolverTest,DefaultTaskCreationServiceTest,DefaultWorkflowRunLaunchServiceReplayTest,WorkflowNodeTerminalFinalizerTest,WorkflowRunExecutionSnapshotTest,OutboxPollSchedulerTest,CrossDayDependencyReconcilerTest,FileArrivalSensorPolicyTest,DefaultWorkerRegistryServiceTest,DispatchChannelSnapshotServiceTest,TriggerLaunchLagMonitorTest,TriggerGracefulShutdownTest,ActiveTaskLeaseRegistryTest,DispatchDeliveryMetricsTest,LocalOutboxDispatchSupportTest,ExportRegionResolverTest,GenerateStepTest,GenerateStepCheckpointTest,ExcelExportFormatTest,DatasetRuleEvaluatorControlRecordTest,DatasetRuleEvaluatorManifestRowCountTest,ImportIngressScannerTest,ImportIngressScannerPaginationTest,TaskDispatcherTest' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/control-flow-existing-changes-tests-20261110.log
```

- 使用隔离容器运行真实数据库定向场景：扫描进度/对象版本/清单失效 3 项、文件等待渠道匹配 2 项、补偿事务 2 项、回放快照 2 项，共 9 项通过、0 跳过；扫描持久化场景使用本地文件对象存储，不能作为 S3 验收。日志 `/tmp/control-flow-real-boundaries-20261110.log`。命令：

```bash
./mvnw -o -ntp -pl batch-orchestrator,batch-worker/import -am '-Dtest=ImportIngressScanPersistenceIntegrationTest#scan_shouldFinishApplySnapshot_beforeCleaningCycle+scan_shouldRecheckObjectVersion_beforeRegistration+scan_shouldWait_whenAuthoritativeManifestDisappearsDuringRead,FileArrivalPatternIntegrationTest#shouldMatchTrustedChannelWithinTenant+shouldMarkLegacyChannelFallback,CompensationFailureRecoveryIntegrationTest#shouldPersistFailureWithoutSelfBlocking_whenCallerHasTransaction+shouldCompleteCommand_whenCallerCommitsOrRollsBack,ReplayExecutionIdentityIntegrationTest#shouldUseSpecifiedDefinitionSnapshot_whenCurrentVersionIsNewer+shouldUseLatestDefinitionAtWorkflowStart_whenDefinitionChangesAfterAdmission' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/control-flow-real-boundaries-20261110.log
```

- `./mvnw -o -ntp -pl batch-common,batch-console-api,batch-orchestrator,batch-trigger,batch-worker/core,batch-worker/dispatch,batch-worker/export,batch-worker/import,sdk/java/core spotless:check -l /tmp/control-flow-spotless-20261110.log` 通过。上述是定向验证，不等同完整 CI、完整并发组合或生产环境验收。

## 全后端可读性复审（结构扫描及候选初审）

按用户补充的范围，复审生产 Java 3100 个文件：Console 1251、Orchestrator 789、Worker 455、Common 367、Trigger 135、Java SDK 94、security-scan 9；`batch-test-support` 23 和 `examples` 11 不计入这次生产代码分母。SDK 数量包含 `testkit` 5 个文件，属于随 SDK 维护的测试工具，单独注明其用途。

主入口应以清晰、精简的步骤表达本用例。私有方法较多时，除看数量外还要核对方法是否形成独立功能簇、依赖和生命周期是否内聚，以及抽取后主流程是否更容易理解；确有独立职责时评估同包专用辅助类。事务代理、自调用、租户上下文、异常处理、锁和资源所有权必须保持不变，不因数量机械拆类。JavaParser 按类和 record 直接声明的方法计数，初筛有 60 个类型的私有方法不少于 15 个（包含 SDK testkit 的 1 个类）；这是类级复核分母，不是 60 个待拆类型，也不同于早前按文件词法统计的 69 个候选。初次仅统计类的结果为 57，后补入 3 个 record。

| 范围 | 文件数 | 结构扫描 | 人工语义复核 |
|---|---:|---|---|
| Common、Trigger、security-scan | 511 | 511/511 解析成功；补充长 return 后窄口径定位 228 条语法候选，类别可重叠 | 70 处普通嵌套 if 已读方法或初始化器主体，27 个单句私有方法已看声明与同文件调用；158 条嵌套表达式仅完成表达式浏览，其余调用链仍需核对 |
| Orchestrator | 789 | 789/789 解析成功；585 条可重叠结构命中 | 32 个长方法及 254 处普通嵌套 if 已读方法主体，20 个类型按职责初判；40 个直接调用候选已读主体，单句包装 75 个看过声明与同文件调用；其余调用链仍需核对 |
| Console | 1251 | 1251/1251 解析成功；按该模块扫描口径有 580 条可重叠语法候选 | 28 个长方法、164 处普通嵌套 if、321 条调用与 21 条构造命中已读最近方法或初始化器主体，16 个类型职责簇初审；调用链与运行行为仍待核 |
| Worker、Java SDK | 549 | 549/549 解析成功；按该模块扫描口径有 285 条原始候选 | 303 处普通嵌套 if 已读方法主体；另有 285 条语句级候选，其中 24 项已进一步核对方法和调用位置，13 项可评估、11 项保留，其余调用链仍需核对 |

结构扫描可定位长条件、长方法、嵌套调用、嵌套异常和私有方法聚集，阈值只是入口；链式调用、简单 getter、lambda 内的调用和必要异常边界可能产生误报，语法扫描也可能漏掉复杂业务表达式。本轮发现长布尔 `return` 会被只检查 `if` 的扫描遗漏，已增加该类别作为复核线索；需排除 lambda 被计入外层返回值等误报。**3100/3100 解析成功不代表 3100 个文件已逐方法完成语义审查。**本报告记录扫描命中的逐类判定与可评估方案；未命中的方法和完整调用链不因扫描结束而视为已经人工审完。

### Common、Trigger 与 security-scan：首轮事实

511 个文件中，窄口径定位到 Common 137、Trigger 80、security-scan 11 条语法候选，其中调用/构造表达式共 158 条，已逐项浏览表达式；大量是简单访问器、日志、builder、链调用或有语义名称的包装，表达式阅读不是完整方法与调用链验证。其余候选及 158 条的上下文仍需进一步确认，数量不能直接作为整改项。以下是已打开调用上下文后的初步判断：

| 位置 | 初步结论与边界 |
|---|---|
| `TriggerSchedulerFacade.unregisterByWorkflowCode` / `pauseByWorkflowCode` | 两处先加载定义并立即嵌套传入删除方法，可评估各自使用 `TriggerDescriptor` 局部变量；必须仍在 `runManaged` 回调和异常边界中执行，且保持删除后停用的顺序。 |
| `TriggerRequestPersistence.persistAndForwardInTransaction` | 可评估先命名待插入的受理实体，再传入 mapper；实体构造必须仍在取得围栏、排除既有请求之后，且紧接数据库插入，后续重复处理与 outbox 次序不变。 |
| `TriggerCatchUpIdentity.requireMatchingIfCatchUp` | JSON 序列化结果直接传入解析，属于紧凑的转换链，暂保留；随后混合快照类型和业务身份判断可评估按含义分组，必须保留 `try` 范围、pattern 变量作用域及原短路和错误码。 |
| `DefaultTriggerService.verifyCatchUpSubmission` | 请求非空、幂等键及字段校验集中在同一长条件。可评估在空值守卫之后命名请求校验，但不能提前访问空请求、变更触发条件或字段读取次序。 |
| `TriggerScheduledRecoveryService.catchUpRejectedOccurrence` / `approvePendingCatchUp` | 入口同时组织校验、持久化及事务内分支，可评估围绕补跑申请和审批提炼清晰阶段或专用协作者；不得移动事务入口、行锁、CAS 与 outbox 写入顺序。 |
| `SelectSqlAstValidator.collectFunctionNames` | 内嵌 visitor 集中实现 SQL 子句补遍历；可评估专用函数名采集辅助类，需保留 visitor 递归、调用顺序及禁止函数识别语义，不能只因方法长拆分。 |
| `NamespacedObjectStore.copy` | 源、目标对象键分别经过 `physicalKey` 后交给存储；短转换表达清楚，暂保留，保持源后目标的求值与异常顺序。 |
| `ReplayCapabilityDeclaration.covers` | 长布尔返回值同时校验声明完整性、插件身份、外部效果、幂等与恢复协议，可评估按有业务含义的阶段表达；先检查自身声明、实际声明判空、实际声明有效性，再按原顺序比较，不能为分组提前读取 `actual` 或改变短路。 |
| `TriggerOutboxRelay` | 15 个私有方法涉及生命周期、轮询、发布回报和指标；`poll` / `pollLocked` 的主阶段已有命名。指标注册是否值得独立需先核对共享计数器和生命周期，不凭 15 个方法认定应拆。 |
| `ObjectStoreStartupCheck.run`、`FilesystemObjectStore.put` / `copy`、`QuartzMisfireRecoveryListener.scheduleRecovery` | 嵌套异常分别承担资源关闭、探针清理、原子移动失败映射及调度恢复；现阶段建议保留，不能仅为减少 `try/catch` 合并。 |

类职责方面，`SelectSqlAstValidator` 的函数遍历 visitor 可作为专用采集器候选：主方法只组织创建和触发遍历，采集器负责补走标量子句和函数名归一化；应由现有 SQL 校验测试保护遍历覆盖和拒绝结果。`TriggerScheduledRecoveryService` 可先评估补跑申请与人工审批各自的准备/事务内执行职责，事务模板仍由原入口控制，协作者必须在原事务上下文中按原顺序执行。`TriggerOutboxRelay` 的指标注册可评估为专用职责，但需保留计数器所有权、先注册指标后调度再审计的启动顺序；目前只列方案，不以类体大小作为实施依据。

### Common、Trigger 和 security-scan：条件与异常初判

8 条长 `if` 条件已读表达式及邻近分支：`FrozenDispatchChannelSnapshot.copyScalar` 是可接受标量类型，`JdbcExceptionSupport.isForeignKeyConstraintViolation` 依赖类型绑定和 SQL 状态顺序，`DnsResolveGuard.isBlockedIpv4` 两处是地址段分组，`ReadinessRecoveryService.recover` 是入口准入，`SecurityScanOrchestrator.run` 是扫描结果分支；这些不因条件项数机械抽取。`DefaultTriggerService.verifyCatchUpSubmission` 和 `TriggerCatchUpIdentity.requireMatchingIfCatchUp` 的分组候选见上表。13 条长布尔 return 已读表达式，其中 `ReplayCapabilityDeclaration.covers` 可按能力约束评估，其他主要是已命名的状态、异常或路由判断。

16 条嵌套 try 逐条看过局部语句；对象存储探针、加密流、文件存储、状态后端、临时文件、桶初始化、限流等待、Quartz、Kafka trace 和外部扫描进程各承担资源释放、结果翻译或中断边界，暂不建议为降低嵌套合并。局部审阅不替代逐项异常注入或完整调用链验证。

### Common、Trigger 和 security-scan：27 个单句私有方法

已逐个核查声明、同文件调用和方法引用；2 项可评估局部内联，25 项建议保留。不以语句数作为删除依据；动态反射及实施时的完整语义仍需核实。

| 声明位置 | 方法 | 判断 | 调用和语义边界 |
|---|---|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchSecurityProperties.java:122` | `isProductionProfile` | 可评估内联 | 唯一调用 validateSecuritySettings:49；替换为 BatchProfileSupport.isProductionProfile(environment)，仍在 environment 空判断之后、bypass 检查之前调用一次。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/RuntimeInfrastructureInspector.java:79` | `formatNodes` | 保留 | 57、63、68 行均复用节点序列化；保持节点顺序与逗号连接，抽象表达共同格式。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/enums/JobInstanceStatus.java:95` | `codes` | 保留 | 35、37、39、40 行四个集合共享状态筛选和不可变投影，不能重复展开。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/enums/WorkflowRunStatus.java:59` | `codes` | 保留 | 31、33 行共享状态筛选和不可变投影。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/file/ExportFileNameResolver.java:63` | `hasText` | 保留 | 28、68 行均使用 hasText，名称直接表达命名规则判断；改成更长的 EmptyChecks.isNotBlank 不带来明确阅读收益，保留两个调用处及原判空语义。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java:42` | `sanitizeTopicSegment` | 保留 | 39 行的具名 topic 片段规范化表达独立语义，避免正则混入路由规则。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/persistence/mybatis/AuditFieldsInterceptor.java:122` | `resolveFields` | 保留 | 89 行调用包含缓存查找、反射遍历和父类处理的独立功能；不是简单转发。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/rls/RlsClosedWorldChecker.java:233` | `inspectTable` | 保留 | 196 行调用五参数重载，语义为普通表；201、203 行显式六参数 true 针对 staging，保留重载可避免布尔标记侵入普通路径。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:137` | `findFirstSensitiveKey` | 保留 | 64、78 行复用每次新建身份集合并递归查找，保留每次调用独立遍历状态。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/sql/SelectSqlAstValidator.java:264` | `acceptSetOperationScalarClauses` | 保留 | 259 行针对 set operation 封装无 top/fetch 参数的访问；内联会在 visitor 中引入两个含义不明的 null。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendIdentity.java:115` | `normalized` | 保留 | 27、31、35、39 行复用 trim 与 Locale.ROOT 小写化，身份规则集中且求值顺序明确。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/MeteredObjectStore.java:30` | `timedRun` | 保留 | 56、61、66、71 行复用 void action 到有返回值计时模板的适配，保留计时和异常边界。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/S3ObjectStore.java:276` | `ensureBucket` | 保留 | 62 行调用把 S3 客户端、日志及配置组织为单独的桶准备步骤；直接暴露五参数收益不明确。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/S3ObjectStore.java:384` | `message` | 保留 | 367、380 行复用异常信息格式，统一字符串模板避免重复。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/Guard.java:86` | `detailKey` | 可评估内联 | 73、82 行使用已设置默认值的局部 code，可直接调用 code.detailKey()；保留异常构造参数求值位置与数量。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/IdGenerator.java:81` | `compactUtcNow` | 保留 | 40、56 行复用 UTC 时间格式化；单个编号与批次各调用一次，不能改变批次共享时间戳。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/TriggerApiAdmissionGuard.java:180` | `configuredMaxConcurrency` | 保留 | 37、74、149、166 行共同应用并发下限，保留初始化与动态读取时机。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java:555` | `runManaged` | 保留 | 93、106、124、136、149、157、162、170、182、198、214、225 行复用无返回值管理操作适配，保留 callManaged 行为及异常。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java:75` | `toUnscheduledStatusInfo` | 保留 | 70 行将定义映射到多字段未调度状态，是具名的响应构建，不应把 builder 展开在循环中。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerReconciler.java:203` | `logInvalidDefinition` | 保留 | 130、145、159 行复用带脱敏的同类错误日志，保留异常隔离与脱敏。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerReconciler.java:283` | `driftCheckers` | 保留 | 250 行以名称表达不同调度类型到对应检查器的注册；直接内联 Map 及方法引用可读性收益不足。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java:108` | `previousWorkday` | 保留 | 102 行表达向前工作日规则，包装固定方向 -1 与 before 错误上下文。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java:112` | `nextWorkday` | 保留 | 103 行表达向后工作日规则，包装固定方向 +1 与 after 错误上下文。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/DefaultLaunchAdapterService.java:161` | `nextFireCalculators` | 保留 | 147 行构建调度类型到计算策略的映射，包括 FIXED_RATE 的真实计算，不是薄壳。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerCatchUpIdentity.java:42` | `conflict` | 保留 | 30、34 行复用同一冲突错误码和国际化 key，捕获 BizException 的边界保留。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerRequestPolicy.java:44` | `forbidden` | 保留 | 27、35 行复用相同授权拒绝错误码和国际化 key。 |
| `security-scan/src/main/java/io/github/pinpols/batch/securityscan/SecurityScanApplication.java:24` | `printUsage` | 保留 | 10 行的多行帮助文本是具名独立职责，直接展开会淹没 main 主流程。 |


### Common 与 Trigger：5 个长方法的主体核查

这些方法已阅读完整主体并形成如下初判，但未追完全部被调用实现或重新运行各候选测试；长度只用于定位。

| 方法 | 拟议方向与必须保留的行为 |
|---|---|
| `SelectSqlAstValidator.collectFunctionNames` | 内嵌 visitor 独立负责函数采集和补遍历，可评估同包采集器；保持 super 调用前后顺序、子句补走和函数名归一化，不能漏检 SQL。 |
| `QuartzMisfireRecoveryListener.scheduleRecovery` | 可评估将已存在触发器的恢复与重绑定组织为具名阶段；必须保留现有检查、重查、合并原快照、注册及异常后二次查询的顺序，不改变并发恢复与 suppressed 异常。 |
| `TriggerRequestPersistence.persistAndForwardInTransaction` | 可评估按既有请求、准入和新请求命名阶段；保留租户上下文、各围栏、条件查询、请求插入、outbox 与 occurrence 记录顺序，以及调用方事务。 |
| `TriggerScheduledRecoveryService.catchUpRejectedOccurrence` | 校验/组装与事务内处理职责可分；保留先校验再读取定义、REQUIRES_NEW、围栏和 occurrence 锁、既有请求检查、插入和待审或 outbox 的顺序。 |
| `TriggerScheduledRecoveryService.approvePendingCatchUp` | 可评估审批目标准备与事务内执行阶段；保留事务内重读、校验、CAS、审批、outbox 和 occurrence 更新顺序，且维持当前失败回滚。 |

### 跨模块补充发现（逐项复核中）

| 位置 | 可评估的调整 | 必须保持 |
|---|---|---|
| `BatchDaySettleScheduler.hasExpectedOrUnresolvedWork` | 将一个布尔返回式中 8 类阻塞查询按原因具名或分阶段表达，使结算检查可以顺序阅读。 | 当前查询从左到右短路；前一类阻塞成立后，后续数据库查询不得执行，租户条件、事务和 SQL 顺序不变。 |
| `DefaultConsoleWorkerApplicationService.toResponse` | 该私有方法直接返回原参数，4 个 HTTP 调用点可移除这一层，保留原类型的局部结果。 | HTTP 取得响应、返回空值或抛异常，以及后续事件发布的先后次序不变。 |
| `CrossDayDependencyResolver.findSpecificVersion` | 只在特定版本分支调用一次且纯转发，可评估将查询直接放回该分支；另一方法 `findLatestModeVersion` 被多处分支复用，应保留。 | 特定版本号为空时仍直接返回，查询只能在原分支执行，实参及数据库调用次序不变。 |
| `DatabaseQuotaRuntimeStateService.refreshState` 的四参数重载 | 当前源码中该私有重载只转发五参数版本，四个实际调用均直接传入五个参数；可进一步核对真实引用后删除未使用重载。 | 保留五参数实现和现有 `persist` 参数；按重载签名核对方法引用，不能仅凭同名调用次数判断。 |
| `Guard.detailKey` | 两处仅把 `ResultCode` 传入私有方法并返回同一 `code.detailKey()`，可评估直接在原异常构造位置调用，减少无独立含义的包装。 | 仍只在原失败分支调用一次，保持异常参数从左到右的求值和错误码、消息不变。 |
| `ImportScannerProperties.isManifestBindingsValid` | 将前缀、清单后缀、模板及重复绑定校验按业务条件梳理。 | 空值保护在先，`seen.add` 有副作用，仍只能在原先各条件均满足时执行。 |
| `TenantConfigPackageExcelApplyService` 监控配置写入 | 七个配置 setter 中的默认值与解析可分别命名承接，降低同一行内嵌表达式的阅读成本。 | 维持每个字段的解析、默认值选择和 setter 次序；Excel 应用整体仍在原调用方事务内。 |
| `DefaultConsoleTenantConfigCopyService` | 评估把配置复制的 diff/影响分析组织为包内专用协作者，让入口保留读取、比较、返回的步骤。 | 租户 RLS、读取顺序、差异口径及异常边界必须不变；先核对依赖是否能形成内聚职责。 |

以上是方案候选，不是已确认的业务缺陷，也不是逐项语义复核已完成的清单。所有语义和调用次序必须与当前实现一致，确需实施时按所属类选择定向测试。

### Console：私有方法集中的类级初审

以下 16 类已查看公共入口、私有方法清单和至少一个代表流程；这是类职责初分，不等于逐个方法、调用方及事务已完成验证。

| 类 | 主流程与辅助职责评估 |
|---|---|
| `DefaultConsoleTenantConfigCopyService` | 优先评估 diff 与影响计算作为包内专用协作者；租户读取、RLS 上下文及初始化顺序留在原边界。 |
| `DefaultConsoleAiApplicationService` | 评估模型调用、流式消费和提供商回退的功能簇；先明确共享线程池、超时、SSE、成本与审计的所有权。 |
| `TenantConfigPackageExcelApplyService` | `applyAll` 已清晰排列各 sheet 写入与缓存失效；不因方法多拆类，继续保持外层导入事务及执行顺序。 |
| `ConsoleClusterDiagnosticService` | 多个诊断入口、缓存与结果映射；入口职责基本分开，先保留并核对缓存键与租户隔离。 |
| `ConsoleOpsQueryService` | 审计、重试和告警等多入口与响应映射；只有映射形成稳定共享职责时才考虑专用投影协作者。 |
| `DefaultConsoleFileTemplateApplicationService` | 评估导入/导出映射草稿构造的独立性，保留租户守卫、默认值及模板写入顺序。 |
| `ConsoleFileQueryService` | 评估实时进度拼装的共享职责，保留授权先于取数、分页和实时回退。 |
| `DefaultConsoleJobDefinitionApplicationService` | 字段、依赖和监控策略优先使用已有 writer/support，不跨越现有事务与事件顺序拆分。 |
| `ConsoleUserBatchProvisioningService` | 评估表格解析/预览是否有独立归属，保留脱敏、预览令牌及执行幂等边界。 |
| `DefaultConsoleConfigApplicationService` | 配置发布及密钥版本是多个公共用例，先保留并检查各自事务、权限与审计。 |
| `DefaultTenantConfigPackageExcelService` | 模板、校验和写入已有 validator、writer、apply 分工，优先保持当前协调职责与事务。 |
| `TenantJobDefinitionApplySupport` | 已是作业导入辅助类；依赖和监控是内部阶段，保留外层事务及写入先后。 |
| `ConfigPackageExcelValidator` | 复用已有 row 与拓扑校验；可评估监控配置校验是否内聚，保持 issue 顺序及租户查询。 |
| `ConfigPackageSampleDataFactory` | 不同方法构造不同示例，先保留；注意 sheet 顺序和模板契约。 |
| `ConfigPackageSheetSpecs` | 声明 sheet、列及规则，不以方法个数拆分；保留列顺序、多语言和生成契约。 |
| `DefaultWorkflowDefinitionService` | 优先复用已有写入支持，保留定义版本、节点约束、事务和刷新事件次序。 |

### Console：28 个长方法的主体核查


28 项均已完整阅读方法主体。这是静态可读性与控制流核对，不等于调用方、数据库事务和运行行为已验证。

| # | 方法 | 静态判断与约束 |
|---:|---|---|
| 1 | `DefaultConsoleQueryApplicationService.buildTimeline:280` | 多来源投影后统一排序，主体按来源顺序排列；保留映射阶段，不因 151 行强制拆。若后续按来源抽取，须保持空时间过滤和 comparator 三键、来源先后。 |
| 2 | `ConsoleClusterDiagnosticService.instanceDiagnosis:207` | 授权、汇总查询、构造诊断项顺序明确；诊断项产生可评估命名独立簇，但参数较多且只用一次，先不强拆；查询与 finding 列表顺序不变。 |
| 3 | `DefaultConsoleApprovalApplicationService.approve:73` | 审批状态推进后按 action 分派，后续标记执行；`switch` 可评估抽成有业务含义的审批执行阶段，须保持 PENDING/APPROVED 重试及 side-effect 成功后才标记顺序。 |
| 4 | `ConsoleSecurityConfiguration.consoleSecurityFilterChain:172` | 声明式 Spring Security 链，路径匹配与 filter 顺序就是安全策略，保留；仅按行数拆会降低可读性。 |
| 5 | `ConsoleAiAttachmentService.upload:45` | 包含配额锁内预留、对象写入和第二事务标记；若抽取应保留两个事务及对象写入在两者中间，不能把上传/额度检查前移或合并事务。 |
| 6 | `DefaultConsoleAiApplicationService.executeChat:213` | 准入、上下文、预算、模型调用与降级已按阶段排列且已有协作者；继续拆时不得改变 ID 生成次数、费用预留/结算与失败审计顺序，暂不按行数拆。 |
| 7 | `DefaultConsoleAiApplicationService.completeApprovedChat:511` | 计费结算、回答组装、完成会话及审计是可识别阶段；可评估把 response 装配收为职责方法，需保持费用→指标→会话→审计顺序与异常传播。 |
| 8 | `DefaultConsoleJobApprovalService.catchUpBatchDay:126` | 包含准备冻结目标与逐项发送，阶段可命名；必须保留 `Propagation.NEVER`、幂等复查、先冻结再 start/submit/accept、失败标 UNKNOWN 顺序。 |
| 9 | `DefaultConsoleJobApprovalService.continueBatchDay:242` | 恢复逻辑与首次执行部分相似，但手动审批分支不同；先验证契约再决定是否复用，保留所有待发项先预校验、再逐项发送与失败标 UNKNOWN。 |
| 10 | `DefaultConsoleJobDefinitionApplicationService.create:89` | 实体与监控策略构造可评估具名阶段，保留当前事务及校验→插入→依赖→失效→创建托管 Workflow→监控策略顺序。 |
| 11 | `DefaultConsoleJobDefinitionApplicationService.copyWithOverrides:509` | 复制、默认值覆盖与监控策略可分阶段描述；不能提前覆盖、移动 mapper 操作或丢失三项历史字段。 |
| 12 | `TerminalNotificationDeliveryService.deliver:94` | claim、目标选择、发送、回报各有租户事务/幂等边界；可评估命名发送分支，但应保留确认位置、未知结果策略、重试时钟与 fenced 完成。 |
| 13 | `TwilioSmsProvider.send:69` | 配置前置校验、按收件人发送和失败即时返回，属于直接主流程；可评估逐收件人函数但不能改变跳过空号码、失败即返回和恢复中断标志。 |
| 14 | `WebhookDeliveryRelay.retryOne:273` | claim、读取订阅、反序列化、发送和重试/放弃分支直观，暂不以行数拆；保留状态写入、重试次数和失败分类。 |
| 15 | `ConsoleAuthenticationFilter.doFilterInternal:95` | 多鉴权入口可评估职责提取，保留 finally 清理和每分支 `filterChain` 一次。 |
| 16 | `WorkflowExecutionGraphAssembler.assemble:44` | 可评估快照校验、节点投影、边投影，保留次序与早退。 |
| 17 | `WorkflowNodeStartEndCodeRule.validate:35` | 行统计与组校验可评估具名阶段，保持 LinkedHashMap 及问题次序。 |
| 18 | `WorkflowDagValidator.validate:62` | 有序阶段与错误优先级明确，保留。 |
| 19 | `TenantConfigPackageExcelApplyService.applyWorkflows:871` | 可评估定义写入、图和快照具名阶段，保留 DB/CAS→节点→边→失效→快照与错误。 |
| 20 | `TenantWorkflowDefinitionApplySupport.upsertWorkflowDefinition:78` | 可评估参数、图、执行校验、版本和快照分段，保持调用方事务。 |
| 21 | `ConfigPackageExcelValidator.addJobDependencyIssues:946` | 可区分逐行引用和全图环检测，保持 issue 顺序与查询短路。 |
| 22 | `ConfigPackageSampleDataFactory.putCommonFoundation:157` | 声明式 fixture 保留。 |
| 23–24 | `ConfigPackageSheetSpecs.buildJobGuides:1124`、`buildFileTemplateGuides:1341` | 纯声明列表，保留。 |
| 25 | `WorkflowGraphTopologyValidator.validateWorkflowGraph:81` | 可评估索引、起止/环、可达性、DSL 引用阶段或内聚校验协作者；保留 issue 增加次序与输入迭代。 |
| 26 | `DefaultWorkflowDefinitionService.fullUpdate:182` | 可评估校验、CAS、图替换、版本快照阶段；保留事务入口和写入、缓存事件的次序。 |
| 27 | `ConsoleRateLimitFilter.doFilterInternal:64` | 四种有序限流分支可评估按场景封装，但必须保持重叠路径、优先级、响应与 `filterChain` 调用。 |
| 28 | `ConsoleIdempotencyInterceptor.preHandle:137` | 现有具名协作者与早退已表达阶段，幂等状态存取顺序清晰，保留。 |

### Worker 和 Java SDK：23 个职责聚集类型初审


以每个类型自身声明的私有方法 >=15 为筛选，构造器不计，重载各计一次，不叠加嵌套类方法；这是辅助定位，不是拆类条件。下表列出公共入口（无 public 的标为包内静态入口）、基于方法名和相关主流程的职责簇及初步处置；尚未对 23 类每个私有方法逐项追调用，辅助类建议只作为后续语义复核候选。涉及事务、共享状态和资源生命周期的拆分必须保持原边界。

| 类型路径 | 行 | 私有方法数 | 公共入口 | 职责簇 | 建议及边界 |
|---|---:|---:|---|---|---|
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java` | 90 | 15 | `taskType,capability,isSafeToReplay,execute` | 请求解析与白名单、认证、HTTP 重试/单次发送 | 入口编排与安全验证属于同一任务执行边界；保留现有类，已有 guardedDns 和客户端等独立职责。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/storedproc/StoredProcTaskExecutor.java` | 74 | 17 | `taskType,capability,execute` | 参数解析、目标权限、过程调用、游标输出 | 校验和执行必须使用同一个连接和 schema；保留本类，游标/授权负责资源边界。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java` | 71 | 16 | `claim,claimBatch,registerClaimedBatch,releaseUnexecutedClaims,execute` | 认领与批次登记、执行开始确认、超时执行、回报 | 多阶段关联租约和 attempt 生命周期；先检查主 execute，现有 timeout/report 不因数量拆类。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java` | 56 | 28 | `claim,claimBatch,renewLease,markNonIdempotentExecutionStarted,renewLeasesBatch,report,submitReportOverHttp` | claim、renew、report HTTP 调用和失败重试 | 客户端协议职责可区分，但涉及批次回退和 outbox 交接；已有 TaskReportBatcher，先评估重复边界，不预设新类。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java` | 62 | 15 | `execute` | 流水线上下文、成功/失败收尾、执行与补偿 | 模板及钩子形成完整状态推进边界；保持主编排和 MDC finally。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java` | 49 | 19 | `包内静态入口` | NAS/OSS 传输、临时文件发布、渠道探测及路径解析 | 多协议功能簇可供后续评估，但共享发布身份、失败结果和对象存储；暂不强拆。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java` | 45 | 15 | `supports,dispatch` | 连接配置、主机校验、上传/发布、断链清理 | 保留同一 SFTP 生命周期内顺序和结果未知语义；已存在连接/目标上下文。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java` | 60 | 25 | `stage,execute` | 数据插件读取、生成、checkpoint 与文件收尾 | 当前工作区已有阶段抽取；先核验入口清晰度、文件保留和 checkpoint 边界，不按数量再抽类。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java` | 31 | 16 | `stage,execute` | 导出 payload、模板快照、名称/业务日/分区 | 主要是准备阶段及配置解读，暂保留；模板错误分类与读取顺序不可变。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java` | 37 | 17 | `stage,execute` | 加密、临时对象上传、校验、发布及重用 | 外部存储发布、结果确认和临时资源同属一条链，保留现状并注意失败结果未知。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/jdbc/JdbcMappedImportSpec.java` | 27 | 20 | `parse,validateIdentifiers` | 配置解析、列映射推导和标识/约束校验 | 纯规格解析为单一领域对象，保留；标识校验不可延后。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java` | 51 | 24 | `id,idempotencyCapability,loadChunk,isPartitionReplaceCopy,isPartitionStageSwapCopy,preparePartitionReplace,finishPartitionStageSwap` | 列映射和 SQL、COPY、分区替换、阶段交换 | COPY 与 RLS/事务绑定同一连接；可评估 SQL 构建的纯计算独立职责，写事务和连接必须留在调用方。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java` | 70 | 22 | `run,run,run,run` | 配置解析、压缩/解压、加解密、校验和字符集转换 | 规则解析和各变换是功能簇，但均服务一个 pipeline；如有复用或清晰独立变更原因再评估拆包，不按数量拆。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 83 | 57 | `scheduledScan,scan` | 扫描租约、发现/索引/应用/清理、清单和文件登记 | 扫描和 fenced 进度留主类；可评估只负责清单解析/匹配的同包 helper，但对象存储复核、租约及数据库时序不能跨边界移动。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java` | 63 | 33 | `stage,execute` | 插件装载、分块提交、checkpoint 续跑、文件清理 | 装载与 checkpoint/幂等耦合；可先评估已有阶段方法清晰度，若单独协作者不应改变事务/提交及位点先后。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java` | 63 | 20 | `stage,execute` | 载荷/尾记录、格式解析、分片、临时文件与状态 | 主 execute 偏长可评估已命名解析/状态阶段；仍须保持业务异常分类、staging 生命周期及 finally 清理。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java` | 54 | 17 | `stage,execute` | 模板、归属校验、流式/字节预处理、解密和字符集 | objectSource 和 pipeline 已承担专责；保留入口编排，路径分支与租户检查次序不变。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ReceiveStep.java` | 60 | 16 | `stage,execute` | 输入 payload、模板快照、文件登记、元数据回填 | 围绕 receive 一个阶段；先审 execute 是否已经清晰，保留数据库绑定次序，不按私有数量新建类。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java` | 53 | 17 | `stage,execute` | 校验会话、数据集/控制总量、分块逐行验证及结果落地 | 可评估复用相同失败构造，但必须保留坏行隔离、计数、阈值与资源关闭；先留阶段主类。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java` | 54 | 23 | `implCode,idempotencyCapability,prepare,compute,validate,commit,feedback` | prepare/compute/validate/commit/feedback 与 staging generation | 多入口显式阶段和 direct/staged 可分层评估；暂保留接口类，事务与 generation fencing 必须保持。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java` | 22 | 16 | `parse,validateIdentifiers` | SQL 转换配置解析、映射、策略与标识校验 | 固定配置解析职责内聚，保留；不为解析子方法数量拆类。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java` | 66 | 18 | `inFlightTaskIds,inFlightCount,applyPlatformDirective,platformState,platformAcceptsNewTasks,onMessage,submittedCount,markCancelled,progressSnapshot,partitionInvocation,stop,stop,isDraining,isFatal` | claim、容量、执行开始、执行、报告、MDC 和停机 | CLAIM/REPORT retry 已由 TaskDispatcherRetryCoordinator 管理；主类保护许可释放和 offset 决策，不机械拆类。 |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java` | 59 | 19 | `start,configBuilder,configFor,dispatch,requeue,awaitReport,baseUrl,kafkaBootstrap,topic,registrations,claims,nonIdempotentStarts,reports,close` | 测试环境生命周期、Kafka 派单、HTTP 伪平台、认领与结果记录 | 可评估把 claim/report 共享状态与 HTTP 编解码拆为 testkit 包内协作者，但先核对并发容器的原子更新、broker/server 生命周期和现有测试契约；目前没有必拆结论。 |


### Worker 和 Java SDK：语句及阶段候选

285 条语法命中初判中，原 24 个建议项进一步核对方法及调用位置后，13 项保留为待评估方案，11 项建议保留现状；其余 261 项为语句级暂留，未逐一完成调用链验证。以下均非已经实施的修改。


对原先 24 条建议项，已核对所在方法及实际调用位置；13 条保留为有具体阅读收益的整改建议，11 条保持原状。这是本组候选的源码复核，未针对这些尚未实施的方案运行测试，也未确认上述位置存在业务 Bug。下表所示行号仍为扫描时快照。

| 文件 | 行 | 决定 | 调用位置、收益和原行为边界 |
|---|---:|---|---|
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java` | 470 | 建议整改 | applyAuth 由 parseInvocation 调用；在 BASIC 分支给配置中的原始密码值具名后再解析环境引用。保留默认空串、校验及异常映射顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java` | 477 | 建议整改 | 同一 applyAuth 的 BEARER 分支给配置 token 具名；解析仍只调用一次，保留空值检查及异常顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerBatchExecutionCoordinator.java` | 128 | 建议整改 | processBatchGroups 给将写 DLQ 的安全消息具名，再传回调；仅在非瞬时错误且 payload 存在时求值，不改变回调次数及 allDlq。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/DispatchReceiptPollScheduler.java` | 213 | 建议整改 | poll 逐项调用 pollOne；将三种 ACK 判定归入具名谓词，按 acknowledged、status、receipt_status 顺序短路；保留 bodyStream 在回写前关闭、Response 在回写后关闭及失败计数。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java` | 444 | 建议整改 | probeOne 唯一调用；单纯拆装三个结果字段后转调，可直接在原位置调用 recordDispatchOutcome，保持 getter 顺序及成功/失败指标更新顺序。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java` | 39 | 保留 | resolve 中 snapshots 已用于读取；这里是空映射与重放非法快照的分支区分，局部转换和判空表达清楚，提取变量收益不明确。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStep.java` | 58 | 保留 | execute 已按 payload、文件、渠道、上下文绑定顺序使用提前返回；另造阶段结果对象会增加状态传递，保留现有一次 try 的错误翻译。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java` | 135 | 建议整改 | sha256Hex 唯一调用 digestToHex；可在原 try 内直接 HexFormat 链式转换，保留计算一次及异常翻译。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java` | 137 | 保留 | generateExport 已按插件、数据、checkpoint、格式策略和完成登记直线组织；进一步拆分需要传递多个可变状态，收益未明确，维持外层 catch 和残文件清理。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 343 | 保留 | parseDelimitedColumns 已将结果命名 source；这里是同一字段的三个配置别名和一次文本转换，额外中间变量不明显提升可读性。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 353 | 保留 | width 已明确承接 fixedWidth 模式下的别名选择和转换；维持三元短路及 firstNonNull 全部实参求值。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 408 | 保留 | quotePolicy 已命名，三个别名紧邻声明，属于单字段声明式配置转换；保持取值顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/AbstractExportFormat.java` | 412 | 保留 | headerRows 已命名，配置别名及默认值集中一处；保留全部参数求值和默认值语义。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/config/ImportScannerProperties.java` | 63 | 建议整改 | isManifestBindingsValid 被配置校验调用；按前缀、清单后缀/模板和唯一性分组为顺序 guard，保持 null 短路，seen.add 只在前置检查通过后执行。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/RecordRuleEvaluator.java` | 87 | 建议整改 | validateNullFields 由记录校验调用；给必填字段列表具名后再遍历，保持 enabled 检查先于字段计算、两个 map.get 的原求值顺序及第一个错误返回。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/jdbc/JdbcMappedImportSpec.java` | 276 | 保留 | mergeMappings 是显式字段追加的线性 stream 链；拆成中间列表只增加局部名称，保持当前字段顺序和类型回填。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 778 | 保留 | validateOwnership 同一绑定身份断言的字段比较已与唯一异常紧邻；分散为多个变量会弱化短路并可能改变 scanStorageIdentity 调用时机。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 791 | 保留 | validateOwnership 的绑定准入条件和去重位于同一判断，seen.add 明确在最后；引入提前求值的子判断有风险，未发现更简洁的不变量表达。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 324 | 建议整改 | persistIndexedObject 在 fence 内仅版本变化分支调用 observe；给已验证对象和摘要具名，保持 hash、observe、随后的 saveProgress 次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 330 | 建议整改 | persistIndexedObject 先更新 DB 再结合 reason 写 AtomicBoolean；拆成具名更新结果，保留 setManifestWaitReason 先于 reason 判空及同一 fenced 事务。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 528 | 建议整改 | commitPreparedObject 在权威清单仍存在分支给对象摘要具名；计算仍仅发生在该分支和同一围栏内。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java` | 541 | 建议整改 | commitPreparedObject 在版本变化分支给对象摘要具名；不改变 observe 的批次标记、分支顺序和 fenced 进度保存。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java` | 117 | 保留 | execute 已分别调用 parsePayloads、applyPartitionFilter 和状态更新；再拆需搬运可变 stagingFile 并削弱 catch/finally 清理的可见性。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java` | 281 | 建议整改 | processValidationBatch 在两处将 ChunkProcessResult 转为相同失败结果；可用具名方法共享该映射，在原资源 try 内及计数更新后调用，不移动 writer 生命周期。 |


### Console：32 处长条件的逐组判定


下列是每处条件和相邻分支的静态判断，不是全调用链或运行证明。5 处候选均须保留短路与异常优先级，不按条件数量强制抽取。

- 5 处可评估按业务事实分组：`BatchDayCatchUpReconciliation.java:109,212`（冻结请求及载荷一致性）；`DefaultConsoleJobApprovalService.java:267`（批次身份及策略）；`WorkflowExecutionGraphAssembler.java:71`（快照身份及图数据）；`ConsoleOidcAuthenticationSuccessHandler.java:40`（配置、认证类型、issuer 与 subject）。OIDC 的模式变量作用域与 null 检查、快照字段的惰性访问必须保持。
- 10 处入口参数校验建议保留：`ConsoleAiConversationService.java:252`、`PipelineProgressQueryService.java:26`、`ConsoleBatchDayReplayApprovalService.java:35`、`BatchDayCatchUpOperationStore.java:47,104,145,215`、`OrchestratorApprovalClient.java:112,125`、`ConsoleExcelStyles.java:566`；这些前置校验与后续解引用有依赖关系。
- 5 处协议或简单值判断建议保留：`ConsoleAiImageNormalizer.java:82,87,91` 的文件头、`DefaultConsoleFileDownloadApplicationService.java:162` 的 CSV 转义、`ConsoleAiKnowledgeBase.java:176` 的向量合法性；文件头长度检查必须先于字节读取。
- 6 处类型/格式准入建议保留：`ConsoleFileRecordDetailResponse.java:41`、`SubscriptionRuleDispatchPolicy.java:49`、`TenantConfigReferenceResolver.java:69`、`ConsoleAtomicTaskConfigService.java:181`、`FixedWidthMappingValidation.java:18,33`；类型模式变量和不同格式的执行条件不得改变。
- 6 处状态或目标一致性检查建议保留：`BatchDayCatchUpOperationStore.java:319`、`BatchDayCatchUpProgress.java:28`、`BatchDayCatchUpReconciliation.java:97,128,163,176`；先前空值保护和确认来源检查不前移。


### Console：长 boolean return 的复核


本轮对 `return` 表达式内含至少 3 个 `&&` / `||` 的 12 处机器命中逐项查看局部语境。其中 `JobExecutionQueryService.java:107` 是 stream lambda 内的条件被计入外层 return，不属于长 boolean return；其他 11 项包括 HTTP 方法集合、空值保护后的费率有效性、图像与边的值相等、可重试异常类型、审计字段脱敏、流订阅匹配、shell 参数、字符白名单和 PostgreSQL 约束匹配。其职责已由方法名或相等比较表达，暂不建议仅凭运算符数拆分；尤其不可把费率、订阅和 shell 参数中的后续取值提前出短路路径。尚未遍历所有调用方，这些返回表达式未逐项运行行为验证。

### Console：嵌套 try 的保留依据

19 项只作局部上下文阅读，未模拟异常：

- `DefaultConsoleKafkaLagQueryService.java:78`：单消费者组错误隔离，`InterruptedException` 恢复中断并停止批次；外层是管理员资源范围。
- `ConsoleProgressSnapshotPublisher.java:105,128`、`WorkflowExecutionPushService.java:106,134`：注册失败清理时保留原异常，或采样失败后尽力发错误事件；清理和通知失败不能替代原失败。
- `ConsoleAiImageNormalizer.java:37,60`：读取器和图形资源分别释放；先读完整 `finally` 再判断能否局部抽取。
- `DefaultConsoleAiApplicationService.java:652`、`ConsoleAiController.java:156,166`：提供商回退及 SSE 取消/失败上报边界；异步 lambda 的语法嵌套不一定属于同一执行栈。
- `ConsoleFileDownloadController.java:64`、`DefaultConsoleConfigSyncApplicationService.java:168`：关闭或记录审计失败作为 suppressed 附加原失败。
- `ConsoleRealtimeEventHub.java:273`：关闭订阅时 emitter 已关闭的清理例外不应改变调用方行为。
- `ConsoleAuthenticationFilter.java:138,172`、`ConsoleSessionRegistry.java:103,136`：鉴权不同入口、会话版本更新、TTL、解析和存储故障属于不同边界。
- `ConsoleSingleSheetExcelImportSupport.java:174`、`JdbcTenantDatabaseContext.java:34`：内层关闭单 sheet 输入流或 JDBC statement，外层负责工作簿、连接和异常翻译；不可直接合并资源作用域。

上述不是全量语义证明；调整前应依据各边界现有用例和真实依赖测试核对原异常、资源关闭顺序及事务/RLS 上下文。



### Orchestrator：长 if：16 条均已阅读

| 位置 | 决定 | 约束 |
|---|---|---|
| `CountContinuityOutboxService:320` | 保留 | 多个整数类型判断表达同一接纳范围。 |
| `FileGroupExpectationService:96` | 保留 | 入口字段校验同一错误出口；`workflowDefinitionId` 判空先于拆箱。 |
| `DefaultFileGovernanceService:785` | 候选 | 上传文件组元数据约束较密，考虑命名校验阶段；必须先判空/空集合再读取 size 或 trim。 |
| `WorkflowRunReplayPreviewService:857` | 保留 | pattern 变量 `id` 的作用域与短路依赖必须保持。 |
| `DefaultTaskAssignmentService:253` | 保留 | ID/version 判空与拆箱次序直接可见。 |
| `DefaultWorkflowRunLaunchService:250` | 保留 | 审批身份和状态校验组成单一授权边界，`operation` 判空必须先于读取。 |
| `WorkflowLaunchWaitService:117` | 候选 | 快照 envelope 与 claim 多字段身份一致性可命名为判断；保持 null guard、比较次序和外层异常翻译。 |
| `WorkflowLaunchWaitService:134` | 候选 | 原始请求与 claim 身份匹配可独立命名；保持上一步 `snapshot.validate()` 之后执行。 |
| `WorkflowLaunchWaitSnapshot:62` | 候选 | 固定快照字段完整性可命名；保留 `workflowVersion` 判空到数值比较的短路。 |
| `WorkflowLaunchWaitSnapshot:71` | 候选 | 快照请求身份可作为独立校验；先判空再访问 original。 |
| `WorkflowRunExecutionSnapshot:656` | 保留 | 两组 pattern 变量受条件作用域约束。 |
| `PipelineStageProgressCache:142` | 保留 | 同一过滤步骤包含动态心跳时间读取，暂不提前计算或移动。 |
| `WaitingPartitionDispatchScheduler:204` | 候选 | 将游标有变化的左侧纯条件具名；CAS 数据库更新仍只能在左侧为真后执行，更新失败后仍原样返回。 |
| `JobMonitoringScheduler:130` | 保留 | 实例 ID 与触发请求 ID 至少一项存在的局部准入条件。 |
| `DispatchChannelSnapshotService:148` | 保留 | pattern 变量及密钥/渠道匹配顺序必须保持。 |
| `JobDependencyWaitingLaunchService:486` | 保留 | pattern 变量、非空及允许集合验证为同一解析边界。 |

候选是可读性建议，不是确认缺陷；如命名只增加跳转则保留原样。

### Orchestrator：嵌套 try/catch：9 处均已阅读并建议保留

| 位置 | 独立边界 |
|---|---|
| `DefaultRetryGovernanceService:254` | 每条重试失败后，记录 FAILED 自身失败单独隔离；不能让失败记录影响下一条。 |
| `DefaultRetryGovernanceService:576` | 内层 savepoint 只回滚业务重排队，外层保证 MDC 清理；不能合并。 |
| `FileGovernanceStorageMaintenance:164` | 对象不存在才继续清理，其他存储异常走外层失败处理。 |
| `PartitionLeaseReclaimScheduler:100` | 单分区故障隔离与外层运行标志 finally 各有职责。 |
| `PartitionLeaseReclaimScheduler:153` | 同上，孤儿扫描独立循环与标志释放。 |
| `BatchDayReplayDispatcher:125` | 条目派发失败与写失败状态再次失败分开记录。 |
| `WaitingPartitionDispatchScheduler:95` | 单条候选失败、每条 MDC 清理、整轮 tick cache 清理不可混用。 |
| `JobMonitoringScheduler:83` | 查询计时及失败信号与整轮计时各自 finally，捕获范围不同。 |
| `S3DryRunObjectStorageProbe:71` | bucket 不存在与其他探测故障产生不同结果，外层还覆盖结果构造。 |

### Orchestrator：类级职责簇及事务归属

已查看入口和调用关系的 5 类，以下均为可选设计方向，尚非授权实施：

1. `DefaultCompensationService`（44 私有）：`submit` 是命令、失败审计和业务事务编排；`replayDeadLetter` → `replayVerifiedUnknownExternalOutcome`（875→913）形成外部结果核验后重放簇，可评估同包协作者承接。`requiresNew` 创建命令、`required` 执行业务、失败审计独立提交/外层回滚后执行的边界必须留在原事务控制点，不引入自调用代理事务。
2. `WorkflowRunReplayPreviewService`（24 私有）：`preview`、`submitForApproval` 在 `REPEATABLE_READ` 中调用 `preparePreview` → `previewNode` → `selectedPlugin`（105/157→454→580→623），节点预览和配置冻结可评估专用协作者。令牌加锁、消费、链接和事务入口保留现有顺序和代理边界；不得把密钥快照暴露到其他层。
3. `DefaultWorkflowNodeDispatchService`（29 私有）：`dispatchNode` 的跨日依赖（176→344）与任务节点派发（208→591）是可进一步考察的职责簇；已有 `CrossDayDependencyResolver` 负责解析，勿复制。运行行锁、租户上下文和 `@Transactional` 外层顺序保持。
4. `BatchDayOpenService`（18 私有）：公开 `openCalendar` 已按打开缺口→收敛依赖等待表达主线；依赖等待检查/记录/恢复（286–460）可作为后续独立簇评估，但当前调用方事务、日历游标和灾难日优先级必须保持，不能仅因私有方法多拆类。
5. `WorkflowGraphValidator`（22 私有）：`validate` 已按节点索引、拓扑、传感器、输出、日历依次调用具名阶段，现阶段保留；拓扑/输出两簇虽可独立，未证明拆类收益。

其余 15 个类型按公开入口、方法簇和事务注解初判如下；这是职责层级，不是所有私有方法逐行验证：

| 类型 | 入口/职责簇 | 决定与边界 |
|---|---|---|
| `DefaultFileGovernanceService` | 上传、确认、文件状态、重派和到达组 | 同域多用例，当前验证及安全文件名复用；保留各公开事务边界，不按数量拆。 |
| `DefaultRetryGovernanceService` | retry、分区重排队、死信重放 | 已有 `RetryRequeueCoordinator`；`REQUIRES_NEW` 与 MANDATORY/savepoint 入口不可直接合并。 |
| `BatchDayReplayService` | 重放提交、预览、条目候选物化、结果影响 | 可评估候选物化簇协作者；保留提交/预览事务、token 锁与条目顺序。 |
| `WorkflowRunReplaySnapshotResolver` | 保护快照解封、校验、恢复定义 | 同一快照读取职责，先保留。 |
| `SensorStateMachine` | 到期、超时、调用传感器、推进节点 | 单一状态机；可改善主方法分阶段表达，但保留 timeout、异常转译和同一节点事务。 |
| `DefaultTaskAssignmentService` | 认领、续租、上报、有效配置加载 | 可评估有效配置解析协作者；保留持有任务实体复用、partition 实时重读及租约事务。 |
| `CrossDayDependencyResolver` | 普通/冻结版本依赖解析 | 同一领域解析流程；保留，单次纯转发可另行核对。 |
| `DefaultWorkflowRunLaunchService` | 启动校验、快照、事务内创建、派发 | 主编排和 prepareRun 紧邻现有事务，先保留；不可改变容量/回放/当前定义优先级与懒读取。 |
| `WorkflowConditionEvaluator` | 条件表达式解析与求值 | 解析器内部方法内聚，保留；不能因方法数拆开。 |
| `BundleArrivalLauncher` | 到达组收集/校验/幂等启动 | 主路径已有三个阶段；保留租户上下文、请求落库和围栏事务。 |
| `BatchDaySettleScheduler` | 扫描、claim/finalize、catch-up | 可评估 catch-up 构造/派发协作者；保持锁顺序及新事务控制点。 |
| `DefaultResourceScheduler` | 窗口/并发/资源/worker 判定与公平性 | `schedule`、`preview` 共享 evaluate，职责一致；保留预览与正式分支的调用顺序。 |
| `DefaultLaunchService` | 校验、去重、实例准备和派发 | 已有 `launchValidationService`/`LaunchParamResolver`；保留事务与同一请求幂等边界。 |
| `DispatchChannelSnapshotService` | 渠道固定、加密快照回放 | 同一快照域；保留 secret 处理和不可变/可变拷贝边界。 |
| `WorkflowRunExecutionSnapshot` record | `from` 中解析 schema/冻结定义、校验图与身份、深层不可变副本；实例负责访问快照 | 可评估同包读取或验证协作者；保留 v1 返回 null、v2 检查/异常顺序、敏感数据及深层不可变语义。 |

### Orchestrator：长方法逐项主体复核（32 / 32）

以下 32 项均读取该方法的完整当前主体并按阶段初判；这不是所有被调用方法、并发路径或测试证据的逐行证明。所谓“候选”是拟议方案而不是已验证安全的改动，保留现有事务传播、异常、短路、数据库读写与参数求值顺序是实施前提。标为“保留”表示目前未发现足以抵消新增抽象的清晰收益，不因方法长而机械拆分。

| 方法 | 初判与必须维持的边界 |
| --- | --- |
| `SuccessInstanceArchiveService.java:77 archiveBatch` | 候选：分组归档复制/核验和热表清理；锁、逐表校验与外键删除顺序及整体事务不能变化，统计字段不丢失。 |
| `TenantSchedulerSnapshotService.java:154 buildQueueSnapshot` | 候选：把队列数据聚合与单队列视图构造分阶段；保留所有 DB 读取顺序和 `getOrDefault` 默认值的急切求值。 |
| `DefaultCompensationService.java:913 replayVerifiedUnknownExternalOutcome` | 候选：拆出明确的来源解析/证据验证阶段；保留死信 CAS、任务 CAS、重跑、绑定和成功记录次序及调用方事务。 |
| `DefaultFileGovernanceService.java:349 redispatchFile` | 候选：按来源核验与重派阶段组织；保留文件/任务/分区读取顺序、三个 CAS、Outbox 与审计的同事务原子性。 |
| `DefaultRetryGovernanceService.java:122 scheduleRetryIfNecessary` | 暂留：已按不可重试、预算与插入排列；MDC 的 finally 和各分支落死信的原顺序需保留。 |
| `DefaultRetryGovernanceService.java:542 replayDeadLetterWithinTransaction` | 暂留：保存点内回滚与外层失败记账、MDC 清理属于不同边界，不合并 catch 或调整传播。 |
| `RetryRequeueCoordinator.java:91 requeuePartition` | 暂留：未知结果核验、CAS、重置与 Outbox 有严格顺序；内部嵌套与 MDC 清理保留。 |
| `BatchDayReplayRangeOperationService.java:110 submit` | 候选：明确预览令牌核验、子日提交阶段；保留锁、消费、插入、关联和校验次序及当前事务。 |
| `BatchDayReplayService.java:135 submitForOperation` | 候选：提取会话构造作为命名步骤；保留两次取时、预览准备、令牌锁与消费、重复异常翻译、持久化回读顺序。 |
| `WorkflowNodeReplayOperationService.java:126 submitForApproval` | 候选：抽取重放操作对象装配；仍在原令牌消费之后构造，保持快照、插入与关联顺序。 |
| `WorkflowRunReplayPreviewService.java:302 approve` | 候选：构造启动请求与持久化载荷可作为命名步骤；锁、批准校验、启动状态 CAS、写入、重读、事件发布顺序不变。 |
| `WorkflowRunReplaySnapshotResolver.java:46 resolve` | 候选：分组快照解密/解析、验证、恢复；保持解析及异常发生时机，尤其原短路层级和冻结结果。 |
| `SensorStateMachine.java:85 probeAndAdvance` | 暂留：入口、超时、策略探测、结果推进已成顺序；异常仅包围探测，不能扩大 catch 范围。 |
| `ChildJobLaunchSupport.java:54 dispatchJobNode` | 候选：请求载荷装配可命名；保持保护、就绪登记、生成 ID、插入、启动、回读和绑定顺序。 |
| `DefaultTaskAssignmentService.java:585 loadEffectiveConfig` | 候选：独立有效配置组装；不额外读任务，不改变实例/分区/定义读取次序、快照优先级及字段求值。 |
| `DefaultTaskOutcomeService.java:130 applyTaskOutcome` | 候选：按围栏后决策、结果推进区分阶段；保持 advisory 锁→回读→所有权校验→重试/完成→推进→事件顺序与事务。 |
| `TaskOutcomeInstanceProgressor.java:60 advance` | 候选：分区统计及进度参数组装可成命名步骤；保留仅在对应分支读取状态/分区、CAS、终态及 Workflow 收敛次序。 |
| `TaskOutcomeInstanceProgressor.java:246 advanceTimedOutWorkflowNode` | 暂留：按查询、进度校验、聚合和收敛排列；重复 builder 不是自动抽取理由，避免改变查询和失败时机。 |
| `ResultVersionWriter.java:73 writeOnTerminal` | 候选：明确结果晋级决策阶段；保持 MANDATORY、业务键锁、去重、DQ、副作用与版本写入次序。 |
| `DefaultWorkflowNodeDispatchService.java:111 dispatchNode` | 暂留：已按围栏、读锁、就绪、依赖及类型分派表达主流程；不得提早读取或改变拒绝异常处理。 |
| `DefaultWorkflowNodeDispatchService.java:591 dispatchTaskNode` | 候选：计划准备与逐分区任务生成可按职责分组；保持 fan-out 索引、准入、创建、发布及版本 CAS 顺序。 |
| `DefaultWorkflowRunLaunchService.java:412 prepareRun` | 候选：事务回调内的快照来源和构造分阶段；REQUIRES_NEW/REPEATABLE_READ、锁、重复键处理和三路惰性优先级必须不变。 |
| `WorkflowRunExecutionSnapshot.java:52 from` | 候选：快照解析/校验/冻结可由专用辅助职责表达；保持 v1 回退、异常时机、映射深冻结和敏感绑定。 |
| `FileGovernanceScheduler.java:260 evaluateArrivalGroup` | 候选：按超时与到齐场景抽取处理阶段；`BUNDLE_LAUNCH_WAITING` 优先于超时、verified 与触发配置顺序不能变。 |
| `PartitionReclaimUnit.java:114 doReclaim` | 候选：未知外部结果分支可单独命名；保持实例围栏、分区 CAS→task NOWAIT→task/step CAS→Outbox，失败回滚不变。 |
| `KafkaOutboxPublisher.java:98 publish` | 候选：三类发送分支和共同的投递结果回调可评估分组；保持 topic 解析/反序列化/异步线程和失败记录语义。 |
| `RedisQuotaRuntimeStateService.java:156 evaluateAndReserve` | 暂留：预检、脚本、索引分开明确；保留脚本异常的故障策略与索引异常只记录不影响主流程。 |
| `BatchDaySettleScheduler.java:420 driveCatchUp` | 候选：按单候选形成独立步骤；保留首次冻结、去重插入、失败继续和自动触发先后。 |
| `WaitingPartitionDispatchScheduler.java:64 dispatchWaitingPartitions` | 候选：候选收集与逐项派发可分步；保留 tick 缓存生命周期、每项 MDC 清理、排序、租户上下文与单项事务/失败隔离。 |
| `TriggerLaunchConsumer.java:120 consume` | 暂留：各 catch 对 ack/nack/重抛区别明确；不合并异常或改变 MDC、计时清理。 |
| `DefaultLaunchValidationService.java:51 load` | 候选：命名定义加载/拒绝步骤；保持先核对身份再允许拒绝写入、快照优先级和并发成功回查。 |
| `DefaultWorkerRegistryService.java:65 register` | 候选：构造注册实体作为明确步骤；保留先限流/版本校验、首次才做配额校验、持久化及能力写入顺序。 |


### Common、Trigger 和 security-scan：普通嵌套 if 补扫

额外定位 **70 处、50 个文件及方法名／初始化器组、30 个文件**，已阅读 50 组所在方法或初始化器主体，67 处建议保留；Quartz 恢复中其余 3 处属于同一阶段，可评估提取负责已有触发器恢复和重绑的具名操作。此为方法主体静态初判，不是跨调用链的语义证明。这里包含循环中受外层条件控制的分支，数量不是问题数；重载可能共组。像 `SensitiveDataValidator.scan` 的递归与首次命中返回、`ContentMaskingUtils.maskText` 的解析失败降级、`PrivateTempFiles` 的锁和清理、Quartz 的恢复锁及数据库条件写入都承担边界，暂不因嵌套而压平。`TriggerScheduledRecoveryService` 的事务内多个阶段可结合上文方案整体审查，但不能提前读取或改变锁、判定与落库顺序。

逐处的位置、处理和需要维持的边界如下：

| 文件:行 | 方法 | 结论 | 原因和边界 |
|---|---|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchSecurityProperties.java:61` | `validateSecuritySettings` | 保留 | 生产环境内依次校验占位符、默认密钥和数据源密码；保持报错与日志次序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BusinessDataSourceBuilder.java:46` | `build` | 保留 | 仅启用路由时要求分片配置，再建立多片数据源；否则走单片。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/config/BusinessDataSourceBuilder.java:95` | `buildMultiShard` | 保留 | 仅设定片池上限时调整 maximumPoolSize 和 minimumIdle，异常仍按原范围释放已建池。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/dto/WorkerHeartbeatDto.java:66` | `<initializer>` | 保留 | 记录紧凑构造器先规范化，再分别校验列表大小并复制；保留异常顺序与防变更语义。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/dto/WorkerHeartbeatDto.java:72` | `<initializer>` | 保留 | 记录紧凑构造器先规范化，再分别校验列表大小并复制；保留异常顺序与防变更语义。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/lock/DistributedLockAspect.java:72` | `around` | 保留 | 未执行时才按注解决定抛错或返回默认值；业务异常先传播。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/persistence/mybatis/AuditFieldsInterceptor.java:102` | `fillSingleEntity` | 保留 | UPDATE 时先写时间，操作人非空才更新；INSERT 行为独立。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/resilience/DegradedResponseHeaders.java:41` | `mark` | 保留 | 只在响应头已有内容时遍历规范化值，之后加入当前来源。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/rls/RlsClosedWorldChecker.java:253` | `inspectTable` | 保留 | 结果集有行时分别检查 ENABLE 与 FORCE；保留查询与资源关闭。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/rls/RlsClosedWorldChecker.java:256` | `inspectTable` | 保留 | 结果集有行时分别检查 ENABLE 与 FORCE；保留查询与资源关闭。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/ApiKeyHasher.java:88` | `verify` | 保留 | PBKDF2 分支才要求 salt，保持算法选择和常量时间比较。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/DnsResolveGuard.java:113` | `isBlocked` | 保留 | IPv6 分支依次检查映射 IPv4、ULA、链路地址，保持策略和短路。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/DnsResolveGuard.java:118` | `isBlocked` | 保留 | IPv6 分支依次检查映射 IPv4、ULA、链路地址，保持策略和短路。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/DnsResolveGuard.java:122` | `isBlocked` | 保留 | IPv6 分支依次检查映射 IPv4、ULA、链路地址，保持策略和短路。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:96` | `rejectIfContainsInlineCredential` | 保留 | 仅命令行选项分支提取选项名，随后再检查完整凭据语法。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:151` | `scan` | 保留 | Map 和 Iterable 分支各自遍历，保留首个命中、递归顺序及 seen 标记。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:154` | `scan` | 保留 | Map 和 Iterable 分支各自遍历，保留首个命中、递归顺序及 seen 标记。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:159` | `scan` | 保留 | Map 和 Iterable 分支各自遍历，保留首个命中、递归顺序及 seen 标记。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:166` | `scan` | 保留 | Map 和 Iterable 分支各自遍历，保留首个命中、递归顺序及 seen 标记。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/service/BatchObjectCryptoService.java:70` | `resolveKeyRef` | 保留 | 仅配置对象存在时读取 key，缺失后回退默认值。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/service/BatchObjectCryptoService.java:211` | `decryptIfNeeded` | 保留 | 非加密头仅在实际读到字节时回推，保留流所有权转移和失败关闭。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/spi/task/BatchTaskExecutorRegistry.java:127` | `register` | 保留 | 已有同实例时直接返回，不同实例报重复；保留注册与错误顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/sql/SelectSqlAstValidator.java:79` | `containsSelectStar` | 保留 | 按 SELECT 类型依次检查通配符和嵌套体，首个命中立即返回。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/sql/SelectSqlAstValidator.java:118` | `collectInitialBodies` | 保留 | 有 WITH 时逐项收集非空子查询，保留队列顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/sql/SelectSqlAstValidator.java:133` | `enqueueNestedBodies` | 保留 | 有 JOIN 时逐个收集 Select 子查询，保留既有遍历顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/startup/SecretPayloadFlywayCallback.java:92` | `migrateLegacyPayloads` | 保留 | 仅本批无进展时重查并决定结束或抛错，保留数据库操作顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/S3ObjectStore.java:370` | `mapException` | 保留 | S3 类型内优先识别无响应体的 404，再按错误码分类。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/ingress/ImportIngressOwnershipRepository.java:110` | `matchesManifestTemplate` | 保留 | 日期分隔符位置和数字位采用互斥判断，保持字符扫描顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/ContentMaskingUtils.java:62` | `maskText` | 保留 | 仅尝试 JSON 时处理解析结果及异常；深度受限异常保持安全回退。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/ContentMaskingUtils.java:66` | `maskText` | 保留 | 仅尝试 JSON 时处理解析结果及异常；深度受限异常保持安全回退。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/PrivateTempFiles.java:65` | `createLockedTempFile` | 保留 | 未成功取得锁才关闭文件句柄并清理两个路径，保持 finally 顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/PrivateTempFiles.java:135` | `deleteStaleUnlockedFile` | 保留 | 仅存在锁文件时尝试加锁，保留锁和文件生命周期。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:194` | `transformEndpoint` | 保留 | 仅存在用户信息时决定恢复或脱敏，保留 URI 转换和异常回退。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:239` | `transformQuery` | 保留 | 按参数逐项恢复已有值或脱敏，保留参数顺序和取值调用。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:241` | `transformQuery` | 保留 | 按参数逐项恢复已有值或脱敏，保留参数顺序和取值调用。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:338` | `restoreMaskedValue` | 保留 | Map 中只对敏感且占位的值恢复，其余递归；列表单独按位置递归。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:368` | `containsMaskedSensitiveValue` | 保留 | Map/Iterable 中逐项深度查找，保留首个命中即返回。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:371` | `containsMaskedSensitiveValue` | 保留 | Map/Iterable 中逐项深度查找，保留首个命中即返回。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:377` | `containsMaskedSensitiveValue` | 保留 | Map/Iterable 中逐项深度查找，保留首个命中即返回。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java:391` | `removeSensitiveFields` | 保留 | 仅 Map 中非敏感字段递归并插入；列表路径不变。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/application/TriggerOutboxRelay.java:283` | `pollLocked` | 保留 | 额度为零时记录可用指标后返回，保留多次停止检查与额度领取顺序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/application/TriggerOutboxRelay.java:414` | `flushOutcomes` | 保留 | 仅有成功结果才批量持久化，再比较更新数量；失败逐项处理。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/application/TriggerOutboxRelay.java:462` | `markPublishFailure` | 保留 | 达到重试上限时先尝试持久化 GIVE_UP，成功才记录日志。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/TriggerSecurityConfiguration.java:139` | `doFilterInternal` | 保留 | API key 存在时先确认入口适用并认证；内部密钥走独立后续分支。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/TriggerSecurityConfiguration.java:142` | `doFilterInternal` | 保留 | API key 存在时先确认入口适用并认证；内部密钥走独立后续分支。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java:72` | `triggerMisfired` | 保留 | 仅缺少下一触发时间时读取前次触发时间并告警，持久化在安排任务之前。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java:188` | `scheduleRecovery` | 可评估 | 可评估将已存在恢复 trigger 的检查、恢复或重绑提为同类私有阶段方法，返回是否已处理；必须保持 checkExists→getTrigger→条件 resume/reschedule→必要时 scheduleJob 的顺序及原 SchedulerException/ObjectAlreadyExistsException 捕获范围，保留 reboundData 合并顺序和所有返回条件。只形成方案，未实施。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java:190` | `scheduleRecovery` | 可评估 | 可评估将已存在恢复 trigger 的检查、恢复或重绑提为同类私有阶段方法，返回是否已处理；必须保持 checkExists→getTrigger→条件 resume/reschedule→必要时 scheduleJob 的顺序及原 SchedulerException/ObjectAlreadyExistsException 捕获范围，保留 reboundData 合并顺序和所有返回条件。只形成方案，未实施。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java:206` | `scheduleRecovery` | 可评估 | 可评估将已存在恢复 trigger 的检查、恢复或重绑提为同类私有阶段方法，返回是否已处理；必须保持 checkExists→getTrigger→条件 resume/reschedule→必要时 scheduleJob 的顺序及原 SchedulerException/ObjectAlreadyExistsException 捕获范围，保留 reboundData 合并顺序和所有返回条件。只形成方案，未实施。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java:293` | `ensureDurableRecoveryJob` | 保留 | 仅缺少持久 Job 时创建；并发已存在异常需再次核对再决定重抛。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java:260` | `scheduleValidatedDescriptor` | 保留 | 仅类型存在时查 handler，存在对应 handler 才执行。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java:449` | `rebindPendingRecoveryTriggers` | 保留 | 仅不存在持久恢复 Job 时创建，并保留竞争创建后的复核与异常传播。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java:84` | `resolveZoneId` | 保留 | 有日历且 timezone 非空时优先采用日历，否则回退。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/ScheduledOccurrenceRecorder.java:66` | `recordWaitingReadiness` | 保留 | 未取得准入锁时先判断版本再转状态，保留事务与提前返回。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerRequestPersistence.java:92` | `persistAndForwardInTransaction` | 保留 | 按锁、幂等、外部事件、准入与 outbox 顺序执行；保留事务和状态判断。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerRequestPersistence.java:116` | `persistAndForwardInTransaction` | 保留 | 按锁、幂等、外部事件、准入与 outbox 顺序执行；保留事务和状态判断。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledOccurrencePlanner.java:75` | `materialize` | 保留 | 仅覆盖失效时计算重新规划起点并夹到覆盖下界，保留事务和插入顺序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledOccurrencePlanner.java:78` | `materialize` | 保留 | 仅覆盖失效时计算重新规划起点并夹到覆盖下界，保留事务和插入顺序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:144` | `catchUpRejectedOccurrence` | 保留 | 锁后识别已接纳请求；人工审批路径插入或加载待办，保留事务和幂等顺序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:171` | `catchUpRejectedOccurrence` | 保留 | 锁后识别已接纳请求；人工审批路径插入或加载待办，保留事务和幂等顺序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:248` | `launchScheduled` | 保留 | 已关闭批日登记竞争失败时再读最新结果，保留异常与回退顺序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:275` | `settledOccurrenceResponse` | 保留 | 识别已受理状态后读请求并核对目标，保留状态响应。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:301` | `resolveScheduledRequest` | 保留 | 存在观测记录时先核对版本和业务日，再使用快照；否则按日历生成。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:398` | `approvePendingCatchUp` | 保留 | 保留幂等查询、CAS、审批失败再读、outbox 和台账迁移顺序及事务范围。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:445` | `approvePendingCatchUp` | 保留 | 保留幂等查询、CAS、审批失败再读、outbox 和台账迁移顺序及事务范围。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:448` | `approvePendingCatchUp` | 保留 | 保留幂等查询、CAS、审批失败再读、outbox 和台账迁移顺序及事务范围。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledRecoveryService.java:564` | `resolveRequestFromPending` | 保留 | 缺 pendingId 时按请求查待办并可返回空；指定 ID 需加载并校验。 |
| `security-scan/src/main/java/io/github/pinpols/batch/securityscan/ScanResult.java:51` | `renderCommand` | 保留 | 仅 DAST 且认证参数存在时脱敏命令，其他命令保持原样。 |
| `security-scan/src/main/java/io/github/pinpols/batch/securityscan/SecurityScanOrchestrator.java:35` | `run` | 保留 | 分组扫描和单项失败分别决定继续、退出或标记，保持执行及报告顺序。 |
| `security-scan/src/main/java/io/github/pinpols/batch/securityscan/SecurityScanOrchestrator.java:43` | `run` | 保留 | 分组扫描和单项失败分别决定继续、退出或标记，保持执行及报告顺序。 |


### 审查收口与后续实施约束

本报告区分语法定位、语句邻近初判、方法主体阅读和调用链/运行验证；同一处可能属于多个候选类别，数字不能相加为整改总数。后续将各候选按相同功能簇合并，再追踪调用点及受影响测试后确定是否值得改。完整方法主体或调用链未核的候选不能标为已确认整改项。

实施时先让公开主流程顺序表达业务阶段；具名阶段具有独立业务含义时保留方法。多个私有方法如果共同承担独立职责、依赖内聚且可在同一生命周期内协作，可提取同包专用辅助类；只有一层简单转发或单次普通表达式不新增类或包装。高风险流程先对照实际事务、锁、数据库读写、异常、资源关闭、短路和参数求值，再选受影响的现有测试进行定向验证。所有方案不改变业务逻辑、语义与执行顺序。


### Orchestrator：单句封装其余 53 项的声明与调用语句复核

逐项读取这 53 项声明及其同文件调用语句；下表为初判，保留反射/动态调用未被文本搜索证明的限制。除标注“可选内联”的 3 个一次转发候选外，不建议仅因方法体单句而改动。候选不属于缺陷，实施前仍须逐个核对完整调用者及参数求值。

| 方法 | 初判 |
| --- | --- |
| `OutboxChildRecordService.java:61 missingParent` | 统一父记录缺失异常，复用。 |
| `SchedulePlanSupport.java:84 hasText` | 文本非空谓词，含方法引用。 |
| `SizeBasedPartitionCountResolver.java:81 positive` | 分区参数下限的统一语义。 |
| `AssetPartitionService.java:192 toPartitionKey` | 业务日期到分区键的命名转换。 |
| `BatchDayOpenService.java:455 markDependencyWait` | 依赖等待状态更新的统一入口。 |
| `CapacityProfileRow.java:46 nonNegative` | 多字段非负归一化。 |
| `DefaultApprovalWorkflowService.java:74 findBySourceIdempotencyKey` | 来源幂等查询复用。 |
| `DefaultCompensationService.java:1202 appendPreInsertFailureLogInNewTx` | 失败审计新事务边界。 |
| `DefaultRetryGovernanceService.java:537 replayDeadLetterWithinTransaction` | 可选内联：3 参数重载只转发一次并传 false；仍在原事务回调内执行。 |
| `DefaultRetryGovernanceService.java:873 replayDeadLetterInNewTransaction` | 两参数重载被多处使用，固定 false 表示普通重放。 |
| `DefaultRetryGovernanceService.java:887 inNewTransaction` | 新事务包装明确传播边界。 |
| `DefaultWorkerDrainGovernanceService.java:164 validateTenant` | 租户参数入口校验复用。 |
| `RetryRequeueCoordinator.java:86 requeuePartition` | 可选内联：4 参数私有重载仅被 3 参数入口调用并补 null；验证原参数顺序。 |
| `WorkflowNodeReplayTargetResolver.java:82 invalidSource` | 来源拒绝异常工厂复用。 |
| `BatchDayReplayRangeOperationService.java:361 previewDays` | 预览日期列表转换复用。 |
| `BatchDayReplayRangeOperationService.java:390 commandForDate` | 可选内联：2 参数私有重载仅一处调用并补 null；保留参数求值次序。 |
| `BatchDayReplayRangeOperationService.java:395 commandForDate` | 命令构造多个调用入口。 |
| `BatchDayReplayRangeOperationService.java:481 invalidPreview` | 错误工厂复用。 |
| `BatchDayReplayService.java:363 invalidPreviewToken` | 错误工厂复用。 |
| `BatchDayReplayService.java:866 replayableStatuses` | 可重放状态集合语义。 |
| `WorkflowNodeReplayOperationService.java:380 invalidPreview` | 错误工厂复用。 |
| `WorkflowReplayJobDefinitionResolver.java:101 invalidPolicy` | 错误工厂复用。 |
| `WorkflowReplayJobDefinitionResolver.java:105 unavailableVersion` | 错误工厂复用。 |
| `WorkflowRunReplayPreviewService.java:442 invalidApprovalDecision` | 错误工厂复用。 |
| `WorkflowRunReplayPreviewService.java:449 previewChanged` | 错误工厂复用。 |
| `WorkflowRunReplaySnapshotResolver.java:461 invalidSnapshot` | 错误工厂复用。 |
| `WorkflowRunReplaySourceResolver.java:99 invalidSource` | 错误工厂复用。 |
| `SensorStateMachine.java:264 failInvalidProbeTime` | 异常探测时间的命名业务处理。 |
| `CrossDayDependencyResolver.java:415 findLatestModeVersion` | 跨日查询复用。 |
| `RerunJobDefinitionResolver.java:143 conflict` | 错误工厂复用。 |
| `WorkflowConditionEvaluator.java:379 looksLikeNumber` | 数字识别谓词复用。 |
| `WorkflowGraphValidator.java:465 warning` | 警告实体工厂复用。 |
| `WorkflowGraphValidator.java:647 issue` | 错误实体工厂复用。 |
| `WorkflowRunExecutionSnapshot.java:549 invalidSnapshot` | 错误工厂复用。 |
| `WorkflowWaitSensorSpecValidator.java:253 issue` | 验证错误实体工厂复用。 |
| `StaleCreatedLaunchRecoveryScheduler.java:243 counter` | 指标构造复用。 |
| `TriggerLaunchRecoveryService.java:222 counter` | 指标构造复用。 |
| `TriggerRequestLaunchReconciler.java:123 counter` | 指标构造复用。 |
| `WorkerController.java:146 resolveTenant` | Heartbeat DTO 身份提取，三个同类型入口及空值处理。 |
| `WorkerController.java:151 resolveTenant` | WorkerTenantRequest 身份提取，三个同类型入口及空值处理。 |
| `FileGovernanceRepository.java:98 storageId` | 存储所有权标识计算复用。 |
| `BatchTopicResolver.java:69 safe` | Kafka topic 字符规范化复用。 |
| `OrchestratorConfigCacheService.java:120 isNegativeCached` | 负缓存语义复用。 |
| `DefaultWorkerSelector.java:182 currentLoadOrZero` | 默认负载谓词及方法引用。 |
| `FileArrivalSensorPolicy.java:174 specError` | 固定错误键和传感器类型的失败工厂。 |
| `JobLifecycleMetricsRecorder.java:152 logFailure` | 失败日志模板复用。 |
| `DefaultLaunchService.java:558 inNewTransaction` | 新事务包装明确传播边界。 |
| `DefaultLaunchService.java:801 isOriginalRequestReplay` | 默认当前实例的业务重放谓词。 |
| `DispatchChannelSnapshotService.java:392 unavailableConfiguration` | 错误工厂复用。 |
| `DispatchChannelSnapshotService.java:397 invalidSnapshot` | 错误工厂复用。 |
| `FileTemplateSnapshotService.java:321 invalidSnapshot` | 错误工厂复用。 |
| `JobDependencyWaitingLaunchService.java:312 waitConflict` | 错误工厂复用。 |
| `LaunchBatchDayService.java:96 logUpsertRetry` | 重试日志模板复用。 |

加此前核查的 21 项及另 1 个未发现源码调用的 `DatabaseQuotaRuntimeStateService.refreshState` 四参数重载，75/75 已完成声明及同文件调用语句层初判；这不等于已证明所有反射、运行时路径或每个调用者完整上下文。

### Orchestrator：40 个直接嵌套调用候选的方法主体初判

40/40 均读取所在完整方法（相同方法的候选复用一次上下文），22 条可评估用命名局部量增强可读性，18 条保留，剩余 0 条未读方法主体。可评估不是必须实施，也不是已完成被调用者副作用/并发等价性证明；以下要求是实施时的必要前提。

| 位置 | 处理 | 理由及顺序边界 |
| --- | --- | --- |
| `DefaultScheduleForwarder.java:220 claimAndPublish` | 可评估 | 提取批量认领结果，必须仍在循环内先数据库操作再加入集合。 |
| `BatchDayCatchupPreviewService.java:116 inventoryInReadOnlyTransaction` | 可评估 | 提取日期查询结果，仍在只读事务和前置校验之后构造集合。 |
| `ForensicExportService.java:104 loadDownloadArtifact` | 可评估 | 提取文件大小，保留检查之后及构造实参求值位置。 |
| `SensorStateMachine.java:152 probeAndAdvance` | 可评估 | 命名运行变量，保持前面实参与变量求值顺序，仍仅在策略存在后执行。 |
| `BatchDayCatchupControlService.java:125 ensureEnabledCalendar` | 可评估 | 命名日历查询结果，保持查询一次及原异常。 |
| `RelatedFileReferenceValidator.java:22 validate` | 可评估 | 命名文件查询结果，仅在 fileId 非空后执行。 |
| `BatchDayReplayRangeOperationService.java:339 prepareDays` | 可评估 | 命名灾备覆盖查询结果，仍逐日执行且早于工作日判断。 |
| `BatchDayReplayService.java:1201 loadForUpdateWithOperationLock` | 可评估 | 命名 operation 行锁查询结果，仍仅在有 operationId 的分支里，先于 session 行锁。 |
| `BatchDayReplayTerminalReconciler.java:208 loadForUpdateWithOperationLock` | 可评估 | 同上：锁查询仅在左侧条件成立时执行且仍先于 session 锁。 |
| `DefaultTaskCreationService.java:205 resolveRelatedFileId` | 保留 | 短条件表达清晰；不得提前读取后备键或更改短路。 |
| `DefaultTaskCreationService.java:208 resolveRelatedFileId` | 保留 | 短条件表达清晰；仅在前项未返回时读取。 |
| `TaskOutcomePayloadSupport.java:111 resolveRelatedFileId` | 保留 | 短条件表达清晰；不得改变 DISPATCH 分支读取顺序。 |
| `TaskOutcomePayloadSupport.java:114 resolveRelatedFileId` | 保留 | 短条件表达清晰；仅在前项未返回时读取。 |
| `CrossDayDependencyResolver.java:321 resolvePinnedRange` | 保留 | 已有命名布尔表达重复选择的业务条件，保留 putIfAbsent 的副作用。 |
| `BatchDaySettleScheduler.java:334 hasExpectedOrUnresolvedWork` | 保留 | 查询 OR 链必须保留短路、位置及顺序；仅作为整体流程候选，不单独提前查询。 |
| `TaskOutcomeSummaryBuilder.java:140 aggregateSuccessfulPartitionOutputs` | 保留 | 简单聚合键赋值，表达意图清楚。 |
| `TaskOutcomeSummaryBuilder.java:147 aggregateSuccessfulPartitionOutputs` | 保留 | 简单聚合键赋值，保留每分区顺序。 |
| `LaunchParamResolver.java:303 buildParamsSnapshot` | 保留 | 快照字段赋值清晰，保留原求值位置。 |
| `TaskDispatchOutboxService.java:201 resolvePartitionCount` | 可评估 | 可对 16 个参数分组命名，所有值仍按原从左到右急切求值，不改成惰性回退。 |
| `TenantSchedulerSnapshotService.java:83 buildLive` | 可评估 | 先命名在线 Worker 组，保持在 worker 快照之后、队列快照之前。 |
| `TenantSchedulerSnapshotService.java:197 buildQueueSnapshot` | 保留 | 保留 getOrDefault，默认对象是急切求值，不能改惰性获取。 |
| `WorkflowNodeRunSupport.java:27 recordReady` | 可评估 | 命名 nextRunSeq 查询结果，保留创建对象和各 setter 的原先后顺序。 |
| `WorkflowNodeRunSupport.java:53 recordReadyIfAbsent` | 可评估 | 同上，保留 insertIfAbsent 前的读取位置。 |
| `LineageEvidenceService.java:84 buildEvidence` | 可评估 | 先命名热记录查询结果，再做 null 转空；保留热/冷均急切查询。 |
| `LineageEvidenceService.java:86 buildEvidence` | 可评估 | 先命名冷记录查询结果，再做 null 转空；不能改成仅热为空才查。 |
| `LineageEvidenceService.java:103 buildEvidence` | 可评估 | 先命名热派发查询结果，再做 null 转空；不改变查询顺序。 |
| `LineageEvidenceService.java:106 buildEvidence` | 可评估 | 先命名冷派发查询结果，再做 null 转空；不改变查询顺序。 |
| `DefaultTaskCreationService.java:157 createStepInstance` | 可评估 | 先命名 step 实例再 insert，仍仅在确认不存在之后执行。 |
| `TaskOutcomeInstanceProgressor.java:217 advance` | 可评估 | 先命名分区列表，仍仅在终态分支内并保持终结器实参求值顺序。 |
| `WorkflowGraphValidator.java:142 validate` | 可评估 | 先命名节点查询结果再 null 转空，节点归一化仍先于边查询。 |
| `WorkflowGraphValidator.java:144 validate` | 可评估 | 先命名边查询结果再 null 转空，保持先节点后边。 |
| `BatchBacklogMetricsScheduler.java:79 sample` | 保留 | 单一指标从查询赋值，保留以免拆散采样 catch 范围。 |
| `BatchBacklogMetricsScheduler.java:87 sample` | 保留 | 单一指标从查询赋值，保留查询与写指标次序。 |
| `BatchBacklogMetricsScheduler.java:89 sample` | 保留 | 单一指标从查询赋值，保留异常短路后续采样。 |
| `RuntimeConsistencyMetricsScheduler.java:62 sample` | 保留 | 单一指标从查询赋值，保留采样顺序。 |
| `JobSlaScheduler.java:91 scanEscalations` | 保留 | 单一指标从查询赋值，保留只在升级启用时查询。 |
| `DataQualityRuleApplicationService.java:51 update` | 可评估 | 提取更新后的数据库回读，保留 requireNonNull 与异常时间。 |
| `ResultVersionQueryService.java:45 findEffective` | 保留 | 简短 Optional 包装表达契约，留在已校验分支。 |
| `ResultVersionQueryService.java:90 findVersionByJob` | 保留 | 简短 Optional 包装表达契约，保留构造业务键后查询。 |
| `ResultVersionQueryService.java:139 findById` | 保留 | 简短 Optional 包装表达契约，保留入口校验后查询。 |


### Console：普通嵌套 if 的方法主体核查

164 个机器命中归并为 109 个含命中的最近方法；此前摘要查看不算完成。最终已完整阅读 **109/109 最近方法主体、覆盖 164/164 命中**，剩余 **0 个最近方法、0 条命中**待同级核查。结论为静态阅读判断，不含全调用链、测试与运行验证。

- #1–3：停机等待、中断恢复；通知恢复的租户上下文/事务/幂等和先重臂再审计；SSE 票据消耗后提取角色，均保留现有层次。
- #4：审批状态决策与执行分发可考虑命名独立执行阶段，保持状态确认、执行副作用与 `markApprovalExecuted` 顺序；#5–6 readiness 将启用与否映射为阻塞或警告，现有局部清楚，不为消层拆分。
- #7：订阅注册关联锁、定时器、引用计数、关闭回调，保留整体一致性与 cleanup suppressed；#8 副本采样的降级判断独立，可后续评估同类策略内聚，但需保持监控快照先更新、隔离动作、日志及连接释放次序。
- #9–11：AI 会话创建、所有者和过期校验、事务轮次分配及绑定；预算 rate 校验与持久化各司其职，保留语义顺序。
- #12–13：模型调用的提示词与多媒体组装形成可评估的命名阶段，但不能移动 provider 能力校验、图片 MIME 解析、deadline 计算、线程提交和取消；是否独立协作者需先追模型调用生命周期，不因私有方法数量拆类。限流降级与每日预算顺序保留。
- #14–15：审计 SpEL/参数名两个入口和 JSON 递归脱敏，保留异常边界及字段顺序。
- #16–20：上传绑定模板先授权校验，再构建 HTTP 请求；模板启用时先校验再 CAS；日期窗口、同一尝试的实时计数补齐及错误信息脱敏均有数据依赖，保留当前判断及求值顺序。

- #21–24：进度发布节流不推进游标；补跑状态、冻结快照和唯一目标验证均表达业务顺序，保留短路、异常翻译和原路径。#25–26 首次/续发补跑的准备和发送阶段可命名，但各自不同的预校验与审批策略不能合并；保持 `Propagation.NEVER`、全量预校验、start→submit→accept 和未知结果回写。
- #27–29：作业新旧依赖比较有两份完整重复，可评估在已有类里抽取具名一致性校验，调用位置仍位于 `legacyCodes` 非空且两边 normalize 之后，保持序列、抛错和后续不同的回退策略；不要为这几行另建类。#30 补偿申请与批准后执行可评估具名阶段；必须保留 `createPendingWorkflowNodeReplay`→回写请求→提交审批→刷新以及批准校验先于实际补偿。

- #31–40：停机中断通知批次、租户来源优先级、按事件准备载荷以及通知回执幂等均保留。`SubscriptionRuleWebhookDispatcher` 的两条重试循环形态接近，但结果日志字段及发送通道不同；若要复用，需证明不引入跨线程时点变化并保留退避、成功/耗尽记录顺序，当前不建议为了减少重复直接套通用框架。终态通知插入两类投递记录可按独立职责评估，但不能改变收执锁、插入、推送、两轮迭代及异常顺序；`depth` 是递归值类型分派保留。

- #41–50：任务配置的敏感数据校验需保持在 schema 与写库之前；登录解密、租户/角色校验、导入用户去重和表格行数限制保留；`afterCompletion` 为匿名回调，完整看过回调主体但外层事务同步注册仍需结合调用方。认证过滤器可评估按 ticket/cookie/bypass 职责命名分段，但保持失败响应、放行顺序与 finally 清理；生产密钥失败与开发警告顺序和 token claims 应保留。

- #51–57：JWT 校验顺序、会话及撤销检查、客户端漂移日志抑制、双重检查锁、租户授权回退和 SSE watch 引用计数均有状态/并发依赖，保留；不得把异步任务及锁内共享状态移出原边界。#58–59 两处调度请求都有 cron/固定间隔解析，可评估复用独立解析谓词，须保留创建允许空 scheduleType、更新不允许空以及各自触发模式、异常捕获与校验顺序。#60 补跑被拒触发先校验 Workflow，再下发补跑，必要时创建审批并刷新；不改变顺序。

- #61–65：图校验、运行拓扑、配置回执和告警路由更新保留有序取数及分支；节点/边查询可考虑按投影职责组织，租户授权和列表/问题顺序不可改变。#66–68 租户复制的 diff、Workflow 图读取、日历读取为独立功能簇，可评估抽包内协作者，必须先确认租户上下文与查询顺序，不得引入并行和重排；#69–70 Excel 更新/应用保留预览 token CAS、图锁、配置版本锁、审批、注册事务完成回调及失败释放顺序，lambda 内 if 为跨作用域结构命中。

- #71–80：Excel 表头去重、分项嵌套事务和初始化严格模式、增改通道与 workflow、引用递归解析、导入依赖及单作业 workflow 优先查找均保留已有求值与写入顺序。`applyWorkflowDefinitions:260–274` 的边校验目前位于 nodes 非空分支内，单纯移动校验会改变行为；`applySpecs` 的分支可评估提取具名操作，但须保留逐项嵌套事务、dry-run 是否写入与统计/错误隔离，不能把异常捕获挪出循环。

- #81–90：Workflow 导入保留参数→图→校验→CAS→快照次序，图替换先读取受掩码参数再删除节点和边，边写入目前受 nodes 非空约束。行校验和依赖图的逐行引用与全图查环可按阶段命名，必须保留 issue 顺序、DB 查询短路与原异常；图校验的大方法可评估内聚协作者，但遍历及 issue 次序不变。表格投影和敏感输出脱敏当前层次清楚，保留。

- #91–100：停机时 stop 与 destroy 分别捕获；图检环和可达性按顺序增加 findings；Excel 表头回调、维护窗口受影响服务及运维管理员的两种身份来源各具约束；保留嵌套和现有顺序。局部 callback 的外层调用仍需依用途追踪。
- #101–109：信任代理时的来源地址优先级；限流器四个有序阶段、熔断的原子状态；异常分类的结构化信息优先、HTTP 解析和兜底；幂等请求的占位及完成态；租户上下文鉴权均不应机械调整。限流可评估按登录、敏感操作、昂贵操作与文件操作的顺序进行具名阶段封装，但保持请求路径可能重叠、计数键、日志和失败响应，且每个条件只有符合时才访问对应配置/用户，禁止提前求值。

**最终：109/109 最近方法主体、164/164 命中已按主体阅读并分类，0 项待同级阅读；这不是全工程逐方法审阅，也不等于关联事务、并发和调用方已验证。** scanner 的方法归属及跨 lambda 的语法嵌套可能误计，匿名方法外层环境、调用路径及运行行为仍待针对实际实施方案核查。


### Worker 和 Java SDK：303 处普通嵌套 if 的主体核查

对 549 个生产源码文件中筛出的 303 处、98 个文件，已阅读所在方法并按功能簇初判；本类筛选排除直接或单语句块形式的 else-if，包含 lambda 内的词法嵌套，不是完整调用链验证。303 处暂建议保留：层次用于控制授权、状态、资源、锁、失败隔离或后续步骤；这不影响前述其他类别中的 13 项可评估方案。行号是本次工作区快照，实施其他候选时仍需重新核对真实调用和行为。

| 路径:行 | 外层行 | 判断 | 理由/待核查条件 |
|---|---:|---|---|
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java:132` | 130 | 保留 | 锁内复查停止状态和实例；保留同步区及创建顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java:136` | 130 | 保留 | 锁内复查停止状态和实例；保留同步区及创建顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java:329` | 327 | 保留 | 可选值解析后校验和仅缩短超时；保留解析与错误顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java:334` | 327 | 保留 | 可选值解析后校验和仅缩短超时；保留解析与错误顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java:356` | 354 | 保留 | 空白名单分支还须执行 DNS 校验和返回，不能合并改变其他路径。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutor.java:436` | 433 | 保留 | 列表逐项验证并拒绝错误类型，保留首个错误。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicConnectionManager.java:92` | 86 | 保留 | 先推进结果集再读取角色能力，保持 JDBC 资源范围。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicExecutorProductionGuard.java:62` | 61 | 保留 | 按环境状态记录或规范化匹配，现有两层各表达独立上下文。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicExecutorProductionGuard.java:107` | 104 | 保留 | 按环境状态记录或规范化匹配，现有两层各表达独立上下文。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutor.java:239` | 237 | 保留 | 超时和参数集合解析的类型、空值及错误顺序清晰。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutor.java:262` | 259 | 保留 | 超时和参数集合解析的类型、空值及错误顺序清晰。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutor.java:265` | 259 | 保留 | 超时和参数集合解析的类型、空值及错误顺序清晰。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutor.java:478` | 476 | 保留 | 截断时仅写入剩余额度并继续排空输出流，保持流生命周期。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java:154` | 144 | 保留 | 演练和成功输出场景下才处理路径及本地标记；保留外部进程和 finally 清理范围。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java:406` | 404 | 保留 | 演练和成功输出场景下才处理路径及本地标记；保留外部进程和 finally 清理范围。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java:412` | 406 | 保留 | 演练和成功输出场景下才处理路径及本地标记；保留外部进程和 finally 清理范围。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java:414` | 412 | 保留 | 演练和成功输出场景下才处理路径及本地标记；保留外部进程和 finally 清理范围。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:209` | 207 | 保留 | 超时覆盖解析后先校验再缩短默认值，保持失败顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:212` | 207 | 保留 | 超时覆盖解析后先校验再缩短默认值，保持失败顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:268` | 266 | 保留 | SQL 词法扫描的状态转换和字符消费依赖外层上下文与分支优先级。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:296` | 295 | 保留 | SQL 词法扫描的状态转换和字符消费依赖外层上下文与分支优先级。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:298` | 297 | 保留 | SQL 词法扫描的状态转换和字符消费依赖外层上下文与分支优先级。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:300` | 299 | 保留 | SQL 词法扫描的状态转换和字符消费依赖外层上下文与分支优先级。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:302` | 301 | 保留 | SQL 词法扫描的状态转换和字符消费依赖外层上下文与分支优先级。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:436` | 434 | 保留 | SQL 词法扫描的状态转换和字符消费依赖外层上下文与分支优先级。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:546` | 537 | 保留 | 按结果集/更新数处理，控制行数与 SQL Array 的释放边界。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:600` | 596 | 保留 | 按结果集/更新数处理，控制行数与 SQL Array 的释放边界。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java:607` | 600 | 保留 | 按结果集/更新数处理，控制行数与 SQL Array 的释放边界。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/storedproc/StoredProcTaskExecutor.java:209` | 207 | 保留 | 先解析、校验超时，再计算缩短值，保持报错顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/storedproc/StoredProcTaskExecutor.java:212` | 207 | 保留 | 先解析、校验超时，再计算缩短值，保持报错顺序。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/storedproc/StoredProcTaskExecutor.java:415` | 413 | 保留 | 仅针对游标结果读取和标记截断，维持结果集资源和输出赋值。 |
| `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/storedproc/StoredProcTaskExecutor.java:418` | 415 | 保留 | 仅针对游标结果读取和标记截断，维持结果集资源和输出赋值。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java:70` | 69 | 保留 | 批量执行先判断错误，再区分运行时异常与其他异常，保留首错传播。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/domain/PipelineStepDefinition.java:37` | 34 | 保留 | 逐键按类型、文本和值转换次序取首个有效参数。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/domain/PipelineStepDefinition.java:56` | 54 | 保留 | 逐键按类型、文本和值转换次序取首个有效参数。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/domain/PipelineStepDefinition.java:59` | 54 | 保留 | 逐键按类型、文本和值转换次序取首个有效参数。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java:329` | 328 | 保留 | 重放能力、看门狗和成功回报各自的条件范围不同，保留写入与日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java:560` | 559 | 保留 | 重放能力、看门狗和成功回报各自的条件范围不同，保留写入与日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java:726` | 724 | 保留 | 重放能力、看门狗和成功回报各自的条件范围不同，保留写入与日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java:734` | 724 | 保留 | 重放能力、看门狗和成功回报各自的条件范围不同，保留写入与日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java:854` | 852 | 保留 | 按多地址、单地址、本地端口依次降级，转换仅在前一结果为空时执行。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskExecutionClient.java:860` | 858 | 保留 | 按多地址、单地址、本地端口依次降级，转换仅在前一结果为空时执行。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java:136` | 133 | 保留 | 事务内有锁、版本号与无 checksum 去重，保留查询和提前返回顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java:138` | 136 | 保留 | 事务内有锁、版本号与无 checksum 去重，保留查询和提前返回顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/TaskExecutionPool.java:117` | 114 | 保留 | 先等待、强制取消，再次等待；不能调整关停及中断时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/TaskReportBatcher.java:149` | 145 | 保留 | 批量提交结果在 finally 内通知所有等待者，保留异常和监视器范围。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:99` | 97 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:117` | 115 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:154` | 152 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:158` | 152 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:162` | 152 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:232` | 224 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:238` | 224 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:241` | 224 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:244` | 241 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:280` | 272 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:295` | 292 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:311` | 310 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java:331` | 310 | 保留 | 续租 CAS、熔断半开、失败计数和条件指标各依赖外层状态；保持原子操作、租约确认和日志顺序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/checkpoint/CheckpointPartitionGuard.java:69` | 67 | 保留 | 浮点先确认有限后再做精确转换，保持异常回退。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinator.java:65` | 63 | 保留 | 仅启用对应开关后读取续租熔断状态，保留短路和轮询流程。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxRepository.java:43` | 42 | 保留 | 按数据库方言初始化或按尝试次数持久化，保留 CAS 结果判定、重试和指标语义。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxRepository.java:48` | 42 | 保留 | 按数据库方言初始化或按尝试次数持久化，保留 CAS 结果判定、重试和指标语义。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxRepository.java:185` | 183 | 保留 | 按数据库方言初始化或按尝试次数持久化，保留 CAS 结果判定、重试和指标语义。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxRepository.java:205` | 183 | 保留 | 按数据库方言初始化或按尝试次数持久化，保留 CAS 结果判定、重试和指标语义。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:396` | 394 | 保留 | 先按优先级取直接值与 JSON，再区分类型；保留解析异常回退与转换时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:399` | 396 | 保留 | 先按优先级取直接值与 JSON，再区分类型；保留解析异常回退与转换时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:532` | 530 | 保留 | 先按优先级取直接值与 JSON，再区分类型；保留解析异常回退与转换时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:535` | 530 | 保留 | 先按优先级取直接值与 JSON，再区分类型；保留解析异常回退与转换时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:572` | 570 | 保留 | 先按优先级取直接值与 JSON，再区分类型；保留解析异常回退与转换时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:594` | 592 | 保留 | 先按优先级取直接值与 JSON，再区分类型；保留解析异常回退与转换时序。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractStageExecutor.java:235` | 232 | 保留 | 先验证列表再逐项筛选，有结果才返回不可变副本。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractStageExecutor.java:239` | 232 | 保留 | 先验证列表再逐项筛选，有结果才返回不可变副本。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractWorkerLoop.java:328` | 323 | 保留 | 只在顶层异常有消息时追踪根因并决定摘要格式。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java:51` | 48 | 保留 | 逐键拷贝及流式 JSON 判重、类型检查顺序不能改变。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java:107` | 105 | 保留 | 逐键拷贝及流式 JSON 判重、类型检查顺序不能改变。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java:110` | 105 | 保留 | 逐键拷贝及流式 JSON 判重、类型检查顺序不能改变。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/PipelineCompensationHook.java:202` | 198 | 保留 | 先处理非空类型再规范化并比较，保留优先返回的候选。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/PipelineStepFlowSupport.java:64` | 62 | 保留 | 只在选择器有效时查找；读取并移除属性后按类型转换。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/PipelineStepFlowSupport.java:99` | 97 | 保留 | 只在选择器有效时查找；读取并移除属性后按类型转换。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/PipelineVerifierHook.java:105` | 103 | 保留 | 在匹配的输出中合并有效键；逐项失败时只记录首个 fatal，保留异常隔离。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/PipelineVerifierHook.java:131` | 124 | 保留 | 在匹配的输出中合并有效键；逐项失败时只记录首个 fatal，保留异常隔离。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/SystemPipelineStepConfiguration.java:54` | 51 | 保留 | 仅在阶段配置是 Map 时逐条拒绝覆盖控制流参数并写入。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerBackpressureController.java:87` | 84 | 保留 | 先执行暂停或恢复再更新可用指标；保留异常边界。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerBackpressureController.java:112` | 109 | 保留 | 先执行暂停或恢复再更新可用指标；保留异常边界。 |
| `batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java:139` | 137 | 保留 | 优先显式主题，依次按类型与 worker code 回退。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java:147` | 144 | 保留 | 非白名单键才检查日志级别并跳过，保留合并次序。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/DispatchSecretResolver.java:35` | 34 | 保留 | 未绑定密钥时根据渠道配置区分拒绝与空绑定。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelConfigSupport.java:23` | 21 | 保留 | 非空值才转换并检查文本，保留转换次数与失败语义。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java:36` | 35 | 保留 | 重放与历史快照缺失的处理不同；流式解析保留状态标志、校验和异常边界。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java:39` | 35 | 保留 | 重放与历史快照缺失的处理不同；流式解析保留状态标志、校验和异常边界。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java:83` | 82 | 保留 | 重放与历史快照缺失的处理不同；流式解析保留状态标志、校验和异常边界。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java:89` | 88 | 保留 | 重放与历史快照缺失的处理不同；流式解析保留状态标志、校验和异常边界。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/HttpDispatchChannelAdapter.java:110` | 107 | 保留 | 仅 API_PUSH 添加相应凭据头，保持各字段判断与构造顺序。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/HttpDispatchChannelAdapter.java:119` | 107 | 保留 | 仅 API_PUSH 添加相应凭据头，保持各字段判断与构造顺序。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/LocalOutboxDispatchSupport.java:175` | 170 | 保留 | 配置了根目录时才解析真实路径并检查归属。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java:177` | 171 | 保留 | 符号链接告警和可选目录归属校验都依赖外层上下文，保留文件调用顺序。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java:192` | 187 | 保留 | 符号链接告警和可选目录归属校验都依赖外层上下文，保留文件调用顺序。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java:164` | 162 | 保留 | 缺配置时区别报错，严格模式内设置 known hosts；连接异常仍关闭资源。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java:349` | 343 | 保留 | 缺配置时区别报错，严格模式内设置 known hosts；连接异常仍关闭资源。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java:356` | 343 | 保留 | 缺配置时区别报错，严格模式内设置 known hosts；连接异常仍关闭资源。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapter.java:102` | 100 | 保留 | 缺配置时区分原因，TLS 开启后再按环境设置要求，保持发送异常边界。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapter.java:224` | 221 | 保留 | 缺配置时区分原因，TLS 开启后再按环境设置要求，保持发送异常边界。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java:86` | 80 | 保留 | 仅已有 ACK 证据才提交状态，并依据写入结果安排后续阶段。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/CompleteDispatchStep.java:72` | 69 | 保留 | 仅回执成功时更新文件状态并附可选回执码，随后记录审计。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:170` | 160 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:178` | 160 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:238` | 223 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:241` | 238 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:255` | 253 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:266` | 255 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:278` | 271 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:281` | 278 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:284` | 281 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:297` | 271 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStep.java:300` | 271 | 保留 | 外部发送前授权、失败后 CAS、未知结果恢复和下游状态严格有序；嵌套体现各数据库结果的分支。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStep.java:83` | 81 | 保留 | 仅有原始载荷时提取 secret；只有已冻结渠道才检查是否仍有效。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStep.java:127` | 126 | 保留 | 仅有原始载荷时提取 secret；只有已冻结渠道才检查是否仍有效。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/RetryDispatchStep.java:143` | 142 | 保留 | 先授权再调用外部渠道，按未知结果或持久化失败区分恢复与补偿。 |
| `batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/RetryDispatchStep.java:168` | 142 | 保留 | 先授权再调用外部渠道，按未知结果或持久化失败区分恢复与补偿。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java:49` | 47 | 保留 | 先判断非空再转换并解析，保留原来的转换次数与异常。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/jdbc/JdbcMappedExportSpec.java:159` | 155 | 保留 | 列表和 JSON 分支不同；仅需解析时检查空文本。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java:51` | 48 | 保留 | 仅配置了允许列表时拒绝缺失或不匹配地区，保留选值优先级。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java:194` | 192 | 保留 | SQL 扫描先判定 token 类型，再按转义规则推进游标。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java:235` | 234 | 保留 | SQL 扫描先判定 token 类型，再按转义规则推进游标。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/ExportArtifactIdentity.java:33` | 30 | 保留 | 只在非空元数据中解析并判断 Map，解析失败回退空对象。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java:195` | 190 | 保留 | 断点启用、已完成且文件存在、指纹匹配后才检查限额或复用，保持磁盘 I/O 与清理状态顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java:203` | 195 | 保留 | 断点启用、已完成且文件存在、指纹匹配后才检查限额或复用，保持磁盘 I/O 与清理状态顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java:205` | 203 | 保留 | 断点启用、已完成且文件存在、指纹匹配后才检查限额或复用，保持磁盘 I/O 与清理状态顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java:400` | 398 | 保留 | 按顶层与嵌套配置优先级解析，编码解析先校验格式再检查支持列表。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java:413` | 411 | 保留 | 按顶层与嵌套配置优先级解析，编码解析先校验格式再检查支持列表。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java:490` | 484 | 保留 | 按顶层与嵌套配置优先级解析，编码解析先校验格式再检查支持列表。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java:296` | 294 | 保留 | 配置映射、文本转换和分区列表的判断与循环步骤简短且顺序明确。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java:306` | 304 | 保留 | 配置映射、文本转换和分区列表的判断与循环步骤简短且顺序明确。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java:351` | 349 | 保留 | 配置映射、文本转换和分区列表的判断与循环步骤简短且顺序明确。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java:300` | 284 | 保留 | 加密失败清理半成品，演练路径计算摘要后删除文件；保留 finally 与异常边界。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java:365` | 362 | 保留 | 加密失败清理半成品，演练路径计算摘要后删除文件；保留 finally 与异常边界。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/DelimitedExportFormat.java:90` | 88 | 保留 | 仅启用累计字段且值非空时改变汇总值；保持分页回调与 writer 作用域。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/ExcelSheetWriter.java:173` | 171 | 保留 | 按列类型尝试转换，失败回退文本；保持样式及写值顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/ExcelSheetWriter.java:180` | 178 | 保留 | 按列类型尝试转换，失败回退文本；保持样式及写值顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/ExcelSheetWriter.java:186` | 184 | 保留 | 按列类型尝试转换，失败回退文本；保持样式及写值顺序。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/GenerateCheckpoint.java:89` | 81 | 保留 | 只对未完成位点解码，再核对文件、偏移和 cursor；失败回退首跑。 |
| `batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/format/JsonExportFormat.java:39` | 37 | 保留 | 非续跑时才写前缀并回退空快照，保持文件续写边界。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java:131` | 128 | 保留 | 仅列表分支逐项过滤并转换字段；lambda 不应提前执行。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java:310` | 306 | 保留 | 坏记录按跳过与失败分桶并保留可变列表引用，更新顺序影响计数与后续读。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java:316` | 306 | 保留 | 坏记录按跳过与失败分桶并保留可变列表引用，更新顺序影响计数与后续读。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java:430` | 424 | 保留 | 坏记录按跳过与失败分桶并保留可变列表引用，更新顺序影响计数与后续读。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java:435` | 424 | 保留 | 坏记录按跳过与失败分桶并保留可变列表引用，更新顺序影响计数与后续读。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/DatasetRuleEvaluator.java:118` | 114 | 保留 | 仅命中行数错误时附清单来源，允许字段集非空时才计算非预期字段。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/DatasetRuleEvaluator.java:294` | 289 | 保留 | 仅命中行数错误时附清单来源，允许字段集非空时才计算非预期字段。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationCoercions.java:137` | 136 | 保留 | 在允许值规则下按字段返回不同错误码，保留规则优先次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationCoercions.java:140` | 136 | 保留 | 在允许值规则下按字段返回不同错误码，保留规则优先次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/jdbc/JdbcMappedImportSpec.java:239` | 237 | 保留 | 映射优先级、输入格式回退和字段名字符扫描须保留分支顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/jdbc/JdbcMappedImportSpec.java:308` | 304 | 保留 | 映射优先级、输入格式回退和字段名字符扫描须保留分支顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/jdbc/JdbcMappedImportSpec.java:372` | 371 | 保留 | 映射优先级、输入格式回退和字段名字符扫描须保留分支顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/jdbc/JdbcMappedImportSpec.java:384` | 377 | 保留 | 映射优先级、输入格式回退和字段名字符扫描须保留分支顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java:530` | 508 | 保留 | RLS 暂存路径下核对 COPY 和 INSERT 行数；保留事务连接及语句资源边界。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java:540` | 508 | 保留 | RLS 暂存路径下核对 COPY 和 INSERT 行数；保留事务连接及语句资源边界。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java:190` | 188 | 保留 | 先解析显式步骤再回退隐式配置；压缩条目名按配置、metadata 优先级解析。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java:236` | 233 | 保留 | 先解析显式步骤再回退隐式配置；压缩条目名按配置、metadata 优先级解析。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java:263` | 261 | 保留 | 先解析显式步骤再回退隐式配置；压缩条目名按配置、metadata 优先级解析。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java:267` | 263 | 保留 | 先解析显式步骤再回退隐式配置；压缩条目名按配置、metadata 优先级解析。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java:307` | 305 | 保留 | 先解析显式步骤再回退隐式配置；压缩条目名按配置、metadata 优先级解析。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/preprocess/ImportPreprocessPipeline.java:311` | 307 | 保留 | 先解析显式步骤再回退隐式配置；压缩条目名按配置、metadata 优先级解析。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:133` | 132 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:267` | 260 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:355` | 351 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:533` | 530 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:550` | 540 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:665` | 663 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:806` | 799 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:816` | 815 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:988` | 984 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:989` | 988 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:1083` | 1082 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:1087` | 1082 | 保留 | 入口归属、租约 fence 内推进进度、清单身份验证和对账接管均有先后依赖；保持数据库、对象访问和日志次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:199` | 196 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:261` | 259 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:264` | 261 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:331` | 328 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:405` | 403 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:419` | 417 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:424` | 417 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ImportPreprocessObjectSource.java:459` | 457 | 保留 | 首片 BOM 和行边界、流式条件及编码回退有独立上下文，保持字节读写及资源关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java:163` | 160 | 保留 | 分区写入前授权，chunk 写入前核对数量，保留事务围栏与位点顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java:308` | 306 | 保留 | 分区写入前授权，chunk 写入前核对数量，保留事务围栏与位点顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:243` | 241 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:376` | 374 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:379` | 374 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:385` | 374 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:421` | 419 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:496` | 494 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:502` | 500 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:513` | 511 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:535` | 532 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:592` | 590 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:595` | 590 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java:598` | 595 | 保留 | 保留临时文件 finally 清理、格式与编码回退、对象流读取及分区过滤顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:105` | 99 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:122` | 119 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:161` | 148 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:183` | 182 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:246` | 239 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:319` | 317 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:324` | 317 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:363` | 361 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:377` | 375 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:381` | 375 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:397` | 394 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:400` | 397 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java:403` | 397 | 保留 | 来源校验、大小与格式分支决定范围读取、解密、spool 及编码策略，保持访问顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ReceiveStep.java:287` | 286 | 保留 | 流式载荷解析和内容回填按 token、输入格式、首个文本的顺序执行。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ReceiveStep.java:383` | 379 | 保留 | 流式载荷解析和内容回填按 token、输入格式、首个文本的顺序执行。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ReceiveStep.java:386` | 383 | 保留 | 流式载荷解析和内容回填按 token、输入格式、首个文本的顺序执行。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ReceiveStep.java:426` | 424 | 保留 | 流式载荷解析和内容回填按 token、输入格式、首个文本的顺序执行。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java:348` | 343 | 保留 | chunk 验证先累计计数再处理首错或阈值；writer 生命周期与返回位置不变。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java:367` | 362 | 保留 | chunk 验证先累计计数再处理首错或阈值；writer 生命周期与返回位置不变。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java:441` | 438 | 保留 | chunk 验证先累计计数再处理首错或阈值；writer 生命周期与返回位置不变。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java:445` | 438 | 保留 | chunk 验证先累计计数再处理首错或阈值；writer 生命周期与返回位置不变。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ValidateStep.java:532` | 529 | 保留 | chunk 验证先累计计数再处理首错或阈值；writer 生命周期与返回位置不变。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/DelimitedFormatParser.java:81` | 80 | 保留 | 仅首表头校验必需字段，尾部缓冲满足条件后再输出记录；保持解析器关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/DelimitedFormatParser.java:92` | 90 | 保留 | 仅首表头校验必需字段，尾部缓冲满足条件后再输出记录；保持解析器关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/DelimitedFormatParser.java:225` | 223 | 保留 | 仅首表头校验必需字段，尾部缓冲满足条件后再输出记录；保持解析器关闭。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ExcelFormatParser.java:208` | 204 | 保留 | 按列绑定、表头行和格式配置分别处理，保留缺字段回退及 SAX 行计数。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ExcelFormatParser.java:299` | 297 | 保留 | 按列绑定、表头行和格式配置分别处理，保留缺字段回退及 SAX 行计数。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ExcelFormatParser.java:315` | 312 | 保留 | 按列绑定、表头行和格式配置分别处理，保留缺字段回退及 SAX 行计数。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ExcelFormatParser.java:428` | 426 | 保留 | 按列绑定、表头行和格式配置分别处理，保留缺字段回退及 SAX 行计数。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ExcelFormatParser.java:433` | 428 | 保留 | 按列绑定、表头行和格式配置分别处理，保留缺字段回退及 SAX 行计数。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/FixedWidthFormatParser.java:67` | 65 | 保留 | 尾行缓冲与数值范围判定依赖外层前提；保留读写及失败次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/FixedWidthFormatParser.java:149` | 144 | 保留 | 尾行缓冲与数值范围判定依赖外层前提；保留读写及失败次序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:51` | 45 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:54` | 45 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:56` | 54 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:61` | 56 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:72` | 45 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:75` | 72 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/JsonFormatParser.java:84` | 45 | 保留 | 流式 records 与对象树回退共用 parser 状态，必须维持 token 消费顺序与关闭范围。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java:109` | 107 | 保留 | 优先覆盖值并按类型解析；字段列表只在存在有效值时返回。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java:112` | 107 | 保留 | 优先覆盖值并按类型解析；字段列表只在存在有效值时返回。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java:143` | 141 | 保留 | 优先覆盖值并按类型解析；字段列表只在存在有效值时返回。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java:291` | 280 | 保留 | 优先覆盖值并按类型解析；字段列表只在存在有效值时返回。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/XmlFormatParser.java:99` | 97 | 保留 | 解析提示优先于模板字段，再回退默认元素，保留文本转换顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/XmlFormatParser.java:106` | 104 | 保留 | 解析提示优先于模板字段，再回退默认元素，保留文本转换顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/XmlFormatParser.java:108` | 106 | 保留 | 解析提示优先于模板字段，再回退默认元素，保留文本转换顺序。 |
| `batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/support/ImportStageSupport.java:76` | 74 | 保留 | 仅模板配置为 Map 时读取并解析 chunk 大小，最后统一限制上界。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingBusinessCleanupTransaction.java:85` | 83 | 保留 | 事务持锁后封存 OPEN generation 并核对受影响行，保留顺序。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingOrphanCleaner.java:214` | 208 | 保留 | 仅有实际删除时记录日志及指标，保留清理批次和事务围栏。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessStepExecutionAdapter.java:121` | 117 | 保留 | 先解析对象再按结构展开 COMPUTE 参数，随后始终解析类型化载荷。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessStepExecutionAdapter.java:125` | 117 | 保留 | 先解析对象再按结构展开 COMPUTE 参数，随后始终解析类型化载荷。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessStepExecutionAdapter.java:127` | 125 | 保留 | 先解析对象再按结构展开 COMPUTE 参数，随后始终解析类型化载荷。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/metrics/ProcessMetrics.java:79` | 76 | 保留 | 指标延迟创建使用锁内双重检查，不能移出同步区。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/metrics/ProcessMetrics.java:98` | 95 | 保留 | 指标延迟创建使用锁内双重检查，不能移出同步区。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/metrics/ProcessMetrics.java:135` | 132 | 保留 | 指标延迟创建使用锁内双重检查，不能移出同步区。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:193` | 184 | 保留 | 事务内有遗留数据时才检查限流日志，有水位字段时才查询水位。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:257` | 255 | 保留 | 事务内有遗留数据时才检查限流日志，有水位字段时才查询水位。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:293` | 292 | 保留 | 零行执行策略和空参数回退独立判断；保持查询与失败路径。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:514` | 511 | 保留 | 零行执行策略和空参数回退独立判断；保持查询与失败路径。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:699` | 697 | 保留 | 类型化 payload 与旧上下文回退互斥，业务日和 metadata 按序放入参数。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:703` | 697 | 保留 | 类型化 payload 与旧上下文回退互斥，业务日和 metadata 按序放入参数。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:710` | 697 | 保留 | 类型化 payload 与旧上下文回退互斥，业务日和 metadata 按序放入参数。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java:758` | 751 | 保留 | 仅水位模式读取带水位的发布结果，再更新运行属性。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java:159` | 154 | 保留 | 输入转换与 DIRECT 模式约束须保持逐项报错次序。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java:225` | 224 | 保留 | 输入转换与 DIRECT 模式约束须保持逐项报错次序。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java:230` | 224 | 保留 | 输入转换与 DIRECT 模式约束须保持逐项报错次序。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSqlValidator.java:146` | 144 | 保留 | 分别从 PlainSelect 与 SetOperationList 读取各自 limit。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSqlValidator.java:153` | 151 | 保留 | 分别从 PlainSelect 与 SetOperationList 读取各自 limit。 |
| `batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/DefaultProcessStageExecutor.java:230` | 225 | 保留 | 存在计算步骤时先判断显式插件，否则回退属性。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/client/BatchPlatformClient.java:253` | 250 | 保留 | 先判断线程和停机预算再等待或停用，不能改变各阶段预算与调用顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/client/BatchPlatformClient.java:278` | 273 | 保留 | 先判断线程和停机预算再等待或停用，不能改变各阶段预算与调用顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfig.java:248` | 245 | 保留 | 仅在配置提供后转换并校验，错误顺序在此阶段固定。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:264` | 261 | 保留 | 容量和平台状态共同决定暂停，恢复须排除受阻分区并保持 poll 心跳。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:275` | 261 | 保留 | 容量和平台状态共同决定暂停，恢复须排除受阻分区并保持 poll 心跳。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:279` | 275 | 保留 | 容量和平台状态共同决定暂停，恢复须排除受阻分区并保持 poll 心跳。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:322` | 320 | 保留 | 先命中指标，再验证类型、有限性和非负值，保留缺失指标时状态。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:325` | 322 | 保留 | 先命中指标，再验证类型、有限性和非负值，保留缺失指标时状态。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:464` | 460 | 保留 | 线程存在、非当前线程及预算允许时才 join，保留中断与超时日志。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:473` | 460 | 保留 | 线程存在、非当前线程及预算允许时才 join，保留中断与超时日志。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java:252` | 250 | 保留 | 仅不可安全重放的任务核查并上报执行开始；保留异常和业务调用顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java:834` | 832 | 保留 | runtimeAttributes 存在时读取 trace，空值不写入 MDC。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherRetryCoordinator.java:90` | 89 | 保留 | 5xx 耗尽与退避中断分支独立，保持重试计数、调用和日志顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherRetryCoordinator.java:107` | 89 | 保留 | 5xx 耗尽与退避中断分支独立，保持重试计数、调用和日志顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/HttpAtomicHandler.java:146` | 140 | 保留 | 配置开启时才解析网络地址并判断，保留 DNS 调用及异常边界。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java:95` | 89 | 保留 | SQL 结果更新计数及词法状态转换具有上下文，保留事务与 buffer 顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java:148` | 147 | 保留 | SQL 结果更新计数及词法状态转换具有上下文，保留事务与 buffer 顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java:151` | 150 | 保留 | SQL 结果更新计数及词法状态转换具有上下文，保留事务与 buffer 顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java:154` | 153 | 保留 | SQL 结果更新计数及词法状态转换具有上下文，保留事务与 buffer 顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java:157` | 156 | 保留 | SQL 结果更新计数及词法状态转换具有上下文，保留事务与 buffer 顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java:182` | 179 | 保留 | SQL 结果更新计数及词法状态转换具有上下文，保留事务与 buffer 顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/builtin/HttpDispatchHandler.java:117` | 113 | 保留 | 失败快速退出和可选 DNS 校验各有外层条件；原异常边界不变。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/builtin/HttpDispatchHandler.java:141` | 135 | 保留 | 失败快速退出和可选 DNS 校验各有外层条件；原异常边界不变。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodec.java:28` | 27 | 保留 | 引用内的转义字符和结束引用状态不同，保持指针推进顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodec.java:29` | 28 | 保留 | 引用内的转义字符和结束引用状态不同，保持指针推进顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/idempotent/SdkIdempotentHandler.java:103` | 101 | 保留 | 未取得占位后区分已完成快照和执行中，保持返回及占位释放路径。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClient.java:187` | 184 | 保留 | 凭据存在才签名，HTTP 成功时才按目标类型反序列化。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClient.java:213` | 212 | 保留 | 凭据存在才签名，HTTP 成功时才按目标类型反序列化。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/scheduler/HeartbeatScheduler.java:125` | 119 | 保留 | 只在身份存在时提取能力标签，保持心跳字段及调用顺序。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java:47` | 44 | 保留 | Map 遍历时验证键类型并递归冻结；lambda 只在 Map 分支执行。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java:97` | 96 | 保留 | 先确认 outputs 存在且为数组，再校验并解析各条内容。 |
| `sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java:102` | 96 | 保留 | 先确认 outputs 存在且为数组，再校验并解析各条内容。 |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java:273` | 271 | 保留 | 分路由读取请求并返回冲突或接受，保持操作与响应时序。 |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java:281` | 277 | 保留 | 分路由读取请求并返回冲突或接受，保持操作与响应时序。 |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java:290` | 288 | 保留 | 分路由读取请求并返回冲突或接受，保持操作与响应时序。 |
| `sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java:296` | 294 | 保留 | 分路由读取请求并返回冲突或接受，保持操作与响应时序。 |



### Console：嵌套调用与构造表达式复核

321 条直接嵌套调用与 21 条构造命中，均已阅读完整未截断表达式以及所属最近方法、构造器或初始化器主体并按用途初判；额外覆盖的 267 条调用归为 118 个方法和 4 个初始化器组。匿名及异步回调仍需在实施前追查外层注册、事务、跨线程和调用链。重复 builder 可多次命中，数量不等于独立问题。声明式配置、简单字段转换、日志和链式处理不机械拆分。

| 位置或功能簇 | 可评估方案和必须保持的边界 |
|---|---|
| `DefaultConsoleWorkerApplicationService` 四个请求 | 直接使用 HTTP 响应，去掉恒等 `toResponse`；保持请求、解析、返回同一引用及后续事件发布顺序。 |
| `DefaultConsoleJobRecoveryService` 两处 | 可命名审批与重放请求；保留 `createPending`、审批校验、提交、刷新及参数左到右求值。 |
| `DefaultConsoleJobApprovalService` | 按原顺序分别命名 snapshot、pending 的序列化结果，再构造准备请求；异常时不能发生后续 prepare。 |
| `ConsoleFileQueryService` | 脱敏策略可在行映射内命名，不得提前到分页映射前或改变逐行查询、缓存和授权次序。 |
| `ConsoleRealtimeEventHub` | 丢失游标时的快照、取时和发送可具名；取时仍限原有条件分支，保持刷新先发布后更新 watch。 |
| 表格固定宽度校验和 Workflow 监控配置 | 可评估命名具有业务含义的谓词或字段结果；保留短路，监控字段逐 setter 按原顺序解析和抛错。 |
| 审计回调、用户创建事务回调与 SSE 订阅 | 保留事务状态判断内注册、异步生命周期、刷新及失败边界；不把回调中的调用提前。 |

其余补核主要是字段与 Excel 声明、投影和格式转换，按原有写法保留；配置写入、账号创建、通知派发及维护响应仍须保持各自的读取、创建、发送和写回顺序。以上为静态方案，不表示已实施或验证全部运行路径；必要的事务、外部调用及副作用需在具体改动前结合对应调用者和测试核对。

### Orchestrator：普通嵌套 if 的方法主体核查

789 个文件中定位 254 处，归为 143 个含命中的方法；均已阅读方法主体并作初判。长方法表已覆盖其中首批 49 处，其余按方法组列于下表。该扫描不包含直接 else-if，也不能说明全部生产方法或调用链已审完。各批过程数值已省略，以此最终口径为准。

#### 普通嵌套 if：追加 13 个方法主体复核

除上面 17 个长方法之外，以下 13 个方法、27 处嵌套已读取完整主体。下表均为初判，括号是行号。

| 方法 | 初判与边界 |
| --- | --- |
| `CountContinuityOutboxService.readContext:307` | 保留：只在字段存在时解析并验证，不能提前解析。 |
| `DefaultSchedulePlanBuilder.resolveExecutionDefinition:155` | 保留：查询兜底与已有定义的身份校验位于互斥分支。 |
| `DefaultSchedulePlanBuilder.resolveDynamicPartitionCount:241` | 保留：日志等级影响是否继续执行后续 resolver，不得改求值次数或提前返回。 |
| `DefaultSchedulePlanBuilder.firstPositiveInt:282` | 保留：转换异常捕获限定于非空分支。 |
| `DefaultSchedulePlanBuilder.firstNonNegativeLong:314` | 保留：解析与异常处理保持原分支。 |
| `DefaultSchedulePlanBuilder.firstText:330` | 保留：非空及非空白逐级判断；当前 `String.valueOf` 两次，不能假设输入 `toString()` 无副作用并合并。 |
| `ReadinessWaitService.sweep:97/104/117/127/152` | 可评估按恢复结果分阶段；记录探测、恢复、按 CAS 是否成功计数和 continue 语义保持不变。 |
| `TenantSchedulerSnapshotService.bottleneckReason:277` | 保留：归一化在非空组条件内，限制类型优先级不变。 |
| `WaitRelationshipSanitizer.sanitizeJson:85/91/96/107/109/112/113/114` | 可评估按日历/作业依赖分别命名处理；保持节点插入、解析、校验及异常抛出次序。 |
| `BatchDayOpenService.openMissingDays:108/112` | 保留：暂停分支先开启当日，后按需回查游标；不能提前执行回查。 |
| `BatchDayOpenService.openOne:188/192` | 保留：灾备规则优先，滚动日期存在查询先于灾备查询。 |
| `BatchDayOpenService.checkCalendarDependencies:317` | 保留：只在 WAIT_CUTOFF 分支读取截止时间并检查。 |
| `BatchDayOpenService.reconcileDependencyWaits:424/433` | 可评估按灾备、人工处理组织阶段；各条件调用及更新/continue 顺序必须不变。 |

#### 普通嵌套 if：再追加 7 个方法主体复核

| 方法 | 初判与边界 |
| --- | --- |
| `BatchDayOpenService.insertSkippedBatchDayIfNoPendingRequest:633` | 保留：仅插入成功后写审计和按原因打印，锁与检查顺序不变。 |
| `CalendarDependencyWaitGovernanceService.requestManualActionInTransaction:85` | 保留：已有请求的一致性判断决定幂等返回还是拒绝。 |
| `DataQualityCheckExecutor.execute:94` | 保留：仅在失败/错误时依据严重度分类，SHADOW 后处理顺序不变。 |
| `DefaultApprovalWorkflowService.submit:37/63` | 保留：插入前后两个幂等回查对应不同并发时点。 |
| `DefaultApprovalWorkflowService.markExecuted:164` | 保留：仅 CAS 未命中时回查并区分幂等终态。 |
| `DefaultCompensationService.markFailedAndLog:447` | 保留：首次更新失败且引用存在时才尝试去引用重写，成功后才更新内存实体。 |
| `DefaultCompensationService.replayDeadLetter:890` | 保留：证据校验与审计仅在已声明核验意图时触发。 |


#### 普通嵌套 if：追加治理与事务分支 7 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `DefaultCompensationService.resolveJobInstance:1132/1139` | 保留：优先按 ID 命中才不查询 instanceNo；各查询只在对应键存在时执行。 |
| `DefaultFileGovernanceService.presignFileDownload:162` | 保留：仅加密代理路径附加审批参数并写相应审计。 |
| `DefaultFileGovernanceService.resolveDispatchTask:684` | 保留：单任务与多任务的校验顺序不同。 |
| `DefaultRetryGovernanceService.autoRetryDueDeadLetters:284/327` | 保留：重试前后预算检查时间、单条新事务和 CAS 未命中 continue 不同。 |
| `DefaultRetryGovernanceService.calculateNextRetryAt:740/745` | 保留：溢出保护、循环短路与 jitter 顺序不变。 |
| `JobMonitoringAlertEmissionService.emitOnce:49` | 保留：已有实例时才 claim，锁→回查→claim→发告警顺序不变。 |
| `BatchDayReplayRangeOperationService.approve:246` | 保留：非待审批状态按审批人区分幂等返回与冲突。 |


#### 普通嵌套 if：追加重放分支 8 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `BatchDayReplayService.lockValidPreviewToken:351/356` | 保留：未消费令牌才检查过期及快照，锁和异常翻译顺序不变。 |
| `BatchDayReplayService.loadForUpdateWithOperationLock:1201` | 条件中的查询可命名局部量，锁仍只在关联 operation 存在时获取，先于 session 锁。 |
| `BatchDayReplayTerminalReconciler.advanceSessionCounts:186` | 保留：终态 CAS 成功后才日志，随后按条件刷新父操作。 |
| `WorkflowRunReplayPreviewService.lockMatchingPreviewToken:194` | 保留：已消费令牌仅在身份一致时幂等返回。 |
| `WorkflowRunReplayPreviewService.previewNode:602` | 候选：依赖解析可作为独立职责，但须保持 blocker 添加次序及配置解析/异常边界。 |
| `WorkflowRunReplayPreviewService.resolveDefinition:639` | 保留：最新配置策略才读缓存并按需查询。 |
| `WorkflowRunReplayPreviewService.selectedPlugin:684` | 保留：模板错误只在当前捕获范围转 blocker，其他类型保持原分派。 |
| `WorkflowRunReplayPreviewService.effectiveJobParams:792` | 保留：参数合并优先级及只捕获节点 JSON 异常的范围不变。 |


#### 普通嵌套 if：追加快照与传感器 8 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `WorkflowRunReplaySnapshotResolver.freezeSelectedDefinitions:246/255/264` | 可评估按快照类型分组校验；定义与快照遍历、验证和写入顺序及异常时机不变。 |
| `WorkflowRunReplaySnapshotResolver.localDate:420` | 保留：按类型解析，失败统一翻译，保留 catch 范围。 |
| `WorkflowRunReplaySnapshotResolver.instant:438` | 保留：空值、字符串和数字处理及失败翻译各自不同。 |
| `HttpPollSensorPolicy.parseHeaders:170` | 保留：只在对象节点上逐项检查禁止的 header，按原顺序插入。 |
| `HttpPollSensorPolicy.evaluateMatch:200/214` | 保留：status/JSON 两种语法、异常翻译和短路边界不同。 |
| `SensorStateMachine.apply:227` | 保留：错误次数未达阈值才计算退避，时间无效先失败返回。 |
| `SensorStateMachine.advanceDownstream:421/424` | 保留：逐节点跳空、区分 END 和普通节点；异常需向上回滚 WAIT 终态。 |
| `ChildJobLaunchSupport.resolveChildDedupKey:168` | 保留：仅单作业工作流查询根请求，回退优先级不变。 |


#### 普通嵌套 if：追加派发和认领 7 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `DefaultPartitionDispatchService.enrichPayload:411/419/425` | 保留：仅目标字段缺失时计算与写入，dryRun 由实例覆盖的优先级不变。 |
| `DefaultPartitionDispatchService.enrichBundleBinding:478/480` | 保留：先清除不可信绑定，按模板选择冻结快照，仅类型匹配才写。 |
| `DefaultTaskAssignmentService.assignWorkerInternal:139` | 保留：任务 CAS 后才 claim 分区，失败标记事务回滚。 |
| `DefaultTaskAssignmentService.consumeExternalOutcomeAuthorization:235/241` | 保留：仅缺少两项授权身份时按需读取父任务；解析失败转拒绝。 |
| `DefaultTaskAssignmentService.recordHeartbeat:348/363/370` | 保留：实例锁→回读→续租→尝试确认→启动状态写入，失败回滚位置不变。 |
| `DefaultTaskCreationService.translateOwnershipConflict:108` | 保留：只翻译明确的约束错误，并保留 cause 链遍历。 |
| `DefaultTaskCreationService.validateForCreate:136` | 保留：字段初始赋值、错误优先级与关联文件校验顺序不变。 |


#### 普通嵌套 if：追加任务结果与跨日依赖 8 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `DefaultTaskCreationService.resolveRelatedFileId:205/208` | 保留：DISPATCH 的文件键优先级与读取短路不变。 |
| `DefaultTaskOutcomeService.advanceHighWaterMark:307` | 保留：水位非空才尝试 CAS，未更新时仅 debug。 |
| `NodePartitionProgressCalculator.resolveTaskNodeCode:80` | 保留：仅单活动节点允许兜底，返回顺序不变。 |
| `TaskOutcomePayloadSupport.resolveRelatedFileId:111/114` | 保留：DISPATCH 键优先级和回退求值顺序不变。 |
| `TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs:130` | 保留：单分片空输出退回命令结果，多分片构造聚合。 |
| `BizDateArithmetic.resolveRange:102/115` | 保留：不同范围格式独立校验上限。 |
| `CrossDayDependencyResolver.resolveDependencySpec:186/189/193/206/215/219/229/231/234` | 可评估区分范围与单日期解析；保留 pinned 查询、required/optional 分支、上下文写入顺序和现有 catch 分类。 |
| `CrossDayDependencyResolver.resolvePinnedRange:331` | 保留：范围缺失时 required 失败、optional 跳过，不能预读其他日期。 |


#### 普通嵌套 if：追加 DAG、启动及条件解析 10 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `DefaultWorkflowDagService.isNodeReadyForDispatchInternal:247` | 保留：无入边的 START 与非 START 日志不同，现均就绪。 |
| `DefaultWorkflowDagService.canNeverFire:463` | 保留：缺失 START 的特殊成功语义及边匹配计数顺序不变。 |
| `DefaultWorkflowNodeDispatchService.restoreRerunCrossDaySnapshot:463` | 保留：仅字符串 alias 被复制；快照失败统一在当前 catch 翻译。 |
| `DefaultWorkflowRunLaunchService.resolveReplayResolution:88/93/108/112` | 可评估重放校验阶段；保留条件加载快照、拒绝操作的时点和异常重抛。 |
| `DefaultWorkflowRunLaunchService.launch:181` | 保留：仅无现有运行且非批准重放时查新定义。 |
| `DefaultWorkflowRunLaunchService.resolveWorkflowCalendarCode:266/274` | 保留：快照优先，快照存在但 calendar 缺失直接报错，不回退新定义。 |
| `DefaultWorkflowRunLaunchService.validateBundleFileReferences:317` | 保留：默认、节点和根参数的覆盖顺序。 |
| `WorkflowConditionEvaluator.matches:38` | 保留：JSON 解析错误只在当前 catch 降级到空参数。 |
| `WorkflowConditionEvaluator.splitListItems:248` | 保留：引号、嵌套深度与逗号扫描的状态机顺序。 |
| `WorkflowConditionEvaluator.splitTopLevel:284/289/291` | 保留：引号、深度、运算符扫描及 index 推进顺序。 |


#### 普通嵌套 if：追加图校验及载荷 10 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `WorkflowConditionEvaluator.indexOfTopLevel:509` | 保留：只在括号顶层匹配运算符。 |
| `WorkflowFanOutSupport.resolveDispatchChannel:109` | 保留：渠道键优先级不变；`String.valueOf` 目前两次，不能假设任意对象转换无副作用。 |
| `WorkflowGraphValidator.loadJobDefinitions:322` | 保留：仅 JOB 节点缺 code 记错误，查询仅在 code 存在且未缓存时发生。 |
| `WorkflowGraphValidator.detectCycles:503` | 可评估简化：当前 `if (dfsHasCycle(...))` 的主体为空，可直接调用 `dfsHasCycle(...)` 并忽略返回值；须保留外层未访问条件、遍历和调用次数，靠现有图校验测试核对。 |
| `WorkflowGraphValidator.validateCrossDayDeps:567` | 保留：仅存在范围字段时解析范围，错误添加顺序不变。 |
| `WorkflowNodePayloadBuilder.buildTaskPayload:79` | 保留：JSON 对象、非对象及解析失败写入不同字段。 |
| `WorkflowNodePayloadBuilder.fallbackFileIdLookup:212` | 保留：按 traceId 再按 batchNo 查找；不要合并两次 `String.valueOf` 的可观察调用。 |
| `WorkflowNodePayloadBuilder.mergeNodeParams:267/269` | 保留：仅节点参数和解析结果为 map 才合并，并保持已有键优先及异常边界。 |
| `WorkflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal:64` | 保留：原事务先锁当前运行、核对冻结图，再判断终态和 CAS/outbox。 |
| `WorkflowRunExecutionSnapshot.validatePinnedDependencies:268` | 保留：重放是否必须冻结与已冻结值有效性是不同检查。 |


#### 普通嵌套 if：追加快照、安全与资源回收 10 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `WorkflowRunExecutionSnapshot.validatePipelineIdentity:338` | 保留：catalog 预期存在与否使用不同校验。 |
| `WorkflowRunExecutionSnapshot.immutableValue:749` | 保留：递归冻结时 map key 必须是字符串，保留类型拒绝及遍历顺序。 |
| `WorkflowRunManagementApplicationService.advanceDownstreamAfterSkip:335/338/345/348` | 保留：过滤与就绪判断后才惰性取得派发服务，之后级联跳过并收敛。 |
| `WorkflowRunReportLock.resolveCalendarCode:56` | 保留：快照 calendar 缺失时才查询定义。 |
| `WorkflowWaitSensorSpecValidator.validateFileArrivalSensorSpec:148` | 保留：只有要求完整文件组时校验 group code，错误收集顺序不变。 |
| `ApiKeyVerifier.verify:191/213` | 保留：缓存到期淘汰、认证命中、touch 和旧哈希升级的顺序。 |
| `InternalAuthFilter.doFilterInternal:91/105` | 保留：API key 可用时先限定路径，认证失败不能降级至其他凭据；MDC 恢复范围不变。 |
| `JdbcPersistenceConflictDetectionAdapter.isUniqueConstraintViolation:22/25` | 保留：仅 SQL 异常检查状态与约束文本，按 cause 顺序返回。 |
| `S3GovernanceStorage.nextProgress:80/92` | 保留：REVISIT/FORWARD 各自更新 marker 和预算的状态机。 |
| `PartitionReclaimUnit.reclaim:82` | 保留：先实例围栏再查询，缺任务时 CAS 后直接返回，有任务时 MDC finally 清理。 |


#### 普通嵌套 if：追加配额和资源准入 10 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `KafkaOutboxPublisher.sanitizePayload:420` | 保留：仅 map 复制后屏蔽敏感键；解析失败回原文的 catch 不变。 |
| `DatabaseQuotaRuntimeStateService.refreshState:253/257/272/281` | 保留：非运行配额、自然日、滑动窗口更新及仅需持久化时写入。 |
| `BatchDaySettleScheduler.claimSettling:233/242/250` | 保留：先围栏回读，再区分活跃、空日、未完成预期和领取。 |
| `CrossDayDependencyReconciler.reconcileOneBound:150/166` | 保留：状态写入成功才发告警，失败和超时分别处理。 |
| `DefaultConcurrencyLimiter.checkFairShareLimit:103` | 保留：只在组限额启用时检查容量，命名条件已表达意图。 |
| `DefaultConcurrencyLimiter.checkTenantLimit:153` | 保留：仅额度启用时执行计数和预留。 |
| `DefaultDispatchAdmissionLimiter.check:40/53` | 保留：先扣队列令牌，成功后才检查租户令牌。 |
| `DefaultPartitionThrottle.check:91/119` | 保留：先租户后队列资源预留，前者拒绝不执行后者。 |
| `DefaultResourceScheduler.evaluate:130/134/154` | 保留：预览模式跳过有状态准入；检查和路由顺序不变。 |
| `DefaultWorkerSelector.select:107/131` | 保留：仅配置允许且主租户未命中时访问共享池，未匹配日志节流不改变计数。 |


#### 普通嵌套 if：追加队列、门闩和监控 8 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `WaitingPartitionDispatchScheduler.selectWaitingCandidates:193` | 保留：正常/演练扫描阶段、跨轮切换及游标 CAS 条件不变。 |
| `WaitingPartitionDispatcher.executeDispatch:68` | 保留：先实例锁、再父 Workflow 行锁和回读，准入成功才释放/写 Outbox。 |
| `WorkerCapabilityTagsAuditScheduler.auditCapabilityTags:82/94` | 保留：无租户记录的校验、租户上下文和日志抽样计数不同。 |
| `WorkerRegistryCache.getOrLoad:59` | 保留：空缓存不命中，读取失败回数据库，写入异常单独降级。 |
| `JobMonitoringScheduler.scanCategory:108` | 保留：查询成功才记录候选/发告警，查询与整体耗时的 finally 不同。 |
| `BatchDayGateService.evaluateAndApply:74/107/108` | 保留：冻结优先、细粒度依赖与日历级回退的执行顺序。 |
| `CutoffScheduleResolver.resolveCutoffTime:75` | 保留：日期覆盖优先于默认项，再回退调用方默认。 |
| `CutoffScheduleResolver.findOverride:101` | 保留：精确日期优先，重复记录的最后命中语义及 continue。 |


#### 普通嵌套 if：追加启动及渠道快照 10 个方法

| 方法 | 初判与边界 |
| --- | --- |
| `DefaultLaunchService.markJobInstanceFailedDueToDispatch:350` | 保留：实例 CAS 成功后才写终态事件、拒绝请求和审计。 |
| `DefaultLaunchService.prepareJobInstance:422` | 保留：重放与当前配置冻结路径、拒绝异常翻译和数据库写入顺序。 |
| `DefaultLaunchService.resolveConcurrentDuplicate:773` | 保留：只有未命中同键实例才识别活动工作流冲突；RERUN 不复用旧实例。 |
| `DispatchChannelSelection.explicitChannel:17` | 保留：按键顺序找到首个非空渠道。 |
| `DispatchChannelSnapshotService.pin:65` | 保留：bundle 数量不匹配才遍历确定首个缺失渠道。 |
| `DispatchChannelSnapshotService.captureForWorkflowReplay:109/133/134/136` | 可评估按重放渠道逐项冻结的职责分组；保留密钥校验/移除、禁用渠道跳过、写入顺序。 |
| `DispatchChannelSnapshotService.pinWorkflowReplay:177` | 保留：非 DISPATCH 带冻结值直接拒绝。 |
| `DispatchChannelSnapshotService.validateSecretBindings:292/295/298` | 保留：密钥引用、ID、版本逐项按当前次序验证。 |
| `DispatchChannelSnapshotService.collectChannels:318` | 保留：递归 map 与 iterable 顺序，渠道命中与继续递归都需执行。 |
| `DispatchChannelSnapshotService.selectedReplayParams:384` | 保留：原配置缺失回空，其他策略使用有效参数。 |


#### 普通嵌套 if：完成最后 10 个方法主体

| 方法 | 初判与边界 |
| --- | --- |
| `DispatchSecretSnapshotResolver.pin:62` | 保留：未解析凭据的目标与非目标渠道按不同语义处理。 |
| `DispatchSecretSnapshotResolver.collectChannels:99/101` | 保留：递归遍历顺序和两次 `String.valueOf` 的现有求值行为。 |
| `FileTemplateSnapshotService.pin:79` | 保留：bundle 保存集合，普通文件仅保存首个快照。 |
| `FileTemplateSnapshotService.pinWorkflowReplay:103/119` | 保留：非文件类型不得带冻结项，普通类型必须恰好一个。 |
| `FileTemplateSnapshotService.resolveReplayPlugin:296/300/302` | 保留：IMPORT/EXPORT 插件来源和优先级不同，直接配置优先于 schema。 |
| `JobDependencyWaitingLaunchService.decodeDependency:505/511` | 保留：同日和前业务日的快照错误/日期合法性分支。 |
| `LaunchBatchDayService.routeLateArrivalIfNeeded:523/527` | 可评估 CAS 未命中回查阶段命名；告警、CAS、最新触发类型回写及返回顺序不变。 |
| `LaunchParamResolver.resolveBatchNo:165` | 保留：参数键与业务日回退优先级，两次字符串转换行为不变。 |
| `LaunchParamResolver.resolveExpectedDurationSeconds:287` | 保留：显式 SLA 参数优先于作业超时，再回退 0。 |
| `FailureClassifier.classify:46` | 保留：显式 UNKNOWN、非法值的日志和 Throwable 分类优先级。 |



### Common、Trigger 和 security-scan：158 条调用与构造表达式

158/158 已阅读未截断表达式，并对数据库、调度、可变状态、事务和认证入口重点查阅所在方法或相关代码；不是 158 个方法均逐个完整阅读。148 条建议保留，10 条可评估命名局部值；同一方法的多个命中可以合并评估，尚非必须修改。

| 文件:行 | 类别／方法 | 判断 | 方案及原顺序边界 |
|---|---|---|---|
| `batch-common/src/main/java/io/github/pinpols/batch/common/security/SensitiveDataValidator.java:145` | `NESTED_ARG` / `scan` | 可评估 | 将 seen.put 的返回值命名为 previous，随后判断是否已访问，使状态写入显式；必须保留仅对 Map/Iterable 执行、put 在判断前且恰好一次。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/ingress/ImportIngressOwnershipRepository.java:23` | `NESTED_ARG` / `findActiveScope` | 可评估 | 先将 mapper.findActiveScope 的数据库返回行命名为 rows，再原位置转换 scopes，保持查询、转换和取首项顺序。 |
| `batch-common/src/main/java/io/github/pinpols/batch/common/storage/ingress/ImportIngressOwnershipRepository.java:29` | `NESTED_ARG` / `listProtectedScopes` | 可评估 | 先将 mapper.listProtectedScopes 的查询行命名，再传给 scopes；数据库调用和转换各执行一次，异常照原样传播。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java:150` | `NESTED_ARG` / `unregisterByWorkflowCode` | 可评估 | 在原 runManaged lambda 内先读取 TriggerDescriptor，再调用 deleteQuartzForDescriptor；读取、Quartz 删除和禁用定义的次序不变。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java:163` | `NESTED_ARG` / `pauseByWorkflowCode` | 可评估 | 在原 runManaged lambda 内先读取 TriggerDescriptor，再调用 deleteQuartzForDescriptor；读取、Quartz 删除和禁用定义的次序不变。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/DefaultTriggerService.java:77` | `NESTED_ARG` / `verifyCatchUpSubmission` | 可评估 | 完成入口校验后，先命名从 command 转换出的 LaunchRequest，再交给 verifyCatchUpSubmission；保持转换先于读取幂等键及原异常传播。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerRequestPersistence.java:134` | `NESTED_ARG` / `persistAndForwardInTransaction` | 可评估 | 将 buildAcceptedEntity 的结果命名后传给 mapper.insert，明确构造与落库两个步骤；保留现有事务、前置围栏和 insert/outbox 次序。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/readiness/ReadinessRecoveryService.java:52` | `NESTED_CTOR` / `recover` | 可评估 | 在原 try 内依次生成业务号、traceId 并命名 ScheduledTriggerCommand，再调用 launchScheduled；保持两次生成顺序和异常捕获范围。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/web/TriggerController.java:49` | `NESTED_ARG` / `launch` | 可评估 | 在原位置依次命名外部事件认证身份和转换后的输入，再传给 applicationService.launch；保持认证上下文读取先于输入转换及服务调用。 |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/web/TriggerController.java:66` | `NESTED_ARG` / `launchEvent` | 可评估 | 在原位置依次命名外部事件认证身份和转换后的输入，再传给 applicationService.launchEvent；保持认证上下文读取先于输入转换及服务调用。 |


### Orchestrator：原 366 条嵌套调用保留项复核

重新对 789 个文件运行同口径解析，406 条直接嵌套调用均与原始记录匹配，去除 210 字符摘要截断。此前暂保留的 **366 / 366 条已逐条查看完整表达式**，筛出 **15 条可评估局部命名**，其余 **351 条暂保留**；没有未读完整表达式。对可能涉及 DB、资源、锁、时间、标识或执行回调的 **34 个定位**补读完整方法主体（含 `log` 实体误分类的 3 处）；这不是对其余所有方法或被调用链的完整人工审计。完整表达式供本轮分类；以下列出新增可评估项。

原始分组为 88 条日志类、55 条 issue/decision 工厂、30 条集合 add、30 条 Map 写入、16 条构造器、15 条 Guard、14 条工具、1 条 builder、117 条其他。复核纠正：日志类中 `KafkaOutboxPublisher.recordDelivery:353/355/357` 的 `log` 是持久化实体，不是日志器；其序列化/净化调用简洁，按实体赋值保留。其余日志类 85 条确属日志/净化输出。集合 add 中大量为 DryRunFinding 构造，Map 中大量是快照/映射装配；不按嵌套形式机械提取。

| 位置 | 额外候选及原顺序约束 |
| --- | --- |
| `InstanceManagementApplicationService.java:208` | 可评估先命名作业实例查询结果，再按原语义 requireFound；仍先于分区查询。 |
| `WorkflowRunManagementApplicationService.java:174` | 可评估先命名节点运行查询结果，再按原语义 requireFound；保持运行状态校验之后。 |
| `JobDependencyWaitingLaunchService.java:167` | 可评估命名取消更新行数再校验；仍只在原请求不存在时执行。 |
| `JobDependencyWaitingLaunchService.java:172` | 可评估命名释放更新行数再校验；仍只在已启动分支执行。 |
| `JobDependencyWaitingLaunchService.java:180` | 可评估命名取消更新行数再校验；仍只在对应终态分支执行。 |
| `JobDependencyWaitingLaunchService.java:188` | 可评估命名检查更新行数再校验；仍在实例已准备分支内。 |
| `JobDependencyWaitingLaunchService.java:191` | 可评估命名载荷异常检查的更新行数再校验；与下方取消及请求更新保持顺序。 |
| `JobDependencyWaitingLaunchService.java:192` | 可评估命名取消更新行数再校验；保持前置 recordCheck 与后续拒绝写入次序。 |
| `TaskOutcomeNodeRunRecorder.java:103` | 可评估命名 nextRunSeq 数据库查询结果；保持各 setter 先后和读取时点。 |
| `TriggerLaunchRecoveryService.java:80` | 可评估命名待恢复载荷保护结果；先按原顺序求值 tenantId、requestId，再 protect，最后求值 trace 参数后插入。 |
| `DefaultPartitionDispatchService.java:174` | 可评估在计时回调内命名调度请求；不能移至计时回调外或改变捕获异常时点。 |
| `OutboxPollScheduler.java:192` | 可评估在现有 try 内命名锁配置；锁对象创建、加锁与 finally 保持顺序。 |
| `OutboxPollScheduler.java:223` | 可评估在现有 try 内命名锁配置；保留调度器各 catch、finally 和结果 holder。 |
| `OkHttpOrchestratorExternalHttpTransport.java:107` | 可评估命名受控 DNS 解析结果后冻结列表；保留解析次数、UnknownHostException 和地址绑定。 |
| `WaitingPartitionDispatcher.java:96` | 可评估命名 Outbox 事件键；先按原顺序取 trace，再构造事件键，仅 release 成功后执行。 |

上述 15 条仅为可评估的可读性方案，不是缺陷或已修改；如果局部变量引入后无法保留左到右实参计算、异常范围、事务、条件短路和副作用时点，就继续保留原代码。


## 本次整改实施记录

以本工作树的 `codex/control-flow-readability-review` 为范围，按本报告候选逐项核对后实施。前文标注“可评估”的内容是审查时快照，不等于每项都需要改；以下记录实施取舍。没有改变 API、数据契约或业务规则。

- 公共与触发：将 SQL 函数名采集器从校验主类移到同包专用类，遍历主体与原代码逐字对照；整理归属查询、敏感字段检查、Quartz 已存在恢复触发器及补跑审批阶段，删除纯转发，明确请求和查询结果。事务模板、Quartz 异常捕获及启动请求调用顺序保持原状。 `ReplayCapabilityDeclaration.covers` 按声明有效性和能力匹配分段，仍按原短路读取；触发入口统一错误出口、入库事务围栏和 Outbox 发布顺序直接可见的部分保留，避免为了拆分搬运可变事务状态。
- Console：按审批执行、Workflow 节点／边投影、补跑预校验和限流阶段梳理主流程；租户复制影响分析、作业复制字段映射、文件模板映射草稿和账号工作簿解析按内聚职责迁入同包辅助类。限流仍按登录、敏感操作、昂贵操作、文件操作顺序执行。涉及对象构造、取时或 setter 先后且拆开会提前求值的表达式保留。
- Orchestrator：整理批日结算短路、等待快照与游标、队列快照、等待分区候选收集、Kafka 投递回调和非幂等租约回收；删除无引用私有重载和无附加语义的转发。32 个长方法与 20 个职责聚集类型已逐项裁定，不按行数或私有方法数量拆分类；会造成事务、锁、状态或多值传递复杂化的阶段保持原实现。
- Worker：整理凭据解析、批量死信、回执确认、通道健康、清单绑定、扫描围栏内数据库调用和校验失败映射。扫描器的清单职责共享存储、租约和进度围栏，未为减少方法数强行拆类。
- 独立差异复核修正了恢复请求构造参数、跨日依赖查询及任务步骤编号的求值次序；保留对象构造中原有 ID 生成时机。新增回执三种格式、限流路径重叠顺序及 Kafka 成功投递 Worker 身份的行为断言。

**本轮验证**：受影响 11 个模块及其依赖完成编译和定向测试，合计 **1294 项通过、失败 0、错误 0、跳过 0**；补跑审批、扫描围栏、对象摘要及分发回执的 **7 个隔离容器集成场景通过**。扫描围栏使用 PostgreSQL 与本地文件对象存储；其他 Spring 集成场景使用隔离 PostgreSQL、Kafka、Redis 和 MinIO，其中对象摘要验证的后端为 S3，不等同目标环境或完整 CI 验收。格式化仅作用于本次变更 Java 文件；受影响 11 个模块的 PMD 和 Spotless 检查、文档结构、Java 可读性扫描与 `git diff --check` 通过。

规约检查实际命令：

```bash
./mvnw -o -ntp -pl batch-common,batch-console-api,batch-orchestrator,batch-trigger,batch-worker/atomic,batch-worker/core,batch-worker/dispatch,batch-worker/export,batch-worker/import,batch-worker/process,sdk/java/core pmd:check spotless:check -l /tmp/readability-remediation-static-20261110.log
python3 scripts/ci/check-java-readability.py --help
bash scripts/python.sh scripts/ci/check-docs-structure.py
git diff --check
```

`check-java-readability.py` 不提供帮助输出，上述首次命令实际执行了其默认全量检查；最终补充改动后又使用不带参数的命令复查通过。

定向测试实际命令：

```bash
./mvnw -o -ntp -pl batch-common,batch-console-api,batch-orchestrator,batch-trigger,batch-worker/atomic,batch-worker/core,batch-worker/dispatch,batch-worker/export,batch-worker/import,batch-worker/process,sdk/java/core -am '-Dtest=PrivateTempFilesTest,DefaultConsoleApprovalApplicationServiceTest,AuditAspectTenantFallbackTest,ConsoleAiControllerTest,ConsoleSessionRegistryTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,ConsoleApiExceptionHandlerTest,DataQualityCheckExecutorTest,DefaultCompensationServiceTest,DefaultRetryGovernanceServiceTest,LineageEvidenceServiceTest,WorkflowRunReplayPreviewServiceTest,WorkflowRunReplaySnapshotResolverTest,DefaultTaskCreationServiceTest,DefaultWorkflowRunLaunchServiceReplayTest,WorkflowNodeTerminalFinalizerTest,WorkflowRunExecutionSnapshotTest,OutboxPollSchedulerTest,CrossDayDependencyReconcilerTest,FileArrivalSensorPolicyTest,DefaultWorkerRegistryServiceTest,DispatchChannelSnapshotServiceTest,TriggerLaunchLagMonitorTest,TriggerGracefulShutdownTest,ActiveTaskLeaseRegistryTest,DispatchDeliveryMetricsTest,LocalOutboxDispatchSupportTest,ExportRegionResolverTest,GenerateStepTest,GenerateStepCheckpointTest,ExcelExportFormatTest,DatasetRuleEvaluatorControlRecordTest,DatasetRuleEvaluatorManifestRowCountTest,ImportIngressScannerTest,ImportIngressScannerPaginationTest,TaskDispatcherTest,ReadabilityFactoryBehaviorTest,SensitiveDataValidatorTest,ImportIngressOwnershipRepositoryTest,BatchSecurityPropertiesTest,QuartzMisfireRecoveryListenerTest,TriggerSchedulerFacadeTest,DefaultTriggerServiceTest,ReadinessRecoveryServiceTest,TriggerControllerTest,HttpTaskExecutorTest,TaskConsumerBatchExecutionCoordinatorTest,DispatchReceiptPollSchedulerTest,DispatchChannelHealthServiceTest,ImportScannerPropertiesTest,ImportScannerManifestBindingTest,ValidateStepTest,DefaultConsoleJobRecoveryServiceTest,DefaultConsoleJobApprovalServiceWorkflowTest,DefaultConsoleJobDefinitionMonitoringPolicyTest,WorkflowExecutionGraphAssemblerTest,WorkflowNodeStartEndCodeRuleTest,DefaultConsoleTenantConfigCopyServiceTest,TerminalNotificationDeliveryServiceTest,FixedWidthTemplateValidationTest,ConfigPackageExcelValidatorTest,ConfigPackageExcelValidatorWorkflowTest,ConsoleRateLimitFilterTest,ConsoleFilePreviewMaskingTest,TenantSchedulerSnapshotServiceTest,WorkflowNodeReplayOperationServiceTest,WorkflowLaunchWaitServiceTest,BatchDaySettleSchedulerTest,WorkflowGraphValidatorTest,CrossDayDependencyResolverTest,DatabaseQuotaRuntimeStateServiceTest,WaitingPartitionDispatchSchedulerRequestTest,WaitingPartitionDispatchSchedulerTenantIsolationTest,BatchDayReplayRangeOperationServiceTest,BatchDayReplayTerminalReconcilerTest,DefaultFileGovernanceServiceTest,JobDependencyWaitingLaunchServiceTest,RetryRequeueCoordinatorTest,TaskDispatchOutboxServiceBehaviorTest,OkHttpOrchestratorExternalHttpTransportTest,DataQualityRuleApplicationServiceTest,RelatedFileReferenceValidatorTest,BatchDayCatchupControlServiceTest,WorkflowRunManagementApplicationServiceTest,KafkaOutboxPublisherTest,PartitionReclaimUnitTest,SqlTransformComputeSqlValidatorTest,SqlTemplateExportSqlValidatorTest,SensorSqlValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/readability-remediation-unit-20261110.log
./mvnw -o -ntp -pl batch-trigger,batch-worker/import,batch-worker/dispatch,batch-worker/export -am '-Dtest=TriggerServiceIntegrationTest#shouldApprovePendingCatchUpAndKeepProcessingUntilRootIsCreated,ImportIngressScanPersistenceIntegrationTest#scan_shouldFinishApplySnapshot_beforeCleaningCycle+scan_shouldRecheckObjectVersion_beforeRegistration+scan_shouldWait_whenAuthoritativeManifestDisappearsDuringRead+expiredOwner_shouldBeFencedAndRollBackFileAndProgress,DispatchReceiptPollIntegrationTest#shouldKeepPendingUntilReceiverAcknowledges,S3ExportStorageIntegrationTest#shouldComputeCorrectSha256AfterWrite' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/readability-remediation-integration-20261110.log
```

上述 1294 项运行后，Console 又整理了批次补跑、AI 响应、认证过滤器、模板草稿和账号工作簿解析；随后针对最终代码重跑 6 个相关测试类，**92 项通过、失败 0、错误 0、跳过 0**。这 92 项与首轮部分重叠，不相加为不同用例数量；其余模块源代码未在首轮后改变。追加改动经独立源码对照，最终 Console PMD 和 Spotless 通过。

```bash
./mvnw -o -ntp -pl batch-console-api -am '-Dtest=ConsoleUserBatchProvisioningServiceTest,DefaultConsoleFileTemplateApplicationServiceTest,FixedWidthTemplateValidationTest,ConsoleAuthenticationFilterTest,DefaultConsoleAiApplicationServiceTest,DefaultConsoleJobApprovalServiceWorkflowTest' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/readability-console-final-tests-20261110.log
./mvnw -o -ntp -pl batch-console-api pmd:check spotless:check -l /tmp/readability-console-final-static-20261110.log
python3 scripts/ci/check-java-readability.py
```

最后整理 `ReplayCapabilityDeclaration.covers` 后，重新运行能力声明、重放能力、重放预览及 Worker 注册 4 个相关测试类，**96 项通过、失败 0、错误 0、跳过 0**；这些用例与首轮有重叠，不作不同用例累加。公共模块 PMD 和 Spotless 复查通过。

```bash
./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=ReplayCapabilityDeclarationTest,WorkflowReplayCapabilityServiceTest,WorkflowRunReplayPreviewServiceTest,DefaultWorkerRegistryServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/readability-replay-final-tests-20261110.log
./mvnw -o -ntp -pl batch-common pmd:check spotless:check -l /tmp/readability-replay-final-static-20261110.log
```

先前 481 项及 9 项是本次后续编辑之前的证据，不替代以上新运行结果。结构扫描及已审候选不代表全工程所有未命中方法和全部运行路径已人工验证。


## Console 最终候选闭环

28 个长方法、16 个职责类型和 5 处长条件均已逐项裁定，未裁定 0；候选之间可重叠。保留意味着当前抽取收益不足或会增加事务、状态及生命周期复杂度，并非未检查。

### Console：28 个长方法的实施裁定

| # | 方法 | 本轮决定及原因 |
|---:|---|---|
| 1 | `DefaultConsoleQueryApplicationService.buildTimeline` | 保留：按来源汇总再排序，映射阶段清楚；拆分没有明确净收益。 |
| 2 | `ConsoleClusterDiagnosticService.instanceDiagnosis` | 保留：授权、查询、诊断顺序清楚，构建结果仅一次且需大量上下文。 |
| 3 | `DefaultConsoleApprovalApplicationService.approve` | 已提取具名审批执行阶段；执行完成后原位置标记 executed。 |
| 4 | `ConsoleSecurityConfiguration.consoleSecurityFilterChain` | 保留：声明式安全链及匹配顺序构成配置本身。 |
| 5 | `ConsoleAiAttachmentService.upload` | 保留：两个事务夹持对象写入，当前阶段直接可见，移动会增加状态传递。 |
| 6 | `DefaultConsoleAiApplicationService.executeChat` | 保留：准入、上下文、计费、模型与降级已有协作阶段，不能打散线程/计费和审计时序。 |
| 7 | `DefaultConsoleAiApplicationService.completeApprovedChat` | 已提取响应组装阶段，原位置创建并逐字段设置。 |
| 8 | `DefaultConsoleJobApprovalService.catchUpBatchDay` | 已提取冻结目标逐项提交阶段；保留准备、start、提交失败标记、accept 顺序。 |
| 9 | `DefaultConsoleJobApprovalService.continueBatchDay` | 已提取全部待发项预校验；仍先全部校验后逐项发送。 |
| 10 | `DefaultConsoleJobDefinitionApplicationService.create` | 保留：校验、参数解析与对象初始化互相依赖，单独封装需额外搬运多个中间值，事务写序已清楚。 |
| 11 | `DefaultConsoleJobDefinitionApplicationService.copyWithOverrides` | 已将覆盖参数装配迁至同包 `JobDefinitionCopyOverrideMapper`。 |
| 12 | `TerminalNotificationDeliveryService.deliver` | 已提取发送阶段；租户 claim/finish 和发送异常捕获原样保留。 |
| 13 | `TwilioSmsProvider.send` | 保留：逐人发送及失败立即返回已经直线表达。 |
| 14 | `WebhookDeliveryRelay.retryOne` | 保留：取数、发送、重试收尾均为单阶段业务顺序。 |
| 15 | `ConsoleAuthenticationFilter.doFilterInternal` | 已提取 SSE 和 cookie 鉴权阶段；每条 filterChain 调用仍在外层 try/finally。 |
| 16 | `WorkflowExecutionGraphAssembler.assemble` | 已提取节点与边投影阶段，保持先节点后边与错误顺序。 |
| 17 | `WorkflowNodeStartEndCodeRule.validate` | 已提取逐行收集与组内数量校验，保持 LinkedHashMap 及 issue 追加顺序。 |
| 18 | `WorkflowDagValidator.validate` | 保留：现有校验阶段和错误优先级清楚。 |
| 19 | `TenantConfigPackageExcelApplyService.applyWorkflows` | 保留：逐行版本 CAS、节点/边写入已有独立协作者；插入/更新计数在循环内，拆分需额外传递可变状态。 |
| 20 | `TenantWorkflowDefinitionApplySupport.upsertWorkflowDefinition` | 保留：已是专用协作者，图写入已有独立阶段，版本 CAS 与快照按事务顺序排列。 |
| 21 | `ConfigPackageExcelValidator.addJobDependencyIssues` | 已将全图依赖环检查与逐行引用校验分开。 |
| 22 | `ConfigPackageSampleDataFactory.putCommonFoundation` | 保留：声明式示例资料。 |
| 23 | `ConfigPackageSheetSpecs.buildJobGuides` | 保留：声明式列清单。 |
| 24 | `ConfigPackageSheetSpecs.buildFileTemplateGuides` | 保留：声明式列清单。 |
| 25 | `WorkflowGraphTopologyValidator.validateWorkflowGraph` | 保留：已是拓扑专用协作者；索引、邻接、节点集及 issue 顺序共享上下文，不为行数新建状态对象。 |
| 26 | `DefaultWorkflowDefinitionService.fullUpdate` | 保留：设计锁、CAS、图替换、版本快照和事件已按次序排列并复用 writeSupport。 |
| 27 | `ConsoleRateLimitFilter.doFilterInternal` | 已提取四类有序限流阶段；新增重叠路径短路顺序测试。 |
| 28 | `ConsoleIdempotencyInterceptor.preHandle` | 保留：现有协作者与早退表达状态边界。 |

### Console：16 个职责类型的实施裁定

| # | 类型 | 本轮决定及原因 |
|---:|---|---|
| 1 | `DefaultConsoleTenantConfigCopyService` | 已将差异影响计算移至 `TenantConfigCopyImpactAnalyzer`，租户读取与差异流程留原类。 |
| 2 | `DefaultConsoleAiApplicationService` | 响应装配已提取；模型调用、超时、流消费和 fallback 共用线程池、deadline、取消及提示词/结果处理，拆分需迁移生命周期和依赖，保留原归属。 |
| 3 | `TenantConfigPackageExcelApplyService` | 保留：各 sheet/缓存失效已有用例顺序，外层事务保持。 |
| 4 | `ConsoleClusterDiagnosticService` | 保留：诊断入口及缓存职责各自清楚，不为结果构造增加协作者。 |
| 5 | `ConsoleOpsQueryService` | 保留：多个查询用例各投影自身结果，部分共享本地化与脱敏，未形成可独立测试的共享投影职责。 |
| 6 | `DefaultConsoleFileTemplateApplicationService` | 已提取导入/导出映射草稿至 `FileTemplateMappingDraftBuilder`，复用同一 ObjectMapper 及 blankToNull。 |
| 7 | `ConsoleFileQueryService` | 保留实时进度协作：assemble/resolve 已有具名阶段且涉及授权、实时桥与 fileInfo 查询顺序；错误记录脱敏策略已就地命名。 |
| 8 | `DefaultConsoleJobDefinitionApplicationService` | 已提取复制覆盖字段装配、合并新旧依赖一致性校验。 |
| 9 | `ConsoleUserBatchProvisioningService` | 已将工作簿解析簇提至 `ConsoleUserBatchWorkbookParser`，共用原表头、行数上限和错误工厂。 |
| 10 | `DefaultConsoleConfigApplicationService` | 保留：多个公共用例分别承担配置发布与密钥版本，不按私有方法数拆。 |
| 11 | `DefaultTenantConfigPackageExcelService` | 保留：模板、validator、writer 与 apply 已有职责分工。 |
| 12 | `TenantJobDefinitionApplySupport` | 保留：本身是作业导入专用辅助类，依赖及监控操作受同一外层事务控制。 |
| 13 | `ConfigPackageExcelValidator` | 已抽依赖图校验；保留既有 row/monitoring/topology 分工与 issue 顺序。 |
| 14 | `ConfigPackageSampleDataFactory` | 保留：示例 sheet 和初始化声明，不按方法数拆类。 |
| 15 | `ConfigPackageSheetSpecs` | 保留：声明式模板列和规则，顺序是契约。 |
| 16 | `DefaultWorkflowDefinitionService` | 保留：复用写入协作者；锁、CAS、快照和缓存事件顺序清楚。 |

### Console：5 处长条件的实施裁定

| # | 位置 | 本轮决定 |
|---:|---|---|
| 1 | `BatchDayCatchUpReconciliation.frozenAutoCommand` | 已命名冻结结构无效判断，身份比较仍按短路延后。 |
| 2 | `BatchDayCatchUpReconciliation.frozenApproval` | 已命名审批身份无效判断，payload 校验仍按原短路延后。 |
| 3 | `DefaultConsoleJobApprovalService.continueBatchDay` | 已命名冻结身份无效判断，policy 判断仍位于其后。 |
| 4 | `WorkflowExecutionGraphAssembler.assemble` | 已命名快照身份无效判断，节点/边检查仍在原顺序。 |
| 5 | `ConsoleOidcAuthenticationSuccessHandler.onAuthenticationSuccess` | 保留：认证 pattern 变量作用域和 issuer/subject 的惰性校验组成单一身份边界；再拆会重复失败处理或增加状态传递。 |
