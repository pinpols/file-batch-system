# 约定约束与漂移防护总账

> 维护日期: 2026-10-13
> 定位: 统一记录项目约定、约束、审计、审核、复扫与 CI 守卫入口,用于后续扫描时防止规范漂移。

本文不是新的规范来源,也不替代 `docs/agent-baseline.md`、编码规约、ADR 或 runbook。它只做一件事:把分散在代码、文档、脚本、CI、审计报告里的约束集中成一张可复扫的总账。

## 权威层级

| 层级 | 权威入口 | 作用 | 漂移风险 |
|---|---|---|---|
| 1 | 根目录 `docs/agent-baseline.md` | 项目硬约束、红线、模块边界、数据库与测试基本纪律 | 新增硬规则后只改代码或口头约定,没有进入根规范 |
| 2 | [docs/coding-conventions.md](../coding-conventions.md) | 编码细则、命名、异常、事务、API、删除规范、安全旁路 | 代码风格、事务边界、参数/命名习惯逐步发散 |
| 3 | [docs/architecture/adr/](../architecture/adr/) | 已决策的架构边界和取舍 | 代码实现和 ADR 方向不一致,或新增例外没有补 ADR |
| 4 | [docs/api/](../api/) | Console / Orchestrator / SDK 契约 | 前后端、SDK、worker transport 契约漂移 |
| 5 | [docs/runbook/](../runbook/) + [docs/testing/](../testing/) | 运维操作、上线门槛、测试覆盖矩阵 | 能跑测试但不能运维救火,或验收证据缺失 |
| 6 | [docs/audit/](./) + [docs/analysis/](../analysis/) + [docs/review/](../review/) | 审计、深扫、复盘、阶段评审 | 发现的问题没有闭环到规范、脚本、测试或 runbook |
| 7 | `scripts/ci/` + `.github/workflows/` | 可执行守卫 | 文档写了但 CI 不拦,或 CI 新增后没人知道 |

## 规模快照（历史统计，使用前复核）

| 类别 | 当前规模 | 主要入口 | 说明 |
|---|---:|---|---|
| ADR | 47 个 ADR 文件 | [docs/architecture/adr/](../architecture/adr/) | 包含架构边界、SDK、checkpoint、capacity、依赖调度等决策 |
| 专项审计报告 | 13 个文件 | [docs/audit/](./) | 包含全仓架构审计、治理总账和后续后端深扫 |
| CI 守卫脚本 | 80 个已登记的 `check-*` / `validate-*` 守卫 | [scripts/ci/README.md](../../scripts/ci/README.md) | 覆盖文档、脚本、仓库卫生、租户隔离、迁移、OpenAPI、配置、版本、许可、测试完整性、Java 规约、安全与可观测；分类正反例和失败汇总边界见守卫目录 |
| GitHub Actions | 18 个 workflow | `.github/workflows/` | PR gate、full CI、staging、SDK parity、CodeQL、workflow lint、OpenSSF Scorecard |
| SDK 契约 fixture | 31 个 case | [docs/api/sdk-contract-fixtures/](../api/sdk-contract-fixtures/) | 覆盖注册、心跳、claim、renew、report、Kafka schema 兼容等 |
| 顶层规范文档 | 3 个核心入口 | [docs/README.md](../README.md) | `agent-baseline`、`coding-conventions`、`changelog` |

## 守卫矩阵

| 方向 | 必看规范 | 可执行守卫 | 典型漂移 |
|---|---|---|---|
| 治理基线与范围路由 | `docs/governance/README.md`; `docs/runbook/ci.md` | `check-governance-baseline-expansion.py`; `detect-change-scope.py` 的治理文件正反例 | 存量基线与新问题同 PR 静默扩张；机器可读契约变更被误归为纯文档而漏跑专项 CI |
| 多租户与数据库 | `docs/agent-baseline.md`; [bounded-context-rules.md](../architecture/bounded-context-rules.md); ADR-017/020/024 | `check-biz-table-tenant-rls.py`; `check-migration-safety.sh`; `validate-flyway-schema.sh`; `check-db-comment-coverage.sh`; `check-new-table-doc-coverage.py`; `check-no-positional-insert-select-star.py`; 相关租户/归档 ArchTest | 漏 `tenant_id`; 新表未登记或数据库结构目录条目被删; `ON CONFLICT` 幂等守卫退化; archive schema 和热表漂移; 位置列插入导致错列 |
| API 契约 | [console-api-protocol.md](../api/console-api-protocol.md); [console-api.openapi.yaml](../api/console-api.openapi.yaml); [orchestrator-internal.openapi.yaml](../api/orchestrator-internal.openapi.yaml) | `check-console-openapi-paths.py`; `check-openapi-breaking.sh`; 前端 `gen:api:check` | Controller 改了但 OpenAPI/前端类型没同步; 返回体字段和页面假设不一致 |
| 固定契约与协议值 | [治理计划](../plans/typed-contract-enum-constant-governance-plan-2026-10-07.md); [精确例外](../governance/java-contract-governance.json); [零债务基线](../governance/java-contract-governance-baseline.json) | `check-java-contract-governance.py` 及 19 个正反例自测；PR/local 增量、规则变更与 Full Gate 全量；枚举改动扩展复扫登记消费者 | 固定 DTO 退回 Map/Object；已确认同域的 enum code 重新散落；已有协议键常量未复用；动态 Map 宽泛豁免 |
| Worker / SDK | ADR-035/036/037/038; [sdk-contract-fixtures](../api/sdk-contract-fixtures/) | `run-sdk-live-transport-gate.sh`; `run-sdk-orchestrator-e2e.sh`; workflow `sdk-contract-parity.yml`; `sdk-orchestrator-e2e.yml`; `sdk-release-validation.yml` | conformance 绿但生产 transport 不通; 五语言 SDK 行为不一致 |
| 编码与架构 | [coding-conventions.md](../coding-conventions.md); `docs/agent-baseline.md`; [project-structure.md](../architecture/project-structure.md) | PMD; Spotless; `check-dependency-boundaries.py`; `check-java-logging-governance.py` 及扫描器自测; `check-no-enable-preview.sh`; `RepositoryMapReturnConventionTest`; `PositionalArgsConventionTest`; `MapperStatusWriteConventionTest`; `ServiceEntryGuardArchTest`; Orchestrator `ControllerPersistenceBoundaryArchTest`（Controller 禁止直连 Mapper/Repository）; Console `LayerBoundaryArchTest`（Web 禁止直连 Mapper/Repository、基础设施、内部客户端和对象存储）; Orchestrator/Console `EntityModelNamingArchTest`（领域 entity 包只允许 `XxxEntity`）; `EntityApiBoundaryArchTest`（Orchestrator 与 Console Controller HTTP 签名禁止暴露 Entity）；Mapper 参数与状态分类按 `coding-conventions.md` §9.3 评审 | 构造注入退回字段注入; 事务放错层; Entity/查询投影命名混淆或 Entity 泄露到 HTTP 契约; 服务入口只在无关代码处出现 null 判断造成假绿; 原始异常 message 注入日志或泄漏凭据; common 引入重依赖; 预览特性混入主线; 仓库出参回退为 `Map<String, Object>` 把字段错误推迟到运行期; Mapper 固定结构改用 Map、状态写入重新硬编码、共享生命周期集合与枚举漂移或专项业务口径被错误泛化; 参数对象以通用 Builder 重新表达非法状态 |
| 配置与环境 | [runbook/](../runbook/); [dict/config-keys.md](../dict/config-keys.md); ADR-039 | `check-config-defaults-sync.py`; `check-feature-switch-registry.py`; `check-helm-env-sync.py`; `check-hardcoded-runtime-config.sh`（BE 验收端口与观测脚本平台地址统一来源）；`check-infrastructure-utf8.py`（Compose/Sim/Dockerfile/Helm/Testcontainers locale 与 PostgreSQL 编码）；`check-production-overlay-safety.py`; `check-version-alignment.sh`（应用、基础服务与 MinIO CLI 镜像版本及运行入口）；`check-helm-prometheusrule-sync.sh`; `validate-kafka-topics.sh`; `check-sql-config-boundaries.sh` | yml、docker、helm、env、平台服务地址、topic、locale、PrometheusRule 不一致; SQL 和配置混在 shell |
| 文档、脚本与仓库卫生 | [document-governance.md](../standards/document-governance.md); [scripts/README.md](../../scripts/README.md) | `check-docs-structure.py`; `check-terminology-doc-sync.py`; `check-changelog-sync.py`; `check-script-governance.py`; `check-shell-scripts.sh`; `check-repository-hygiene.py` | 链接或图片失效;核心枚举与术语值表漂移;索引漏项;守护未登记;Shell 告警增长;本机路径或产物入库 |
| 测试与验收 | [testing/](../testing/); [verifications/](../verifications/) | `check-e2e-shard-coverage.sh`; `check-e2e-run-completeness.sh`; `check-module-test-coverage.sh`; `check-no-silent-disabled-tests.sh`; `check-test-conventions.py`; `select-affected-tests.py`; `scripts/fix-fixture-xlsx.py --check`; workflow `full-ci-gate.yml`; `staging-gate.yml`; `strict-verify.yml` | 只保留 happy path; disabled test 静默增加; sim/IT 覆盖和业务场景脱节; Sim 配置包 Excel 与导入 schema 漂移; 测试类/方法的 `@DisplayName` 与测试方法命名约定静默发散 |
| 安全与合规 | [compliance/](../compliance/); [open-source-governance.md](../standards/open-source-governance.md); `docs/agent-baseline.md` 安全红线 | `security-scan.sh`; `check-license-compliance.sh`; `check-sbom-sync.sh`; `check-dependency-licenses.sh`; `check-github-action-pinning.py`; `install-upstream-modules.sh`（PR/Full Gate Trivy 扫描前预热 Maven 缓存）; `.trivyignore`（漏洞例外元数据）; `.trivyignore.yaml`（配置误报按规则和路径精确豁免）; workflow `codeql.yml`; `full-ci-gate.yml`; `workflow-lint.yml`; `scorecard.yml` | 依赖许可不清; 入库 SBOM 与 Maven 依赖图漂移; Action 使用可变引用; Maven Central 限流导致 Trivy 误失败; 配置误报豁免范围过宽; bypass 开关 fail-open; workflow 权限过大; secret 泄露到日志 |
| 运维与恢复 | [runbook/incident-response.md](../runbook/incident-response.md); [runbook/troubleshooting-decision-tree.md](../runbook/troubleshooting-decision-tree.md); ADR-042/044 | `scripts/ops/inspect-all.sh`; `scripts/ops/heal-stuck-workflows.sh`; 相关 sim / drill / staging gate | Console 只能看不能救; DLQ/outbox/卡实例缺少幂等恢复动作 |

## 复扫频率

Nightly 验证顺序由 `scripts/ci/tests/test_daily_validation_workflow.py` 守护，
接入 PR / Full 的 CI 质量守护组：SIM 失败或步骤超时后仍运行 strict（环境启动成功时），
保留两项独立结果，任一失败阻断镜像构建；不把取消或环境不可用记为通过。

预推送编译模块选择由 `scripts/ci/tests/test_pre_push_module_selection.py` 守护，
保护数字模块名、聚合模块去重和完整改动集合，接入本地 pre-commit 及 PR / Full 同一守护组。

基础设施抽象边界守护 `check-infrastructure-abstraction-boundaries.py` 以 PR merge-base 的引用语句与出现次数作为比较基线,只阻断新增引用;历史引用的日志安全修复不触发整文件清债。专项回归覆盖新增导入、重复引用增加及空白排版。完整历史收敛仍由治理计划和全量直连客户端守护承担。

### CI 难以守住的人工/AI 周期审查

PR/Full Gate 对已编码的规则和已写出的用例有效，但不能证明规范仍符合当前实现、测试仍代表真实场景、用户仍能完成业务操作。本表是**建议的人工/AI 审查节奏**，没有为它新增定时工作流。每月以“上次审查提交到当前 main”的增量为主；季度对高风险存量路径抽样，避免只审新增文件。配对前端的专项清单见 `../batch-console/docs/engineering/convention-drift-review.md`（本地配对仓库路径），本轮确认问题与证据见[前后端复核快照](./cross-repo-governance-drift-review-2026-10-12.md)。

| 建议频率 | 审查主题 | CI 可能全绿但仍退化的原因 | 人工/AI 核对动作与入口 |
|---|---|---|---|
| 每月；主链改动后立即 | **业务语义与跨模块组合**：Trigger→Orchestrator→Worker 的状态、业务日、晚到/补跑、重试/补偿、checkpoint、最新结果选择 | 单项状态机或 E2E 用例通过，仍可能漏掉新旧功能组合、乱序事件、失败后恢复和账期结果对账 | 从[核心模型](../architecture/core-model.md)和相关 ADR 选本月改动的业务路径，沿入口、持久化、消息、Worker、终态和下游读取追踪；抽取一个失败/重试/并发反例，核对最终业务数据与操作语义 |
| 每月；配置或页面改动后立即 | **租户配置与产品工作流**：Excel/REST 默认值、角色可见性、前台配置维护和运维动作 | OpenAPI、字段类型和接口测试可一致，但模板仍可能让用户填写平台资源，页面能提交却无法完成实际配置/恢复 | 依[产品旅程审计](./product-journey-adversarial-audit-2026-10-10.md)与配置方案，用四类角色分别走“建作业→运行→诊断→重试/补数”；核对前后端、模板说明、权限与结果反馈，不把 API 成功当作流程可用 |
| 每月；新增测试基线或门禁后立即 | **测试代表性与 CI 覆盖空洞** | 断言与实现共用常量、mock 绕过生产 SQL/事务、fixture 过时、路径选择器漏测，都可能产生绿色假象 | 抽查变更链路的测试输入、独立预期、真实依赖层级、`skip`/隔离项、workflow 路由和最近失败记录；对照[测试质量治理](../standards/ci-test-quality-governance.md)与[验收计划](../testing/full-project-test-plan.md)，指出“已测什么”和“未测什么” |
| 每季度；模块职责变化后立即 | **架构职责与抽象强度**：Controller/Application/Domain/Mapper、Trigger/Orchestrator/Worker、`batch-common`、SDK | 依赖方向守卫可通过，但业务决策可能迁入 Controller、同一数据出现多写入口、工具/策略类过度拆分或重复实现 | 对照[模块结构](../architecture/project-structure.md)、[边界规则](../architecture/bounded-context-rules.md)及 ADR，沿代表性调用链看职责归属、事务所有者、依赖方向和扩展点使用理由；检查新增抽象是否消除了真实复杂度 |
| 每季度；权限/数据边界变化后立即 | **租户与安全语义**：跨租访问、审批/运维权限、动态 SQL、文件与密钥边界 | RLS/注解/CodeQL 可查到已知模式，但不能替代完整调用链授权、越权场景和安全例外理由 | 抽样真实入口到 Mapper/外部通道的租户上下文，构造跨租与低权限操作反例；逐项复核安全告警、豁免和 `bypass`/降级开关的实际暴露面，记录可复现证据 |
| 每季度；配置路径变化后立即 | **配置事实来源和生命周期**：Spring 绑定、DB 动态配置、缓存、多副本、实例运行中配置版本 | key/默认值同步门禁难判断启动后是否真的加载、生效时机是否正确，或不同副本与既有实例是否看到同一策略 | 对照[配置治理](../runbook/config-governance.md)和[功能开关](../runbook/feature-switches.md)，追踪声明→加载→覆盖→刷新/失效→消费者；核对关闭态、回滚、运行中修改及配置审计 |
| 每季度；文档或运维流程重写后立即 | **文档与运维可执行性**：ADR、结构图、runbook、脚本、待办和验收报告 | 链接/术语守卫只能证明文件存在；命令、截图、架构说明、完成状态和目标环境证据仍可能过时 | 对每个本季改动域选一份权威文档和一条操作路径，逐项对照当前代码/参数/权限/命令；将历史结论标成快照，未闭环事项仅登记到[`todo-master`](../analysis/todo-master.md) |
| 每季度；规则/工作流变化后立即 | **治理守卫自身**：扫描范围、例外、门禁分组与模式、required checks、路径过滤与定时任务 | 检查执行成功也可能漏扫新模块，白名单扩张、报告/忽略模式未登记、规则延期，或 `SKIP` / `IGNORE` / `REPORT` 被当作通过 | 对照[CI 定时治理](../runbook/ci-schedule-governance.md)、`scripts/ci/README.md` 和本总账；抽样 enforce 失败、report 失败、ignore 不执行及正常放行案例，核对 group/mode、owner/到期日、触发路径和主干实际 run |
| 每季度；前端关键路径变更后立即 | **前端守卫有效性**：OpenAPI 生成、浏览器目标环境、角色/租户、i18n、UI 基线、条件跳过 | 类型/译文检查可能全绿但页面不可用；staging 变量、测试账号或 API origin 配错会让 E2E 未测到目标环境；基线提高可掩盖新债务 | 在配对仓逐项核对 workflow 输入→Playwright/global setup→目标 origin/凭据→测试结果；按四角色和中英文抽样页面，统计 SKIP 和基线扩张，确认目标环境业务结果 |
| 每季度；扩容或大数据发布前立即 | **性能与资源设计**：热表/索引、PG 锁与连接池、线程/缓存/文件句柄、Kafka/对象存储背压 | 性能脚本只证明既定负载；增长后的表、租户分布、任务形状和实际瓶颈可能已经变化 | 对照[容量治理](../runbook/production-capacity-governance.md)及最近基线，抽查热点查询和业务耗时、资源水位、失败率与对账结果；无真实数据时只记录风险假设，不宣称优化收益 |
| 上线前；故障/演练后复审 | **恢复与运维闭环**：DLQ、Outbox、卡实例、备份/PITR、HA、告警投递 | 单测/模拟可绿，但值班人员可能无法按 Console/runbook 恢复，目标环境故障切换未验证 | 依[HA readiness](../runbook/ha-readiness.md)及 runbook，从告警触发到定位、审批、执行、幂等重试和结果对账走一遍；记录环境、版本、RTO/RPO 与无法在本地验证的环节 |

审查记录须写明基线提交、范围/抽样依据、正反例、证据层级和未覆盖项；结论区分**确认问题、待验证风险、暂缓事项、抽样未发现问题**。确认问题进入修复与回归，反复出现且可机器判断的规则再加入 CI。历史 audit/review 是风险线索，不直接代表当前事实；日常巡检、安全扫描和季度依赖盘点的实际 schedule 见[CI 定时策略](../runbook/ci-schedule-governance.md)及[季度依赖治理](../runbook/quarterly-dependency-governance.md)。

### 变更触发的复扫

| 场景 | 建议复扫动作 | 通过标准 |
|---|---|---|
| 每个 PR | 核对变更分类器选出的 PR gate 步骤、required check 状态和被跳过的专项守卫；相关代码变更再运行 OpenAPI、SQL/配置边界、迁移安全和 Java 规约检查 | 应运行的守卫确实执行且通过；SKIP 和报告性结果不算通过；失败项修复或有明确 ADR/文档例外 |
| 改 Console API | 同步 OpenAPI、协议文档、前端 `api.generated.ts` 和调用方 | 前后端类型一致; 页面不依赖不存在的字段 |
| 改 DB 迁移 | 复扫 tenant/RLS、archive schema、Flyway schema、迁移安全、位置列插入 | 租户隔离不退化; 迁移可前向执行; archive 与热表规则一致 |
| 改 worker / SDK transport | 跑 SDK fixture、live transport、orchestrator e2e | Java/Python/Go/JS/Rust 行为不漂移; conformance 和生产链路一致 |
| 改配置、镜像、依赖版本 | 跑版本对齐、env/prod 同步、许可、SBOM、安全扫描 | 本地、CI、部署、测试容器版本统一; 许可风险明确 |
| Release 前 | 跑 full CI、staging gate、关键 sim、runbook 演练证据 | 不只有单测绿,还要有运维和真实链路证据 |
| 新增硬规则 / ADR 例外 | 更新 `docs/agent-baseline.md` 或 ADR,追加 `docs/changelog.md`,同步本文 | 人读入口和机器守卫都能找到该规则 |

## 同步清单

| 如果改了 | 必须同步 |
|---|---|
| `/api/console/**` Controller / DTO | `docs/api/console-api.openapi.yaml`; `docs/api/console-api-protocol.md`; 前端 `../batch-console/src/types/api.generated.ts`; 前端调用方 |
| Orchestrator 内部 API 或 worker transport | `docs/api/orchestrator-internal.openapi.yaml`; `docs/api/sdk-contract-fixtures/`; `docs/api/sdk-shared-constants.yaml`; SDK parity 测试 |
| 批量核心表或归档表 | Flyway migration; archive mirror; RLS/tenant guard; mapper XML; runbook 里的诊断 SQL |
| Kafka topic / PrometheusRule / Helm 值 | `validate-kafka-topics.sh`; `check-helm-prometheusrule-sync.sh`; docker canonical 配置; 相关 runbook |
| 依赖版本 / 镜像版本 | Maven version property; testcontainers 镜像; docker compose; helm appVersion; 许可和 SBOM |
| 新 CI gate / 新审计脚本 | `scripts/ci/README.md`; 本文守卫矩阵; 对应 workflow 或 PR gate |
| 核心状态 / 调度 / 触发 / 节点 / 运行模式 enum | `docs/architecture/core-model.md`; `docs/dict/glossary.md`; `check-terminology-doc-sync.py --write` |
| 新 ADR / 硬约束例外 | ADR 目录; `docs/changelog.md`; 本文权威层级或守卫矩阵 |

## 当前缺口

| 缺口 | 风险 | 建议 |
|---|---|---|
| 本文统计仍是人工快照 | workflow、ADR 增删后可能忘记更新 | CI 守护脚本清单已由 `check-script-governance.py` 校验；workflow、ADR 数量后续可继续改为自动生成 |
| ShellCheck warning | 已归零 | `check-shell-scripts.sh` 对全部版本化 Shell 脚本执行语法检查并禁止任何 warning |
| Java 治理守卫执行路由 | 已收敛 | `*ArchTest` / `*ConventionTest` 由 PR、Full、Staging 的 `java-governance` 独立执行，并由 `check-java-governance-test-coverage.py` 核对源码与 Surefire 报告 |
| 跨前后端漂移需要两个仓库共同验证 | 后端 CI 绿不代表前端页面可用 | Console API 变更默认要求前端 codegen check 和页面 smoke 证据 |
| 运维恢复脚本和 Console 操作边界仍需持续对齐 | 可能出现“脚本能救、Console 不能救”或反向漂移 | 运维闭环能力变更时同步 runbook、Console 页面和脚本 |

## 进行中的代码质量治理

| 计划 | 范围 | 防漂移要求 |
|---|---|---|
| [固定契约、有限域与常量治理计划](../plans/typed-contract-enum-constant-governance-plan-2026-10-07.md) | Controller/Application Service 固定 DTO、既有枚举复用、稳定协议键和增量门禁 | 动态 Map 使用精确例外；公开契约同步 OpenAPI 与配对前端；门禁先增量后全量，不把 Sonar `S1192` 机械设为阻断 |
| [Java 可读性与结构一致性治理路线图](../plans/java-readability-refactoring-roadmap-2026-08-12.md) | CGLIB 自注入、固定 Map 契约、复杂类、测试风格、suppression 与配置类表达 | 结构重构与行为变更分离；动态 Map 和声明式规格保留明确例外；核心事务/状态链路必须通过 IT、E2E 或 sim |

## 维护规则

1. 新增或删除 CI 守卫脚本,必须更新本文的快照和守卫矩阵。
2. 新增 ADR、硬约束或架构例外,必须能从本文追到权威入口。
3. 审计报告发现的系统性问题,必须至少闭环到以下一项:代码修复、测试守卫、CI gate、ADR、runbook 或本文。
4. 如果本文和 `docs/agent-baseline.md`、ADR、OpenAPI、runbook 冲突,以后者对应权威文档为准,并立即修正本文。
