# WDI 分阶段 PR 交付准备（2026-10-30）

## 25 项治理状态核对（2026-10-30）

按[后端治理记录](../audit/backend-25-package-governance-2026-10-26.md)的工作包表逐行核对，共 25 项（其中 3a、3b 分列）：22 项标记“部分”，3 项标记“部分（仍待继续）”，没有标记整体完成的项。用户提出的“25 项至少部分完成后准备 PR”条件已达到；这不等于全部治理关闭或当前 PR 可合并。导出分片重叠缺陷已按用户选择在本地统一哈希并完成定向数据库回归；最终提交范围、远端 CI 和必要交付验证仍须按实际状态处理。

## 本地未跟踪文件刷新（2026-10-30）

较早盘点时后端 `fix/wdi-current` / `f3d5e400d` 有 73 个未跟踪文件：按路径初分为测试 38、迁移 5、文档 2、其他 28；前端当时 `fix/wdi-current` / `662d254` 有 10 个：测试 8、本地产物 1、其他 1。该历史分类不代表其应当全部交付；逐文件 SHA-256 和路径分别保存在忽略目录 `logs/verification/pr-readiness-20261030/file-batch-system-untracked-current.tsv`、`batch-console-untracked-current.tsv`。

同日最新只读快照：后端有 1,737 个路径含暂存变化、1,626 个含未暂存变化、3 个未跟踪文件；前端分别为 485、601、0，暂存与未暂存路径可能重叠。后端未跟踪的三个文件是 `AbstractWorkerKafkaNamespaceIsolationSupport.java`、`test_e2e_shard_coverage.py`、`test_e2e_method_completeness.py`。原抽象基类显示 `MD`（暂存有修改、工作树已删除），四个继承类、PR/Full/Staging 工作流与两个 E2E 守卫也存在未暂存变化。仅提交现有暂存区将遗漏这些后续修改；整理提交时需按完整工作树审查，不在此自动更改暂存区。V367–V371、导出分片回归及扫描资源现已进入暂存区，但部分文件仍有未暂存变化，不能只凭路径状态认定最终内容完整。

PR 与 Full CI 的测试组依赖 Flyway、迁移安全、导出内容及选测等测试文件；导出检查还依赖 `scripts/sim/export_partition_evidence.py` 与场景查询 SQL，安全扫描模块依赖 `security-scan/src/main/resources/semgrep_report.py`。这些文件在较早盘点时未跟踪，最新快照已暂存；交付时仍须连同调用方与工作树后续修改审查。`git diff --cached --check` 当前报告 V358 迁移与后端验证文档各一处文件末尾空行；工作树版本此前已清理，但暂存区尚未更新。前端暂存区另有报告/源码尾空行及 Markdown 行尾提示；归档文档中的双空格是硬换行，遵守用户要求不改归档。本次仅盘点，未暂存或提交。

## 目的与边界

在保留当前前后端工作树及全部暂存、未暂存、未跟踪内容的前提下，整理可审阅的阶段性交付。用户明确要求 25 个治理工作包至少均达到部分完成后再准备 PR；不要求所有工作包完全关闭，但可合并状态仍需按其实际范围完成检查。本文是一次本地快照，不授权暂存、提交、推送、创建或合并 PR，不作为全仓治理完成声明。

## 基线和快照

| 仓库 | 工作树与分支 | HEAD / 本地 origin/main | 工作区状态 |
|---|---|---|---|
| 后端 | `.codex/worktrees/wdi-current/file-batch-system`，`fix/wdi-current` | `f3d5e400d3b5bd737124b57cf0a38e685432aab2` / `ee01e97515b04fd167ed17977535ec5cab8572f8` | 1,662 个路径含暂存变化，1,591 个路径含未暂存变化，65 个未跟踪文件；两类可能重叠 |
| 前端 | `.codex/worktrees/wdi-current/batch-console`，`fix/wdi-current` | `662d254e2f8b97f93021de548815470d2291c997` / 同 HEAD | 475 个路径含暂存变化，600 个路径含未暂存变化，10 个未跟踪文件；两类可能重叠 |

2026-10-30 本次只读查询远端 `main`，两仓远端均与各自本地 `origin/main` 一致；后续交付前需重新核对。后端 HEAD 是 `wip-baseline-snapshot` 双父节点快照：第一父节点是上述 main，第二父节点是 `6221f01a73bbac0c0a94ed2973fbfa1ba40f43d5`（快照的 index 提交）。它不是普通功能提交；直接推送当前历史会同时带上这两笔快照历史，须先审查历史和目标 PR diff。不得通过重置当前工作树来整理。

后端相对本地 `origin/main` 的已跟踪文件差异为 4,743 个，其中 HEAD 与 main 的差异已有 4,100 个；前端已跟踪文件差异为 978 个。上述差异含不同层次的重叠文件，**不能加总为独立变更数**，也不含未跟踪文件。当前状态与文件清单保存在本机忽略目录 `logs/verification/pr-readiness-20261030/`；该清单生成在本文创建之前，之后变化须重新核对。前端一个 CSV 有 Git 的 CRLF 转 LF 提示，未在此次盘点中改写。

## 已确认的交付依赖

1. **批日补跑恢复链**：后端 Console 控制器、服务、Mapper/XML、Orchestrator 及其测试中存在未跟踪文件；`V367`、`V369`、`V370` 增量迁移亦未跟踪，且 `V370` 依赖 `V367` 创建的表。后端 Console OpenAPI、前端生成类型 `src/types/api.generated.ts` 和批日页面/调用方都有相关改动。候选 PR 必须把实际使用的代码、迁移、契约和测试按依赖闭合，不得仅提交已跟踪文件。
2. **其他未跟踪迁移**：`V368` 保存审批提交原因，`V371` 增加 Trigger 恢复代际；它们各自需与写入、读取和测试确认对应关系。当前只确定文件职责，不能直接认定它们能独立合并。
3. **已有迁移差异**：相对 main 的已跟踪迁移中有新增、修改和重命名。用户要求增量 Flyway、连续版本和空库部署；必须区分已有版本变更与新增迁移，核对最终迁移集合、执行顺序和受影响测试，不能只凭新增编号连续认定可交付。当前应用户要求，仅本仓 `fix/wdi-current` 的 PR 临时放开已有迁移有效 SQL 漂移阻断；合并后须移除例外。其他分支、merge queue 和版本连续性仍严格检查。
4. **前后端契约**：后端 `docs/api/console-api.openapi.yaml` 与前端生成类型均有改动；业务页面调用也有改动。拆分前应按具体端点建立依赖，不以两个仓库各自能编译代替同一契约的核对。
5. **产物与测试**：前端未跟踪文件既有源码和测试，也有 `output/playwright/` 下文件；后端未跟踪文件含源码、测试、迁移和扫描模块资源。逐项判定是否为交付所需或本地产物，不能一概排除或一概暂存。

### 前后端 PR 契约来源与顺序

2026-10-30 只读查询后端远端：`main` 为 `ee01e97515b04fd167ed17977535ec5cab8572f8`，与本地 `origin/main` 一致；远端不存在 `fix/wdi-current`。前端 PR 的 `resolve-backend-openapi.sh` 在未配置 `BE_OPENAPI_URL` 覆盖时，先尝试与前端 PR 同名的后端分支，404 才回退后端 main。当前前端生成类型与后端 main 契约执行 `BE_OPENAPI_PATH=/tmp/wdi-main-openapi-20261030.yaml npm run gen:api:check` 实际退出 1、报告漂移，日志 `/tmp/wdi-frontend-main-contract-check-20261030.log`；这不是当前配对工作树间的漂移结论。配对工作树此前 `gen:api:check` 已通过，日志见 `logs/verification/backend25-2026-10-30/notification-ui-contract/wdi-notification-ui-api-check.log`，该历史日志未绑定源码哈希。

交付前应先让前端 PR 可读取包含对应 OpenAPI 的后端分支，确认前端 PR 分支名、后端远端分支名及实际解析日志一致，再提交前端 PR；也可在后端契约先合入 main 后按实际状态重新核对。若配置覆盖 URL，则以实际覆盖来源为准；本次未读取 secrets、未推送分支或触发远端 CI。只读快照的当前后端契约 SHA-256 为 `9dabf788d8d9a807c7d6ef48b04a731b14becf81b49644cfd67de579f3882a48`，前端生成类型 SHA-256 为 `d27f5beb2fdb0930156b6efc0512631490c40ff66d54ca96bc416c4d9450fe55`，交付前需重新比对。

### 未跟踪文件补充盘点

后续只读复核时，后端未跟踪文件为 68 个：25 个生产源码、35 个测试、5 个迁移、2 个文档和 1 个打包资源；前端为 10 个：1 个生产源码、8 个测试和 1 个 `output/playwright/` 候选。完整路径、类别及本次文件 SHA-256 记录在本机忽略目录 `logs/verification/pr-readiness-20261030/{backend,frontend}-untracked-manifest.tsv`，不代表这些文件均已审查或应整体提交。

有明确依赖的例子：当前工作树的文件证据响应和 Console 查询调用未跟踪的 `SensitiveEvidenceMasking`；Trigger 集成测试引用未跟踪的 `TriggerCatchUpQueryTestHarness`；安全扫描模块从 JAR 资源加载未跟踪的 `semgrep_report.py`。这些例子说明把当前工作树后续修改纳入提交时，必须同步核对所依赖文件；不推断仅提交现有暂存区一定产生相同错误。前端 `output/playwright/batch-fence-setup.js` 是本机测试设置脚本，本次未发现仓库内文本引用；是否应保留或交付尚未裁定，不删除或自动暂存。

2026-10-30 补充逐项定位后端未跟踪生产资产：共 31 个路径，包含 5 个 Flyway 迁移、24 个 Java 源文件、1 个 Mapper XML 和 1 个扫描资源。路径、内容 SHA-256 与 Java/XML/Python/SQL 文件中的名称引用保存在 `logs/verification/pr-readiness-20261030/untracked-production-dependencies.tsv`；这是名称检索，不代表所有引用或交付范围已确认。当前已跟踪代码直接引用的例子包括文件模板启用围栏和定宽映射校验、派发渠道选择、Trigger 补跑身份与 readiness 时间窗口、Atomic 参数解析，以及 Trigger 恢复代际参数。若候选提交包含这些调用方，须同步审查对应未跟踪文件。

名称检索没有其他直接引用的三个 Java 类不是因此可排除：`DefaultBatchDayCatchUpRecoveryService` 由 Spring `@Service` 装配；`ConsoleBatchDayCatchUpRecoveryController` 暴露批日请求的放弃、继续和核对接口，前端 `src/api/batchDays.ts` 与 OpenAPI 均引用对应路径；`InternalCatchUpVerificationController` 暴露内部核对接口，Console 的 `BatchDayCatchUpReconciliation` 按路径调用。提交时应按组件装配和 HTTP 路径核对，不能只靠类名引用判断。未跟踪文件是否纳入最终 PR 仍以选定的业务闭环和最终 diff 为准；本次未暂存、未编译或重复测试。

### 审批和 Trigger 恢复的迁移依赖补充

2026-10-30 按当前工作树追踪两条链路；下列清单是提交时的最小关联提示，并非完整文件清单，也不表示可以脱离其他连续版本单独交付：

| 迁移 | 必须一起核对的调用链 | 现有验证与边界 |
|---|---|---|
| 未跟踪的 `V368__preserve_approval_submission_reason.sql` | Orchestrator 的 `ApprovalCommandMapper.xml`、`ApprovalCommandEntity` 和 `DefaultApprovalWorkflowService` 写入并比较原提交原因；`ApprovalWorkflowService` 暴露来源原因，Console 的 `OrchestratorApprovalClient` 用其核对原请求。对应的数据库、内部 HTTP、Console 回查测试及未跟踪的 `ApprovalSourceReconciliationE2eIT` 也须逐项判定纳入范围。 | 历史 PostgreSQL、HTTP 和 Console 定向证据见审计记录 `BUG-20261029-APPROVAL-ORIGINAL-REASON`；本次未重跑。仅支持空库，不推断旧审批记录的原原因。 |
| 已暂存的 `V348__trigger_launch_recovery.sql` 与未跟踪的 `V371__trigger_launch_recovery_generation.sql` | V348 创建恢复锚点，V371 增加代际；`TriggerLaunchRecoveryMapper.xml`、实体、两种参数对象和 `TriggerLaunchRecoveryService` 共同传递及比较代际。应同时核对 Mapper、恢复服务、交错恢复及迁移结构测试。 | 历史 PostgreSQL 交错测试及相关测试 34/34 的日志见 `logs/verification/backend25-2026-10-30/recovery-overlap-attempts/requeue-final.log`；本次未重跑，不代表目标环境或完整回归通过。 |

这些路径存在暂存、未暂存和未跟踪混合状态；准备提交时必须审查最终内容而不是只按当前暂存区判断。Flyway 仍须包含并核对 V367–V371 的连续序列及各自相关代码；本轮仅完成上述两条调用链的只读依赖定位。

通过 `gh pr list --state open --limit 100` 分别查询两仓，前 100 个开放 PR 中未发现标题或分支名匹配 WDI、Workflow、daily 或 integration 的结果；这是命名筛选，不证明不存在名称不同的相关 PR，创建前仍需核实依赖。

以上只列已定位到的依赖，不是完整 PR 拆分图。`docs/audit/backend-25-package-governance-2026-10-26.md` 的 25 包均已有部分工作证据，但均未全部关闭；候选 PR 中未完成内容须显式列出。

2026-10-30 在无其他 Maven 进程时执行 `./mvnw -ntp -DskipTests test`，根 reactor 17 个模块均成功，日志 `/tmp/wdi-reactor-compile-current-20261030.log`。命令包含生产与测试源码编译，但部分模块复用已有编译产物、全部测试执行被跳过；不能称为干净构建或测试通过。独立 `security-scan` 不属于该 reactor，其单独构建和测试见 25 包治理记录；完整 reactor 测试、CI 和目标环境仍待验证。

同日先前的 `ExportPartitionMixedFallbackIntegrationTest` 在两种插件中均复现分片间 ID 1 重复，日志 `/tmp/wdi-export-mixed-current-20261030.log`。后续按用户选择统一哈希，真实数据库反例及受影响测试 15/15 通过；该测试当前仍未跟踪，交付时须一并纳入。PR 的 Worker 分片按类名排除 `*IntegrationTest`，不能依赖该分片执行此反例；其排除表达式不排除 `*IT`，部分文件名虽为 `IntegrationTest.java`、类名却为 `*IT` 的测试仍会被选中。Full CI Worker 分片选测包含该反例，远端尚未运行；真实 SIM、跨分片数据快照及容量仍未验证。

同日补充 `ExportPartitionContentE2eIT`：隔离的真实 PostgreSQL、Kafka、对象存储和 Export Worker 中，旧范围配置下四个分片的任务成功，读取四个产物后验证 40 个业务键恰好各出现一次，1/1 通过。此前首次运行因测试仅通过流水线文件引用枚举，查到最后一个文件而失败；改按该次唯一批次来源读取四个文件后通过。此证据覆盖一条内置 SQL 模板导出路径，不代替 SIM、跨分片一致性快照或容量验证。测试文件当前存在暂存与未暂存的不同版本，交付须核对最终内容。

同日补充新增测试报告盘点：从相对 `origin/main` 新增及未忽略未跟踪的 Java 测试文件中，按源码顶层测试类名和测试注解枚举 337 个候选；其中 10 个在所属模块当前 `target/surefire-reports` 未找到对应报告。9 个 E2E 类均找到更早的运行记录或保存的 XML；另一个 `ArchiveDiagnosticCadenceProbeIT` 有历史成本探针记录，按用户要求性能后测。缺少当前报告不等于没执行，历史记录也不等于当前源码的完整通过；其中 `StaleWorkerReportFenceE2eIT` 的历史整类结果早于当前新增场景。未因报告覆盖或缺失而重复运行已成功测试；完整 E2E、当前源码和远端 CI 状态仍待核对。

2026-10-30 对双仓检查暂存与工作树的差异空白：两仓未暂存差异检查均通过，但暂存区各有空白提示。后端暂存的 V358 尾部空行在工作树中已去除；前端 `schedulerSnapshotPresentation.ts` 的工作树已按 Prettier 整理并通过目标 ESLint/Prettier，尚未更新暂存区。前端归档文档及一份报告的行尾双空格属于 Markdown 硬换行，遵循用户要求未改归档文档；其他暂存报告尾部空行与历史差异留待按最终提交范围核对。暂存区没有因本次核查被修改，准备提交时须重新审查最终暂存内容，不得将这次工作树检查当成暂存区通过。

同日对调度快照展示函数的提取做有限行为对照：从前端 `origin/main` 取得原函数，与当前 `schedulerSnapshotPresentation.ts` 在空值、标签、在线状态及错误追踪信息等 21 个输入上逐项比较，结果一致；源码 SHA-256 分别为页面 `c141a6cf1d5a79d137a6cd10291333b899838e214113f7970347efd5122534ef`、提取文件 `9827a6bbb8cf57f9792e13e403b2d05d90e3c3261b90fa2e83041a21a3a6ca25`。这是函数级对照，不覆盖页面租户切换、异步请求或浏览器联调；上述范围沿用各自已有测试证据，未重复运行。

## 建议执行顺序

1. 对两仓的快照历史、暂存区、未暂存和未跟踪文件做逐组归属，识别共享 API、迁移、构建配置及生成文件；先选能形成闭环的交付单元，再决定是否需要堆叠 PR。不要只按目录或文件数量拆分。
2. 为每个候选单元列变更范围、前置依赖、已复现缺陷、已有有效测试证据及未验证项；确认最终 diff 不遗漏未跟踪源码/迁移，也不误纳入本地产物。导出多分片统一哈希的本地修复须包含未跟踪反例测试，并说明真实 SIM、跨分片一致性快照及容量尚未验证。
3. 核对 main 与已有同任务 PR，再准备独立、可审阅的提交历史；在用户明确授权相应交付动作后，审查拟提交内容，按仓库流程执行相关提交检查。推送和创建 PR 分别核实授权；保留原工作树。
4. 草稿说明中列清后续项和验证范围。合并前核对适用 required checks、前后端契约、迁移以及本次变更受影响的回归；此前全 reactor 失败或中断，局部通过不能替代完整成功。性能和容量验证按用户要求暂缓，不据此声称性能验收通过。

本轮仅进行 Git/源码只读盘点和记录，未暂存、提交、推送、创建 PR 或重复运行测试。无可靠的完成时间估计；下一步以依赖闭合和实际检查结果确定候选 PR，而非继续扩大扫描范围。

## 2026-10-30 本地提交及推送门禁结果

用户要求把 WDI 双仓本地改动提交 PR。前端本地提交为 `4b3a244`、`548db72`，后端本地提交为 `cda501955`，均基于 `fix/wdi-current`；尚未成功推送或创建 PR。后端提交门禁通过，前端工作树未安装 Husky hook，补做变更范围检查并修复已发现的问题。配对 OpenAPI 漂移检查、前端构建、文档构建、可维护性和调用可读性检查通过；四组受影响 Vitest 共 44 项通过，五个拆分样式的编译结果与原 scoped CSS 一致。后端最终提交历史的 Gitleaks 扫描无命中；前端 3 项扫描命中为测试或业务操作键，并非已确认凭据。

`git push -u origin fix/wdi-current` 的 pre-push 检查实际退出 1，完整日志 `/tmp/wdi-backend-pr-push.log`：

1. `check-empty-checks.py --base origin/main` 报告约 130 处新增空值判断；这是规范命中，不等同于已证实的业务缺陷，需要按实际模块和语义处理。
2. 推送检查自动刷新 `docs/stats/java-readability-inventory.md`，生成差异尚未提交，保留在工作树中。
3. 隔离工作树 Java 治理测试中，`MapperStatusWriteConventionTest` 报告 3 处 UPDATE SET 状态字面量，`RepositoryMapReturnConventionTest` 的旧覆盖数量断言失败；应分别核对调用链和基线，不能直接削弱门禁。
4. 推送脚本的 FQN 检查将 `@ResourceLock("java.util.Locale.default")` 字符串误认为全限定类型；需修正守卫并验证真违规和字符串放行。字段注入检查同时报告测试字段注入，须按实际测试约定逐项处理。
5. 隔离工作树 clean compile 通过；全量 SIM、BE-ACC、远端 CI、Sonar、目标环境以及按用户要求后续再做的性能容量验证均未因此通过。

提交门禁早期曾因 Git hook 的环境变量泄漏导致临时仓库自测影响共享仓库 `core.bare`；已恢复并在自测进程隔离 Git 本地变量，使用受污染的模拟环境复验 73 项自测通过（1 项跳过），临时父仓库仍为非裸仓库。不要通过忽略门禁或扩大白名单来推送；前端 CI 的契约解析依赖后端同名远端分支，待后端可交付后再推送前端及创建配对草稿 PR。

### 2026-10-30 推送阻塞修复进展

- 仓储 `Map` 返回基线剔除 6 条已类型化的历史方法；覆盖验证改为检查每个剩余基线文件和方法，定向 `RepositoryMapReturnConventionTest` 通过。
- 三条状态更新改由业务调用方传入原目标状态；`MapperStatusWriteConventionTest`、`ReadinessWaitSweeperTest`、`TriggerLaunchRecoveryServiceTest` 共 22 项通过。使用 Testcontainers PostgreSQL 的 `ScheduledOccurrenceMapperIntegrationTest#shouldTimeoutStillExpiredReadinessOnlyOnce`、`TriggerLaunchRecoveryIdentityIntegrationTest`、`BatchDayCatchUpOperationSchemaIntegrationTest` 共 47 项通过。
- 本地 pre-push 全限定类型扫描按提交版完整 Java 源码屏蔽注释和字面量，保留真实代码命中；正反例测试 2 项通过，在当前提交差异复核无实际 FQN 命中。脚本治理与 `shellcheck -S warning` 通过。
- 空值门禁原始输出为 133 个候选、82 个文件；注入检查的新增差异有 525 个 `@Autowired` 注解，含构造器及测试，不能把注解总数直接当违规数。两项尚须按边界和源码逐项处理，不能机械等同业务缺陷或绕过门禁。

### 2026-10-30 空值与测试注入续修

SDK 的分发、HTTP/SQL Atomic 和 testkit 命中使用 SDK 自有 `EmptyChecks`，定向 61 项测试通过；独立安全扫描模块的原生检查按不依赖后端公共模块逐行保留原因，当前工作树空值检查及独立 Spotless 检查通过。Console 批日查询、诊断、回放过滤、密钥范围和 AI 鉴权/检索的空值判断按原短路和空白语义调整，相关定向测试 39 项通过（其中批日查询 3 项、其他 36 项）。

`ConfigSyncAuditRecoveryIntegrationTest` 和 `BatchConsoleApiApplicationIntegrationTest` 已按 Spring 上下文改为构造器注入；前者的真实认证调用定向 1 项、后者的真实集成测试 5 项通过。原推送检查中约 497 个字段/方法注入候选来自测试，尚有大量其他测试类未处理；候选须重新计算，不能以注解总数或本批测试外推全部已通过。性能容量未运行。

续修补充：`AlertEscalationNotifyMapperIntegrationTest` 的 2 个字段并入原有 Spring 测试构造器，`BatchDayCatchUpOperationSchemaIntegrationTest` 的 5 个字段改为构造器注入；分别选取告警数据库筛选、批日补跑恢复场景进行真实集成验证，两项均通过。本批共处理 4 个测试类的 15 处字段注入；未对其余测试类批量改写。提交前需以最终差异重新统计其余候选。

继续处理时，AI 输入、批量窗口和作业请求校验、幂等拦截的空值判断按原有语义调整，定向 5 个测试类共 28 项通过。配对前端草稿 PR #345 的远端检查在 head `548db726809d65007756c7298de8b748d34480e2` 上报告：保存键测试预期缺项、干净环境 TypeScript 检查失败；CodeQL 报告 E2E 夹具中 1 条 SSRF 和 4 条命令注入候选，尚未经安全链路核实。文档构建通过；不得将这些候选直接表述为已验证漏洞。

后续将 `JobPushDeliveryIntegrationTest` 的 8 个依赖改为构造器注入，保留事务管理器限定符；并发领取和防重复发送的两项真实集成测试通过。`TenantConfigDependencyRlsIntegrationTest` 的 8 个依赖改为构造器注入，普通数据库角色下的依赖导出测试 1 项通过。用户要求不机械处理，本批仅按两个类的 Spring 和事务上下文逐一修改；剩余候选继续处理。

审批回查、租户就绪、审计和文件投影的空值检查按原返回语义调整，5 个相关测试类共 37 项通过。每次改动只验证受影响范围；后端当前仍未推送，后续需要重新统计完整差异和执行推送门禁。

## 2026-10-30 空值与测试注入收尾

针对上次推送检查发现的新增空值判断及测试字段注入继续核对：按相对 `origin/main` 的当前差异运行空值规则，结果为零；通过 Java 语法树核对新增 `@Autowired` 注解的归属，未发现剩余字段注入。测试替身仍由 Spring 测试框架管理；需要重置上下文的测试按默认逐方法实例生命周期核对，Quartz 测试中会重新赋值的调度器保持可变。空值表达式保留原有短路和异常语义。

`./mvnw -ntp -DskipTests test` 在当前工作树完成，编译了生产和测试源码，未执行测试，日志为 `/tmp/wdi-pr-test-compile-20261030.log`。这不能证明测试运行、远端 CI 或端到端回归通过；性能和容量验证仍按要求留待后续。提交及推送仍须以实际门禁和远端结果为准。
