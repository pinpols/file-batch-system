# Java 可读性复评（2026-11-11）

## 结论

[可读性路线图](../plans/java-readability-refactoring-roadmap-2026-08-12.md)阶段 0–8 处理过的问题（CGLIB 自注入、固定 Map 契约、大类拆分、suppression、嵌套三元）在本次扫描中没有复发。剩余阅读成本集中在 R1–R6：同一判别条件反复分叉、两套图校验规则、超长平铺映射、空值判断两种写法并存、状态字面量、静默吞异常。补充复核又加了 R7–R11：过长的守卫条件、try 覆盖面过大、用 null 选模式的薄壳、类职责过宽。补充复核二加了 R12–R16：过度防御、重复判断、校验方法里夹带写入、可合并的嵌套 if、啰嗦写法。补充复核三加了 R17–R21：超长 builder 直接作实参、纯转发门面、私有薄壳转发、跨模块复制粘贴、冗余写法。补充复核四按抽取粒度复核，加了 R22：入口方法流程与细节混写；过度抽取方面，除 R19 的纯转发外没有发现系统性问题。补充复核五对照业界方法论补齐维度，加了 R23–R27，并按核心业务流程逐层复核了入口方法。

本文只记录发现和建议，不授权实施。所有条目均为“改进建议 / 候选”，未经测试验证，也不是缺陷结论。

## 基线与方法

- 仓库：`file-batch-system`，revision `29cede911`，工作区干净。
- 范围：各模块 `src/main/java`，共 3139 个文件（含 `batch-test-support`、`security-scan`）。
- 方法：去掉注释和字符串后，按花括号做启发式统计，再人工抽查。行数、参数个数和嵌套深度只用于找候选，不作为清理指标（[`coding-conventions.md` §1.3、§1.7](../coding-conventions.md#13-长方法与参数密度)）。
- 机器统计口径：方法非空行 ≥ 80 的有 79 个（已排除 record/构造器）；花括号嵌套 ≥ 6 层的有 28 个；`catch (Exception|RuntimeException)` 共 593 处，其中 `catch (Exception ignored)` 48 处（注：2d101162f 及 Task 2 之后按实际核对为 32 处，其中 30 处原本已有注释或走 `SwallowedExceptionLogger`，Task 2 补了 2 处注释）。参数 ≥ 7 的命中大多是带注解的 Controller 查询参数，属于误报，不列入。

## 发现

| # | 类别 | 位置（示例） | 现象 | 建议 | 行为保持约束 |
|---|---|---|---|---|---|
| R1 | 同一判别反复分叉 | `TaskOutcomeInstanceProgressor.advance`（168 行） | `dagInstance ? 按分区行统计 : 读实例计数器` 连续出现 5 次，分别得到 total/success/failed/cancelled/terminated | 引入不可变计数值对象，用两个具名工厂 `fromStatusRefs(...)` 和 `fromInstanceCounters(...)` 各算一次；主方法只保留阶段顺序 | 查询次数不变（非 DAG 不多查分区行）；终态核对仍用原 6 个字段比较 |
| R2 | 两套图校验规则 | 编排器 `WorkflowGraphValidator`（655 行）与 Console `WorkflowGraphTopologyValidator`（335 行） | 两处各有 `DSL_NODE_REF` 正则、可达性、起止边和环检测，一处校验实体，一处校验 Excel 行 | 把纯图算法（邻接表、可达性、环、祖先、DSL 引用解析）提到 `batch-common` 的无状态工具，两端只负责把各自输入转成同一图模型并翻译错误 | 错误码与提示文案、Excel 行号定位不变；先用两端现有测试做 characterization |
| R3 | 超长平铺映射 | `DefaultConsoleQueryApplicationService.buildTimeline`（150 行） | 11 个来源各自 `forEach` 拼一个 `ConsoleTraceTimelineItem`，前 12 行（`traceId` 加 11 个列表）只是解包 | 每类来源一个具名映射方法（或 `Function<T, ConsoleTraceTimelineItem>` 列表），主方法变为“收集 → 合并 → 排序” | 排序键与同时刻的先后顺序不变 |
| R4 | 空值判断两种写法 | 全仓 | `EmptyChecks.isNull/isNotNull` 3758 处，`== null / != null` 4135 处，同一方法内常混用 | 先在 `coding-conventions.md` 裁定一种写法及例外，再按模块分批统一；不建议一次性全仓替换 | 纯机械替换；规则调整按 project-engineering-review 要求后置于存量盘点 |
| R5 | 状态字面量 | `DefaultConsoleApprovalApplicationService:89、218`，`DefaultConsoleConfigSyncApplicationService:241`，`AuditAspect:200`，`JobExecutionQueryService:100` 等 21 处 | `"PENDING".equalsIgnoreCase(...)` 等硬编码状态比较 | 改为对应枚举的 `code().equals(...)`（参照 `PartitionStatus.SUCCESS.code()` 的写法）；无枚举时补枚举 | 已持久化字面量值不变（§1.7）；`equalsIgnoreCase` 改为严格比较前须先确认库内大小写 |
| R6 | 静默吞异常 | `BizExceptionUtils:82、111`，`BatchObjectCryptoService:261`，`AuditAspect:402` 等 48 处 `catch (Exception ignored)`（实际 32 处，见上文说明） | 读者无法判断是“预期失败可忽略”还是遗漏 | 逐处补一行中文注释说明为什么可以忽略；能缩小异常类型的缩小；需要可观测的改为 debug 日志 | 不改变返回值与异常传播 |

### 次要候选

- 嵌套 ≥ 6 层集中在导入解析器（`JsonFormatParser.parse`、`FixedWidthFormatParser.parse`、`ExcelFormatParser.parse`、`ValidateStep.processValidationBatch`）和 `SqlTaskExecutor.runStatements/fetchResults`。适合用提前返回 + 行级处理方法拆浅，但都在数据通路上，需要配套性能基线。
- 其他超过 100 行的方法：`DefaultWorkflowRunLaunchService.prepareRun`（160）、`WorkflowGraphTopologyValidator.validateWorkflowGraph`（167）、`DefaultTaskOutcomeService.applyTaskOutcome`（122）、`DefaultConsoleAiApplicationService.executeChat`（121）、`ParseStep.execute`（120）、`SqlTransformComputePlugin.compute`（116）。`prepareRun` 开头 7 行只是把参数对象逐字段解包到局部变量，可以直接用 `preparation.xxx()`。
- 两个及以上 boolean 参数的方法：`TaskOutcomeStatePolicy.resolveWorkflowEvent`、`DefaultTaskOutcomeService.applyFailureOutcome`、`updateStepInstanceProgress`。调用现场看不出 `true, false` 的含义，可以考虑具名枚举或命令对象（§1.6）。

## 补充复核：if / try / 方法抽取 / 类提取

第一轮只统计了嵌套深度、宽泛 catch 数量、过长方法，类清单直接沿用了库存报告。本节按同一基线补扫这四项，并人工抽查头部候选。

| 维度 | 机器候选 | 抽查结论 |
|---|---:|---|
| if 内联复合条件（≥ 4 项，排除纯 null 比较） | 57 | 见 R7、R8 |
| else-if 链（≥ 5 段） | 4 | 都在 `SqlTaskExecutor`、`SqlAtomicHandler`、SDK testkit 里，是按语句类型分派，可改为 switch，优先级低 |
| try 体 ≥ 40 行 | 43 | 见 R9 |
| 嵌套 try | 102 | 抽查的 `StoredProcTaskExecutor`、`AtomicConnectionManager`、`SqlTaskExecutor` 主要是 JDBC 资源与回滚的嵌套，属于合法边界；可以改成 try-with-resources 的不在此列 |
| catch 后包装重抛 | 173 | 翻译边界写法，合法，不列入 |
| 只用一次的单行私有方法 | 13 | 多数有业务命名（`nextWorkday`、`invalidCredentials`、`isTerminal`），保留。见 R10 |
| 注入字段 ≥ 12 的类 | 29 | 排除参数对象和内部行类型（如 `FileTemplateExcelRowParser` 的 42 个字段属于内部行类，是误报）后，见 R11 |
| public 方法 ≥ 30 的类 | 5 | 见 R11 |

| # | 类别 | 位置 | 现象 | 建议 | 行为保持约束 |
|---|---|---|---|---|---|
| R7 | 守卫条件过长 | `ConsoleOidcAuthenticationSuccessHandler:40` | 一个 `if` 里 9 项条件，混合了配置开关、`instanceof` 模式绑定、issuer 比较、subject 长度与字符集 | 提取 `Optional<OidcUser> trustedOidcUser(Authentication)`，按“开关 → 类型 → 注册方 → issuer → subject”逐项提前返回；模式绑定变量不能放进命名布尔里，所以用方法拆 | 拒绝路径与响应不变；`getIssuer()` 判空必须在 `toString()` 之前 |
| R8 | 参数校验写成复合 if | `DefaultTriggerService.verifyCatchUpSubmission:67`（8 项） | 命令完整性校验与业务类型判断写在一个 if 里 | 命名布尔 `catchUpCommandComplete`，或把完整性校验放到命令构造边界（§1.6 fail-fast） | 错误码 `INVALID_ARGUMENT` 与文案不变 |
| R9 | try 覆盖面过大 | `DefaultWorkflowRunLaunchService.prepareRun`：try 体 132 行，只捕获 `DuplicateKeyException` 与 `BizException`；另有 `PrepareDispatchStep.execute`（88 行）、`DefaultRetryGovernanceService:554`（87 行）、`SftpDispatchChannelAdapter:223`（82 行） | 读者看不出 catch 为什么要包住整段 | 2026-11-11 计划阶段核实：`prepareRun` 的 catch 在 `transaction.execute` 外面，`DuplicateKeyException` 抛出后 PostgreSQL 事务已中止，必须等事务回滚后才能在 catch 里查询胜出者，所以**不能收窄**。改法是把事务体提成具名方法，让 try 只剩一行，并在 catch 上注明原因。其余三处先确认抛出点再决定 | 事务边界（REQUIRES_NEW + REPEATABLE_READ）、回滚语义、胜出者查询的时机不变 |
| R10 | 薄壳传 null | `SqlFunctionNameCollector.acceptSetOperationScalarClauses:89`（给 `acceptScalarClauses` 传两个 `null`） | 用 null 组合表达“集合运算没有 GROUP BY/HAVING” | 把 `acceptScalarClauses` 拆成分组子句和排序/分页子句两个方法，集合运算只调用后者。2026-11-11 核实：`ImportIngressScanner.putMissingBundleMetadata` 是给布尔模式起的具名入口，符合 §1.6，属于误报 | 访问顺序与采集结果不变 |
| R11 | 类职责过宽 | `DefaultConsoleOrchestratorProxyService`（1115 行，49 个 public 方法）、`ConsoleQueryController`（47 个接口）、`DefaultConsoleQueryApplicationService`（45 个）；注入依赖多的有 `DefaultWorkflowRunLaunchService`（21 个）、`DefaultTenantConfigPackageExcelService`、`WorkflowRunReplayPreviewService`（各 19 个）、`DefaultLaunchService`（18 个） | 代理类把实例、工作流、重放、结果版本、血缘等所有编排器调用都放进一个端口 | 代理按领域拆成多个 Port（实例 / 工作流 / 重放 / 结果版本 / 血缘），Controller 随之拆分；启动服务按“准入/容量等待 → 持久化 → 派发”看能否分出协作者。依据是存在独立的变化原因，而不是行数 | URL、JSON、鉴权注解不变；拆端口不改 HTTP 客户端与超时配置 |

## 补充复核二：过度防御、重复判断、嵌套 if、方法职责

同一基线，按启发式扫描找候选，再人工抽查。

| 维度 | 机器候选 | 抽查结论 |
|---|---:|---|
| 构造器注入的 final 字段被判空 | 55 | 混合。`DefaultRetryGovernanceService:489`（`jobExecutionLogMapper`）、`DefaultTaskAssignmentService:929`（`resourceSchedulerProperties`）、`ConsoleMetaQueryService:172`（`messageSource`）在生产环境不可能为 null，判空只是为了照顾测试里传 null 的构造方式，属于过度防御。`DownstreamFallback:189` 的值来自 `ObjectProvider.getIfAvailable()`，判空合理。见 R12 |
| MyBatis 返回的 List 被判空 | 27 | MyBatis 查询列表不会返回 null，Mockito 对 List 返回值的默认值也是空列表。例：`DefaultWorkflowDagService:106`、`BatchDayReplayService:620、840、1120、1145`。见 R12 |
| `requireNonNull` 之后再判空 | 3 | `AtomicExecutorProductionGuard:117、130`、`AckDispatchStep:79`。见 R12 |
| 同一方法内重复的 if 条件 | 141 | 多数合理：停机标志 `stopping.get()` 在步骤之间反复检查，异常消息按关键字分类。需要改的见 R13 |
| 可合并的嵌套 if（外层只包一个内层 if，且都没有 else） | 13 | 一部分在两层之间有中文注释说明安全理由（如 `ConsoleTenantGuard:70`），这种保留。其余见 R15 |
| 名字像查询或校验，实际在写数据 | 11（排除 `updatedAt()` 等 getter 后） | 见 R14 |
| return/throw 之后跟 else | 7 | 见 R16 |
| `if (...) return true; return false;` | 2 | 见 R16 |
| 赋值后立即返回 | 60 | §1.4 允许用有业务名的中间变量，不作为问题；只在名字不提供信息时顺手改 |
| 名字带 And 的方法 | 67 | 抽查 `resolveAndValidate`、`deleteAndPublish`、`scanAndDelete`，名字如实描述了两步动作，不列入 |

| # | 类别 | 位置 | 现象 | 建议 | 行为保持约束 |
|---|---|---|---|---|---|
| R12 | 过度防御 | 上表前三行 | 给生产环境不可能为 null 的值判空，读者会误以为这里真有 null 的情况 | 注入依赖：确认所有生产构造路径都传了非 null 值（普通 Spring bean，不是 `ObjectProvider` / `@Nullable` / `required = false` / 条件装配）后删掉判空，测试改成传 mock。Mapper 列表：删掉判空，测试里的 `thenReturn(null)` 改成 `thenReturn(List.of())` | 只删除生产路径走不到的分支；条件装配的依赖保留判空 |
| R13 | 重复判断 | `ImportIngressScanner.scanCycle:180-186` | `isNotNull(current) && "INDEX/APPLY/CLEAN".equals(current.phase())` 连写三次 | 先判一次 null，再用 `switch (current.phase())` 分派 | 阶段字符串保持原字面量（§1.7）；未知阶段的处理不变 |
| R14 | 方法职责不清 | `DefaultLaunchService.validateBundleParamsBeforeInstanceCreation:160`、`validateRelatedFileReference:176`、`validateParentInstanceReference:188` | 名字是校验，实际还会把触发请求写成 REJECTED；“写 REJECTED → 重抛”在 `launch` 的 `TemplateCaptureRejected` 分支里又写了一遍，共 4 份 | 三个校验方法只负责校验；在 `launch` 里用一个阶段方法统一捕获 `BizException`，调用一次 `rejectAcceptance(request)` 再重抛 | 只有原来会触发拒绝的异常才写 REJECTED：`DictEnum.fromCode` 不能移进 catch 范围；拒绝写入发生在重抛之前；三个校验的执行顺序不变 |
| R14 | 方法职责不清 | `AssetPartitionService.loadDataAssetId:148`、`S3GovernanceStorage.listReconcilePage:36` | `load…` 实际会先 upsert；`list…` 会先初始化游标行 | 前者改名为 `ensureDataAssetId`；后者把游标初始化提成私有方法 `openCursor(storageId, prefix)`，让写入从名字上看得出来。公共方法名不改 | 写入顺序和次数不变 |
| R15 | 可合并的嵌套 if | 扫描列出的 13 处中两层之间没有注释的 | 外层只包一个内层 if | 合并成一个条件；条件达到 3 项时按 §1.5 起名 | 短路顺序不变 |
| R16 | 啰嗦写法 | `DefaultConsoleJobDefinitionApplicationService.reaches:772`、`ApiKeyVerifier.scopesAllowAny:278`，以及 7 处 return/throw 之后的 else（如 `DefaultTaskOutcomeService.applyFailureOutcome:400`） | 布尔值绕一圈再返回；分支已经返回了还写 else | 直接返回条件表达式；去掉 else，把后面的代码提到外层 | 纯形式改动 |

## 补充复核三：方法套方法、薄壳转发、复制粘贴与冗余写法

| 维度 | 机器候选 | 抽查结论 |
|---|---:|---|
| 实参/返回值嵌套 ≥ 3 层调用（排除 lambda、方法引用、builder、`Map.of`/`List.of`） | 696 | 抽查 8 处，多数是 `setX(str(e.y()))` 这种短而连贯的写法，§1.4 允许保留。`PositionalArgsConventionTest` 只拦截新改的行，这批是存量，不批量改。真正值得改的模式见 R17 |
| 直接传给调用的 builder 链 ≥ 10 段 | — | §1.1 规定必须先提成局部变量。`TriggerLaunchRecoveryService:190`（11 段）、`ForensicExportLogTransactionService:28`（12 段）违反；同类 7–9 段的（`DefaultWorkerRegistryService:273` 等）按规则可保留 |
| 纯转发类（≥ 80% 的方法只把参数原样转给另一个对象） | 2 | `DefaultTaskExecutionService` 17 个方法全部转发给拆分出来的 `TaskCreationService` / `TaskAssignmentService` / `TaskOutcomeService`；`DefaultConsoleQueryApplicationService` 53 个方法中有 43 个转发，属于已暂缓的 R11 范围。见 R18 |
| 私有纯转发方法 | 39 | 见 R19 |
| public 同名转发（排除 `@Override`） | 21 | 多为 Controller → Service、SDK 对外 API，属于正常分层，不列入 |
| 跨文件复制粘贴（8 行以上相同代码窗口） | 140 组 | 头部三组见 R20。Create/Update 请求 DTO 之间的重复是两个独立的 API 契约，保留 |
| `result` / `data` 等泛化局部变量名 | 272（`result` 254 处） | 按 §1.4 只在改到时顺手改，不单独立批次 |
| “请求有值就用请求的，否则沿用原值”的三元表达式 | 18 | 其中 11 处在 `JobDefinitionCopyOverrideMapper`。见 R21 |
| `Optional.ofNullable(x).orElse(默认值)` | 26 | 只为给 null 一个默认值就创建 Optional，见 R21 |
| `collect(Collectors.toList())` | 4 | 见 R21 |
| `Boolean.TRUE.equals(...)` | 168 | 对象类型是可空的 `Boolean`（来自数据库或 JSON），这样写是正确的，保留 |
| 注释掉的代码 | 1（误报） | `InternalAuthFilter:95` 是说明性注释 |

| # | 类别 | 位置 | 现象 | 建议 | 行为保持约束 |
|---|---|---|---|---|---|
| R17 | 方法套方法 | `TriggerLaunchRecoveryService:190`、`ForensicExportLogTransactionService:28` | 11、12 段的 builder 直接写在 Mapper 调用的实参里，违反 §1.1 | 先赋给有业务名的局部变量，再传给 Mapper | 求值顺序不变（builder 里的 `Instant.now()` 等调用不能挪位置） |
| R18 | 纯转发门面 | `TaskExecutionService` 接口 + `DefaultTaskExecutionService`（17/17 转发） | 职责早已拆到三个服务里，门面只剩转发；读者要多跳一层才能找到实现。使用方只有 `TaskControllerApplicationService`，另有 16 个测试文件引用 | 使用方改为直接注入三个具体服务，删掉门面接口和实现。§4.4 的门面只指 `WorkerRuntimeFacade`，不覆盖这里 | 事务注解要随方法一起核对：门面方法上如果有 `@Transactional`，挪到调用方或具体服务上，传播行为不变 |
| R19 | 私有薄壳转发 | 39 处，例如 `CronExpressionFormatRule:66-76` 的 `asString` / `upper` / `isBlank`；`SchedulePlanSupport:83`、`ExportFileNameResolver:62` 的 `hasText` 只是改个名调用 `EmptyChecks.isNotBlank`；`DefaultConsoleConfigApplicationService:451`、`DefaultConsoleFileTemplateApplicationService:188` 的 `resolveTenant` 只是调用 `tenantGuard.resolveTenant` | 读者要跳进去才发现什么也没做 | 改成静态导入或直接调用。像 `ConsoleClusterDiagnosticService` 的 `isActive` / `isTerminal` 这种用业务名包装集合判断的，保留 | 纯形式改动 |
| R20 | 复制粘贴 | ① `OkHttpConsoleExternalHttpTransport` 与 `OkHttpOrchestratorExternalHttpTransport`：各 156 行，换掉模块名后只差 16 行，而且都含出站地址校验（防 SSRF）；② `TaskDispatchExecutor` 与 `AbstractTaskConsumer` 有约 30 行相同的 MDC 设置和清理 | ① 是安全相关代码，修一处漏一处的风险最大 | ① 合并到 `batch-common`（`common/http` 下已有 `OutboundHttpTransport` 接口；`batch-common` 目前没有 OkHttp 依赖，需要新增，或者另设小模块）；② 提取共用的 MDC 作用域工具 | ① 地址校验、DNS 固定、超时、重定向策略逐项不变，两个模块的配置项名称不变；② MDC 字段集合和清理时机不变 |
| R21 | 冗余写法 | `JobDefinitionCopyOverrideMapper` 等 18 处“有值才覆盖”的三元；26 处 `Optional.ofNullable(x).orElse(d)`；4 处 `Collectors.toList()` | 写法比意图长 | “有值才覆盖”改用一个能返回 null 的 `firstNonNull(a, b)` 工具。不能用 `Objects.requireNonNullElse`，它在两边都为 null 时会抛异常，而原写法返回 null。`Optional.ofNullable(x).orElse(d)` 在 `d` 不为 null 时改成 `Objects.requireNonNullElse(x, d)`。`Collectors.toList()` 改 `.toList()` 前，先确认结果之后不会被修改，因为 `.toList()` 返回的是不可变列表 | null 语义与可变性不变 |

## 补充复核四：抽取粒度与主流程可读性

判断标准：入口方法（public 或 `@Override`，位于 Service / Step / Executor / Handler 等类中）应该一眼读出做了哪几步，细节放在私有方法或协作类里；反过来，不能把一句表达式、或只是转发的调用硬拆成方法。依据是 [`coding-conventions.md` §1.3、§1.4、§1.7](../coding-conventions.md#13-长方法与参数密度)，本节不新增规则。

| 维度 | 机器候选 | 抽查结论 |
|---|---:|---|
| 该抽没抽：入口方法 ≥ 40 行，并且同时包含循环、try、builder、Mapper 调用、嵌套 if、stream 中的 3 种以上 | 44 | 确认是普遍问题，见 R22。最典型的几处：`JobMonitoringAlertEmissionService.emitOnce`（84 行，没有调用任何私有方法：加锁、重查候选、逐字段组装 30 行告警详情、计算资源键、写告警，全在一个方法里）、`TaskOutcomeInstanceProgressor.advanceTimedOutWorkflowNode`（91 行，0 个私有方法调用）、`ReadinessWaitService.sweep`（82 行，1 个）、`PrepareDispatchStep.execute`（112 行，2 个）、`WorkflowRunReplayPreviewService.approve`（100 行，2 个） |
| 该抽没抽：方法里用 `// 1.`、`// 2.`、`// ---` 这类注释分段 ≥ 3 段 | 10 | 分段注释说明作者自己也把它当成多个步骤，却没有抽成方法。例：`WorkflowDagValidator.validate`（5 段）、`PipelineCompensationHook.runCompensation`（4 段）、`ForensicExportService.doExport`、`DefaultRetryGovernanceService.dispatchDueRetries`、`SqlTransformComputePlugin.buildSqlParams`。`DnsResolveGuard.isBlockedIpv4` 的 8 段是逐个地址段的说明，像一张表，保留 |
| 过度抽取：只用一次、≤ 2 条语句、没有分支的私有方法 | 276 | 抽查 10 处，基本都是在给一个概念起名（`isWorkflowTerminal`、`isQuoted`、`eventKey`、`resolveDefinitionVersion`），属于合理的抽取，不列入。真正的过度抽取是只转发、不起任何新名字的那类，已在 R19 登记（39 处） |
| 过度抽取：只用一次的私有方法连续调用 ≥ 4 层 | 12 | 抽查最深的 3 条（`ImportIngressScanner.scanCycle → indexPage → persistIndexedObject → registerExportTrigger`，每层 14–66 行），每一层都是实际的阶段，不是层层转发，保留 |
| 过度拆类：≤ 40 行、只有一个方法、只被一个类使用 | 13 | 抽查的 `WorkflowMonitoringCandidateService`、`JobDependencyWaitMetricsQuery`、`ForensicExportLogTransactionService` 都是为了让 `@Transactional` 生效而单独成 bean（阶段 2 去掉自注入后的标准做法）；其余是自动配置、健康检查或 Filter。没有发现应该合并回去的类 |

| # | 类别 | 位置 | 现象 | 建议 | 行为保持约束 |
|---|---|---|---|---|---|
| R22 | 入口方法流程与细节混写 | 上表前两行的 54 个候选（逐行见候选清单 CSV，类别 `entry-mixed-levels`、`step-comment-sections`）；已在其他条目里处理的不重复计入：R1 `advance`、R3 `buildTimeline`、R9 `prepareRun`、R14 `launch` | 读者要读完整个方法才知道它做了什么；校验、查询、逐字段组装、写库、发事件交错出现 | 入口方法只保留阶段顺序（如“守卫 → 加锁 → 加载 → 判定 → 组装 → 写入 → 发布”），每一行同一抽象层级。逐字段组装 Map / 参数对象、循环体、重试与锁的细节、分段注释对应的段落，抽成以业务意图命名的私有方法；只有存在独立变化原因或需要事务代理边界时，才抽到协作类。不为一句表达式起方法，也不新增只转发的方法 | 事务边界、锁的获取与释放顺序、查询与写入次数、异常翻译边界、日志与指标的时机都不变；每个方法单独提交 |

**主流程验收标准（R22 每个方法都要满足）：**

1. 入口方法的主体能在一屏内读完，读出的是步骤名，不是实现细节。
2. 入口方法中不再出现：超过 5 个字段的逐字段组装、超过 5 行的循环体、嵌套 if、分段注释。
3. 新抽出的每个方法，名字都表达业务意图，并且至少满足一条：被调用不止一次；或者封装了一个完整的步骤；或者给一个条件、概念起了名字。只为“拆短”而拆、只做转发的方法不允许。
4. 协作类只在有独立变化原因或需要事务代理边界时才新增。

## 补充复核五：方法论覆盖矩阵与核心流程逐层复核

### 方法来源

仓库里没有专门审可读性的 skill。`project-engineering-review` 的 25 个工作包偏规约条款（第 16、17、19 项涉及复合条件和嵌套调用）；用户级的 `refactor-audit`、`convention-audit` 是批量修复流程；PMD 规则集只启用了 `NcssCount`、`ExcessiveParameterList` 等少数几条，没有认知复杂度。所以本节用业界几套常用方法补齐维度：

- Martin Fowler《重构》第 2 版的 24 种坏味道；
- SonarSource 认知复杂度（G. Ann Campbell，2017）：结构 +1，嵌套额外加分，布尔运算符序列 +1。本节用脚本近似计算，只用来排序，不作为门槛；
- Kent Beck 的组合方法（Composed Method）和单一抽象层次（SLAP）：主方法读出来是一串步骤；
- Google 工程实践《What to look for in a code review》：复杂度、命名、注释、一致性。

### 覆盖矩阵

| 方法论维度 | 对应条目 | 结论 |
|---|---|---|
| Long Function / Composed Method / SLAP | R1、R3、R9、R14、R22、R27 | 主要问题，按核心流程逐层处理（见下） |
| Large Class / Divergent Change | R11 | 已决定暂缓 |
| Long Parameter List / Flag Argument | 第一轮参数扫描；Task 16 | 7 个以上参数的多为 Controller 注解误报；布尔参数见 R23 |
| Duplicated Code | R2、R13、R20 | 已排入计划 |
| Middle Man / Lazy Element | R18、R19；补充复核四的小类 | 只有纯转发需要处理；为事务边界单独成 bean 的小类保留 |
| Speculative Generality / 死代码 | R23（新） | 未使用的私有方法参数 23 个；“未使用的私有方法”扫描误报太多（内部类调用），结论不采用 |
| Primitive Obsession | R5、R24（新） | 状态字面量已排入计划；公共方法用 `String` 表达有限域的有 126 处，只登记 |
| Comments（注释掩盖代码） | R6、R25（新） | 吞异常的说明已排入计划；方法体内 ≥ 5 行的连续注释 24 处 |
| Magic Number | R26（新） | 207 处候选，其中 `128` 反复出现，多为长度上限 |
| Data Clumps | 本节扫描 | 只有 `bucket, key, contentType` 在 6 个存储类里一起出现，影响小，不列入 |
| Feature Envy | 本节扫描 | 108 处，几乎都是 `toResponse(entity)`、`buildParam(request)` 这类映射方法。映射本来就要读另一个对象的字段，不列入 |
| Message Chains（迪米特法则） | 本节扫描 | 0 处 4 段以上的 getter 链 |
| Repeated Switches | 本节扫描 | `switch (type)` 出现在 11 个文件里，但 type 分属不同枚举，不列入 |
| Mutable Data / 修改入参 | 本节扫描 | 53 处，多为 Filter、TypeHandler、组装器，属于正常写法；`TaskOutcomeInstanceProgressor.advance` 修改了入参 `jobInstance`，Task 4 改写时一并核对 |
| Mysterious Name | R21、本节扫描 | `ctx` / `cfg` 等缩写局部变量 29 处，泛化名 `result` 254 处，改到时顺手改 |
| 嵌套与复合条件 | R7、R8、R15、Task 17 | 已排入计划 |
| 异常处理 | R6、R9 | 已排入计划 |
| 测试代码 | 本节扫描 | 命名统一（8926 个测试中 8733 个有 `@DisplayName`，7282 个以 `should` 开头）。过长测试和无断言测试的脚本统计不可靠（没识别 MockMvc 的 `andExpect`），记为未验证，需要用 IDE 或 PMD 复核 |

### 新增条目

| # | 类别 | 位置 | 现象 | 建议 | 行为保持约束 |
|---|---|---|---|---|---|
| R23 | 未使用的参数 | 23 个私有方法参数，例：`DefaultTaskOutcomeService.applyFailureOutcome` 的 `cancellationOutcome`、`DefaultCompensationService.createWorkflowNodeReplayOperation` 的 `commandNo` 和 `traceId`、`DefaultLaunchValidationService.rejectInvalidExecutionSnapshot` 的 `triggerRequest` | 读者会以为这个参数影响结果，其实没有 | 删掉参数，调用处同步删除。`applyFailureOutcome` 删掉之后，Task 16 不再需要为它引入枚举 | 只删真正没有读取的参数；调用处删除实参时，实参表达式本身如果有副作用要保留 |
| R24 | 用字符串表达有限域 | 126 个 public 方法参数，例：`JobLifecycleMetrics.recordCompletion(jobType, terminalStatus)`、`WorkflowNodeRunSupport.recordReady(nodeType)` | 调用方可以传任意字符串 | 只登记，不在本阶段改。持久化和跨进程传递的状态字符串按 §1.7 保持原样；如要改，由[有限域与常量计划](../plans/typed-contract-enum-constant-governance-plan-2026-10-07.md)统一处理 | — |
| R25 | 注释掩盖代码 | 方法体内 ≥ 5 行连续注释的 24 处，例：`HttpTaskExecutionClient.report`（16 行）、`PartitionReclaimUnit.doReclaim`（8 行）、`DefaultRetryGovernanceService.dispatchDueRetries` | 需要大段注释才能看懂的代码块 | 逐处判断：解释 WHY 或不变量的保留；解释“这段代码在做什么”的，把那段代码抽成以注释主旨命名的方法，注释改为方法的类注释 | 纯形式改动 |
| R26 | 魔法数字 | 207 处候选，例：`DefaultRetryGovernanceService:191、848` 的 `1023`，多个服务里的 `128` | 读者不知道数字的来源 | 有业务含义的（长度上限、截断长度）提成所属类的命名常量；同一个上限在多个类里出现时，先确认是不是同一个数据库列的长度，是的话放到该实体或 Mapper 附近 | 数值不变 |
| R27 | 核心流程入口方法 | 见下面的逐层复核 | — | 按核心流程逐层整改，取代 R22 原来按指标排序的 A/B/C 批次 | 同 R22 |

### 核心流程逐层复核

按业务流程从上游到下游排查：触发受理 → 启动编排 → 派发与认领 → Worker 执行 → 回报与推进 → 治理与恢复。下表的 cc 是用脚本近似计算的认知复杂度，“私调”是方法调用本类私有方法的次数。顶层语句数是方法第一层缩进上的语句数，多数情况下可以用来判断一眼能不能读出步骤。

| 层 | 类.方法 | 规模 | 读者看到的问题 | 建议的步骤序列 |
|---|---|---|---|---|
| L1 触发受理 | `DefaultTriggerService.validateRequest` | cc 19，62 行，私调 0 | 一串校验平铺 | 按校验对象分组：命令完整性 → 目标存在 → 时间与业务日（与 R8 合并处理） |
| L1 | `TaskControllerApplicationService.renewBatch` / `claimBatch` / `reportBatch` | cc 15 / 11 / 9，42–45 行 | 请求转命令、批量调用、按下标回填结果，三件事写在一起；三个方法结构相同 | 转命令 → 批量调用 → 组装结果；按下标回填的部分抽成方法 |
| L2 启动编排 | `DefaultWorkflowRunLaunchService.launch` / `prepareRun` | cc 19 / 36 | `prepareRun` 已在 R9 处理 | `launch` 在 R9 之后复查一次 |
| L2 | `BatchDayGateService.evaluateAndApply` | cc 25，75 行，顶层语句 33 | 守卫、策略解析（`"NONE"`、`"INHERIT"` 字符串直接比较）、前一日查询、告警，全部平铺 | 守卫 → 解析生效策略 → 判定前一日状态 → 应用结果；策略解析抽成一个方法 |
| L2 | `LaunchBatchDayService.routeLateArrivalIfNeeded` | cc 21，75 行，私调 2 | 迟到判定与改写请求混写 | 判断是否迟到 → 决定路由目标 → 改写请求 |
| L2 | `DefaultLaunchService.launch` | cc 8，54 行，私调 10 | 已基本是步骤序列；校验夹带写入在 R14 处理 | R14 之后复查 |
| L3 派发与认领 | `DefaultWorkflowNodeDispatchService.dispatchNode`、`rejectDeferredChildRequest` | cc 23 / 19，81 / 84 行 | `rejectDeferredChildRequest` 只调用 3 次私有方法 | 按节点类型分派 → 准备载荷 → 派发 / 拒绝 |
| L3 | `DefaultTaskAssignmentService.renewLeaseBatch`、`recordHeartbeat` | cc 19 / 20，52 / 43 行，`renewLeaseBatch` 私调 0 | 批量续租的循环体直接写在方法里 | 单条续租抽成方法；`loadEffectiveConfig`（102 行，私调 17）已是步骤序列，保留 |
| L3 | `DefaultPartitionDispatchService.enrichPayload` | cc 21，36 行，私调 0 | 多种载荷补充规则写在一起 | 每种补充一个方法 |
| L4 Worker 执行 | `TaskDispatchExecutor.executeBatchDetailed` | cc 27，87 行，顶层语句 23 | 去重、认领、建映射、执行，四段平铺 | 去重消息 → 批量认领 → 关联认领结果 → 执行并收集结果 |
| L4 | `AbstractTaskConsumer.doConsume` | cc 13，93 行 | 消费主流程过长 | 解析消息 → 准入 → 执行 → 提交 offset 的决定；offset 提交顺序不变 |
| L4 | `AbstractStageExecutor.runStageLoop` | cc 22，87 行 | 阶段循环体过长 | 单个阶段的执行抽成方法 |
| L4 | `DeliverDispatchStep.prepareDeliveryAttempt` | cc 32（核心流程最高），97 行 | `if (newAttempt) {…}` 之后紧接 `if (!newAttempt) {…}`，最后 `return null`：两个互补分支写成了两个独立的 if | 改成 `newAttempt ? startNewAttempt(...) : resumeAttempt(...)` 两个具名方法；先确认原来两个分支都没有命中时确实返回 null |
| L4 | `PrepareDispatchStep.execute`、`ParseStep.execute` | 113 / 120 行 | 已在 R22 / Task 17 列出 | 并入本层 |
| L5 回报与推进 | `DefaultTaskOutcomeService.applyTaskOutcome` | cc 24，146 行，顶层语句 48 | 结果回报的核心入口。“查任务上下文 → 判空 → 判 RUNNING”在加锁前后各写一遍；先查后锁再查本身是必要的，但两段重复 | 抽出 `requireRunningTaskContext(...)`，加锁前后各调用一次；主方法变成：取上下文 → 加锁 → 再取上下文 → 校验归属与本次调用 → 写任务结果 → 写分区结果 → 推进实例 / DAG |
| L5 | `TaskOutcomeInstanceProgressor.advance` / `advanceTimedOutWorkflowNode` | cc 27 / 5，168 / 92 行 | `advance` 在 R1 处理；`advanceTimedOutWorkflowNode` 92 行，私调 0 | R1 之后复查 `advance`；另一个按超时判定 → 更新节点 → 推进 DAG 拆开 |
| L5 | `DefaultWorkflowDagService.isNodeReadyForDispatchInternal`、`cascadeSkipDownstream`、`canNeverFire` | cc 23 / 21 / 19 | 就绪判定逻辑密集 | 只做 R15 合并条件、§1.5 命名布尔；不改判定顺序 |
| L6 治理与恢复 | `DefaultRetryGovernanceService.autoRetryDueDeadLetters` | cc 27，77 行，顶层语句只有 3 条 | 循环体约 70 行，处理单条死信的细节全在循环里 | 抽出 `retryDueDeadLetter(deadLetter)`，主方法只剩“查到期记录 → 逐条重试” |
| L6 | `RetryRequeueCoordinator.requeuePartition` | cc 21，91 行 | 校验与重排写在一起 | 校验可执行 → 重置分区 → 写重试事件 |
| L6 | `BatchDayOpenService.reconcileDependencyWaits`、`checkCalendarDependencies` | cc 20 / 20 | 依赖核对逻辑密集 | 按依赖类型拆分 |

核心流程以外的 R22 候选（候选清单里其余的行）放到最后，按同一标准逐个判断。

### 全量覆盖（2026-11-11 补做）

上面的逐层表只覆盖手选的 37 个核心类；补充复核四的 54 个候选也只扫描了类名以 `Service` / `Step` / `Executor` 等结尾的类。为确认“流程与细节混写”没有遗漏，按同一标准对全部业务类重扫了一遍。

**范围**：各模块 `src/main/java` 下的全部类，排除接口、record、枚举，以及按类名后缀识别的没有流程的类型（`Entity`、`Param`、`Query`、`View`、`Response`、`Request`、`Mapper`、`Config`、`Properties`、`Exception`、`Constants`、`Snapshot`、`Event`、`Command`、`Result` 等），也排除 `batch-test-support` 和 `examples`。共 **1188 个业务类、8236 个方法**，分布在 271 个业务包里。

**判定标准**（方法 ≥ 30 个非空行时才判断）：入口方法（非 private）满足任一条件即命中：顶层语句 ≥ 15；认知复杂度 ≥ 15；≥ 50 行并且调用本类私有方法 ≤ 2 次。私有方法 ≥ 80 行，并且顶层语句 ≥ 15 或认知复杂度 ≥ 15 的也命中。Controller 单独看：方法 ≥ 15 行即命中（§6.3 要求 Controller 只做协议转换）。

**结果**：

| 模块 | 业务类 | 方法 | 命中 |
|---|---:|---:|---:|
| `batch-orchestrator` | 318 | 2065 | 111 |
| `batch-console-api` | 410 | 2992 | 96 |
| `batch-worker`（6 个子模块合计） | 228 | 1830 | 75 |
| `batch-trigger` | 55 | 336 | 8 |
| `sdk/java` | 52 | 370 | 8 |
| `batch-common` | 122 | 635 | 3 |
| `security-scan` | 3 | 8 | 1 |
| 合计 | 1188 | 8236 | 302（入口 282，私有 20），另有 Controller 26 |

命中的 302 个方法分布在 92 个业务包里，其中 186 个在核心链路（编排器的服务、调度、引擎、配额、MQ、租约、文件治理、SLA，以及 Trigger、各 Worker、SDK 调度器），116 个在支撑功能（Console 配置导入、通知、审计 AI、运维查询等）。命中最多的包：`console-api/infrastructure/config`（21）、`orchestrator/service`（16）、`orchestrator/application/service/task`（15）、`orchestrator/application/service/governance`（13）、`orchestrator/infrastructure/scheduler`（11）、`orchestrator/application/service/workflow`（11）、`orchestrator/application/service/replay`（11）、`console-api/domain/notification/service`（10）。

**按形态分类**（每类都抽查了顶层语句确认）：

| 形态 | 判定 | 数量（核心 / 支撑） | 示例 | 整改手法 |
|---|---|---|---|---|
| A 平铺组装 | 顶层语句 ≥ 15，认知复杂度 ≤ 8 | 35 / 50 | `WorkflowTerminalOutboxService.writeTerminalEvent`：顶层 32 条语句，大半是 `payload.put(...)` | 把组装抽成以产物命名的方法（如 `terminalEventPayload(run)`），或移到 DTO 的静态工厂（§8.1） |
| B 主体塞在循环或分支里 | 顶层语句 ≤ 8，认知复杂度 ≥ 15 | 34 / 16 | `BatchDaySettleScheduler.driveCatchUp`：顶层只有 6 条语句，认知复杂度 39，细节全在 for 循环体里；`TaskDispatcherRetryCoordinator.claimWithRetry`、`DispatchChannelHealthService.probeConfiguredChannels` 同类 | 把循环体、分支体抽成处理单个元素的方法 |
| C 混合 | 其余 | 116 / 45 | `DispatchChannelSnapshotService.captureForWorkflowReplay`（cc 41）、`DefaultTaskOutcomeService.applyTaskOutcome` | 先写出步骤序列，再按步骤抽取 |
| D 已接近步骤序列 | 私有方法调用次数 ≥ 顶层语句的 60% 且认知复杂度 < 15 | 1 / 5 | — | 复查后多半保留 |

全部 302 个方法和 26 个 Controller 方法已写入候选清单，类别格式为 `core:形态` / `support:形态` / `controller-over-15-lines`。

**本次覆盖的边界**：

- 1188 个业务类全部按统一指标检查过；命中的 302 个按形态抽查确认了分类；**并没有逐个人工阅读 302 个方法**，逐个判断留到执行阶段（Task 23 每个方法的 Step 1–2）。
- 没有命中的 7934 个方法只说明低于阈值。30 行以下的方法没有判断；读起来是否清楚，还要在改到时按主流程验收标准复看。
- 类名后缀过滤可能误排除个别名字以 `Result` / `Snapshot` 结尾、但其实承载流程的类。上面已知的 `WorkflowRunExecutionSnapshot`（786 行）不在本次统计里，已由 R1 / Task 4 附近的复查覆盖。执行时如果发现类似的类，补进清单。

## R5 逐处结论（Task 1）

判定依据：只有写入端（Mapper、实体、状态机）实际落库的正是该枚举的 `code()` 时才替换；`BatchLifecycleStatus` 是派生类型、不直接落库，不能作为持久化字段的比较对象。

| 位置 | 字面量 | 结论 | 说明 |
|---|---|---|---|
| `DefaultConsoleApprovalApplicationService`（82、89、214、218、227） | `PENDING`、`REJECTED`（忽略大小写） | 已替换为 `ApprovalCommandStatus` | `approval_status` 由 `DefaultApprovalWorkflowService` 以该枚举的 `code()` 写入，库约束为 PENDING/APPROVED/REJECTED/EXECUTED；原有 `equalsIgnoreCase` 保留。同方法内未列入计划的 `EXECUTED` 比较未动 |
| `DefaultConsoleJobApprovalService:260`、`BatchDayCatchUpReconciliation:47` | `RUNNING`（operationStatus） | 不替换 | 该字段是补跑操作自己的状态机（PREPARED/RUNNING/COMPLETE/UNKNOWN，见 `BatchDayCatchUpOperationMapper.xml`），`ReplayOperationStatus` 没有 PREPARED/COMPLETE/UNKNOWN，不是同一个值域 |
| `DefaultWorkflowDagService`（583、603、622）、`WorkflowRunStuckReconciler:151` | `FAILED`（节点状态） | 已替换为 `WorkflowNodeRunStatus` | 节点状态由 `WorkflowNodeRunStatus.*.code()` 写入；忽略大小写的三处保持 `equalsIgnoreCase` |
| `FileGroupExpectationService:220`、`ExternalEventExpectationService:273`、`LaunchBatchDayService:383` | `FAILED`（dayStatus） | 不替换 | `batch_day_instance.day_status` 是批次日自己的状态机（OPEN/CUTOFF/IN_FLIGHT/SETTLED/FAILED），`BatchLifecycleStatus` 不含 SETTLED/IN_FLIGHT 且不直接落库 |
| `BundleArrivalLauncher:409` | `FAILED`（dayStatus） | 不替换 | 同上，比较的是 `BatchDayInstanceEntity.dayStatus()` |
| `BatchDayReplayService:512` | `FAILED`（回放 entry 状态） | 不替换 | entry 状态为 PENDING/RUNNING/SUCCEEDED/FAILED，没有一个枚举覆盖该值域（`ReplayOperationStatus` 无 PENDING，`SUCCEEDED` 与 entry 的写入也不对应）；同类的 `ENTRY_PENDING` 等常量留给有限域治理计划 |
| `JobExecutionQueryService`（100、112、113） | `RUNNING`（stage/step 状态） | 已替换为 `PipelineRunStatus` | 状态来自 `pipeline_step_run.step_status`，写入端 `PlatformPipelineRunRepository.startStepRun` 使用 `PipelineRunStatus.RUNNING`；同文件第 73 行的 `NOT_EXECUTED`/`PENDING` 是展示占位，未动 |
| 未列入计划的同字段字面量 | — | 留待后续 | 计划清单之外，同字段上还有 `DefaultWorkflowDagService` 的 `"SKIPPED"`（约 578、582、607、624）与 `STATUS_SUCCESS="SUCCESS"`（65）、`WorkflowRunStuckReconciler:59` 的 `Set.of("SUCCESS","FAILED","SKIPPED")`、审批的 `"EXECUTED"`/`"APPROVED"`，本任务未动，留作后续整改 |
| `ResultVersionPromoteService:47、76`、`DefaultConsoleConfigSyncApplicationService:241`、`AuditAspect:200`、`DefaultCompensationService:1171` | — | 不替换 | 没有对应枚举，本计划不新增枚举 |

## R9 逐处结论（Task 8）

判定依据：catch 处理的异常是否可能来自 try 里的任意一步，以及 catch 是否依赖 try 体里逐步更新的状态。两者任一成立就是整段翻译边界，收窄会改变异常归类。

| 位置 | 结论 | 说明 |
|---|---|---|
| `DefaultWorkflowRunLaunchService.prepareRun` | 已处理：事务体提成 `createRunInTransaction`，try 只剩一行 | catch 必须留在 `transaction.execute` 外面：唯一键冲突后 PostgreSQL 事务已中止，胜出者查询只能在回滚后执行；已在 catch 上注明。传播行为、隔离级别未改 |
| `PrepareDispatchStep.execute`（try 约 88 行） | 整段翻译边界，保留；补注释候选 | `catch (Exception)` 把载荷解析、文件/渠道加载、绑定流水线实例中任意一步的异常统一翻译为 `DISPATCH_PREPARE_PARSE_FAILED` 阶段失败；收窄会让仓储异常直接抛出阶段之外。可在 catch 上注明“整段准备阶段的统一失败出口”，或另开批次把 try 体提成具名方法 |
| `DefaultRetryGovernanceService.replayDeadLetterWithinTransaction`（外层 try 约 87 行） | 整段边界，保留；补注释候选 | 外层是 `try/finally` 的 MDC 作用域，必须包住整个重放过程；内层 try 是保存点边界，catch 回滚到保存点后再记失败次数，覆盖重排队与 `markReplaySuccess` 两步。可另开批次把内层 catch 的失败记账提成具名方法，边界本身不收窄 |
| `SftpDispatchChannelAdapter.uploadViaSftp`（内层 try 约 82 行） | 整段翻译边界，保留；补注释候选 | catch 依赖 try 体逐步更新的 `tempRemotePath`、`manifestTempRemotePath`、`publicationAttempted`、`publicationConfirmed`，用于清理远端临时文件并区分“外部结果未知”与普通失败；收窄会丢失这一归类。可在 catch 上注明这些标志的含义 |

## 决策记录（2026-11-11）

用户裁定以下三项。2026-11-11 用户另裁定执行方式为子代理驱动（每任务一名实现者加一名评审者），并授权提交、推送与开草稿 PR；每次合并仍需用户确认。PR-1（Task 1–3）已实施并评审，其余任务未开工。

| 事项 | 裁定 | 落地方式 |
|---|---|---|
| R4 空值判断写法 | **C：按语义区分。** 纯对象判空用 `x == null` / `x != null`；字符串用 `EmptyChecks.isBlank` / `isEmpty`（按“空白算不算空”选择）；集合、Map、数组用 `EmptyChecks.isEmpty` | 先在 `coding-conventions.md` 新增 §1.8，再按模块分批替换，排在本计划其他任务之后（阶段 9 计划 Task 18） |
| R11 大类按领域拆分 | **B：暂缓。** 登记在 todo-master，等有需求变化落到这些类时再立计划 | 已登记 todo-master“Java 可读性阶段 9”一行 |
| 次要候选 | **A：本轮一起做** | 阶段 9 计划 Task 15–17 |

### 裁定落地前核实的约束

- `EmptyChecks.isBlank` 用 `Character.isWhitespace` 判断，`String.trim()` 去掉的是 `<= '\u0020'` 的字符，两者对控制字符和部分 Unicode 空白结论不同。所以 `x == null || x.trim().isEmpty()`（1 处）不能替换成 `EmptyChecks.isBlank(x)`；`x == null || x.isBlank()`（与 `trim` 形式合计 228 处候选）和 `x == null || x.isEmpty()`（116 处）可以替换，但要先确认 `x` 的静态类型。
- `EmptyChecks::isNotNull` 方法引用有 7 处，替换为 `Objects::nonNull`。
- 生产代码中 `EmptyChecks.isNull/isNotNull` 共 3733 处。`ServiceEntryGuardArchTest` 两种写法都认，不需要改守卫。静态分析治理记录过 16 条 Sonar S2259 误报，原因是 Sonar 识别不了 `EmptyChecks` 的空值契约，改成原生写法后这类误报应消失。
- 次要候选的复核结果：
  - else-if 长链实为 SQL 拆语句的字符状态机，Worker 的 `SqlTaskExecutor.SplitScanner` 和 SDK 的 `SqlAtomicHandler.StatementSplitState` 各有一份。SDK 是独立发布的，不能依赖 `batch-common`，所以两份分别整理，不合并。
  - 多布尔参数：`DefaultTaskOutcomeService` 的 `retryScheduled` 与 `cancellationOutcome` 互斥（第 210 行 `retryScheduled` 以 `!cancellationOutcome` 开头），适合换成一个枚举。`TaskOutcomeStatePolicy.resolveWorkflowEvent` 的调用处实参都是具名访问器（`context.allPartitionsFinished()` 等），读起来没有歧义，保留不改。
  - 深嵌套的 15 处中，`JsonFormatParser`、`FixedWidthFormatParser`、`BizTableSchemaRegistrar`、`TenantConfigInitApplyHandlers` 没有单测，改之前要先补 characterization 测试。仓库里没有解析器的微基准（`load-tests` 只有 Gatling 接口压测），数据通路上的改动只能做本地前后计时对比，记为粗略证据。
- 疑似行为问题（另案，不在可读性提交里修）：两份 SQL 拆分器都把单引号里的 `\'` 当作转义。PostgreSQL 在 `standard_conforming_strings = on`（默认值）时，普通字符串里的反斜杠不是转义符，`'C:\'; DROP …` 这类输入可能拆错。需要单独复现后再定性。

## 整改计划

2026-11-11 已据本文排出实施计划：[Java 可读性阶段 9 实施计划](../plans/java-readability-phase-9-plan-2026-11-11.md)。按上面的决策记录：R4 排在最后，R11 暂缓，次要候选纳入本轮。

## 建议顺序

1. R5、R6、R10：改动小，风险低，可按模块分批。
2. R1、R3、R7、R8：单类内重构，先补 characterization 测试。
3. R9：先确认抛出点，再决定收窄还是加注释。
4. R2、R11：跨模块或拆端口，各自单独开 PR。
5. R4：先裁定规则，再分批改。

未验证：本次没有运行测试或 PMD/Checkstyle；行数统计是启发式结果，与 IDE 统计可能有少量出入。
