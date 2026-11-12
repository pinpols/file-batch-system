# Java 可读性阶段 9 实施计划（2026-11-11）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 2026-11-11 复评的 R1–R10、R12–R23、R25–R27 和次要候选做纯结构整改，降低阅读成本，不改变任何外部行为。

**Architecture:** 每个任务只动一个职责。先用 characterization 测试锁住当前行为（重构任务里的测试在改代码前就应通过），再搬代码。只有 Task 9 在 `batch-common` 新增一个跨模块工具类，其余都是类内重构。

**Tech Stack:** Java 21、Spring Boot、MyBatis、JUnit 5 + AssertJ + Mockito、Maven（`./mvnw`）、Spotless。

**Spec:** [Java 可读性复评（2026-11-11）](../audit/java-readability-reassessment-2026-11-11.md)。规则来自 [`coding-conventions.md` §1.3–§1.7](../coding-conventions.md#13-长方法与参数密度) 和[可读性路线图](java-readability-refactoring-roadmap-2026-08-12.md)的“不变红线”。

**状态：** 2026-11-12 更新。执行方式为子代理驱动，已授权提交、推送、开草稿 PR 和合并 PR 链。PR-1 #1354（Task 1–3）、PR-2 #1355（Task 4–8、13）和 PR-3 #1356（Task 9）已合入 main，文档 PR #1353 已合入 main（9b9f2d8c1）；PR-4 #1357（Task 10–12、14）已合入 main（9346767fe）；PR-5（Task 15–17）在分支 `chore/java-readability-p9-batch5`，将作为草稿 PR 直接以 main 为目标；Task 19–24 和 18 未开工；R13（Task 12）已撤销。

**裁定（见[复评决策记录](../audit/java-readability-reassessment-2026-11-11.md#决策记录2026-11-11)）：** R4 按语义区分写法，放在最后的 Task 18；次要候选纳入本轮，作为 Task 15–17。

**不在本计划范围：** R11（编排器代理和查询 Controller 按领域拆分），已决定暂缓，登记在 todo-master；复评中发现的 SQL 拆分器反斜杠转义疑似问题，需另案复现。

## Global Constraints

- 结构重构与行为变更分开提交；执行中发现的行为缺陷另开 issue，不并入本计划的提交。
- Console/内部 API 的路径、JSON 字段、状态码、错误码、错误文案和鉴权语义不变。
- 提取前后保持求值顺序、调用次数、短路顺序、异常时机和副作用（§1.4、§1.7）。
- 已持久化或跨进程传递的状态字符串保持原值；`equalsIgnoreCase` 的比较保持忽略大小写（§1.7）。
- 事务边界、隔离级别、传播行为和回滚语义不变。
- 不新增本计划未列出的公共类型或公共方法；生产协作者只用构造器注入；不写全限定类名；解释性注释用中文。
- 每个任务结束时，所改模块的 Spotless check 必须是 clean。
- 测试命令统一写法：`./mvnw -o -ntp -pl <模块> -am -Dtest=<类或通配> -Dsurefire.failIfNoSpecifiedTests=false test`。

## Review Focus

1. **状态比较的大小写**：原来用 `equalsIgnoreCase` 的地方，换成枚举后仍须忽略大小写。Task 1 Step 2 逐处核对。
2. **守卫条件的短路顺序**：OIDC 的 `getIssuer()` 判空必须在 `toString()` 之前；Trigger 的 `request()` 判空必须在读 `triggerType()` 之前。Task 6、Task 7 的负向用例覆盖。
3. **唯一键冲突后事务已中止**：`prepareRun` 的 catch 必须留在 `transaction.execute` 外面，否则查询胜出者时会命中已中止的事务。Task 8 Step 1 用注释锁定，Step 3 跑原有测试。
4. **时间线同时刻的先后顺序**：拆分 `buildTimeline` 后，时间相同的条目仍按原来的来源顺序输出。Task 5 Step 1 覆盖。
5. **删掉的判空其实有生产路径会走到**：条件装配的 bean、`ObjectProvider`、`@Autowired(required = false)`、手工 `new` 的生产代码都可能传 null。Task 10 Step 1 逐处查构造路径，查不清的保留判空。

另外，DSL 引用重复出现时，Excel 校验对每次匹配各报一条问题，由 Task 9 Step 1 覆盖。

## 准备

- [ ] 在 worktree `../file-batch-system-readability` 的 `chore/java-readability-phase-9` 分支（基于 `origin/main`）上工作。
- [ ] 基线编译：`./mvnw -o -ntp -DskipTests install -pl batch-common,batch-orchestrator,batch-console-api,batch-trigger -am`，预期 `BUILD SUCCESS`。

PR 划分：Task 1–3 合成 PR-1（低风险）；PR-1 合入后，Task 4–8 和 Task 13 合成 PR-2；Task 9 单独作为 PR-3；Task 10–12、14 合成 PR-4（去掉多余防御和啰嗦写法）；Task 15–17 合成 PR-5（次要候选）；Task 19、21、22 合成 PR-6（转发和冗余写法）；Task 20 单独作为 PR-7（涉及出站安全代码）；Task 23 先按核心流程 L1–L6 分层、再按支撑功能分包、最后 Controller，每批一个 PR；Task 24 合入 PR-6；Task 18 在以上全部合入后最后执行，拆成 2–3 个 PR（如 common+sdk+trigger / orchestrator / console-api+workers），不再每个模块一个 PR。逐处候选清单在 [`java-readability-reassessment-2026-11-11-candidates.csv`](../audit/java-readability-reassessment-2026-11-11-candidates.csv)，执行时把每行的结论（删除 / 保留及原因）填进 `decision` 列。提交、推送和合并按 `git-pr-workflow` 执行，每次都要有当次授权。

---

### Task 1：状态字面量改用枚举（R5），并登记计划

**Files:**
- Modify：下表“替换”列中的生产类
- Modify：`docs/plans/java-readability-refactoring-roadmap-2026-08-12.md`（新增 §7 链接本计划）
- Modify：`docs/plans/todo-master.md`（“Java 可读性阶段 9”一行的状态）
- Modify：`docs/audit/java-readability-reassessment-2026-11-11.md`（R5 逐处结论）

| 位置 | 字面量 | 候选枚举 |
|---|---|---|
| `DefaultConsoleApprovalApplicationService:89、218、227` | `PENDING`、`REJECTED`（忽略大小写） | `ApprovalCommandStatus` |
| `DefaultConsoleJobApprovalService:260`、`BatchDayCatchUpReconciliation:47` | `RUNNING`（operationStatus） | `ReplayOperationStatus` |
| `DefaultWorkflowDagService:583、603、622`（忽略大小写）、`WorkflowRunStuckReconciler:151` | `FAILED`（节点状态） | `WorkflowNodeRunStatus` |
| `FileGroupExpectationService:220`、`ExternalEventExpectationService:273`、`LaunchBatchDayService:383`（忽略大小写） | `FAILED`（dayStatus） | `BatchLifecycleStatus` |
| `BatchDayReplayService:512`、`BundleArrivalLauncher:409` | `FAILED` | 按字段来源确定 |
| `JobExecutionQueryService:100、112、113` | `RUNNING`（stage/step 状态） | `PipelineRunStatus` / `StepInstanceStatus`，按字段来源确定 |
| 不替换：`ResultVersionPromoteService:47、76`、`DefaultConsoleConfigSyncApplicationService:241`、`AuditAspect:200`、`DefaultCompensationService:1171` | — | 没有对应枚举，本计划不新增枚举 |

- [ ] **Step 1：逐处确认字段与枚举对应。** 只有当该字段在写入端（Mapper/实体/状态机）使用的正是这个枚举的 `code()` 时才替换；对不上的改为“不替换”，把原因写进复评文档 R5。
- [ ] **Step 2：替换。** 严格比较写成 `Enum.X.code().equals(value)`；原来忽略大小写的写成 `Enum.X.code().equalsIgnoreCase(value)`。常量放在比较左侧，保持对 null 安全。
- [ ] **Step 3：跑受影响模块的测试。**
  `./mvnw -o -ntp -pl batch-console-api -am -Dtest='DefaultConsoleApprovalApplicationService*Test,DefaultConsoleJobApprovalService*Test,BatchDayCatchUp*Test,JobExecutionQueryService*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
  `./mvnw -o -ntp -pl batch-orchestrator -am -Dtest='DefaultWorkflowDagService*Test,WorkflowRunStuckReconciler*Test,FileGroupExpectation*Test,ExternalEventExpectation*Test,LaunchBatchDay*Test,BatchDayReplayService*Test,BundleArrivalLauncher*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
  预期：`Failures: 0, Errors: 0`。
- [ ] **Step 4：更新文档。** 路线图新增“§7 2026-11-11 阶段 9”，写一段说明并链接本计划；todo-master 的“Java 可读性阶段 9”一行（2026-11-11 已登记）把状态改为“PR-1 进行中”。
- [ ] **Step 5：提交** `refactor: compare persisted statuses through enum codes`（docs 文件一起提交）。

### Task 2：吞掉的异常写明原因（R6）

**Files:**
- Modify：`grep -rn 'catch (Exception ignored)' --include='*.java' batch-* sdk/java | grep /main/` 列出的 48 处

- [ ] **Step 1：逐处归类，三选一。**（a）预期内的失败，可以忽略：在 catch 块里加一行中文注释说明为什么可以忽略。（b）能确定实际只会抛某个更窄的异常：缩小捕获类型，并加注释。（c）忽略后会影响排障：改成 `log.debug`，带上下文字段，不打印敏感值。三种都不改变返回值，也不改变异常是否继续传播。
- [ ] **Step 2：检查有没有漏掉的。**
  `grep -rn -A1 'catch (Exception ignored)' --include='*.java' batch-* sdk/java | grep /main/ | grep -vE 'ignored\)|//|log\.' `
  预期：没有输出（每处 catch 的下一行都是注释或日志）。
- [ ] **Step 3：跑测试。** 对每个改动的模块运行 `./mvnw -o -ntp -pl <模块> -am test`，预期 `Failures: 0, Errors: 0`。
- [ ] **Step 4：提交。** 按模块分开提交，`refactor(<模块>): document intentionally ignored exceptions`。

### Task 3：拆开 `SqlFunctionNameCollector` 的 null 组合（R10）

**Files:**
- Modify：`batch-common/src/main/java/io/github/pinpols/batch/common/sql/SqlFunctionNameCollector.java:69-112`
- Create：`batch-common/src/test/java/io/github/pinpols/batch/common/sql/SqlFunctionNameCollectorTest.java`

**Interfaces:**
- 不变：`static Set<String> collect(Statement statement)`（package-private）
- 新增私有方法（在匿名 visitor 内）：`<S> void acceptGroupingClauses(Expression groupBy, Expression having, S context)`、`<S> void acceptOrderingClauses(List<OrderByElement> orderBy, Limit limit, Offset offset, S context)`
- 删除：`acceptSetOperationScalarClauses`、`acceptScalarClauses`

- [ ] **Step 1：写 characterization 测试。** 用 `CCJSqlParserUtil.parse(...)` 得到 `Statement`：
  - `collect_includesFunctionsInGroupByAndHaving`：`select a from t group by lower(a) having count(*) > 1` → 包含 `lower`、`count`
  - `collect_includesFunctionsInSetOperationOrderBy`：`select a from t union select a from u order by pg_terminate_backend(1)` → 包含 `pg_terminate_backend`
  - `collect_includesFunctionsInLimitAndOffset`：`select a from t limit abs(1) offset abs(2)` → 包含 `abs`
  - `collect_normalizesQuotedNames`：`select "PG_READ_SERVER_FILES"('x')` → 包含 `pg_read_server_files`
- [ ] **Step 2：在改代码前运行，确认测试通过。** `./mvnw -o -ntp -pl batch-common -Dtest=SqlFunctionNameCollectorTest test`，预期 PASS。
- [ ] **Step 3：拆方法。** `visit(PlainSelect)` 依次调用 `acceptGroupingClauses`、`acceptOrderingClauses`，顺序与原来的 `acceptScalarClauses` 一致；`visit(SetOperationList)` 只调用 `acceptOrderingClauses`。
- [ ] **Step 4：再跑测试。** 跑同一命令，再加上 `./mvnw -o -ntp -pl batch-orchestrator,batch-worker/export,batch-worker/process -am -Dtest='SensorSqlValidatorTest,SqlTemplateExportSqlValidatorTest,SqlTransformComputeSqlValidatorTest,ReadabilityFactoryBehaviorTest' -Dsurefire.failIfNoSpecifiedTests=false test`，预期全部 PASS。
- [ ] **Step 5：提交** `refactor(common): split scalar clause traversal by clause group`。

### Task 4：分区计数值对象（R1）

**Files:**
- Create：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionOutcomeCounts.java`
- Modify：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskOutcomeInstanceProgressor.java:59-115`
- Create：`batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionOutcomeCountsTest.java`

**Interfaces:**
- Produces（package-private `record`）：`PartitionOutcomeCounts(long total, long success, long failed, long cancelled, long terminated)`
  - `static PartitionOutcomeCounts fromStatusRefs(List<PartitionStatusRef> refs)`：total 为 `refs.size()`，其余四项按 `PartitionStatus.X.code().equals(ref.partitionStatus())` 计数
  - `static PartitionOutcomeCounts fromInstanceCounters(JobInstanceEntity instance)`：读 `expected/success/failed/cancelled/terminatedPartitionCount`，null 记为 0
  - `long broadFailed()` = failed + cancelled + terminated；`long finished()` = success + broadFailed；`boolean allFinished()` = total > 0 且 finished == total
  - `boolean matches(PartitionStatusSummary summary)`：summary 为 null 返回 false；否则 total/success/failed/cancelled/terminated/broadFailed 六项全部相等才返回 true

- [ ] **Step 1：写失败测试** `PartitionOutcomeCountsTest`：
  - `fromStatusRefs_countsEachTerminalStatus`：状态为 [SUCCESS, SUCCESS, FAILED, CANCELLED, TERMINATED, RUNNING] → total 6、success 2、failed 1、cancelled 1、terminated 1、broadFailed 3、finished 5、`allFinished()` 为 false
  - `fromInstanceCounters_treatsNullCountersAsZero`：计数字段全为 null → 各项都是 0，`allFinished()` 为 false
  - `allFinished_whenEveryPartitionTerminal`：total 3、success 2、failed 1 → true
  - `matches_falseWhenSummaryNull`、`matches_falseWhenAnyFieldDiffers`（逐项改一个字段，参数化）、`matches_trueWhenAllSixEqual`
- [ ] **Step 2：运行，确认因类型不存在而编译失败。** `./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=PartitionOutcomeCountsTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [ ] **Step 3：实现 record，并改写 `advance`。** 写成 `counts = dagInstance ? fromStatusRefs(statusRefs) : fromInstanceCounters(jobInstance)`；非 DAG 时不查分区行，与原来一致。`terminalPartitionSummaryIsInconsistent = !counts.matches(terminalVerification)`。方法后半段对原局部变量的引用全部改成从 `counts` 读取。
- [ ] **Step 4：跑测试。** `-Dtest='PartitionOutcomeCountsTest,TaskOutcome*Test,DefaultTaskOutcomeService*Test'`，预期全部 PASS。
- [ ] **Step 5：提交** `refactor(orchestrator): compute partition outcome counts once`。

### Task 5：时间线按来源映射（R3）

**Files:**
- Modify：`batch-console-api/src/main/java/io/github/pinpols/batch/console/application/observability/DefaultConsoleQueryApplicationService.java:279-430`
- Test：`batch-console-api/src/test/java/.../DefaultConsoleQueryApplicationServiceTest.java`

**Interfaces:**
- 每种来源一个私有静态方法 `addXxxItems(List<ConsoleTraceTimelineItem> items, List<T> sources)`，`Xxx` 取来源名：JobInstance、WorkflowRun、WorkflowNodeRun、File、FilePipeline、AuditLog、OperationAudit、ExecutionLog、OutboxDelivery、Alert、DeadLetter，共 11 个。原 `forEach` 体原样搬入；用到 `traceId` 的方法额外接收 `String traceId`。

- [ ] **Step 1：写 characterization 测试** `traceSnapshot_keepsSourceOrderForEqualTimestamps`：构造一个作业实例和一个告警事件，两者 `occurredAt` 相同，断言时间线顺序与改动前一致。先在未改动的代码上运行，记下实际顺序，再写成断言。
- [ ] **Step 2：运行，确认新测试和 `traceSnapshot_buildsChronologicalTimelineWithoutChangingSourceLists` 都通过。** `./mvnw -o -ntp -pl batch-console-api -am -Dtest=DefaultConsoleQueryApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [ ] **Step 3：拆方法。** `buildTimeline` 只剩三步：按原来的来源顺序调用 11 个 `addXxxItems`、原有排序、返回。去掉开头逐个取字段的 12 行（`traceId` 加 11 个来源列表），改为直接调用 `sources.xxx()`。
- [ ] **Step 4：再跑 Step 2 的命令**，预期 PASS。
- [ ] **Step 5：提交** `refactor(console): map trace timeline sources one by one`。

### Task 6：OIDC 守卫按阶段提前返回（R7）

**Files:**
- Modify：`batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java:36-54`
- Test：`batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandlerTest.java`

**Interfaces:**
- 新增 `private Optional<OidcUser> trustedOidcUser(Authentication authentication)`，按顺序检查：开关启用 → `OAuth2AuthenticationToken` → principal 是 `OidcUser` → registrationId 相等 → issuer 非 null 且与配置相等 → subject 非空白、长度 ≤ 255、全 ASCII。任何一项不满足就返回 `Optional.empty()`。

- [ ] **Step 1：补 characterization 测试**（沿用测试类里已有的 `invoke(...)` 和 mock）。以下 6 个用例都断言：返回 302，跳转到 `/login?authError=oidc`，没有 `Set-Cookie`，调用了 `loginAudit.failed(TENANT_ID, "OIDC_IDENTITY_INVALID")`，`identityMapper` 没有被调用：
  - `shouldRejectInvalidIdentity_whenOidcDisabled`
  - `shouldRejectInvalidIdentity_whenAuthenticationIsNotOAuth2Token`
  - `shouldRejectInvalidIdentity_whenIssuerMissing`（`getIssuer()` 返回 null，不能抛 NPE）
  - `shouldRejectInvalidIdentity_whenSubjectBlank`
  - `shouldRejectInvalidIdentity_whenSubjectLongerThan255`（256 个 `a`）
  - `shouldRejectInvalidIdentity_whenSubjectNotAscii`（`"用户"`）
  另加边界用例 `shouldAcceptSubjectOfExactly255Ascii`：255 个 `a`，并且 `identityMapper` 被调用了一次。
- [ ] **Step 2：在改代码前运行，确认通过。** `-Dtest=ConsoleOidcAuthenticationSuccessHandlerTest`
- [ ] **Step 3：实现 `trustedOidcUser`。** `onAuthenticationSuccess` 改成 `Optional<OidcUser> trusted = trustedOidcUser(authentication); if (trusted.isEmpty()) { 审计 + 跳转; return; }`，后续逻辑改用 `trusted.get()`。
- [ ] **Step 4：再跑 Step 2 的命令**，预期 PASS。
- [ ] **Step 5：提交** `refactor(console): stage OIDC identity guards`。

### Task 7：补跑提交校验改用命名布尔（R8）

**Files:**
- Modify：`batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/DefaultTriggerService.java:65-76`
- Test：`batch-trigger/src/test/java/io/github/pinpols/batch/trigger/service/DefaultTriggerServiceTest.java`

- [ ] **Step 1：补参数化 characterization 测试** `verifyCatchUpSubmission_rejectsMalformedCommand(String caseName)`。用例包括：null 命令、null request、空白幂等键、空白 tenantId、null workflowDefinitionId、workflowDefinitionId 为 0、null bizDate、triggerType 为 `MANUAL`。每个用例都断言抛出 `BizException`，`ResultCode.INVALID_ARGUMENT`，文案与 `REQUEST_BODY_REQUIRED` 一致，并且测试类里的持久化 mock 都没有被调用。
- [ ] **Step 2：在改代码前运行，确认通过。** `./mvnw -o -ntp -pl batch-trigger -am -Dtest=DefaultTriggerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [ ] **Step 3：拆条件。** 前 7 项完整性检查放进局部变量 `boolean catchUpCommandIncomplete`；`if (catchUpCommandIncomplete || command.request().triggerType() != TriggerType.CATCH_UP)`。这样 `triggerType()` 仍然只在 request 非 null 时才读取。
- [ ] **Step 4：再跑 Step 2 的命令**，另加 `-Dtest=TriggerServiceIntegrationTest`（需要该 IT 的运行环境；环境不可用时记为未验证），预期 PASS。
- [ ] **Step 5：提交** `refactor(trigger): name catch-up command completeness check`。

### Task 8：`prepareRun` 事务体提成具名方法（R9）

**Files:**
- Modify：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java:411-575`
- Modify：`docs/audit/java-readability-reassessment-2026-11-11.md`（R9 另外三处的结论）

**Interfaces:**
- 新增 `private WorkflowRunEntity createRunInTransaction(RunPreparation preparation)`：内容是原 lambda 第 430–561 行，原样搬入。

- [ ] **Step 1：改写。** `try { return transaction.execute(ignored -> createRunInTransaction(preparation)); }`。两个 catch 保持原样、位置不变，并在 `catch (DuplicateKeyException ...)` 上加注释：唯一键冲突后 PostgreSQL 事务已中止，必须在事务回滚后才能查询胜出者，因此 catch 留在 `transaction.execute` 外面。`prepareRun` 开头只给事务体用的局部变量随事务体一起移走，`existing` 预检查保留在原处。
- [ ] **Step 2：确认事务配置没变。** `git diff` 中 `setPropagationBehavior`、`setIsolationLevel` 两行没有改动。
- [ ] **Step 3：跑测试。** `./mvnw -o -ntp -pl batch-orchestrator -am -Dtest='DefaultWorkflowRunLaunchService*Test,WorkflowRunLaunch*Test' -Dsurefire.failIfNoSpecifiedTests=false test`，预期 PASS。
- [ ] **Step 4：复核另外三处大 try**（`PrepareDispatchStep.execute:71`、`DefaultRetryGovernanceService:554`、`SftpDispatchChannelAdapter:223`）。对每处写一行结论：是整段翻译边界（保留，补注释）还是可以收窄（另开批次）。结论写进复评文档 R9，本任务不改这三处代码。
- [ ] **Step 5：提交** `refactor(orchestrator): name workflow run creation transaction body`。

### Task 9：共享工作流图的可达性与 DSL 引用解析（R2）

**Files:**
- Create：`batch-common/src/main/java/io/github/pinpols/batch/common/validation/WorkflowGraphs.java`
- Create：`batch-common/src/test/java/io/github/pinpols/batch/common/validation/WorkflowGraphsTest.java`
- Modify：`batch-orchestrator/.../application/service/workflow/WorkflowGraphValidator.java`（`DSL_NODE_REF`:67、`validateReachability`:247、`validateDslNodeRefs`:273、`dfs`:530）
- Modify：`batch-console-api/.../infrastructure/excel/WorkflowGraphTopologyValidator.java`（`DSL_NODE_REF`:77、可达性:180/195、DSL 引用:232、`dfsCollect`:288）

**Interfaces:**
- Produces（`public final class WorkflowGraphs`，私有构造器）：
  - `public static Set<String> reachableFrom(Collection<String> roots, Map<String, List<String>> adjacency)`：从所有 roots 出发沿邻接表能到达的节点，包括 roots 本身；遇到环不会死循环
  - `public static List<String> dslNodeReferences(String nodeParams)`：`$.nodes.<code>.` 中的 `<code>` 按出现顺序返回，保留重复；参数为 null 或空白时返回空列表
- 不共享：环检测、祖先计算。两端的环检测上报方式不同（编排器遇到第一个环就停止，Excel 校验会多处上报），在两个类里分别加中文注释说明。

- [ ] **Step 1：写失败测试** `WorkflowGraphsTest`：
  - `reachableFrom_followsEdgesTransitively`：邻接 a→b、b→c，d 孤立，roots 为 [a] → `{a, b, c}`
  - `reachableFrom_terminatesOnCycle`：a→b、b→a，roots 为 [a] → `{a, b}`
  - `reachableFrom_emptyRoots`：返回空集
  - `dslNodeReferences_keepsOrderAndDuplicates`：`{"x":"$.nodes.n1.out","y":"$.nodes.n2.v $.nodes.n1.z"}` → `[n1, n2, n1]`
  - `dslNodeReferences_blankParams`：null 和 `"  "` 都返回 `[]`
- [ ] **Step 2：运行，确认编译失败。** `./mvnw -o -ntp -pl batch-common -Dtest=WorkflowGraphsTest test`
- [ ] **Step 3：实现 `WorkflowGraphs`，再跑 Step 2 的命令**，预期 PASS。
- [ ] **Step 4：两端改为调用共享方法。** 删掉两处 `DSL_NODE_REF`、`dfs` 和 `dfsCollect`。错误码（V2/V3/V4）、错误文案、Excel 表名/行号/列名都不变；每个引用仍各报一条问题。
- [ ] **Step 5：跑两端测试。**
  `./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=WorkflowGraphValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
  `./mvnw -o -ntp -pl batch-console-api -am -Dtest='ConfigPackageExcelValidatorWorkflowTest,ConfigPackageExcelValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  预期全部 PASS。
- [ ] **Step 6：提交** `refactor: share workflow graph reachability and DSL reference parsing`。

### Task 10：删掉对注入依赖的多余判空（R12）

**Files:**
- Modify：CSV 中 `category = injected-final-field-null-check` 的 55 行对应的生产类，以及构造这些类时传了 null 的测试
- Modify：`docs/audit/java-readability-reassessment-2026-11-11-candidates.csv`（填 `decision`）

- [ ] **Step 1：逐行查生产构造路径。** 满足任一条件就保留判空，并在 `decision` 列写明原因：字段来自 `ObjectProvider.getIfAvailable()` / `getIfUnique()`；参数带 `@Nullable` 或 `@Autowired(required = false)`；bean 带 `@ConditionalOn…`、只在某个 profile 下存在；生产代码里有手工 `new` 并传 null。都不满足的标为“删除”。已知保留：`DownstreamFallback:189`。
- [ ] **Step 2：改测试。** 在 Step 1 标为“删除”的类中，用 `grep -rn "new <类名>(" --include='*Test.java'` 找出传 null 的位置，改成传 `mock(...)`。改完先跑一遍这些测试，预期 PASS（这时生产代码还没改）。
- [ ] **Step 3：删掉判空。** 删掉判空和它守护的那个提前返回或默认值分支。如果判空只是三元表达式的一边（如 `DefaultTaskAssignmentService:929`），直接改成调用 getter。
- [ ] **Step 4：跑每个改动模块的测试**（`./mvnw -o -ntp -pl <模块> -am test`），预期 `Failures: 0, Errors: 0`。
- [ ] **Step 5：提交。** 按模块分开提交，`refactor(<模块>): drop null guards on required collaborators`。

### Task 11：删掉对 MyBatis 列表结果和 `requireNonNull` 之后的多余判空（R12）

**Files:**
- Modify：CSV 中 `category = mapper-list-null-check`（27 行）和 `null-check-after-requireNonNull`（3 行）对应的生产类
- Modify：在这些 Mapper 方法上 stub 了 `thenReturn(null)` 的测试

- [ ] **Step 1：确认是 MyBatis 返回值。** 被调用的方法必须是 Mapper 接口上用 XML 或注解映射的查询。如果是 `default` 方法、手写实现类或别的 Repository，先读实现：确实可能返回 null 的就保留，并在 `decision` 写明。
- [ ] **Step 2：改测试。** `grep -rn "<方法名>(.*thenReturn(null)" --include='*Test.java'`，把对列表方法的 `thenReturn(null)` 改成 `thenReturn(List.of())`，先跑这些测试，预期 PASS。
- [ ] **Step 3：删掉判空。** `x == null || x.isEmpty()` 改成 `x.isEmpty()`；`if (x == null) { x = List.of(); }` 整段删除。`requireNonNull(v)` 之后的判空直接删掉。
- [ ] **Step 4：跑改动模块的测试**，预期全部 PASS。
- [ ] **Step 5：提交** `refactor(<模块>): trust MyBatis empty list results`。

### Task 12：导入扫描的阶段分派只判一次（R13，已撤销）

> **已撤销（2026-11-12）：** `ImportIngressScanner.scanCycle`（`ImportIngressScanner.java:176-188`）是顺序的 DISCOVER→INDEX→APPLY→CLEAN 链，每一块都会重新给 `current` 赋值，一次调用可以连续推进多个阶段；改成 `switch` 会改变行为，因此不改代码。以下步骤作废。

**Files:**
- Modify：`batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java:175-190`
- Test：`batch-worker/import/src/test/java/.../ImportIngressScannerTest.java`

- [ ] **Step 1：确认现有测试覆盖 INDEX、APPLY、CLEAN 三个阶段，以及 `current` 为 null 的情况。** 缺哪个，就补一个 characterization 用例 `scanCycle_resumes<Phase>Phase` / `scanCycle_startsFreshWhenNoCursor`。
- [ ] **Step 2：在改代码前跑 `ImportIngressScannerTest`、`ImportIngressScannerPaginationTest`**，预期 PASS。
- [ ] **Step 3：改写。** 先写 `if (EmptyChecks.isNotNull(current))`，里面用 `switch (current.phase())` 分派 `"INDEX"`、`"APPLY"`、`"CLEAN"`，阶段字符串保持原字面量。`default` 分支和 null 时的走向与原来完全一致：原来三个 if 都不命中时会往下执行，现在也往下执行。
- [ ] **Step 4：再跑 Step 2 的命令**，预期 PASS。
- [ ] **Step 5：提交** `refactor(worker-import): dispatch ingress phase once`。

### Task 13：校验方法不再夹带拒绝写入（R14）

**Files:**
- Modify：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java:111-113、134-136、160-203`
- Modify：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/asset/AssetPartitionService.java:148`
- Modify：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/S3GovernanceStorage.java:36-42`
- Test：`batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchServiceTest.java`

**Interfaces:**
- 新增 `private void rejectAcceptance(LaunchRequest request)`：只做一件事，调用 `triggerRequestMapper.updateAcceptance(tenantId, requestId, REJECTED.code(), null)`
- 新增 `private void validateLaunchReferences(LaunchRequest request, JobType jobType, Map<String, Object> effectiveParams)`：按原顺序调用三个校验；`catch (BizException e) { rejectAcceptance(request); throw e; }`
- 三个 `validate…` 方法里的 try/catch 和 `updateAcceptance` 全部删掉，只剩校验和抛异常
- `AssetPartitionService.loadDataAssetId` 改名为 `ensureDataAssetId`（私有方法，调用处同步改）
- `S3GovernanceStorage` 新增 `private Cursor openCursor(String storageId, String prefix)`：先 `insertIfAbsent`，再 `select`；`listReconcilePage` 改为调用它

- [ ] **Step 1：确认现有测试仍覆盖三种拒绝路径。** 现有用例：`shouldRejectBundleLaunchBeforeCreatingInstanceWhenBindingsMissing`、`shouldRejectLaunchWhenRelatedFileDoesNotBelongToTenant`、`shouldRejectMissingTenantParentInstanceBeforeInsert`。三个都断言 `updateAcceptance(..., REJECTED, null)` 只调用一次，并且没有创建作业实例；缺少“只调用一次”断言的，补上 `verify(..., times(1))`。
- [ ] **Step 2：在改代码前跑 `DefaultLaunchServiceTest`、`AssetPartitionServiceTest`**，预期 PASS。
- [ ] **Step 3：改写 `DefaultLaunchService`。** `launch` 第 111–113 行的三次调用换成 `validateLaunchReferences(request, loaded, effectiveParams)`；第 134–136 行 `TemplateCaptureRejected` 分支里的 `updateAcceptance` 换成 `rejectAcceptance(request)`。原来 `DictEnum.fromCode(...)` 在 try 外面，它抛的异常不会写 REJECTED。新的 catch 会包住整个校验调用，所以要在 `launch` 里先算出 `JobType jobType`，再调用 `validateLaunchReferences(request, jobType, effectiveParams)`；`validateBundleParamsBeforeInstanceCreation` 改为接收 `JobType`，不再接收 `LaunchLoadResult`。
- [ ] **Step 4：改名，并拆出 `openCursor`。**
- [ ] **Step 5：再跑 Step 2 的命令，再加上 `S3GovernanceStorage*Test`**，预期全部 PASS。`TemplateCaptureRejected` 分支没有单测，这一行等价替换只靠 diff 审查确认，在 PR 描述里写明“未单测覆盖”。
- [ ] **Step 6：提交** `refactor(orchestrator): keep launch validators free of rejection writes`。

### Task 14：去掉可合并的嵌套 if 和啰嗦写法（R15、R16）

**Files:**
- Modify：CSV 中 R15（13 行）、R16（9 行）对应的生产类

- [ ] **Step 1：逐行判断。** R15：两层 if 之间有说明性注释，或者内层条件依赖外层算出来的局部变量的，保留，在 `decision` 写明原因；其余合并成 `if (a && b)`，合并后条件达到 3 项的按 §1.5 起局部布尔名。R16：`if (c) return true; return false;` 改成 `return c;`；return/throw 之后的 `else { … }` 去掉 else，把内容提到外层。
- [ ] **Step 2：跑改动模块的测试**，预期全部 PASS。
- [ ] **Step 3：提交** `refactor: flatten mergeable conditions`。

### Task 15：SQL 拆语句状态机用一个状态枚举（次要候选）

**Files:**
- Modify：`batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutor.java`（内部类 `SplitScanner`，约 282–330 行）
- Modify：`sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandler.java`（内部类 `StatementSplitState`，约 137–195 行）
- Test：`SqlTaskExecutorTest`、`SqlAtomicHandlerTest`

**Interfaces:**
- 两个内部类各自新增私有枚举 `LexState { CODE, LINE_COMMENT, BLOCK_COMMENT, SINGLE_QUOTE, DOUBLE_QUOTE }`，替换 `inLineComment` 等 4 个布尔字段；逐字符处理改为 `switch (state)`。SDK 不能依赖 `batch-common`，两份分别改，不合并。

- [x] **Step 1：补 characterization 测试**（两个测试类各补一组，用同样的输入）：`splits_onTopLevelSemicolonOnly`（`select 1; select 2` → 两句）、`keepsSemicolonInsideQuotesAndComments`（`select ';' -- a;b\n; select "x;y" /* ; */` → 两句）、`dropsEmptyStatements`（`;;select 1;` → 一句）、`keepsBackslashQuoteBehavior`（`select 'a\'; select 2'`：按改动前的实际结果写断言，只锁住现状，不判断对错）。
- [x] **Step 2：在改代码前运行，确认通过。** `./mvnw -o -ntp -pl batch-worker/atomic -am -Dtest='SqlTaskExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false test`；`./mvnw -o -ntp -pl sdk/java/core -am -Dtest='SqlAtomicHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] **Step 3：改成状态枚举。** 每个状态的退出条件与原来的布尔分支一一对应，`prev` 的含义不变。
- [x] **Step 4：再跑 Step 2 的两条命令，另加 `SqlTaskExecutorHardeningTest`、`SqlTaskExecutorDialectGuardTest`**，预期全部 PASS。
- [x] **Step 5：提交** `refactor: model SQL splitting as an explicit lexer state`。

### Task 16：互斥的两个布尔参数改成一个枚举（次要候选）

**Files:**
- Create：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/FailureDisposition.java`
- Modify：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskOutcomeService.java:204-216、252-266、373-460`
- Create：`batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/task/FailureDispositionTest.java`

**Interfaces:**
- Produces（package-private 枚举）：`enum FailureDisposition { CANCELLED, RETRY_SCHEDULED, NOT_RETRIED }`
  - `static FailureDisposition of(boolean cancellationOutcome, boolean retryScheduled)`：cancellation 为 true 返回 `CANCELLED`；否则 retry 为 true 返回 `RETRY_SCHEDULED`；否则返回 `NOT_RETRIED`。成功路径传两个 false，同样得到 `NOT_RETRIED`，与原来两参数都为 false 时的走向一致。
- 2026-11-11 核实：`applyFailureOutcome` 的 `cancellationOutcome` 参数在方法体里没有使用（R23），直接删掉，`applyFailureOutcome` 只保留 `boolean retryScheduled`，不需要枚举。
- `updateStepInstanceProgress(command, task, FailureDisposition disposition, Instant finishedAt, String outputSummary)` 替换原来的两个布尔参数；方法内 `retryScheduled` 改成 `disposition == RETRY_SCHEDULED`，`cancellationOutcome` 改成 `disposition == CANCELLED`。
- 不改：`TaskOutcomeStatePolicy.resolveWorkflowEvent`（调用处实参是具名访问器，见复评决策记录）。

- [x] **Step 1：写失败测试** `FailureDispositionTest`：`(true,false)→CANCELLED`、`(false,true)→RETRY_SCHEDULED`、`(false,false)→NOT_RETRIED`、`(true,true)→CANCELLED`（取消优先，与第 210 行的推导一致）。
- [x] **Step 2：运行，确认编译失败。** `-Dtest=FailureDispositionTest`
- [x] **Step 3：实现枚举并改两个方法的签名与调用处。** 第 204–216 行算出两个布尔后，紧接着得到 `FailureDisposition disposition = FailureDisposition.of(cancellationOutcome, retryScheduled)`；第 255 行 `partition != null && retryScheduled` 改成读 `disposition`。
- [x] **Step 4：跑测试。** `-Dtest='FailureDispositionTest,DefaultTaskOutcomeService*Test,TaskOutcome*Test'`，预期 PASS。
- [x] **Step 5：提交** `refactor(orchestrator): pass failure disposition instead of two flags`。

### Task 17：深嵌套方法拆浅（次要候选）

**Files（按扫描结果，花括号嵌套 ≥ 6 层）：**

| 方法 | 现有测试 | 数据通路 |
|---|---|---|
| `JsonFormatParser.parse:27` | 无，先补 | 是 |
| `FixedWidthFormatParser.parse:25` | 无，先补 | 是 |
| `ExcelFormatParser.parse:78` | `ExcelFormatParserTest` | 是 |
| `ValidateStep.processValidationBatch:280` | `ValidateStepTest` | 是 |
| `ImportPreprocessPipeline.run:120` | `ImportPreprocessPipelineTest`、`ImportPreprocessPipelineRsaTest` | 是 |
| `GenericJdbcMappedImportLoadPlugin.copyChunk:492` | `GenericJdbcMappedImportLoadPluginTest` | 是 |
| `SqlTaskExecutor.runStatements:500`、`fetchResults:587` | `SqlTaskExecutorTest` 等 | 是 |
| `BizTableSchemaRegistrar.registerOnStartup:49` | 无，先补 | 否 |
| `TenantConfigInitApplyHandlers.applySpecs:169` | `DefaultConsoleTenantConfigInitApplicationServiceTest` | 是 |
| `QueryExportHandler.doExecute:49`、`SqlAtomicHandler.runStatements:75`（SDK） | `QueryExportHandlerTest`、`SqlAtomicHandlerTest` | 是 |
| `KafkaTaskConsumer.refreshConsumerLag:311`（SDK） | `KafkaTaskConsumer*Test` | 否 |
| `SecurityScanOptions.parse:29` | `SecurityScanOptionsTest` | 否 |

- [x] **Step 1：没有测试的 4 个方法先补 characterization 测试。** `JsonFormatParser` 至少覆盖 4 种输入：根是数组、根是含 `records` 字段的对象、根是单个对象、空输入，以及 `records` 中夹 `null` 元素的情况。`FixedWidthFormatParser` 覆盖正常行、短行、空行。另外两个各覆盖一条成功路径和一条跳过路径。先在未改动的代码上跑通。
- [x] **Step 2：数据通路方法先记录基线计时。** 写一个临时测试（不提交），用同一份生成数据（JSON / 定长各 20 万行，Excel 2 万行）重复解析 5 次，取中位数，记到复评文档。
- [x] **Step 3：逐个方法拆浅。** 只用三种手法：守卫条件提前返回或 `continue`；把循环体提成私有方法；按输入形态分派到具名方法（如 `JsonFormatParser` 拆成 `parseRootArray`、`parseRecordsEnvelope`、`parseSingleObject`）。资源仍由原来的 try-with-resources 管理，不改异常翻译边界，不改读取顺序。每个方法单独提交。（SecurityScanOptions.parse 按设计保留，原因见复评文档）
- [x] **Step 4：每个方法改完就跑它的测试**，预期 PASS。数据通路方法重跑 Step 2 的计时，中位数退化超过 5% 就回退，并在复评文档写明原因。（SqlTaskExecutor 计时结论不确定，未达成 5% 验证，按判断保留，见复评文档）
- [x] **Step 5：提交**（每个方法一个提交）`refactor(<模块>): flatten <方法名>`。

### Task 18：空值判断按语义统一（R4，最后执行）

**Files:**
- Modify：`docs/coding-conventions.md`（新增 §1.8 空值判断写法）
- Modify：`scripts/ci/check-empty-checks.py`（门禁改为 §1.8 的拆分规则，随第一个 Task 18 PR 一起改）
- Modify：各模块生产代码，拆成 2–3 个 PR

- [ ] **Step 1：写规则。** §1.8：对象引用判空用 `x == null` / `x != null`；字符串在“空白也算空”时用 `EmptyChecks.isBlank`，只在 null 或长度为 0 时才算空的用 `EmptyChecks.isEmpty`；集合、Map、数组用 `EmptyChecks.isEmpty`；Stream 里用 `Objects::nonNull` / `Objects::isNull`。`EmptyChecks.isNull` / `isNotNull` 保留 API，不再在新代码里使用。写明 `trim().isEmpty()` 与 `isBlank` 结论不同，不能互换。
- [ ] **Step 2：机械替换对象判空。** 按模块执行：`EmptyChecks.isNull(expr)` → `expr == null`，`EmptyChecks.isNotNull(expr)` → `expr != null`，`EmptyChecks::isNotNull` → `Objects::nonNull`。`expr` 不是简单标识符或方法调用时，给替换结果加括号，并检查周围运算的优先级。替换后删掉不再使用的 import。
- [ ] **Step 3：替换组合判空。** 只替换静态类型确认是 `String` / `CharSequence` / `Collection` / `Map` / 数组的：`x == null || x.isBlank()` → `EmptyChecks.isBlank(x)`，`x == null || x.isEmpty()` → `EmptyChecks.isEmpty(x)`，对应的 `!=` / `&&` 取反形式同理。`x == null || x.trim().isEmpty()` 不替换。
- [ ] **Step 4：每个模块跑完整测试和 Spotless。** `./mvnw -o -ntp -pl <模块> -am test`，再跑 `spotless:check`，预期都是 clean。另外跑 `ServiceEntryGuardArchTest`，预期 PASS。
- [ ] **Step 5：检查有没有漏掉的。** `grep -rn 'EmptyChecks\.is\(Not\)\?Null(' --include='*.java' <模块>/src/main`，预期没有输出。
- [ ] **Step 6：提交**（每个模块一个提交、一个 PR）`refactor(<模块>): use native null checks per conventions §1.8`。**门禁先行：** 在第一个 Task 18 PR 中，把 `scripts/ci/check-empty-checks.py` 改成 §1.8 的拆分规则，并与 conventions §1.8 一起提交，必须早于任何 `EmptyChecks.isNull` → `== null` 的替换。阶段 9 内为通过旧门禁而写入、Task 18 必须回头处理的位置：`WorkerTaskLeaseRenewer.java` ~:245、`ConsoleMetaQueryService.java:172`、`ConsoleOidcAuthenticationSuccessHandler` 中 Optional 的 allow 注释、`WorkflowGraphs` 里的 `EmptyChecks.isNotEmpty`。

### Task 19：超长 builder 先命名，私有薄壳改直接调用（R17、R19）

**Files:**
- Modify：`batch-orchestrator/.../application/trigger/TriggerLaunchRecoveryService.java:190`、`batch-orchestrator/.../application/service/forensic/ForensicExportLogTransactionService.java:28`
- Modify：候选清单 CSV 中 `category = private-pure-forward` 的 39 行

- [ ] **Step 1：R17。** 两处 builder 各自提成局部变量，变量名取参数对象的业务名（如 `retryParam`、`exportLogEntity`），再传给 Mapper。builder 里调用 `Instant.now()` 等有副作用的方法时，提取后位置相对其他语句不变。
- [ ] **Step 2：R19 逐处判断。** 只转发给静态工具的，改成静态导入后直接调用，删掉私有方法；只转发给注入协作者的（如 `resolveTenant`），调用处直接写 `tenantGuard.resolveTenant(...)`。名字本身带业务含义、包装集合判断的（如 `isActive`、`isTerminal`），保留，在 `decision` 列写明。
- [ ] **Step 3：跑改动模块的测试**，预期全部 PASS。
- [ ] **Step 4：提交** `refactor: inline pass-through helpers and name long builders`。

### Task 20：合并两份 OkHttp 出站适配器（R20 ①）

**Files:**
- Create：`batch-common/src/main/java/io/github/pinpols/batch/common/http/OkHttpOutboundHttpTransport.java`
- Modify：`batch-common/pom.xml`（新增 OkHttp 依赖，版本沿用父 POM 的版本管理）
- Delete：`batch-console-api/.../support/http/OkHttpConsoleExternalHttpTransport.java`、`batch-orchestrator/.../infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java`
- Modify：两个模块中装配这两个 bean 的配置类
- Test：两个模块现有的传输层测试，加上新建的 `batch-common/src/test/java/io/github/pinpols/batch/common/http/OkHttpOutboundHttpTransportTest.java`

**Interfaces:**
- Produces：`public class OkHttpOutboundHttpTransport implements OutboundHttpTransport`，构造参数与两个旧类相同。2026-11-11 的 diff 显示，两份除了类名、包名和类注释，只有局部变量命名不同，不需要再加区分模块的构造参数。如果实现时发现日志或指标里带了模块名，停下来记录，不要自行加参数。

- [ ] **Step 1：先确认 `batch-common` 能加 OkHttp。** 跑 `./mvnw -o -ntp -pl batch-common dependency:tree`，并看依赖收敛和许可证门禁（`docs/standards/open-source-governance.md`）。不允许加的话，停下来，在复评文档里写明，换成另设小模块的方案，并请用户确认。
- [ ] **Step 2：把两个旧类的现有测试复制一份，指向新类**，作为 `OkHttpOutboundHttpTransportTest`，覆盖：拒绝私有地址、DNS 解析结果固定、超时、不跟随重定向、空 body 的处理。确认新测试编译失败。
- [ ] **Step 3：实现新类。** 以 orchestrator 版本为准；两版只差局部变量命名的那 16 行，任选一种写法，语义必须相同。
- [ ] **Step 4：两个模块改为装配新类，删掉旧类。** 配置项名称和默认值保持不变。
- [ ] **Step 5：跑测试。** `batch-common` 的新测试，加上两个模块中所有名字含 `Outbound`、`ExternalHttp`、`Transport` 的测试，预期全部 PASS。
- [ ] **Step 6：提交** `refactor: share the OkHttp outbound transport`。

### Task 21：Worker 任务消息的 MDC 字段提成共用方法（R20 ②）

**Files:**
- Create：`batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskMessageMdc.java`
- Modify：`batch-worker/core/.../application/TaskDispatchExecutor.java:214`（`putExecutionMdc`）、`batch-worker/core/.../support/AbstractTaskConsumer.java:416`（`injectMdc`）
- Create：`batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/TaskMessageMdcTest.java`

**Interfaces:**
- Produces：`final class TaskMessageMdc`，`static void putMessageFields(TaskDispatchMessage message)`：放入两处方法开头完全相同的那约 30 行字段（从 `TENANT_ID` 开始）。
- 两处各自独有的部分留在原方法里：`TaskDispatchExecutor` 的 `WORKER_TYPE`（取自 message）和 `WORKER_ID`；`AbstractTaskConsumer` 的 `TOPIC`、`WORKER_TYPE`（取自 `workerConfiguration()`）和 `WORKER_ID`（registration 可能为 null）。
- MDC 的清理方式不动：两处的 `BatchMdc.removeAll(...)` 字段集合不一样，本任务不合并。

- [ ] **Step 1：写失败测试** `TaskMessageMdcTest.putMessageFields_putsEveryMessageField`：构造一个各字段都有值的 `TaskDispatchMessage`，逐字段断言 `MDC.get(...)` 的值；另加一个 null 字段的用例，断言与 `BatchMdc.put` 对 null 的现有处理一致。
- [ ] **Step 2：运行，确认编译失败**，然后实现。
- [ ] **Step 3：两处改为先调用 `TaskMessageMdc.putMessageFields(message)`，再放各自独有的字段。** 放入的顺序与原来一致。
- [ ] **Step 4：跑 `TaskMessageMdcTest`、`TaskDispatchExecutor*Test`、`AbstractTaskConsumer*Test`、`*TaskConsumer*Test`**，预期 PASS。
- [ ] **Step 5：提交** `refactor(worker-core): share task message MDC fields`。

### Task 22：删除任务执行门面，冗余写法收短（R18、R21）

> 执行记录（2026-11-12）：`Nullables.coalesce` 已存在且语义一致，复用它，不新增 `firstNonNull`；`TaskControllerApplicationService` 只调用分配类方法，只注入 `TaskAssignmentService`。R21 按同一定义实际命中 52 处“有值才覆盖”三元（复评中的 18 处是抽样数），逐处核实回退对象非 null、取值无副作用后全部改写；另有 20 处 `Optional.ofNullable(x).orElse(常量)`、4 处 `.toList()`。

**Files:**
- Delete：`batch-orchestrator/.../application/service/task/TaskExecutionService.java`、`DefaultTaskExecutionService.java`、`DefaultTaskExecutionServiceTest.java`
- Modify：`TaskControllerApplicationService.java` 和另外 15 个引用 `TaskExecutionService` 的测试文件
- Create：`batch-common/src/main/java/io/github/pinpols/batch/common/utils/Nullables.java`
- Modify：R21 列出的生产类

**Interfaces:**
- `TaskControllerApplicationService` 改为注入 `TaskCreationService`、`TaskAssignmentService`、`TaskOutcomeService`，每个调用改成直接调用对应的服务（对照 `DefaultTaskExecutionService` 的转发表逐个替换）。
- Produces：`public final class Nullables`，`public static <T> @Nullable T firstNonNull(@Nullable T preferred, @Nullable T fallback)`：`preferred` 不为 null 返回它，否则返回 `fallback`（可以是 null）。

- [ ] **Step 1：R18。** `DefaultTaskExecutionService` 没有 `@Transactional`（2026-11-11 已核实），事务边界都在三个具体服务上，删除门面不改事务。按转发表替换使用方和测试，删掉门面接口、实现和它的单测。
- [ ] **Step 2：跑 `TaskControllerApplicationServiceTest`、`TaskControllerTest`，以及 15 个集成测试中能在本地跑的**，预期 PASS；环境不可用的记为未验证。
- [ ] **Step 3：写 `NullablesTest`**：`(a, b)→a`、`(null, b)→b`、`(null, null)→null`。确认编译失败后，实现 `Nullables`。
- [ ] **Step 4：R21 替换。** “有值才覆盖”的三元改成 `Nullables.firstNonNull(request.getX(), copied.getX())`；带转换的（如 `toUpperOrNull`）写成 `request.getX() != null ? 转换(request.getX()) : copied.getX()`，不硬套工具。`Optional.ofNullable(x).orElse(d)` 只在 `d` 是非 null 常量或非 null 值时，才改成 `Objects.requireNonNullElse(x, d)`。`Collectors.toList()` 的 4 处，确认结果后续不会被 `add`/`remove`/`sort` 后改成 `.toList()`，会被修改的保留原写法。
- [ ] **Step 5：跑改动模块的测试**，预期全部 PASS。
- [ ] **Step 6：提交。** R18 和 R21 分开提交：`refactor(orchestrator): drop pass-through task execution facade`；`refactor: shorten null-default idioms`。

### Task 23：入口方法只保留流程，细节下沉（R22、R27，全量 302 + Controller 26）

**Files:**
- Modify：候选清单 CSV 中 `finding = R22` 的全部行（`decision` 已写“已由其他任务处理”的跳过）
- Test：各类现有测试；没有测试的入口方法先补

**验收标准：** 复评文档“主流程验收标准”4 条，每个方法都要满足。

**顺序：** 先做核心链路（类别以 `core:` 开头，共 186 个），按业务流程逐层推进，每层一个 PR；然后做支撑功能（`support:`，116 个），按包分 PR；Controller（26 个）最后单独一个 PR。

| 批次 | 范围（按 CSV 中文件路径归属） | 本层重点（复评“核心流程逐层复核”表） |
|---|---|---|
| L1 触发受理 | `batch-trigger`、编排器 `controller` 下的应用服务（`TaskControllerApplicationService`） | `validateRequest`、`renewBatch` / `claimBatch` / `reportBatch`、`QuartzLaunchJob.execute`、`TriggerScheduledRecoveryService.catchUpRejectedOccurrence` |
| L2 启动编排 | 编排器 `service`、`application/service/workflow` 的启动部分、`application/readiness`、`infrastructure/quota` | `BatchDayGateService.evaluateAndApply`、`DispatchChannelSnapshotService.captureForWorkflowReplay`、`LaunchBatchDayService.routeLateArrivalIfNeeded`、`RedisQuotaRuntimeStateService.evaluateAndReserve`、`ReadinessWaitService.sweep` |
| L3 派发与认领 | 编排器 `application/service/task` 的派发与分配、`application/service/workflow` 的派发、`infrastructure/mq`、`application/engine` | `dispatchNode`、`rejectDeferredChildRequest`、`renewLeaseBatch`、`enrichPayload`、`KafkaOutboxPublisher.publish`、`CountContinuityOutboxService.checkContinuity` |
| L4 Worker 执行 | 全部 `batch-worker/*`、`sdk/java/core/dispatcher` | `executeBatchDetailed`、`doConsume`、`runStageLoop`、`prepareDeliveryAttempt`、`WorkerTaskLeaseRenewer.renewActiveTaskLeases`、各格式解析器、`claimWithRetry` / `reportWithRetry` |
| L5 回报与推进 | 编排器 `application/service/task` 的 outcome 部分、`DefaultWorkflowDagService`、`WorkflowNodeTerminalFinalizer`、`*TerminalOutboxService` | `applyTaskOutcome`、`advanceTimedOutWorkflowNode`、`writeTerminalEvent`、三个就绪判定方法（只命名条件，不改顺序） |
| L6 治理与恢复 | 编排器 `application/service/governance`、`application/service/replay`、`infrastructure/scheduler`、`infrastructure/file`、`infrastructure/sla`、`infrastructure/lease` | `autoRetryDueDeadLetters`、`requeuePartition`、`BatchDaySettleScheduler.driveCatchUp`、`BatchDayReplayRangeOperationService.submit`、`FileGovernanceScheduler.evaluateArrivalGroup`、`emitOnce` |
| S 支撑功能 | `support:` 类别，按包分 PR：`console-api/infrastructure/config`（21）先做，其次 notification、excel、job、workflow、audit、file、ops 等 | — |
| C Controller | `controller-over-15-lines` 的 26 个 | 只保留协议转换，业务逻辑移入应用服务（§6.3） |

L2–L5 是主链路，测试要求最高：每层除了单测，还要跑覆盖该链路的集成测试（如 `JobLaunchToFinishLifecycleIntegrationTest`、`WorkerClaimProgressCompleteIntegrationTest`、`RetryExecutionClosureIntegrationTest`），环境不可用的记为未验证。

**按形态选手法**（CSV 类别中的 A / B / C / D）：A 把组装抽成以产物命名的方法或 DTO 静态工厂；B 把循环体、分支体抽成处理单个元素的方法；C 先写步骤序列再抽取；D 复查，多半保留。

每个方法按同一套步骤：

- [ ] **Step 1：逐个人工阅读并确认。** 读完方法本身，确认确实是流程与细节混写；不是的在 `decision` 写“保留”并说明原因（例如本身就是一张声明式配置表）。确认要改的，再列出现有测试覆盖的路径：守卫提前返回、主成功路径、每个会写库或发事件的分支都要有测试，缺的先补 characterization 测试，在未改动的代码上跑通。
- [ ] **Step 2：写出目标步骤序列。** 在 PR 描述里先写一行，如 `emitOnce = 加锁 → 重查候选 → 组装告警详情 → 计算资源键 → 写告警`，再动代码。
- [ ] **Step 3：按序列抽取。** 每个步骤抽成以业务意图命名的私有方法，入口方法只剩步骤调用和守卫返回。只有存在独立变化原因或需要事务代理边界时，才抽到协作类，并在 PR 描述中写明原因。锁、事务、查询和写入的次数与顺序都不变。
- [ ] **Step 4：跑 Step 1 列出的单测和本层集成测试**，预期 PASS；环境不可用的集成测试记为未验证。
- [ ] **Step 5：对照 4 条验收标准自查**，结论填进 CSV 的 `decision` 列。
- [ ] **Step 6：提交**（每个方法一个提交）`refactor(<模块>): read <方法名> as a sequence of steps`。

### Task 24：未使用参数、注释掩盖代码、魔法数字（R23、R25、R26）

**Files:**
- Modify：R23 的 23 个私有方法参数所在类；R25 的 24 处方法内长注释；R26 中有业务含义的数字

- [ ] **Step 1：R23。** 确认参数在方法体里确实没有读取（包括 lambda 和内部类），再删掉参数和调用处的实参。实参表达式有副作用的（如调用 `Instant.now()`、读取并递增计数器），先赋给局部变量保留调用，再删参数。
- [ ] **Step 2：R25。** 逐处判断注释讲的是 WHY / 不变量，还是“这段代码在做什么”。前者保留；后者把对应代码抽成以注释主旨命名的私有方法，注释移到该方法的说明里。结论写进 CSV 的 `decision` 列（R25 的清单在执行时由扫描结果补进 CSV）。
- [ ] **Step 3：R26。** 只处理有业务含义的数字：长度上限、截断长度、位运算掩码、重试上限。提成所属类的 `private static final` 常量，名字说明来源（如 `MAX_ERROR_MESSAGE_LENGTH`）。同一个数值对应数据库列长度时，在常量注释里写出列名。
- [ ] **Step 4：跑改动模块的测试**，预期全部 PASS。
- [ ] **Step 5：提交。** 三类分开提交：`refactor: drop unused parameters`、`refactor: name commented code blocks`、`refactor: name magic numbers`。

## 收尾

- [ ] 每个 PR 合入前，跑所改模块的完整测试：`./mvnw -o -ntp -pl <模块列表> -am test`。
- [ ] 复评文档逐条写明状态（已完成 / 保留及原因 / 移交），路线图 §7 同步更新；只把实际跑过、并且通过的命令记为通过。
