# 本地双钩子全量只读扫描与代码规约扫描报告（2026-10-25）

## 结论摘要

本轮按用户要求，以 `code-review-and-gates` 与 `ci-governance` 技能口径，对本地两个 Git 钩子（pre-commit、pre-push）内部的**全部门禁**做**全量、只读**扫描，并输出本报告。**未修改任何源码、未暂存、未提交、未推送**；仅新增本报告和 `docs/audit/README.md` 索引行。

- 扫描快照：工作树 `~/.codex/worktrees/wdi-current/file-batch-system`，分支 `fix/wdi-current`，HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2` 加未提交增量；2026-10-25 23:21–23:50 CST。
- 扫描前状态：staged 1440 / unstaged 680 / untracked 40；扫描期间树仍被并行工作流修改（结束态 unstaged 684 / untracked 43），结论只代表上述快照。
- **pre-commit 钩子：53 个门禁，45 通过 / 8 失败。**
- **pre-push 钩子：21 个门禁，18 通过 / 3 失败**；另做 1 项钩子外补充检查（OpenAPI breaking，失败）。
- 失败项全部指向**并行工作流当前未收敛的代码/文档**或**只读口径固有差异**；未发现本会话此前交付项（问题 1/2 修复、13 个 console-api 测试修复、空库迁移、配置治理等）回归。
- 此前 15:10 快照标记红的 `infrastructure-abstraction-boundaries` 6 项，本轮复测已通过（并行会话已完成下沉修复，见 [基础设施边界六项修复](infrastructure-abstraction-boundary-fix-2026-10-25.md)）。

## 扫描方法与只读原则

门禁来源：`scripts/local/pre-commit-checks.sh`（421 行，53 个 `gate_run`）与 `scripts/local/pre-push-sdk-checks.sh`（629 行，检查 0–4）。钩子本体按暂存区/增量/隔离 HEAD 工作树执行，不适合"当前工作树全量只读"目标，因此逐项展开为等价只读命令：

| 门禁类别 | 钩子原行为 | 本轮只读替代 | 说明 |
|---|---|---|---|
| 格式化 | `spotless:apply` 后 `spotless:check` | 仅 `spotless:check` | 不写格式、不暂存 |
| 配置治理登记表 | `--write` 后 `git add` | `check-config-governance.py`（无 `--write`） | 只校验 |
| SBOM/许可证 | 重新生成并 `git add` | `check-license-compliance.sh` | 只比对，不覆盖 `docs/compliance/sbom.json` |
| 代码量快照 | 临时 worktree 生成并 `git add` | `check-loc-snapshot.py` | 只校验 |
| 隔离 HEAD 工作树 clean compile | `git worktree add --detach HEAD` 后编译 | **当前树** `clean compile` | 钩子看不到未提交修复，按当前树执行更有信息量 |
| 增量 Java 规约 | 暂存/`--base` 增量 | 全仓文件清单或显式 `--base origin/main` | 全量口径，历史债会一并暴露 |
| 单测类门禁 | unittest 套件 | 原样执行 | — |

边界与未覆盖事项：

- 未执行真实 `git commit` / `git push`，未运行钩子脚本本体（隔离 HEAD 树口径）与需要真实服务/目标环境的验证。
- `code-review-and-gates` 要求的人工语义项（低阈值嵌套调用、复合条件命名、参数内聚、职责拆分、注释 WHY 等）**无法由门禁机械覆盖**；本轮只做门禁覆盖矩阵与结果汇总，未逐文件人工语义审阅，作为后续专项。
- 全量口径与新债/历史债混合：例如 `comment-language` 全仓命中 390 条历史英文注释，钩子增量口径并不阻断；报告如实标注归属，不把历史债计为本次新增违规。

## pre-commit 全量结果（53 项：45 通过 / 8 失败）

### 失败项

| 门禁 | 结果摘要 | 根因/归属 |
|---|---|---|
| comment-language（全量路径口径） | 390 条英文说明性注释命中，集中在 `batch-e2e-tests`（61）、worker 测试与 orchestrator 集成测试；含少量非注释行候选 | **全量历史债**；钩子为暂存增量口径，不阻断历史代码；需人工判定是否属新增 |
| spotless-check | `batch-console-api` 2 个文件格式违规：`WorkflowExecutionGraphAssembler.java`、`WorkflowExecutionGraphAssemblerTest.java` | 并行工作流新增代码未格式化 |
| java-logging | `WorkflowMonitoringScheduler.java`：`SwallowedExceptionLogger` 的 where 标签 `catch:workflow-monitoring-` 不合口径（不得含空白/分号/换行，见日志架构文档） | 并行工作流 SLA 功能新增代码 |
| java-readability | 13 处：12 处 `var` 显式类型、1 处 `ConsoleExecutionProgressConfiguration` 缺 `@Configuration(proxyBeanMethods = false)` | 并行工作流新增代码 |
| test-conventions（基线口径） | 新增 14 处测试 `@DisplayName` 缺口：`JobExecutionQueryServiceTest`（8）、`PipelineProgressQueryServiceTest`（3）、`ConsoleFilePipelineProgressBridgeTest`（3） | 并行工作流新增测试 |
| license-compliance（只读） | `docs/compliance/sbom.json` 与当前 POM 解析结果不一致（如新增 `caffeine@3.2.4`） | 只读模式下不重生成 SBOM；钩子内会自动同步 |
| loc-snapshot-check | `docs/stats/archive/README.md` 不存在 → 脚本读取索引异常 | 并行文档整理删除/未恢复该 README |
| repository-hygiene | 7 处文档个人绝对路径：`docs/audit/*`（3）、`docs/verifications/wdi-functional-closure-2026-10-25.md`（1）、`docs/plans/*`（1）等 | 并行工作流新增审计/验收文档 |

### 通过项（按域概括，完整逐项见日志）

- **Java 规约**：contract（candidates 0 / regressions 0）、lombok 注入（基线 origin/main，4038 路径）、文本块格式、结构化多行字符串（约 250.6s）、抑制项注册表、Map/List/Set.of 空值、Pipeline 摘要键、MyBatis generated key、位置式 INSERT SELECT *。
- **脚本/CI 治理**：ShellCheck 全仓 216 文件、Shell Linux 可移植性、GitHub Actions SHA 固定（26 个定义文件）、actionlint、软门禁治理（4 项）、flaky 隔离（0 项）、脚本治理（83 guards）、Testcontainers 复用标签（216 脚本）、Java 治理测试清单（32 项 / 11 模块）、CI 质量门禁单测套件。
- **文档/仓库**：docs-structure、doc-timestamp-policy（dated 302 / undated 281）、code-doc-references、SLO/SLI 目录、schema-governance-assets、sim 配置包 Excel schema、UTF-8 编码（7188 文件）、暂存区空白/冲突标记。
- **配置/部署**：config-defaults-sync、infrastructure-utf8（20 service configs）、feature-switch registry（56 switches / 151 env）、env-variable governance、dependency boundaries、helm-env-sync、production-overlay-safety、env-file-shell-safety。
- **其他**：MapOF、MyBatis、pipeline summary keys、`check-config-governance`（148 configuration properties）。

## pre-push 全量结果（22 项：18 通过 / 4 失败，其中 1 项为钩子外补充）

| 门禁 | 结果 | 耗时 | 说明 |
|---|---|---|---|
| check-empty-checks | ❌ | 0.8s | **51 处**新增零散空值判断（`workflow/application` 15、`job/application` 8、orchestrator task 6 等），需改 `EmptyChecks` |
| infrastructure-abstraction `--base origin/main` | ✅ | 46.5s | 此前 6 项已由并行会话修复，本轮 0 命中 |
| readiness-doc-sync `--base origin/main` | ✅ | 344.6s | 全量逐文件 `git diff` 耗时较长但正常，非挂起 |
| direct-client-boundaries | ✅ | 0.6s | — |
| trivy-ignore-expiry | ✅ | 0.0s | — |
| env-file-shell-safety | ✅ | 0.1s | — |
| sdk-config-env-parity | ✅ | 0.0s | — |
| config-defaults-sync `--check` | ✅ | 0.1s | — |
| helm-env-sync | ✅ | 0.1s | — |
| feature-switch-registry | ✅ | 0.0s | — |
| config-governance | ✅ | 1.8s | 148 configuration properties |
| env-variable-governance | ✅ | 0.1s | — |
| hardcoded-runtime-config | ❌ | 4.2s | 当前文档禁止个人绝对路径：7 处（与 repository-hygiene 同源） |
| test-config-sources | ✅ | 0.2s | — |
| changelog-sync `--base origin/main` | ✅ | 0.4s | — |
| shell-scripts-full | ✅ | 17.8s | 216 文件 |
| flyway-version-unique | ✅ | 0.3s | 无重复版本号 |
| validate-flyway-schema | ✅ | 27.1s | — |
| docker-bake-parse | ✅ | 0.5s | `docker buildx bake --print` 解析通过 |
| clean-compile-current-tree | ✅ | 59.7s | 当前树全仓 clean compile 通过 |
| java-governance-tests | ❌ | 25.0s | `batch-test-support` 的 `MapperStatusWriteConventionTest` 失败：`AlertEventMapper.xml#closeEligibleMonitoringAlert` 在 UPDATE SET 使用固定状态字面量 `status = 'CLOSED'`，须由 Java 语义方法传参 |
| openapi-breaking（钩子外补充） | ❌ | 4.2s | 相对 origin/main：85 changes / 8 error，均为 `pipeline-definitions` 系列 8 个路径被移除（WIP 退休 Pipeline 接口，需 deprecation/版本化策略） |

## 代码规约扫描覆盖矩阵（code-review-and-gates 口径）

| 规约域 | 权威依据 | 执行门禁 | 结果 |
|---|---|---|---|
| Java 显式类型与 `@Configuration(proxyBeanMethods=false)` | coding-conventions §3.3 等 | java-readability | ❌ 13 处 |
| Java 日志治理/异常输出 | logging-architecture | java-logging | ❌ 1 处标签口径 |
| 注释语言（中文注释规范） | coding-conventions | comment-language | ⚠️ 全量历史 390 条 |
| 文本块与结构化多行字符串 | coding-conventions §0.2 | java-text-block-style、java-structured-string-concat | ✅ |
| 空值判断统一入口 | EmptyChecks 规范 | check-empty-checks（pre-push）| ❌ 51 处 |
| 依赖注入（构造器注入、Lombok） | coding-conventions §3 | java-lombok-injection | ✅ |
| FQN 禁令 | coding-conventions §2 | pre-push 增量规约扫描 + ArchTest | ✅（钩子外增量口径见说明） |
| Map/List/Set.of 空值风险 | coding-conventions | mapof-null-values | ✅ |
| 测试 `@DisplayName` 与命名 | coding-conventions §14.5 | test-conventions | ❌ 14 处 |
| Mapper 状态写入（禁止 UPDATE SET 固定字面量） | coding-conventions 持久层规范 | MapperStatusWriteConventionTest（java-governance） | ❌ 1 处 |
| 参数数量/长构造/位置参数 | coding-conventions §1、§1.3、§1.4 | PMD + PositionalArgsConventionTest + java-governance | ✅（本轮未见新增违例） |
| SQL/MyBatis 边界 | sql 规范 | mybatis-generated-keys、no-positional-insert-star、validate-flyway-schema | ✅ |
| Shell/脚本治理 | script-sql-governance | shellcheck、script-governance、actionlint、soft-gate、flaky | ✅ |
| 文档结构与日期策略 | documentation-governance | docs-structure、doc-timestamp、code-doc-references | ✅（新报告需索引登记，见下） |
| 仓库卫生（无生成物/个人路径/凭据） | repository-hygiene | repository-hygiene、hardcoded-runtime-config 文档段 | ❌ 7 处个人绝对路径 |

**人工语义项（技能要求、门禁不覆盖，本轮未逐文件审阅）**：低于三层阈值的难读嵌套调用与 return 表达式、复合判断是否值得命名 boolean、4–6 参数是否表达内聚概念、方法职责混杂/单转调壳、中间变量业务含义、注释 WHY、死代码与重复策略、测试是否验证外部行为。建议由并行工作流在功能收敛后做一次专项人工复核。

## 发现清单（按严重度）

### P1：当前树门禁红（均非本会话产出，需并行工作流修复）

1. `batch-orchestrator/src/main/resources/mapper/AlertEventMapper.xml#closeEligibleMonitoringAlert`：UPDATE SET 固定状态字面量 `status = 'CLOSED'` → 改为 Java 语义方法传参（`MapperStatusWriteConventionTest`）。
2. Spotless：`batch-console-api/src/main/java/.../workflow/application/WorkflowExecutionGraphAssembler.java` 及其测试类格式违规。
3. `WorkflowMonitoringScheduler`：`SwallowedExceptionLogger` where 标签 `catch:workflow-monitoring-` 不合日志架构口径。
4. Java 可读性 13 处（12 处 `var`、1 处 `@Configuration` 声明），文件集中于 console workflow/file/observability 新增代码。
5. 测试 `@DisplayName` 14 处：`JobExecutionQueryServiceTest`、`PipelineProgressQueryServiceTest`、`ConsoleFilePipelineProgressBridgeTest`。
6. EmptyChecks 51 处新增零散空值判断（workflow/job application 层为主）。
7. 仓库卫生/硬编码文档检查：7 处审计/验收/计划文档含个人绝对路径（`docs/audit/`、`docs/verifications/`、`docs/plans/`）。
8. LOC 快照：`docs/stats/archive/README.md` 缺失导致 `check-loc-snapshot.py` 异常，需恢复该目录索引（或同步删除引用）。

### P2：只读模式固有限制（非代码缺陷）

1. `license-compliance`：POM 未提交增量导致 `docs/compliance/sbom.json` 与解析结果不一致；真实钩子会重生成并暂存，本轮只读不写。
2. `comment-language` 全量路径口径命中历史英文注释 390 条（钩子为增量口径）；如需治理应单独立项，不与本快照混合。

### P3：钩子外补充与跨会话事项

1. OpenAPI breaking：`pipeline-definitions` 8 个路径相对 origin/main 移除（GET/POST/PUT/PATCH/events/queries 等），属 WIP 退休接口；若保留删除需走 deprecation 或版本化，否则 frontend 契约会运行时失败。
2. 之前 15:10 快照的"全绿"结论仅对应旧快照；当前树已被并行工作流覆盖，提交前必须以新快照重跑真实两钩子。

## 与既有交付的关系

- 本会话此前完成的问题 1（readiness 移交）、问题 2（Kafka 重试耗尽恢复）、13 个 console-api 测试修复、空库迁移、配置/文档/脚本类门禁修复，均未在本轮全量扫描中出现回归信号（`infrastructure-abstraction` 6 项复测通过；`direct-client`、`readiness`、`config-*`、`flyway`、`shell` 等均通过）。
- 本轮按用户要求**不提交代码**：扫描前后工作树 staged/unstaged/untracked 数量变化来自并行工作流，不是本报告产生的源码修改；本报告只新增本文件与索引行。

## 建议下一步

1. 由并行工作流先收敛 P1 的 8 项（格式、日志标签、可读性、`@DisplayName`、EmptyChecks、Mapper 状态、文档个人路径、archive README），再重跑本轮同套件。
2. P1 清零后，用**真实钩子**（staged/HEAD 口径）在提交前做一次 pre-commit + pre-push 验证；`openapi-breaking` 需单独决策 deprecation/版本化。
3. 人工语义项（技能清单中门禁不覆盖部分）建议在功能收敛后做一次抽查，避免仅凭机器绿灯判断可读性。

## 证据与边界

- 逐门禁命令、退出码、耗时、首条诊断保存在本地临时扫描目录（`precommit-gates.log` / `prepush-gates.log` 及失败项完整日志），不入库、不作为随仓库分发材料。
- 扫描为静态/本地验证：不包含真实服务、锁定环境、E2E 或生产证据；未执行 `commit`/`push`/合并。
- 结论只适用于 2026-10-25 23:21–23:50 CST 快照；树在扫描期间仍有并行写入（staged 1440 不变，unstaged 680→684，untracked 40→43）。
