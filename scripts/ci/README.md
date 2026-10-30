# CI 脚本说明

## Java 固定契约与协议值守卫

`check-java-contract-governance.py` 分三类检查：JCON-1 保护 Controller 和 Application Service 接口的固定返回类型；JCON-2 只复扫注册类及权威枚举 code；JCON-3 只检查同类已有 KEY/PARAM/MDC 常量却在 key 消费点重新写字面量。注释、SQL 文本块、测试 fixture、日志文案和不同类的同值键不混入判断。

- 精确例外：`docs/governance/java-contract-governance.json`，必须包含方法签名及业务理由。
- 违规基线：`docs/governance/java-contract-governance-baseline.json`，当前为空；标识不依赖行号，禁止增加相同缺口数量。
- 权威枚举变化时，增量扫描扩展到登记消费者；例外必须精确，基线计数不得使用负数或布尔值。
- 本地：`python3 scripts/ci/check-java-contract-governance.py --base-ref origin/main`；全量：省略 `--base-ref`；只报告：`--mode report --json`。
- 自测：`python3 -m unittest scripts/ci/tests/test_check_java_contract_governance.py`。
- PR 对 Java 改动增量检查；规则、workflow 或注册表变化触发全量复扫。Full Gate 全量检查；pre-commit 检查暂存 Java，规则文件变化额外全量复扫。
- Sonar S1192 继续提供人工候选，不作为无差别字符串清零门禁。见 [治理计划](../../docs/plans/typed-contract-enum-constant-governance-plan-2026-10-07.md)。

本目录存放 GitHub Actions 与本地均可复用的 CI 门禁脚本。

## 完整守护清单

下表是 `check-*` / `validate-*` 可执行守护的登记源；`check-script-governance.py` 会阻止新增守护漏登记。

| 方向 | 守护 |
|---|---|
| 应用与架构 | `check-application-governance.py`、`check-dependency-boundaries.py`、`check-direct-client-boundaries.py`、`check-infrastructure-abstraction-boundaries.py`、`check-no-enable-preview.sh` |
| SDK 配置 | `check-sdk-config-env-parity.py`（Java/Python env 工厂和五语言 live transport 前缀）、`check-sdk-runtime-alignment.py`（仓库 Node 入口、SDK/前端声明与 CI 版本矩阵） |
| SDK 双栈 | `run-sdk-happy-eyeballs-gate.sh`（五语言真实 loopback socket 单栈/双栈/黑洞矩阵） |
| 文档与变更 | `check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-terminology-doc-sync.py`、`check-changelog-sync.py`、`check-loc-snapshot.py`、`check-readiness-doc-sync.py`、`check-slo-sli-catalog.py`、`check-comment-language.py` |
| 脚本与仓库 | `check-shell-scripts.sh`、`check-shell-linux-portability.py`、`check-script-governance.py`、`check-destructive-ops-governance.py`（删除类命令增量基线）、`check-governance-baseline-expansion.py`（治理基线扩张的限期审批）、`check-repository-hygiene.py`、`check-env-file-shell-safety.py`、`check-hardcoded-runtime-config.sh`（含 BE 验收端口及观测脚本 Console/Orchestrator 地址来源）、`check-utf8-encoding.py`（全仓 UTF-8 字节扫描）、`check-testcontainers-reuse-label.py`（Testcontainers 复用容器清理谓词）、`check-github-action-pinning.py`（外部 Action 固定 40 位 SHA并保留版本注释）、`check-soft-gate-governance.py`（报告/忽略模式责任与期限） |
| 配置与部署 | `check-config-defaults-sync.py`、`check-config-governance.py`、`check-direct-config-key-access.py`、`check-env-variable-governance.py`、`check-feature-switch-registry.py`、`check-five-worker-parity.py`、`check-keda-autoscaling.py`、`check-helm-env-sync.py`、`check-infrastructure-utf8.py`（Compose/Sim/Dockerfile/Helm/Testcontainers locale 与数据库编码）、`check-production-capacity-governance.py`、`check-production-overlay-safety.py`、`check-version-alignment.sh`、`validate-kafka-topics.sh`（全仓 topic 字面量 ↔ `BatchTopics.java`：env 模板 / `batch-defaults.yml` / helm / init 脚本 / load-tests） |
| 数据库与 SQL | `check-biz-table-tenant-rls.py`、`check-db-comment-coverage.sh`、`check-db-scripts-safety.sh`、`check-migration-safety.sh`、`check-mybatis-generated-key-columns.py`、`check-new-table-doc-coverage.py`、`check-no-positional-insert-select-star.py`、`check-postgres-client-fallback.sh`、`check-schema-governance-assets.py`、`check-sql-config-boundaries.py`、`check-sql-config-boundaries.sh`、`validate-flyway-schema.sh` |
| API 与兼容 | `check-console-openapi-paths.py`、`check-openapi-breaking.sh` |
| Java 质量 | `check-empty-checks.py`、`check-java-lombok-injection.py`、`check-java-logging-governance.py`、`check-java-readability.py`、`check-java-text-block-style.py`、`check-java-structured-string-concat.py`（增量阻止结构化多行模板拼接）、`check-java-suppression-registry.py`、`check-mapof-null-values.py`、`check-pipeline-summary-keys.py`（stage 摘要键 / 续跑回灌键 / 前端计数键契约）、`check-required-java-docs.sh` |
| 测试完整性 | `check-e2e-run-completeness.sh`、`check-e2e-shard-coverage.sh`、`check-integration-test-coverage.py`、`check-module-test-coverage.sh`、`check-no-silent-disabled-tests.sh`、`check-test-conventions.py`（中文 `@DisplayName` 类级/方法级 + 测试方法命名，增量拦截）、`check-flaky-test-governance.py`、`check-diff-coverage.py`、`run-critical-mutation.sh`、`report-ci-quality-trends.py`、`scripts/sim/fix-fixture-xlsx.py --check`（四份 Sim 配置包 Excel schema 与内容约束） |
| 安全与许可 | `check-dependency-licenses.sh`、`license-allowlist.sh`、`check-license-compliance.sh`、`check-sbom-sync.sh`、`check-trivy-ignore-expiry.py` |
| 观测 | `check-helm-prometheusrule-sync.sh`、`check-log-lifecycle.sh`、`check-observability-contract.py` |

## 门禁判定与正反例

本节解释门禁类别的判定边界。表中“拦截”是应失败的变更，“放行”是看起来相似但属于合法场景的变更；它们是判定示例，不替代脚本本身的精确规则。具体脚本、触发范围和本地命令以本页清单、`docs/runbook/ci.md` 及对应脚本帮助为准。

| 类别 | 主要拦截情形 | 应放行情形 | 代表性守卫 |
|---|---|---|---|
| 文档与变更记录 | 破坏文档结构/链接、核心状态字典未同步、版本变更缺少 Changelog | 不影响发布行为的普通文字修订；允许按日期策略存放的归档文档 | `check-docs-structure.py`、`check-terminology-doc-sync.py`、`check-changelog-sync.py` |
| Shell / Python / 仓库卫生 | Shell 语法或 ShellCheck 告警、Linux 不兼容语法、脚本未登记、提交本机产物 | 通过公共配置读取平台服务端口和基础地址；脚本自有的临时端口和显式可覆盖的场景参数 | `check-shell-scripts.sh`、`check-shell-linux-portability.py`、`check-script-governance.py`、`check-repository-hygiene.py`、`check-hardcoded-runtime-config.sh` |
| 配置与部署 | 环境默认值、Helm、Compose、功能开关或生产覆盖彼此漂移；生产配置启用不安全值 | 只在一个场景使用且有明确 owner 的测试参数；不属于平台配置源的临时客户端端口 | `check-config-defaults-sync.py`、`check-env-variable-governance.py`、`check-helm-env-sync.py`、`check-production-overlay-safety.py` |
| 数据库 / SQL | 业务表缺租户隔离、迁移有破坏风险、位置式 `INSERT ... SELECT *`、SQL 绕过独立文件与变量传值 | 由权威状态/配置来源驱动的 SQL；经过登记并有专项测试的精确例外 | `check-biz-table-tenant-rls.py`、`check-migration-safety.sh`、`check-no-positional-insert-select-star.py`、`check-sql-config-boundaries.sh` |
| API 与 SDK | OpenAPI 出现未登记破坏性变更、五语言共享契约不一致、运行时版本超出声明范围 | 只改变实现且不改变公开契约的内部重构；允许的兼容新增字段；ADR-048 批准的全新库同步切换仅登记精确列出的旧 Workflow/Pipeline 契约及计划 I6/R35 的 `POST workflow-runs/{id}/skip-node` 必填 query `reason`，其他 breaking 仍失败 | `check-openapi-breaking.sh`、`check-console-openapi-paths.py`、SDK parity workflow |
| Java 规约与架构 | 新增空检查、字段注入/日志违规、固定协议值漂移、结构化多行字符串退化、依赖越界 | 不在规则覆盖范围内的测试 fixture/说明文本；已精确登记且有理由的例外 | `check-empty-checks.py`、`check-java-contract-governance.py`、`check-java-lombok-injection.py`、`check-dependency-boundaries.py` |
| 测试完整性 | 测试被静默禁用、E2E 分片漏场景、差异覆盖率不足、测试展示名称不符合约定；模块测试 profile 重复维护共享默认项；Sim 配置包 Excel 表头或内容约束与当前导入 schema 漂移 | 按契约定义的负向用例、明确标记且登记的 flaky 隔离项、Java 测试为特定场景显式覆盖配置 | `check-no-silent-disabled-tests.sh`、`check-e2e-run-completeness.sh`、`check-e2e-shard-coverage.sh`、`check-diff-coverage.py`、`check-test-config-sources.py`、`scripts/sim/fix-fixture-xlsx.py --check` |
| 安全 / 供应链 / 许可 | 未登记的高危漏洞豁免、例外到期、禁止许可证、SBOM 与 POM 不一致、Action 使用可变引用 | 扫描器确认的误报可按规则与路径精确登记；许可结论明确且符合项目策略的依赖 | `check-trivy-ignore-expiry.py`、`check-license-compliance.sh`、`check-sbom-sync.sh`、`check-github-action-pinning.py` |
| 可观测性 | 告警定义与服务指标契约漂移、日志生命周期破坏敏感信息保护要求 | 业务日志内容变化但不改变受守护字段、生命周期或敏感信息规则 | `check-observability-contract.py`、`check-helm-prometheusrule-sync.sh`、`check-log-lifecycle.sh` |

OpenAPI 另精确登记 2026-10-31 用户批准的 9 条请求约束差异，范围、同步交付要求和正反例命令见 [CI 运行手册](../../docs/runbook/ci.md)。其他请求收紧仍须阻断。

例子如何读：`scripts/local/be-acceptance.sh` 若重新声明 `CONSOLE_PORT` 的平台默认值，或观测脚本重新拼装 Console/Orchestrator 本地地址，属于配置重复，应被 `check-hardcoded-runtime-config.sh` 拦截；若某测试脚本定义自己的 `PREVIEW_PORT`，该值仅控制测试进程监听端口且可由环境变量覆盖，则不应因“出现数字端口”被误拦。测试中的状态值若是要验证的公开协议 fixture，应与生产权威枚举保持对照，而不是简单禁止字面量。

本页的拦截/放行例子是面向人的说明文字，不是可执行配置或门禁 fixture；不粘贴违规片段，不把说明文档纳入代码规则的误报豁免。若未来确需在文档中展示精确违规源码，应使用明确的非执行标记，并为对应扫描器添加窄范围文档样例测试，证明样例不会触发门禁且真实源码仍会被拦截。

## 快速失败与失败汇总

汇总失败是为了减少“修一个、重跑一次”的往返，不代表允许后续步骤忽略失败。当前路由如下：

| 执行范围 | 当前策略 | 可以继续的条件 | 必须立即停止的情形 |
|---|---|---|---|
| 本地 pre-commit | `BATCH_GATE_COLLECT=1`；适用的独立检查逐项输出，末尾 `gate_assert_collected` 汇总并返回非零 | 多个 lint、文档、配置、静态契约检查互不依赖 | 扫描器所需解释器/依赖不可用、Git 暂存区信息无法取得等脚本级前置错误；单项内部有生成产物依赖时，该子流程在产物生成失败处停止 |
| 本地 pre-push | 独立静态门禁失败后继续其他适用检查，并在末尾汇总；符合条件时仍执行 clean compile | 不依赖失败项输出的其他静态检查 | base/工作区不可判定等无法可靠计算增量范围的前置错误；编译失败后不应继续依赖编译产物的检查 |
| PR 静态门禁 | policy、supply-chain、Java quality 分路执行；各组 `gate_run` 收集失败，required `static-checks` 汇总 | 同一静态组中的独立脚本可继续 | checkout、运行时/工具安装、权限或必需输入准备失败；扫描器无法启动时不能报告扫描通过 |
| Full Gate 静态门禁 | 在一个静态 job 内收集独立门禁结果，最后统一失败 | 互不依赖的策略与静态扫描 | 环境安装、依赖下载等阻断后续全部检查的前置失败 |
| 编译、单元/集成/E2E、真实链路 | 按 job/步骤依赖关系执行；矩阵可设 `fail-fast: false` 以收齐其他 shard 结果，但整体仍失败 | 其他独立 shard 可并行完成结果采集 | 服务启动、数据库迁移、测试所需构建产物失败时，不运行依赖它们的阶段；超时/取消/环境失败不算通过 |

汇总策略的验证标准：同时制造两项互不依赖的失败，应看到两项诊断且最终退出码非零；制造基础前置失败，应看到后续依赖步骤未执行且 job 失败；所有项通过才输出整体通过。不得通过 `continue-on-error`、忽略退出码或只检查最后一项实现“汇总”。

## `check-version-alignment.sh`

校验应用发布版本和基础服务镜像版本的一致性。镜像检查以 `.env.example` 为模板，
并与本机存在的 `.env.local`、`.env.test`、`.env.prod` 比较；同时核对 Testcontainers
镜像及 CI、Compose、Sim 等运行入口的引用。MinIO 客户端镜像单独使用
`MINIO_MC_IMAGE_REPOSITORY` / `MINIO_MC_IMAGE_TAG`，并校验 Compose 初始化容器与
`scripts/lib/minio-mc.sh` 的 fallback 和环境模板一致。

```bash
bash scripts/ci/check-version-alignment.sh
```

该检查已接入 PR Gate 与 Full CI Gate；修改基础镜像版本或运行入口时应同步更新
`.env.example`、各环境文件和对应 fallback。

## 门禁结果格式

Git hook、GitHub workflow 和独立事实来源检查统一输出最终状态行：

```text
✅ 通过 | code=TEST_CONFIG_SOURCES | gate=测试配置事实来源 | group=policy | mode=enforce | exit_code=0 | action=none
❌ 不通过 | code=TEST_CONFIG_SOURCES | gate=测试配置事实来源 | group=policy | mode=enforce | exit_code=1 | action=fix_and_retry
⚠️ 报告 | code=STRICT_VERIFY_LIVE | gate=在线 strict 验证 | group=acceptance | mode=report | exit_code=1 | action=review_report
⏭️ 忽略 | code=LOCAL_EXPERIMENT | gate=本地实验检查 | group=quality | mode=ignore | exit_code=0 | action=none | reason=统一控制配置未执行该门禁
```

字段顺序固定为状态、`code`、`gate`、`group`、`mode`、`exit_code`、`action`；补充信息只在末尾追加`reason`。日志和GitHub步骤摘要使用同一条结果，`code`保留各仓既有命名空间，不改required check名称。

| 状态 | 含义与退出规则 |
|---|---|
| PASS / 通过 | 命令成功，`exit_code=0`，`action=none` |
| FAIL / 不通过 | 保留原命令非零退出码，`action=fix_and_retry`；收集模式最后统一非零退出 |
| SKIP / 跳过 | 条件不满足，`mode=conditional`并给出原因，不作为已执行或已通过 |
| REPORT / 报告 | 仍执行命令，保留实际非零`exit_code`，`action=review_report`；报告模式本身返回0，不冒充PASS |
| IGNORE / 忽略 | 控制配置要求不执行，`mode=ignore`并给出原因，不冒充PASS |

门禁自测使用独立的控制输入、失败账本和摘要文件；预设负例不进入调用方的真实CI摘要。真实执行器只发布本次实际结果，本地自测通过不等于远端CI已通过。

结果字段始终保持单行：百分号、管道、回车、换行、制表符分别编码为`%25`、`%7C`、`%0D`、`%0A`、`%09`。非法状态、执行器参数缺失及无命令均输出结构化错误并返回2，不能落成空命令PASS。

旧检查器缺少`group`/`mode`的成功状态行去重；旧失败、报告、忽略和跳过以`诊断 |`前缀保留，避免丢掉唯一原因；已合规的嵌套结果、扫描报告、测试进度和构建日志保留。最终门禁状态由执行器给出，不能从外围命令退出0推断所有子检查都执行过。失败汇总逐项复用同一格式，最后输出`GATE_COLLECTION_SUMMARY`、失败数并返回1。

定向自测：`bash scripts/ci/tests/test-gate-result-collection.sh`（包含控制模式和共用格式契约），复用PR/Full既有自测步骤。


### 分组、开关和模式

统一执行模式只有三种：`enforce` 执行并按真实退出码阻断；`report` 执行并保留真实退出码到状态行，但执行器返回成功；`ignore` 不执行命令并明确输出忽略状态。条件未命中产生的 `SKIP` 是路由结果，`mode=conditional`，不等同于人工忽略或检查通过。

控制优先级从高到低是总开关关闭、单门禁覆盖、分组覆盖、全局模式、默认 `enforce`：

```bash
bash scripts/ci/run-gate.sh --group quality --mode report LOCAL_PMD "PMD" -- ./mvnw pmd:check
BATCH_GATE_GROUP=quality BATCH_GATE_MODE=report gate_run LOCAL_PMD "PMD" ./mvnw pmd:check
BATCH_GATE_GROUP_MODES='quality=report,docs=ignore' bash scripts/local/pre-commit-checks.sh
BATCH_GATE_GROUP_MODES='local-pre-push=report' git push
BATCH_GATE_MODES='PRE_COMMIT_SPOTLESS=enforce,PRE_COMMIT_JAVA_READABILITY=ignore' bash scripts/local/pre-commit-checks.sh
BATCH_GATE_ENABLED=0 bash scripts/ci/run-gate.sh --group quality LOCAL_PMD "PMD" -- ./mvnw pmd:check
```

`BATCH_GATE_ENABLED=0` 等价于全局 `ignore`，用于明确关闭当前执行上下文；CI 不得把 required 门禁配置为 `report` 或 `ignore`。仓库 workflow 中的报告/忽略模式必须紧邻 `soft-gate-id` 登记，并在 `docs/governance/soft-gates.json` 记录 `group`、`mode`、控制方式、owner、基线、升级期限和目标。`continue-on-error` 或工具原生非阻断能力只作为无法由统一执行器包裹的适配方式，仍受同一登记守卫约束。各检查脚本不再自行读取 `BATCH_CI_SKIP_*`。

分组用于状态汇总和控制，不改变工作流职责：PR 静态检查使用 `policy`、`supply-chain`、`quality`，Full Gate 静态检查使用 `full-static`；其他入口按 `contract`、`test`、`build`、`release`、`acceptance`、`operations` 等稳定职责命名。不得为了绕过门禁临时改组。

PR、Full Gate 和本地 hook 的静态检查设置 `BATCH_GATE_COLLECT=1`。`enforce` 模式的业务门禁失败时
`gate_run` 会记录错误并继续执行后续检查，末尾由 `gate_assert_collected` 汇总全部失败后
统一返回非零；`report` 与 `ignore` 不进入失败集合。checkout、运行时安装等前置环境步骤仍保持立即失败。

本地 pre-commit 对三类确定性派生产物自动同步并暂存：代码量快照、POM 对应的 SBOM、
`@ConfigurationProperties` 对应的配置治理目录。自动同步前会检查权威源不存在未暂存或
未跟踪改动，防止误带工作区内容。Changelog、安全例外、功能开关与许可证风险说明等
需要语义判断的文件保持人工维护，CI 只读阻断。

`check-sbom-sync.sh` 从当前 Maven reactor 重生成 `target/bom.json`，并与
`docs/compliance/sbom.json` 比较 CycloneDX 版本、组件坐标、版本、许可证、哈希和依赖图。
数组顺序、描述等跨环境展示字段不参与比较。任何 POM 依赖或版本调整都必须同步提交入库
SBOM；`check-license-compliance.sh` 复用已生成结果再次校验，避免 Full Gate 只上传动态
artifact、却放过仓库快照漂移。

`compare-sbom.py` 仅豁免精确登记的 OS 专属组件。目前只有 Reactor Netty 在 macOS 解析时
附加的 `io.netty:netty-resolver-dns-native-macos`；比较时同时移除对应组件和依赖边。其他
组件的坐标、版本、许可证、哈希或依赖关系发生变化仍会阻断。

许可证检查默认每次重建 Maven 许可证清单和 SBOM；只有调用方刚完成同一组生成命令时才可
显式传 `--reuse-generated`。AGPL、SSPL、BUSL、CPAL、EUPL、Commons Clause、Elastic、
PolyForm、无 Classpath Exception 的纯 GPL 及未知许可证均阻断。

## `daily-validation-change-gate.py`

判断定时触发对应的北京时间运行日是否包含代码/配置变更，Markdown、RST、`LICENSE` 和 `NOTICE` 不触发验证。日期按最近一次 cron 计划时间推导，因此定时任务延迟到午夜后启动仍检查原计划日；手动 dispatch 按当前北京时间日期处理，并可勾选 `force` 绕过变更条件。本地运行可设 `VALIDATION_DAY` 固定检查日期、`VALIDATION_EVENT=schedule` 与 `VALIDATION_SCHEDULE='31 13 * * *'`模拟定时触发，或设 `FORCE_VALIDATION=true` 强制运行；脚本不接受 `--force` 参数。所有人类可见的结果使用共享 `gate-result.sh` 状态、错误码和原因格式，`should_run/reason` 仅写入 `$GITHUB_OUTPUT` 供 workflow 路由。

```bash
python3 scripts/ci/daily-validation-change-gate.py
FORCE_VALIDATION=true python3 scripts/ci/daily-validation-change-gate.py
```

Nightly 的 SIM 与 strict 保持同环境顺序验证：SIM 结束后静默调度，再执行 strict。
strict 只以环境成功启动为前置，不要求 SIM 成功；SIM 失败或步骤超时仍执行 strict，
任一验证失败都阻断后续镜像构建。SIM 限时 180 分钟、静默调度限时 5 分钟、strict 限时 20 分钟，
工作流总限时 240 分钟；主动取消、Runner 丢失或环境启动失败无法保证继续验证，
这些情况必须按未执行报告，不能记为通过。步骤摘要分别展示环境、SIM、strict 的结果。

strict-verify 的 Console 探活检查 Actuator 响应为有效 JSON 且包含字符串 `status`，不要求 HTTP 200：
最小验证栈未启动的可选依赖可能让聚合健康端点返回 503，但这仍证明应用已响应。无响应、无效 JSON
或缺少状态字段仍会按未启动处理。回归测试 `test_console_api_readiness.py` 在本地 pre-commit、PR 与 Full Gate 执行：
`python3 -m unittest scripts/ci/tests/test_console_api_readiness.py`。

预推送模块选择由 `tests/test_pre_push_module_selection.py` 守护：保留 `batch-e2e-tests`
等带数字的完整模块名、同一 Worker 聚合模块去重，不再静默截断超过五个的改动模块。
此测试接入本地 pre-commit 和 PR / Full 的 CI 质量守护组。
`test_daily_validation_workflow.py` 在 PR / Full 的 CI 质量守护组执行，
防止 strict 改回隐式成功依赖或吞掉失败退出码。
`test_sdk_orchestrator_e2e_scripts.py` 在脚本域的本地、PR 与 Full 门禁执行，模拟 PostgreSQL
initdb 临时服务，以及 Java、Python、TypeScript、Rust 样例准备失败，防止真栈 E2E 再次出现
冷启动竞态或空诊断日志。Go 样例直接以受监控的 `go run` 进程启动，不存在独立准备阶段。

```bash
python3 -m unittest scripts/ci/tests/test_daily_validation_workflow.py
python3 -m unittest scripts/ci/tests/test_sdk_orchestrator_e2e_scripts.py
```

`check-sql-config-boundaries.py` 是同名 `.sh` 稳定入口的实现，两者都登记，避免包装层与实现层单独漂移。
扫描覆盖 `scripts/`、`load-tests/` 和 `deploy/`，识别 `psql`/`psql_* -c`、SQL heredoc 与直接 SQL 语句；
数据库维护命令也应使用独立 `.sql` 文件和 psql 变量传值。

`check-shell-linux-portability.py` 默认覆盖 Git 已跟踪及未跟踪的现存 Shell 文件；索引中仍保留、
但工作树已删除的脚本不再作为读取对象。显式文件参数与默认扫描遵循相同的现存文件边界，
不跳过新脚本的 BSD/macOS 专属命令检查。回归源码见
[`test_check_shell_linux_portability.py`](tests/test_check_shell_linux_portability.py)，
覆盖删除路径、未跟踪违规脚本及已有 Linux fallback；实际执行证据单独记录，不将源码存在计为测试通过。

## PR 按需路由

`pr-gate.yml` 始终启动，以保证 ruleset required Job 在每个 PR 上稳定回报状态；
`.github/actions/detect-change-scope` 统一调用 `scripts/ci/detect-change-scope.py`，
`pr-gate-scope` 和 `sdk-contract-scope` 输出稳定的
`java`、`sql`、`database`、`scripts`、`docs`、`config`、`api`、`sdk`、`ci`、
`tests`、`docker`、`helm`、`maven`、`unknown`、`unit-required`，以及
`unit-a-required`、`unit-b1-required`、`unit-b2-workers-required`、
`unit-b2-console-required` 字段。后续 workflow 应消费这些
输出，不要重新维护一套路径 glob。`database` 保留为旧门禁兼容别名；`unknown=true`
时必须按代码变更处理。`unit-required` 是四个单元分片输出的汇总兼容字段；PR workflow
直接消费具体分片输出。Common、Test Support、数据库迁移、POM/Maven Wrapper、未知路径和
未登记的 `batch-*` 源码模块均 fail-closed 为全分片。CI、脚本、
SDK 变更仍应由各自专项 workflow/静态检查覆盖。已登记的根目录运行时与质量工具配置归入
`config`，不因缺少 Java 影响而启动 Maven unit；未登记的新路径仍归入 `unknown` 并保守执行。
SDK 契约 workflow 与 PR gate 共用同一探测器。
`config/quality/*.xml` 明确归入 `java/config/ci`，触发 Java 静态质量检查；规则变化本身不启动业务单元分片，目录 README 仍只属于文档范围。

探测器不属于某个具体业务门禁，后续 workflow 应复用该 composite action 和输出契约；不要复制
`dorny/paths-filter` 或在 YAML 中新增另一套路径白名单。GitHub ruleset 中应同时要求
两个 scope check、`sdk-contract-required` 以及 PR 快速门禁；未分类路径必须走全量/保守门禁。

```bash
# 本地查看当前分支相对 main 的范围
python3 scripts/ci/detect-change-scope.py --base origin/main --head HEAD --json
python3 scripts/ci/detect-change-scope.py --staged --json
```

本地 `pre-commit` 用 `--staged` 对暂存路径执行相同分类；`pre-push` 用 `--base/--head` 对分支差异分类。两种本地输入都输出相同 scope 名称，避免 hook 各自维护路径分类规则。提交钩子只运行命中范围的快速检查，推送钩子运行分支级契约和适用的 clean compile；二者均通过统一门禁执行器支持分组与单项控制。`report` 仍执行检查并汇总真实失败但不阻断，`ignore` 明确不运行；不要将这两种本地便利模式用于 CI required checks。

PR 事件使用 `base` 与已检出的 `head` 提交树直接比较；merge queue、push、schedule 和手工触发没有
可靠 PR diff 时回退为全范围，避免误跳过门禁。探测器本身只负责分类，不决定哪些检查
是 required；required check 仍由 ruleset 和 workflow job 名称负责。
Secret scan 属于跨域检查，仍对所有非 Draft PR 执行，因为凭据可能出现在任意文件类型中。

## `check-code-doc-references.py`

校验后端 Java、XML、POM 和配置文件注释中引用的仓库内 `docs/`、`sdk/` 路径存在，避免文档归档或目录迁移后代码注释继续指向失效路径。

```bash
python3 scripts/ci/check-code-doc-references.py
```

## `check-terminology-doc-sync.py`

校验 `docs/dict/glossary.md`、`docs/architecture/core-model.md`、
`docs/design/status-state-machines.md`、`docs/architecture/pipeline-vs-workflow-boundary.md` 和
`docs/coding-conventions.md` 中标记的枚举值块与
`batch-common` Java enum 一致，覆盖核心生命周期状态、调度类型、触发来源、工作流节点类型和
运行模式。修改权威 enum 后先刷新文档，再提交两者：

```bash
python3 scripts/ci/check-terminology-doc-sync.py --write
python3 scripts/ci/check-terminology-doc-sync.py
```

已接入 PR Gate 和 Full Gate；该检查只防离散值漂移，术语解释和实体关系仍需按核心模型审查。

## `check-comment-language.py`

检查源码、配置、脚本和 SQL/Flyway diff 中新增的说明性注释是否为简体中文；SQL `COMMENT ON` 的表、列、索引、约束、函数、模式和触发器说明也纳入检查。机器指令、标准许可证声明和代码示例不判为说明性注释；普通注释仅检查新增行，变更 SQL 文件中的对象说明按完整语句检查。可用 `--paths` 对指定目录执行存量扫描，包含 Git 未忽略的未跟踪文件。

```bash
python3 scripts/ci/check-comment-language.py --staged
python3 scripts/ci/check-comment-language.py --base-ref origin/main
python3 scripts/ci/check-comment-language.py --paths scripts load-tests/scripts load-tests/sql
```

当前作为本地增量预检接入 `scripts/local/pre-commit-checks.sh`，仅检查暂存 diff 新增的说明性注释，不扫描存量文件。PR/Full CI 暂不阻断。Flyway 已提交迁移的注释修改会改变 checksum；本项目只支持全新空库部署，不支持已应用旧迁移的数据库原位升级。结构校验仅在有效 SQL 未变化时放行注释和 `COMMENT ON` 元数据修改；对已提交迁移的 DDL/DML 变化仍会阻断，修复应新增迁移版本。

## `check-empty-checks.py`

检查生产 Java 本次 diff 新增的 `null`、字符串空、集合空判断。统一入口为
`batch-common` 的 `EmptyChecks`：`isNull/isNotNull`、`isEmpty/isNotEmpty`、
`isBlank/isNotBlank` 分别表达对象、字符串和集合的语义。脚本只检查新增行，历史
代码由专项迁移逐步收口，不会因为一次接入产生大范围机械改动。

默认检查当前工作区相对 `HEAD` 的新增行，并包含 Git 未跟踪的生产 Java 源码；
显式指定 `--base` 时只比较该提交与 `HEAD`。测试、忽略文件和已删除文件不纳入。

`check-java-fqn-diff.py` 供本地 pre-push 使用：以提交版 Java 源码掩去注释、字符串和文本块，再检查新增行中的全限定类型名；执行失败会阻断推送。回归测试为 `python3 -m unittest scripts/ci/tests/test_check_java_fqn_diff.py`。
SDK 使用自己的 `internal.EmptyChecks`，不依赖后端公共模块。

```bash
python3 scripts/ci/check-empty-checks.py
python3 scripts/ci/check-empty-checks.py --base origin/main
python3 -m unittest scripts/ci/tests/test_java_worktree_source_inventory.py
```

已接入 `pr-gate.yml` 和 `full-ci-gate.yml` 的静态检查。

## `check-env-file-shell-safety.py`

校验仓库跟踪的 `.env*` 文件可被 Bash 脚本安全 `source`。本地和 CI 会把
`.env.example` 复制成 `.env.local` 后注入脚本；活动的 `KEY=value` 行如果值包含空白，
必须写成 `KEY="..."` 或 `KEY='...'`，避免 JVM 参数这类值被拆成命令执行。

```bash
python3 scripts/ci/check-env-file-shell-safety.py
```

已接入 `pr-gate.yml`、`full-ci-gate.yml` 和本地 `scripts/local/pre-push-sdk-checks.sh`。

## `check-readiness-doc-sync.py`

检查 readiness 相关契约触点的 diff：`readiness`、`AssetPartition`、`ResultVersion`
以及 trigger 调度主路径变更时，必须在同一 PR 中同步至少一类证据：设计文档、OpenAPI /
协议文档或 readiness 相关测试。仅将 `getMessage()` 替换为安全异常摘要且未改动其他代码的
纯日志差异不视为契约变更。该脚本是轻量同步守护，不替代 IT / E2E 语义验证。

```bash
python3 scripts/ci/check-readiness-doc-sync.py
python3 scripts/ci/check-readiness-doc-sync.py --base origin/main
```

已接入 `pr-gate.yml`、`full-ci-gate.yml` 和本地 `scripts/local/pre-push-sdk-checks.sh`。

## `check-trivy-ignore-expiry.py`

校验 `.trivyignore` 中每组 CVE 白名单必须带 `owner`、`reason`、`expires: YYYY-MM-DD`，
且 `expires` 未过期。安全漏洞豁免只允许作为有期限的临时措施，不能长期静默留在仓库。
当前 `.trivyignore` 不再包含 CVE 豁免；扫描命中应优先升级依赖，只有经验证仍需临时接受的风险才可登记。
配置误报使用 `.trivyignore.yaml` 按规则和文件路径精确豁免，并填写原因；
Trivy 的漏洞扫描和配置扫描分别加载对应白名单，避免配置规则被全仓静默忽略。

```bash
python3 scripts/ci/check-trivy-ignore-expiry.py
python3 scripts/ci/check-trivy-ignore-expiry.py --today 2026-09-13
```

已接入 `pr-gate.yml`、`full-ci-gate.yml` 和本地 `scripts/local/pre-push-sdk-checks.sh`。

`check-dependency-licenses.sh` 会先运行 `tests/test-dependency-license-allowlist.sh`，验证双许可豁免仅匹配报告中精确的 `RocksDB JNI` 组件行。`license-review.yml` 的路径触发器同时覆盖检查脚本、匹配 helper 与该测试，避免只改豁免逻辑时跳过门禁。

## `run-full-regression.sh`

统一 Maven 回归入口：默认测试、`*IT` / E2E、可选压测 smoke、部署 smoke、升级 / 回滚验证与巡检。参数与行为以脚本内 `usage()` 为准。

```bash
bash scripts/ci/run-full-regression.sh --help
```

## `run-staging-live-smoke.sh`

staging live rollout / rollback smoke 的薄封装：默认开启 live deploy smoke 和 deployment verification，直接复用 `run-full-regression.sh`。

```bash
bash scripts/ci/run-staging-live-smoke.sh
```

## 测试质量治理

必需门禁不再全局重跑失败测试。确认的 flaky 用例使用 `@FlakyTest` 填写 Issue、责任人和到期日，默认测试排除，定时任务通过 `run-flaky-quarantine.sh` 单独执行。`collect-flaky.sh` 仍用于识别隔离执行中的首次失败记录。

PR / Full Gate 的各 Java shard 在现有测试后生成 JaCoCo XML，并由 `check-diff-coverage.py` 校验本 shard 变更可执行行覆盖率不低于 80%。PR 的编排、Trigger、PROCESS、DISPATCH、IMPORT、EXPORT 和 Console API 分片通过 `run-pr-coverage-integration.sh` 追加经验证的真实业务集成场景；Console API 分片也运行配置包和配置定义的定向 Testcontainers 集成测试。Full Gate 仍执行各模块完整单测与集成测试。这样保留覆盖率门槛，同时避免将所有慢速集成测试重复塞入 PR。每周或手动 Full Gate 通过 `run-critical-mutation.sh` 对文件状态机和生命周期映射器运行 PIT，不扩展到全仓。

Full Gate 汇总 Surefire/Failsafe 报告与最近 30 次工作流运行，使用 `report-ci-quality-trends.py` 输出 JSON 和 Markdown，保存 90 天。报告区分 E2E 套件全部跳过与成功，并按分片统计 XML 中的测试累计时间；该时间不含排队或作业准备，不能作为墙钟耗时。

```bash
bash scripts/ci/collect-flaky.sh
bash scripts/ci/collect-flaky.sh -- --json build/flaky.json --warn-threshold 3
```

## `security-scan.sh`

本地 / CI 安全扫描一键入口：先打包 `security-scan` 独立 Java 模块，再按参数执行 secret、依赖、SAST、文件系统、镜像和 ZAP 扫描。默认执行全量扫描，可通过 `--mode` 收窄范围。
PR 和 Full CI 的 secret 模式打包时运行独立模块单测；显式 `--skip-tests` 会跳过它们。
SAST 模式需要 `python3` 汇总完整 Semgrep 报告；高危规则命中或扫描错误、警告使其失败，中低级别保留供人工复核。具体语义见 [安全扫描手册](../../docs/runbook/security-scan.md)。

```bash
bash scripts/ci/security-scan.sh --help
```

## `check-console-openapi-paths.py`

校验 `docs/api/console-api.openapi.yaml` 中 `/api/console` 下的 **GET/POST** 路径是否与 `batch-console-api` 里 `Console*Controller` 的 `@RequestMapping` + `@GetMapping` / `@PostMapping` 一致，避免文档与实现漂移。

**依赖**：Python 3、PyYAML（由 `scripts/requirements.txt` 统一声明）。

```bash
make python-env
make check-openapi
```

成功时打印路由数量并以退出码 `0` 结束；不一致时打印「仅 OpenAPI」与「仅代码」的差异并以 `1` 结束。

**接入的 workflow**（均在 checkout 之后、Java 构建之前执行）：`.github/workflows/pr-gate.yml`、`.github/workflows/full-ci-gate.yml`、`.github/workflows/staging-gate.yml`。

## `check-dependency-boundaries.py`

校验依赖边界约束：

- `batch-common` 不得新增对象存储、OTEL exporter、AI SDK、Excel 处理等运行时重依赖
- 全业务模块不得引入 `spring-boot-starter-data-jdbc`（持久层统一 MyBatis；见 ADR-001）
- `batch-console-api` 与 `batch-orchestrator` 须在运行时 POM 中同时出现 `spring-boot-starter-jdbc` 与 `mybatis-spring-boot-starter`（脚本会校验）
- 生产模块不得从其他模块的源码树加载 Maven resource；共享测试资源必须通过 `batch-test-support` 提供
- 纯测试模块 `batch-e2e-tests` 对仓内模块的依赖必须全部使用 `test` scope

```bash
python3 scripts/ci/check-dependency-boundaries.py
```

成功时打印 `OK: dependency boundaries satisfied.` 并以退出码 `0` 结束；违反约束时打印错误并以 `1` 结束。

## `check-helm-env-sync.py`

校验 Helm templates 注入的 `BATCH_*` 环境变量是否能被应用消费，避免生产 Chart 变量名写错后静默失效；同时确认生产一等开关入口（限流、请求签名等）已显式渲染。

生产一等开关入口从 `docs/runbook/feature-switch-registry.yml` 读取；新增公共开关时先按
`docs/runbook/config-ops-tiering.md` 判断是否属于 L0，再登记 registry、补 Compose / Helm / 运维说明。

```bash
bash scripts/python.sh scripts/ci/check-helm-env-sync.py
```

成功时打印 `Helm BATCH_* env 与应用消费入口一致` 并以退出码 `0` 结束；违反约束时列出未知变量或缺失入口并以 `1` 结束。

## `check-env-variable-governance.py`

校验环境变量治理文档、关键配置入口和常用检查脚本仍然存在，并确认 `.env.example` 保留开发、场景测试、压测和生产排障最常用的关键变量模板。它不要求 `.env.example` 收录全部 `BATCH_*`，全量公共开关仍以 `feature-switch-registry.yml` 和 `feature-switches.md` 为准。

```bash
bash scripts/python.sh scripts/ci/check-env-variable-governance.py
```

成功时打印 `environment variable governance doc and critical entry points are aligned` 并以退出码 `0` 结束；缺文档锚点、缺关键文件或缺关键变量模板时以 `1` 结束。

## `check-five-worker-parity.py`

校验 Import / Export / Process / Dispatch / Atomic 五类内建 Worker 在 topic、租户 ACL、Kafka lag 告警、消费路由、sim 恢复和配置包示例中的横向完整性。配置包场景还包括 `SCHEDULE`，它是业务配置场景而不是 Worker 类型。Pipeline stage 仍只覆盖前四类；Atomic 是单任务执行器，并由独立隔离模板部署。

```bash
python3 scripts/ci/check-five-worker-parity.py
```

## `check-keda-autoscaling.py`

校验五类内建 Worker 的 KEDA ScaledObject、Kafka topic/consumer group、冷却参数、HPA 互斥和优雅停机模板保持一致。该守护只验证静态部署契约，不能替代 staging 的扩缩容、rebalance 和 lease 回收演练。

**topic 与 consumerGroup 的期望值不写在本脚本里**：topic 从 `BatchTopics.java` 的
`TASK_DISPATCH_*` 常量读取，consumerGroup 从各 worker 的
`batch-worker/<x>/src/main/resources/application.yml` 默认值读取。这样改 `BatchTopics.java`
会立刻反映到本守护，而不是「脚本与自己的副本一致」而给出虚假通过。

```bash
python3 scripts/ci/check-keda-autoscaling.py
```

成功时打印 `✅ 通过 | code=KEDA_AUTOSCALING | gate=KEDA 自动扩缩容` 并以退出码 `0` 结束；
缺任一 Worker、helm 值或 consumerGroup 与 worker 声明不一致时，逐条列出文件并以 `1` 结束。

## `validate-kafka-topics.sh`

校验全仓 topic 字面量与 `batch-common/.../kafka/BatchTopics.java`（唯一权威）一致，覆盖 5 处载体：

| 载体 | 校验方向 |
|---|---|
| 指定 env 模板的 `KAFKA_TOPICS`（默认 `.env.example`） | 双向 diff（缺 topic / 多 topic 都报） |
| `batch-common/src/main/resources/batch-defaults.yml` 的 `${BATCH_TOPIC_*:默认值}` | 字面量 ∈ `BatchTopics` |
| `helm/batch-platform/**` 中带引号的 `"batch.*"` 字面量 | 字面量 ∈ `BatchTopics` |
| `scripts/data/init-kafka-topics.sh` 的 `default_topics` | 必须覆盖全部 active 常量 |
| `load-tests/scripts/cleanup-load-test-environment.sh` 的 topic 清单 | 字面量 ∈ `BatchTopics` |

只做「字面量 ∈ 常量」与「常量 ⊆ init 清单」两个方向，不做全等 —— 各载体按设计只承载
自己关心的子集（如 helm 只管 5 个 dispatch topic）。helm 侧只取**引号形式**，
避免把 `batch.example.com/...` 这类 k8s label 前缀误判为 topic。

```bash
bash scripts/ci/validate-kafka-topics.sh .env.example
```

## `check-config-governance.py`

校验所有生产源码 `@ConfigurationProperties` 绑定点都登记在
`docs/runbook/config-governance-registry.yml`，并明确配置来源、生效方式和敏感级别。同时禁止直接引入
`@RefreshScope`、配置重绑定器以及未评审的 Spring Cloud/Nacos/Apollo 配置客户端。

```bash
python3 scripts/ci/check-config-governance.py
python3 scripts/ci/check-config-governance.py --write  # 新增配置类后重建登记表
```

成功时打印 `configuration governance valid` 并以退出码 `0` 结束；登记表或 Console
运行时副本漂移、发现运行时刷新违约或引入未经评审的配置中心依赖时，以 `1` 结束并打印修复建议。

## `check-direct-config-key-access.py`

扫描生产 Java 中通过字符串 key 读取配置的写法，输出治理 inventory，覆盖
`Environment#getProperty` / `getRequiredProperty`、`System#getProperty` 和 `@Value("${...}")`。
每条命中给出分类建议（`PROJECT_CONFIG`、`SECRET_CONFIG`、`SPRING_INFRA`、`JVM_SYSTEM`、
`TEST_ONLY`）并标记是否命中白名单。口径见
[`docs/runbook/config-key-access-governance.md`](../../docs/runbook/config-key-access-governance.md)。
扫描排除根目录 `logs/` 的验证快照，保留项目源码及未跟踪源码的检查。

阶段 0（报告模式，当前接入 Full Gate `static-checks`）只输出清单、恒以 `0` 退出，
不阻断历史存量；同时输出 `build/config-key-access.json` 供治理看板使用：

```bash
python3 scripts/ci/check-direct-config-key-access.py --report
python3 scripts/ci/check-direct-config-key-access.py --json build/config-key-access.json
```

阶段 1（增量拦截，已接入 PR Gate `PR_DIRECT_CONFIG_KEY_BASELINE` 和 Full Gate
`FULL_DIRECT_CONFIG_KEY_BASELINE`）只对相对基线新增的高风险 `PROJECT_CONFIG` /
`SECRET_CONFIG` 命中失败，历史存量走 baseline 不阻断。`batch.*` 的 `Environment#getProperty` /
`System#getProperty` 与 `@Value` 都归入 `PROJECT_CONFIG`，因此阶段 2 的“`batch.*` 默认失败”
沿用同一拦截范围；阶段 2 额外要求白名单条目写明原因 / owner / 复查条件，缺项即失败。
收敛一项后从基线删除对应行：

```bash
python3 scripts/ci/check-direct-config-key-access.py --write-baseline \
  docs/governance/direct-config-key-access-baseline.txt
python3 scripts/ci/check-direct-config-key-access.py --check-baseline \
  docs/governance/direct-config-key-access-baseline.txt
```

匹配前先剥离 `//` 与 `/* */` 注释（`strip_comments`）：注释里的 `@Value("${...}")` 只是文档，
不是配置读取。否则迁移说明、反例示例、“已废弃写法”注解都会把检查逼成“删掉解释才过”。
剥离保持等长并保留换行，因此行号与原文一致。基线当前为 **0 项**（存量已全部收敛），
意味着此后任何新增高风险 `batch.*` 读取都会被 PR Gate 直接拦下。

## PMD 参数边界

`config/quality/pmd-ruleset.xml` 按 `docs/coding-conventions.md` §1 执行三档约束：Mapper 公共方法达到5个、Service公共接口达到6个、其它方法达到7个参数时必须封装。Mapper/Service规则只补5/6参数区间，避免与普通方法规则重复报告；构造器、record、DTO与Spring构造注入沿用既有豁免，不用公共上下文容器隐藏依赖。

`python3 -m unittest discover -s scripts/ci/tests -p test_pmd_parameter_policy.py` 使用与根POM相同版本的真实PMD，核对4/5/6/7边界、接口隐式public、普通public/private方法、构造器及record；包含构造器的文件仍必须拦截其它违规方法。本机离线运行需预先缓存该Maven插件；该检查不连接业务数据库。XPath上下文规则参考[PMD官方说明](https://pmd.github.io/pmd/pmd_userdocs_suppressing_warnings.html#the-property-violationsuppressxpath)。

## `check-migration-safety.sh`

通过 Squawk 检查新增迁移及已有迁移中的有效 SQL 改动；纯注释与 `COMMENT ON` 差异不重扫已有 DDL。范围分类的正反例由本地脚本预检及 PR/Full CI 质量守卫测试组执行，也可单独运行：

```bash
python3 -m unittest scripts.ci.tests.test_check_migration_safety
```

## `validate-flyway-schema.sh`

静态检查迁移名称、从 V1 开始的连续性、重复版本、空文件、BOM/CRLF 和已有迁移有效 SQL 漂移。版本缺号会使检查失败；历史注释或 `COMMENT ON` 变更仍会改变 checksum，仅适用于新建空库。版本连续、缺号和重复的正反例接入本地脚本域预检及 PR/Full 的 CI 质量守卫测试组，也可单独执行：

当前本仓 `fix/wdi-current` 发起的 PR 临时设置 `FLYWAY_ALLOW_EXISTING_DDL_CHANGES=true`，只放开已有迁移有效 SQL 漂移阻断，并打印空库部署提示；版本连续性及其他检查不变。其他分支、手动运行和 merge queue 默认严格检查。该 PR 合并后删除工作流例外与脚本开关，避免后续复用同名分支时继续放行。

```bash
python3 -m unittest scripts.ci.tests.test_validate_flyway_schema
```

## `check-test-conventions.py`

按 `docs/coding-conventions.md` §14.4 / §14.5 检查 `src/test/java` 下 Java 测试的两族约定：

源码范围取 Git 已跟踪和未跟踪但未忽略的 Java 文件，覆盖新模块与嵌套 SDK；忽略目录中的本地备份不参与扫描。已跟踪源码不会因新增 `.gitignore` 规则漏检，Git 枚举失败会使检查失败。

| 缺口类型 | 判定 |
|---|---|
| `missing-class-display` | 含测试方法的类（含 `@Nested` 内部类）没有**类级** `@DisplayName` |
| `missing-method-display` | `@Test` / `@ParameterizedTest` / `@RepeatedTest` / `@TestFactory` 方法没有 `@DisplayName` |
| `non-chinese-display` | `@DisplayName` 文本不含中文 |
| `banned-method-name` | 方法名被禁形状：`test` / `test1` / `testXxx` / `test_xxx` / `xxx_test`（**硬失败，存量 0**） |
| `non-preferred-method-name` | 方法名既不是 `shouldXxx...` 也不含下划线（纯 camelCase，走基线） |

实现要点：先把注释、字符串、字符与文本块掩码成等长空白（保留换行），再做括号配对与方法归属，
因此 SQL 文本块里的 `{` / `}`、注释掉的 `@Test` 都不会干扰判定；注解块边界只认**括号深度为 0** 的
`}` / `;` / `{`，否则 `@ValueSource(strings = {...})` 这类注解参数里的数组大括号会让已标注的方法
被误报为缺失。`@DisplayName` 说明范围严格截止到注解右括号，不能借方法正文中的中文放过英文说明。只判形状与是否存在，不评判措辞质量。

```bash
python3 scripts/ci/check-test-conventions.py --report
python3 scripts/ci/check-test-conventions.py --write-baseline \
  docs/governance/test-conventions-baseline.txt
python3 scripts/ci/check-test-conventions.py --check-baseline \
  docs/governance/test-conventions-baseline.txt
```

终态是**全部补齐**（基线归零）；基线只是分批实施的顺序装置（标识忽略行号漂移，收敛一项即从基线消失）。
**PR Gate `PR_TEST_CONVENTIONS` 与 Full Gate `FULL_TEST_CONVENTIONS` 只拦新增缺口**，不阻断历史存量。
基线生成时为 7,694 项：`@DisplayName` 5,432（类级 954 / 方法级 4,473 / 非中文 5）+ 方法命名 2,262；
禁用形状 0 项，`batch-trigger` 已整模块收敛（0 项）。

## `check-db-scripts-safety.sh`

盘点维护/仿真/压测 SQL 与运行脚本中的数据库、文件、S3、Kafka、Redis/Valkey、Docker 和 Kubernetes 删除操作；命令数量新增或扩大必须先审查再更新基线。危险 SQL 还必须有头部风险声明。`ON CONFLICT` 仍提示核对幂等契约。

- **WARN**(不阻断):脚本含 `ON CONFLICT` → 提示核对幂等契约是否受约束变更影响。
- **FAIL**: SQL 含 `DROP` / `TRUNCATE` / `DELETE FROM` 或关键约束变更，且头部没有风险标记(🔴/⚠/DANGER/禁止执行/破坏性…)。

接入 PR Gate 的 SQL safety job；测试 seed 也纳入扫描，因为误连到错误数据库时同样会造成数据损失。

```bash
bash scripts/ci/check-db-scripts-safety.sh
python3 scripts/ci/check-destructive-ops-governance.py
python3 -m unittest scripts/ci/tests/test_sim_runtime_cleanup.py
python3 -m unittest scripts/ci/tests/test_sim_sftp_known_hosts.py
python3 -m unittest scripts/ci/tests/test_sim_export_partition_evidence.py
python3 -m unittest scripts/ci/tests/test_sim_fault_channel_cleanup.py
python3 -m unittest scripts/ci/tests/test_sim_worker_readiness.py
python3 -m unittest scripts/ci/tests/test_heal_stuck_outbox_dry_run.py
python3 -m unittest scripts/ci/tests/test_runtime_warning_audit.py
python3 -m unittest scripts/ci/tests/test_check_destructive_ops_governance.py
```

SIM 的 SFTP 通道夹具从受控容器的公钥建立 `known_hosts`，与输入源文件放在同一共享目录，
供严格主机校验和后续渠道重试使用。失败保留现有配置并清理生成的临时文件；成功后原子替换。
SIM preflight 会检查所选平台库中五类 Worker 的新鲜心跳，且共用一个超时预算；HTTP health 不能替代数据库目标一致性检查。
preflight 与直接 sim 入口还会在清理、reset、重启前校验基础设施 Compose 归属、应用/SDK Kafka 地址及实际发布端口；host/docker 显式模式优先，auto 只识别当前项目（包括暂时停止的受管容器），探测失败不降级为宿主机模式。SFTP 端口从所选映射派生，探针和 Stage 20 的公钥文件按项目分目录。`test_sim_worker_readiness.py` 与 `test_sim_sftp_known_hosts.py` 同时覆盖共享栈存在、错误端点、失败探测、非法端口及路径越界边界。
WARN 审计要求至少一个可读取的应用日志；目录缺失、空目录、仅有被排除日志、盘点或读取失败都返回输入错误，不能写成 0 条告警通过。无 WARN 的正常应用日志仍可通过。按真实文件路径检查规则上下文，支持含空格/冒号的运行目录；`test_runtime_warning_audit.py` 覆盖这些输入边界及原有 EXPECTED/ACTION/UNKNOWN 判定。
该助手新增的一处文件清理已按 `mktemp` 路径来源、失败范围和配置恢复行为审查，
登记为 `scripts/sim/env-common.sh|filesystem-remove=1`；不涉及既有配置、其它输入或目录递归删除。

## `check-db-comment-coverage.sh`

校验本次新增或修改的 Flyway 迁移：新建 `batch` / `archive` 业务表必须在同文件包含
`COMMENT ON TABLE`；`batch` 表中名称命中状态、策略、载荷、幂等、密钥引用、哈希、超时、窗口、
时区、版本、重试、优先级、目标或来源引用等关键语义的新增字段必须包含 `COMMENT ON COLUMN`。
`archive` 镜像字段继承源表语义，只维护表级说明，避免双份字段注释漂移。历史对象的补齐基线和豁免
口径见 `docs/audit/database-and-class-comment-audit-2026-08-21.md`。

```bash
bash scripts/ci/check-db-comment-coverage.sh origin/main
```

## `check-new-table-doc-coverage.py`

对本次新增/修改的 Flyway 迁移做增量检查：新建 `batch` / `archive` 表必须登记在
[`database-schema-guide.md`](../../docs/design/database-schema-guide.md) 的表职责目录中，并以反引号列出准确表名。
分区子表 `PARTITION OF` 不单独登记；同时禁止删除已登记的历史表条目。历史存量不在每个 PR 重扫，
避免把一次增量规则变成全量补档门槛。PR Gate 与 Full CI Gate 均运行该检查；迁移新增表未登记或目录条目被删时阻断。

```bash
python3 scripts/ci/check-new-table-doc-coverage.py --base origin/main
python3 -m unittest scripts/ci/tests/test_check_new_table_doc_coverage.py
```

## `check-required-java-docs.sh`

校验应用入口及标注 `@Configuration`、`@AutoConfiguration`、`@ConfigurationProperties` 的 Spring
类型均有顶层 Javadoc，要求注释说明架构职责，不对 DTO、实体、Mapper、枚举等自解释类型制造模板注释。

```bash
bash scripts/ci/check-required-java-docs.sh
```

## `check-version-alignment.sh`

校验版本一致性:根 `pom.xml <revision>` ↔ helm `Chart.yaml appVersion`(预发态下 appVersion 合法地停在上一 GA,仅 GA 态强制相等)↔ `load-tests/pom.xml`(独立 reactor,但必须继承根 parent 复用版本与安全依赖治理);校验 `.env.*` 的 `*_IMAGE_TAG` 不漂移，并强制 PostgreSQL、Kafka、MinIO、Valkey 的 Testcontainers 镜像与 `.env.example` 对齐。接入 `pr-gate.yml` 的 `static-checks` job。

```bash
bash scripts/ci/check-version-alignment.sh
```

## `check-e2e-run-completeness.sh`

e2e 运行侧闭环守护。`-Dsurefire.failIfNoSpecifiedTests=false` 会把「shard 清单列了某 `*E2eIT`、但因路径/改名没被选中」静默吞成绿色。本脚本要求每个类有唯一报告且至少一个用例实际执行；全类跳过或空报告失败，部分跳过仍单独保留报告。与 `check-e2e-shard-coverage.sh`（静态校验漏项、失效项及重复类）互补；PR 和 Full CI 运行其正反例 `test_e2e_shard_coverage.py`。

当选择器包含 `类名#方法一+方法二` 时，静态守卫核对该类全部测试方法各分配一次；运行侧读取 Surefire XML，逐方法要求集合完全一致且无失败或跳过。方法选择仅支持不带通配符的明确名称，无法识别源码测试时失败关闭。对应正反例由 PR 和 Full CI 执行。

```bash
bash scripts/ci/check-e2e-run-completeness.sh "<逗号分隔类名>" <surefire-reports-dir>
```

### Java 治理测试完整性

`check-java-governance-test-coverage.py` 统一发现 `*ArchTest` 和 `*ConventionTest`。PR、
Full 和 Staging Gate 的 `java-governance` job 单独执行这些测试；业务 unit/IT 分片不再重复执行。
源码从 Git 已跟踪文件及未忽略的未跟踪文件枚举，按 `src/test/java` 边界确定模块；已跟踪文件即使命中
忽略规则仍会检查，当前工作区已删除的文件不再要求报告。未跟踪的忽略目录（包括验证快照）不计入，
真实源码的重复全限定名继续失败。该检查要求可用的 Git 工作树；Git 枚举失败时明确报错，不回退为目录扫描。
`MyBatisSqlAnnotationConventionTest` 扫描 Java 生产和测试源码，禁止 MyBatis SQL 注解、Provider 注解及 `@SelectKey`；注释、字符串和其他同名注解不计入。
`MapperStatusWriteConventionTest` 在该分片扫描全部 Mapper XML，禁止写语句的
`SET`（包括 `ON CONFLICT ... DO UPDATE SET`）回退为固定状态字面量；查询中的共享生命周期集合另由 Mapper 契约测试与枚举精确对照。

```bash
python3 scripts/ci/check-java-governance-test-coverage.py
python3 scripts/ci/check-java-governance-test-coverage.py --verify-reports
```

第二条命令必须在 Maven 执行后运行，任一源码类没有对应 `TEST-<FQCN>.xml` 就失败。
实际执行统一使用 `bash scripts/ci/run-java-governance-tests.sh`；该入口同时接入本地 pre-push，
防止本地与三个在线 workflow 的模块清单和 Maven 参数漂移。pre-commit 只运行源码清单检查，
避免每次提交承担 Maven 构建成本。

## `install-upstream-modules.sh`

将 E2E / staging 所需的上游 Maven 模块安装到 `~/.m2`，避免测试模块重复向 Maven Central 请求内部模块 POM。它保持 `batch-e2e-tests`
排除和 `-DskipTests` 约束不变，并跳过上游应用的 Spring Boot executable jar repackage：E2E 使用模块普通 jar；需要 `-exec.jar` 的重启恢复场景会在测试自己的子构建中生成，不依赖上游预构建产物。对 Maven Central 的临时 5xx / 传输失败使用 Maven
强制更新、传输层重试和最多三轮退避重试，避免依赖下载瞬时失败被误报为 E2E 业务失败。

```bash
bash scripts/ci/install-upstream-modules.sh
```

## `check-infrastructure-abstraction-boundaries.py`

diff-only 守护。application、domain、service、web 主代码相对 PR merge-base 新增的引用禁止直接引用或 import
Kafka、Redis、JDBC、Quartz、RestClient/WebClient、AWS SDK 等具体基础设施类型；应依赖
Port、Adapter 或业务抽象。config、infrastructure、mapper、support、shared client、common 底层适配包
继续拥有实现细节。

```bash
python3 scripts/ci/check-infrastructure-abstraction-boundaries.py --base origin/main
```

该守护已接入 PR gate。历史存量按治理文档分批收敛，不在本守护里一次性清零。
基线按引用语句及出现次数抵扣,既有引用的纯日志修改或空白排版不触发历史债务阻断;新增引用或重复新增同一引用仍阻断。新文件按空基线检查。回归测试为 `python3 -m unittest scripts/ci/tests/test_check_infrastructure_abstraction_boundaries.py`。

## `check-direct-client-boundaries.py`

全量扫描生产 Java 源码，禁止 application/domain 业务层直接导入 Redis、Kafka、JDBC 等具体客户端。
测试源码、`infrastructure`、`config`、`support`、Kafka consumer 入口和运维 lag 探针按职责放行。

```bash
python3 scripts/ci/check-direct-client-boundaries.py
```

该守护已接入 PR gate 和 full gate，用于防止缓存、MQ、数据访问实现细节重新漂回业务层。

## `check-java-logging-governance.py`

禁止 Java 应用和测试直接写 `System.out` / `System.err`，并禁止直接调用
`printStackTrace()`。生产日志不得直接拼接异常 `getMessage()` / `toString()`：预期 fallback、
重试或吞异常使用 `SwallowedExceptionLogger.summary()` 输出脱敏、单行、限长摘要；未预期故障将
`Throwable` 作为 SLF4J 最后一个参数保留完整堆栈。Java SDK 因 ADR-035 不依赖平台 common，
使用 SDK 内部等价的 `ExceptionLogSummary`。安全扫描 CLI 的终端输出是用户界面，按显式路径白名单保留。

```bash
python3 scripts/ci/check-java-logging-governance.py
python3 -m unittest scripts/ci/tests/test_check_java_logging_governance.py
```

治理脚本及其自测已接入 PR、full CI 和本地 pre-commit 的 Java 变更检查。

## `check-java-readability.py`

检查生产 Java 主代码的可读性边界：禁止使用隐式 `var`，并要求普通 Spring
`@Configuration` 显式声明 `proxyBeanMethods = false`。前者让业务、状态机和基础设施边界
显式表达类型；后者避免没有 bean 方法互调需求的配置类重新引入 CGLIB 代理。
脚本会忽略注释、Javadoc、字符串、字符字面量和测试代码，不会误报文档示例或业务文本。
默认枚举当前工作区已跟踪和未跟踪的生产 Java 源码，排除忽略文件和已删除路径；
可用 `test_java_worktree_source_inventory.py` 验证文件范围与拦截行为。
门禁输出显式标注 `scope=explicit-type,configuration-proxy`；通过不代表嵌套调用、参数合法状态、
多个 `null` 或抽象适度性已经完成语义审查。

`PositionalArgsConventionTest` 另按 PR 基线检查新增/修改的生产 Java 行：方法实参、构造实参或
`return` 中存在至少三层连续调用时阻断。Javac AST 扫描不计入 fluent receiver 链和 lambda 函数体，
历史未改代码与测试 fixture 不纳入该门禁；只用于定位高置信候选，不替代语义评审。

```bash
python3 scripts/ci/check-java-readability.py
```

## `check-java-lombok-injection.py`

检查生产 Java 变更中的依赖注入与 logger 约定：禁止 `@Autowired` / `@Inject` / `@Resource`
字段或方法注入；普通生产类使用 Lombok `@Slf4j`。构造器注入允许；有多个构造器且 Spring
需要明确选择时，保留构造器上的 `@Autowired`。logger 例外按已登记的文件、调用形式和数量核准。
默认全量扫描用于 Full Gate；PR 使用基线增量，
本地提交前只检查暂存区涉及的生产 Java 文件。手写访问器不做文本式自动禁止，需人工确认没有与
Lombok 生成相同语义的重复实现。

扫描器回归测试：

```bash
python3 -m unittest scripts/ci/tests/test_check_java_lombok_injection.py
```

```bash
python3 scripts/ci/check-java-lombok-injection.py
python3 scripts/ci/check-java-lombok-injection.py --base-ref origin/main
python3 scripts/ci/check-java-lombok-injection.py --staged
```

UTF-8 检查器测试：

```bash
python3 -m unittest scripts/ci/tests/test_check_utf8_encoding.py
```

## `check-java-text-block-style.py`

检查全仓 Java text block 的基础排版：结束 `"""` 必须独立占一行，避免多行
SQL / JSON / XML / Lua fixture 退回难读的行尾分隔符形式。脚本不判断所有
`"\n" +` 拼接是否必须改成 text block，避免误报签名串、canonical request 和动态协议拼接。
默认枚举已跟踪及未跟踪、未被 Git 忽略且实际存在的 Java 文件，包含测试；跳过工作区已删除路径。
显式传入文件列表时只检查该范围。使用词法状态区分注释、普通字符串和文本块，单行先关闭再开启的三元文本块分别处理；对应正反例见 `test_check_java_text_block_style.py`。临时 Git 仓正反例纳入 `test_java_worktree_source_inventory.py`。

```bash
python3 scripts/ci/check-java-text-block-style.py
python3 -m unittest scripts/ci/tests/test_java_worktree_source_inventory.py
```

## `check-java-structured-string-concat.py`

阻止新增的多行结构化字符串拼接。检查 JSON、XML、YAML、SQL、Markdown、CSV、PEM、INI、properties、Lua 和 Shell 模板；只报告相对基线新增的候选，
避免存量一次性阻塞，也不把签名串、普通动态消息或循环生成内容机械改成文本块。
默认与 `HEAD` 对比时纳入未跟踪新文件、排除忽略和已删除文件；显式 `--base-ref` 仍仅比较该提交至 `HEAD`，
显式文件列表仍保持指定范围。枚举与拦截行为由 `test_java_worktree_source_inventory.py` 补充验证。XML 候选须从标签结构开始；包含脱敏说明的普通诊断字符串不当作 XML，正反例由 `test_check_java_structured_string_concat.py` 验证。

```bash
python3 scripts/ci/check-java-structured-string-concat.py --base-ref origin/main
python3 -m unittest scripts/ci/tests/test_check_java_structured_string_concat.py
```

本地预提交会对暂存 Java 文件相对 `HEAD` 检查；PR CI 相对目标分支检查，Full Gate 同样运行该门禁及其测试。

## `check-java-suppression-registry.py`

检查生产 Java 的 `@SuppressWarnings`、SpotBugs `@SuppressFBWarnings`、Sonar 行注释 `//NOSONAR`，以及模块
`src/main/resources` 下所有 YAML 中的 IDE `noinspection` 是否使用已审核的精确规则，并按路径和数量对账。它不要求 suppression 数量归零，
但会阻断未登记的新规则、位置或数量，防止静态检查例外在重构中无理由扩散。规则用途和移除条件见
`docs/standards/java-suppression-registry.md`。

```bash
python3 scripts/ci/check-java-suppression-registry.py
python3 -m unittest scripts/ci/tests/test_check_java_suppression_registry.py
```

## `report-java-readability-inventory.py`

生成 Java 可读性治理候选快照，覆盖 CGLIB 自注入、大类、public Map 契约、
`@SuppressWarnings`、配置类和宽参数显式例外。该脚本只报告候选，不自动改写源码，
也不把数量本身判定为缺陷。

```bash
bash scripts/python.sh scripts/ci/report-java-readability-inventory.py
bash scripts/python.sh scripts/ci/report-java-readability-inventory.py \
  --output docs/stats/java-readability-inventory.md
bash scripts/python.sh scripts/ci/report-java-readability-inventory.py \
  --check docs/stats/java-readability-inventory.md
```

`--check` 是阶段 7 的防漂移门禁：生产 Java 文件增删或候选统计变化时，必须重新生成并审核快照，
不能让过期报告继续作为验收证据。

## `check-docs-structure.py`

校验 `docs/` 一级目录入口、各层 README 的真实文档/子目录跳转及用途（含归档）、当前文档的仓库内相对链接、根 README 文档导航入口、
`docs/README.md` 文档治理入口，以及禁止提交的 Finder 元数据和带日期本机验收报告。归档正文和外部
URL 不联网检查，避免历史快照或第三方站点波动造成误报。该检查已接入 PR gate 和 full gate。

```bash
bash scripts/python.sh scripts/ci/check-docs-structure.py
```

## `check-doc-timestamp-policy.py`

校验 `docs/` 文件名是否符合“当前事实稳定命名、一次性报告带日期、历史材料归档”的策略。
当前事实目录（如 `architecture/design/runbook/sdk`）不允许继续新增带日期文件名；报告、审计、
复核、验证、技术试验和阶段 plan 使用完整 YYYY-MM-DD 基线日期。原文档资产持续更新，
增量报告按日期留痕；归档快照必须保留原日期。统一英文 kebab-case、主题在前日期在后，
多版本同时保留版本号，跨日记录使用完整起讫日期；月度/H2 标记不替代日期；README、ADR 等固定约定名保留。
脚本只检查可机械判断的命名约束，文档职责及历史/当前归属仍须按正文复核。

```bash
python3 scripts/ci/check-doc-timestamp-policy.py
```

## `check-testcontainers-reuse-label.py`

守护 Testcontainers 复用容器的清理谓词。`withReuse(true)` 的容器由 Testcontainers 打上
`org.testcontainers.hash` 标签（定义在 `GenericContainer`），复用逻辑只认该标签；清理脚本
若改用 `reuse-hash` 之类的字面量判定「复用容器要保留」，谓词恒不匹配，会把正在运行的复用
容器当孤儿删除，`withReuse` 的跨 JVM 复用加速静默失效。

规则：

- 引用 `org.testcontainers` 的 Shell 脚本不得出现 `reuse-hash` 字面量（产物中不存在）。
- 过滤 `label=org.testcontainers=true` 的 Shell 脚本必须同时引用 `org.testcontainers.hash`，
  否则无法把复用容器与真孤儿区分开。

```bash
python3 scripts/ci/check-testcontainers-reuse-label.py
python3 -m unittest scripts/ci/tests/test_check_testcontainers_reuse_label.py
```

已接入 `pr-gate.yml`、`full-ci-gate.yml` 的静态检查和本地 `scripts/local/pre-commit-checks.sh`。

### SIM 故障渠道的配置收尾

容量脚本 `run-control-plane-worker-benchmark.sh` 的自动清理用 `test_control_plane_benchmark_cleanup.py` 验证：任一步清理失败不得返回成功；两步都应尝试，主流程失败时保留原退出码。该用例接入本地 pre-commit 脚本测试组与 PR / Full 的清理边界门禁；PR 按脚本、数据库或 CI 变更执行。

同组 `test_control_plane_mixed_pressure.py` 验证 Workflow ID/运行标识传递、失败关闭、完整子 Job 数量和业务日分配。日期用例用 `javac` / `java` 编译并执行仿真器的实际 feeder 方法，保留每模块分发文件序号，验证 Atomic/Trigger 共享 Workflow 的并发日期唯一性；它不执行 Gatling 或真实容量测量。真实 PostgreSQL 指标与日期夹具另行验证，入口见 `load-tests/README.md`。

同组 `test_dr_drill_rls_rebuild.py` 提取真实DR脚本的RLS重建函数与结果处理，Docker全部为替身，验证重建失败/脚本缺失必须使演练失败并保留恢复库、成功不强制保留；已接入本地pre-commit与PR/Full脚本门禁。它不执行备份、恢复或测量RPO/RTO。

Stage 5b 只启用具有专属名称 `[SIM:dispatch-stage5b]` 的 `tb/tb_api_fail` 故障渠道；目标 endpoint 由 `sim_mockserver_base_url` 按当前宿主机/Compose 运行方式解析，并作为 psql 变量传入独立 fixture SQL。正常、失败和信号退出后由 `scripts/local/sql/disable-stage5b-fault-channel.sql` 按租户、code、名称和类型精确停用，不依赖固定宿主机端口，不删除故障与运行历史。场景归属记录在渠道名称，不能把测试元数据混进受运行时白名单约束的配置 JSON。完整 harness 的 `prepare_sim_inputs` 先执行同一 SQL，处理上次被强制终止的残留。清理失败会返回失败，不能只打印提示后通过。对应脚本自测与真实 PostgreSQL 渠道健康集成测试共同核对其它租户不受影响，以及停用/软删除后不参与启动健康统计。

运行 WARN 策略可在第六列指定同一日志文件必须存在的上下文正则。Atomic 的固定 ADR-029 提醒仅在该日志明确只有 `local` profile、且执行器列表为当前 SIM 的 shell/sql/stored-proc 时按本地提醒处理；缺失上下文、生产 profile 或新增执行器仍阻断。空白名单放行全部已从 EXPECTED 收紧为 ACTION_REQUIRED，不能用提醒规则覆盖实际配置缺口。九项审计自测验证这些放行和拦截边界；不由本地提醒分类推导生产隔离已验证。

### SIM Worker 配置与 checkpoint 夹具收尾

`test_sim_profile_ownership.py` 在现有 PR / Full Gate 脚本自测组运行。它核对 harness 与独立场景的重启归属、恢复时健康等待、原始失败码保留、清理失败阻断，以及选定阶段的参数和结果范围。入口消息实际在 `set -u` 下执行，防止未加花括号的变量与中文标点连读。checkpoint 夹具只恢复自己注入的 chunk 值；并发修改、SQL 失败及 Worker 未就绪都不能静默通过。历史 `pipeline_progress` 不再按租户/阶段批量删除，当前请求仍用自己的 pipeline ID 查询进度。脚本自测与真实场景复验分别记证据。

### 分发健康恢复与仿真协议

`DispatchChannelHealthConfigurationTest` 实际加载 Dispatch 应用 YAML 并绑定类型属性，独立断言六类恢复探针，防止 YAML 把 API 等类型的默认值覆盖掉。此项属于现有 Dispatch Java shard。`test_sim_fault_channel_cleanup.py` 同时核对每条业务 MockServer 规则有明确的 HEAD/GET 健康规则、稳定 ID、真实 500 断言，以及健康缺失/数据库错误时关闭失败。模拟器装配只按稳定 ID 更新本仓库规则，不使用全局 reset/clear；Stage 14 等 Worker 真实探针写入 HEALTHY 后才发起业务，保留原准入守卫和故障历史。

索引守卫正反例：`python3 -m unittest scripts/ci/tests/test_check_docs_structure.py`。

共享测试资产代码量归属检查：`python3 -m unittest scripts/ci/tests/test_lean_loc_fixture_group.py`；根 test-data/ 计入测试组，不能因移出 docs/ 而进入生产代码组。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [bump-version.sh](bump-version.sh) | 一条命令升级应用版本，同步关键落点。 |
| [check-application-governance.py](check-application-governance.py) | 应用治理契约。 |
| [check-biz-table-tenant-rls.py](check-biz-table-tenant-rls.py) | 业务表多租户与 RLS。 |
| [check-changelog-sync.py](check-changelog-sync.py) | Changelog 同步。 |
| [check-code-doc-references.py](check-code-doc-references.py) | 代码文档引用。 |
| [check-comment-language.py](check-comment-language.py) | 检查新增的纯英文说明性注释。 |
| [check-config-defaults-sync.py](check-config-defaults-sync.py) | 配置默认值同步。 |
| [check-config-governance.py](check-config-governance.py) | 配置治理注册表。 |
| [check-console-openapi-paths.py](check-console-openapi-paths.py) | Console controller 与 OpenAPI 路由同步。 |
| [check-db-comment-coverage.sh](check-db-comment-coverage.sh) | 数据库注释覆盖。 |
| [check-db-scripts-safety.sh](check-db-scripts-safety.sh) | 数据库脚本安全。 |
| [check-dependency-boundaries.py](check-dependency-boundaries.py) | 模块依赖边界。 |
| [check-dependency-licenses.sh](check-dependency-licenses.sh) | 第三方依赖许可证审查。 |
| [check-destructive-ops-governance.py](check-destructive-ops-governance.py) | 高风险操作清单。 |
| [check-diff-coverage.py](check-diff-coverage.py) | 使用 JaCoCo XML 校验本次变更新增/修改的可执行 Java 行覆盖率。 |
| [run-pr-coverage-integration.sh](run-pr-coverage-integration.sh) | 按 PR 分片运行业务集成场景并追加 JaCoCo 数据。 |
| [check-direct-client-boundaries.py](check-direct-client-boundaries.py) | 业务层直连客户端边界。 |
| [check-direct-config-key-access.py](check-direct-config-key-access.py) | 直接配置 Key 读取快照。 |
| [check-doc-timestamp-policy.py](check-doc-timestamp-policy.py) | 文档日期命名策略。 |
| [check-docs-structure.py](check-docs-structure.py) | 文档结构与内部链接。 |
| [check-e2e-run-completeness.sh](check-e2e-run-completeness.sh) | 守护:e2e shard 实际跑数 == 清单声明数(运行侧闭环)。 |
| [check-e2e-shard-coverage.sh](check-e2e-shard-coverage.sh) | E2E shard 覆盖。 |
| [check-empty-checks.py](check-empty-checks.py) | 集中式空值检查。 |
| [check-env-file-shell-safety.py](check-env-file-shell-safety.py) | 环境文件 Shell 安全。 |
| [check-env-variable-governance.py](check-env-variable-governance.py) | 环境变量治理。 |
| [check-feature-switch-registry.py](check-feature-switch-registry.py) | 功能开关注册表。 |
| [check-five-worker-parity.py](check-five-worker-parity.py) | 五类 Worker 配置一致性。 |
| [check-flaky-test-governance.py](check-flaky-test-governance.py) | flaky 测试隔离治理。 |
| [check-github-action-pinning.py](check-github-action-pinning.py) | GitHub Actions SHA 固定。 |
| [check-governance-baseline-expansion.py](check-governance-baseline-expansion.py) | 治理基线扩张审批。 |
| [check-hardcoded-runtime-config.sh](check-hardcoded-runtime-config.sh) | 运行时配置集中管理。 |
| [check-helm-env-sync.py](check-helm-env-sync.py) | Helm 环境变量同步。 |
| [check-helm-prometheusrule-sync.sh](check-helm-prometheusrule-sync.sh) | Helm 告警规则同步。 |
| [check-infrastructure-abstraction-boundaries.py](check-infrastructure-abstraction-boundaries.py) | 基础设施抽象边界。 |
| [check-infrastructure-utf8.py](check-infrastructure-utf8.py) | 基础设施 UTF-8 配置。 |
| [check-integration-test-coverage.py](check-integration-test-coverage.py) | 集成测试覆盖。 |
| [check-java-contract-governance.py](check-java-contract-governance.py) | Java 固定契约与协议值治理。 |
| [check-java-fqn-diff.py](check-java-fqn-diff.py) | 本地推送时检查新增 Java 代码的全限定类型名。 |
| [check-java-governance-test-coverage.py](check-java-governance-test-coverage.py) | Java 治理测试清单。 |
| [check-java-logging-governance.py](check-java-logging-governance.py) | Java 日志与异常输出治理。 |
| [check-java-lombok-injection.py](check-java-lombok-injection.py) | Java Lombok 与依赖注入规约。 |
| [check-java-readability.py](check-java-readability.py) | Java 可读性约定。 |
| [check-java-structured-string-concat.py](check-java-structured-string-concat.py) | Java 结构化多行字符串。 |
| [check-java-suppression-registry.py](check-java-suppression-registry.py) | Java 抑制项注册表。 |
| [check-java-text-block-style.py](check-java-text-block-style.py) | Java 文本块格式。 |
| [check-keda-autoscaling.py](check-keda-autoscaling.py) | KEDA 自动伸缩契约。 |
| [check-license-compliance.sh](check-license-compliance.sh) | 开源许可证与 SBOM。 |
| [check-loc-snapshot.py](check-loc-snapshot.py) | 代码量快照同步。 |
| [check-log-lifecycle.sh](check-log-lifecycle.sh) | 本地日志生命周期。 |
| [check-mapof-null-values.py](check-mapof-null-values.py) | 不可变集合空值风险。 |
| [check-migration-safety.sh](check-migration-safety.sh) | 数据库迁移安全。 |
| [check-module-test-coverage.sh](check-module-test-coverage.sh) | Maven 测试模块覆盖。 |
| [check-mybatis-generated-key-columns.py](check-mybatis-generated-key-columns.py) | MyBatis generated key 列限制。 |
| [check-new-table-doc-coverage.py](check-new-table-doc-coverage.py) | 新增数据库表文档登记。 |
| [check-no-enable-preview.sh](check-no-enable-preview.sh) | 禁止生产启用 JDK preview。 |
| [check-no-positional-insert-select-star.py](check-no-positional-insert-select-star.py) | 禁止位置式 INSERT SELECT *。 |
| [check-no-silent-disabled-tests.sh](check-no-silent-disabled-tests.sh) | 测试禁用原因检查。 |
| [check-observability-contract.py](check-observability-contract.py) | 日志与遥测交付契约。 |
| [check-openapi-breaking.sh](check-openapi-breaking.sh) | OpenAPI 破坏性变更。 |
| [check-pipeline-summary-keys.py](check-pipeline-summary-keys.py) | Pipeline 摘要键契约。 |
| [check-postgres-client-fallback.sh](check-postgres-client-fallback.sh) | PostgreSQL 客户端 fallback。 |
| [check-production-capacity-governance.py](check-production-capacity-governance.py) | 生产容量治理自动化。 |
| [check-production-overlay-safety.py](check-production-overlay-safety.py) | 生产 Helm overlay 安全。 |
| [check-readiness-doc-sync.py](check-readiness-doc-sync.py) | Readiness 契约文档同步。 |
| [check-repository-hygiene.py](check-repository-hygiene.py) | 检查仓库中的本机产物与受控文件布局。 |
| [check-required-java-docs.sh](check-required-java-docs.sh) | Java 架构文档完整性。 |
| [check-sbom-sync.sh](check-sbom-sync.sh) | SBOM 依赖快照同步。 |
| [check-schema-governance-assets.py](check-schema-governance-assets.py) | 校验数据库结构治理巡检入口、SQL 资产和文档索引的一致性。 |
| [check-script-governance.py](check-script-governance.py) | 检查脚本头注释、脚本索引与 CI 工作流索引。 |
| [check-sdk-config-env-parity.py](check-sdk-config-env-parity.py) | SDK 配置环境一致性。 |
| [check-sdk-runtime-alignment.py](check-sdk-runtime-alignment.py) | SDK 与前端运行时版本对齐。 |
| [check-shell-linux-portability.py](check-shell-linux-portability.py) | Shell Linux 兼容性。 |
| [check-shell-scripts.sh](check-shell-scripts.sh) | Shell 语法与 ShellCheck。 |
| [check-slo-sli-catalog.py](check-slo-sli-catalog.py) | 校验 SLO / SLI 目录、Runbook 入口和治理文档的一致性。 |
| [check-soft-gate-governance.py](check-soft-gate-governance.py) | 软门禁治理。 |
| [check-sql-config-boundaries.py](check-sql-config-boundaries.py) | 阻止 Shell 脚本新增内联 SQL，并约束历史债务只能减少。 |
| [check-sql-config-boundaries.sh](check-sql-config-boundaries.sh) | SQL 与配置边界。 |
| [check-terminology-doc-sync.py](check-terminology-doc-sync.py) | 核心术语文档同步。 |
| [check-test-config-sources.py](check-test-config-sources.py) | 测试配置事实来源。 |
| [check-test-conventions.py](check-test-conventions.py) | 测试 @DisplayName 约定。 |
| [check-testcontainers-reuse-label.py](check-testcontainers-reuse-label.py) | Testcontainers 复用标签判定。 |
| [check-trivy-ignore-expiry.py](check-trivy-ignore-expiry.py) | Trivy 忽略项有效期。 |
| [check-utf8-encoding.py](check-utf8-encoding.py) | 仓库文本 UTF-8 编码。 |
| [flyway_sql_diff.py](flyway_sql_diff.py) | 比较 Flyway 迁移有效 SQL，识别纯注释 / COMMENT ON 元数据改动，供迁移安全与 Flyway 校验复用。 |
| [check-version-alignment.sh](check-version-alignment.sh) | 应用与基础设施版本对齐。 |
| [collect-flaky.py](collect-flaky.py) | 汇总 Surefire 与 Failsafe 报告中的不稳定测试。 |
| [collect-flaky.sh](collect-flaky.sh) | surefire/failsafe flaky 汇总薄封装。 |
| [compare-sbom.py](compare-sbom.py) | 比较两份 CycloneDX SBOM 的依赖语义，忽略跨环境的不稳定展示字段。 |
| [daily-validation-change-gate.py](daily-validation-change-gate.py) | 仅在北京时间当天存在代码或配置变更时运行 nightly 仿真验证。 |
| [detect-change-scope.py](detect-change-scope.py) | 分类变更文件并输出本地与 CI 门禁的执行范围。 |
| [feature_switch_registry.py](feature_switch_registry.py) | 提供功能开关注册表的加载与解析能力。 |
| [install-upstream-modules.sh](install-upstream-modules.sh) | 安装 E2E 与治理测试依赖的上游 Maven 模块。 |
| [license-allowlist.sh](license-allowlist.sh) | 提供依赖许可证白名单的公共判定函数。 |
| [prepare-platform-release.py](prepare-platform-release.py) | 核对双仓版本并计算下一统一版本及 Release 准备参数。 |
| [report-bounded-context-dependencies.sh](report-bounded-context-dependencies.sh) | 报告模块之间的限界上下文依赖。 |
| [report-ci-quality-trends.py](report-ci-quality-trends.py) | 生成测试质量快照和 Full Gate 历史趋势报告。 |
| [report-java-readability-inventory.py](report-java-readability-inventory.py) | 生成 Java 可读性候选清单并核对当前快照。 |
| [run-critical-mutation.sh](run-critical-mutation.sh) | 对关键状态转换与生命周期映射运行 PIT 变异测试。 |
| [run-flaky-quarantine.sh](run-flaky-quarantine.sh) | 执行已登记的隔离测试并记录其真实结果。 |
| [run-full-regression.sh](run-full-regression.sh) | CI / staging 统一回归入口。 |
| [run-gate.sh](run-gate.sh) | 按统一职责分组和模式执行门禁并传播结果。 |
| [run-java-governance-tests.sh](run-java-governance-tests.sh) | Java 架构与约定检查。 |
| [run-sdk-happy-eyeballs-gate.sh](run-sdk-happy-eyeballs-gate.sh) | 执行五语言 SDK 的单栈、双栈与黑洞连接矩阵。 |
| [run-sdk-live-transport-gate.sh](run-sdk-live-transport-gate.sh) | 执行五语言 SDK 真实传输层契约门禁。 |
| [run-sdk-orchestrator-e2e.sh](run-sdk-orchestrator-e2e.sh) | 五语言 BYO SDK 样例 worker 对真实 Orchestrator/Trigger 的端到端验证。 |
| [run-staging-live-smoke.sh](run-staging-live-smoke.sh) | 调用统一回归入口执行 staging 升级与回滚 smoke。 |
| [sdk-happy-eyeballs-fixture.py](sdk-happy-eyeballs-fixture.py) | 为五语言 SDK 提供真实 loopback socket 的 Happy Eyeballs 故障矩阵。 |
| [security-scan.sh](security-scan.sh) | 编排密钥、依赖、代码、镜像与动态安全扫描。 |
| [select-affected-tests.py](select-affected-tests.py) | Test Impact Analysis (TIA) POC — 测试类粒度；从 Git 跟踪及未忽略的未跟踪文件枚举当前源码，按实际顶层测试类名输出，正反例 `python3 -m unittest scripts/ci/tests/test_select_affected_tests.py`。变更范围仅比较指定 Git 提交且不覆盖配置、SQL 等变更，不作为完整回归或 PR 选测门禁。 |
| [select-affected-tests.sh](select-affected-tests.sh) | Test Impact Analysis (TIA) POC — bash 入口,封装 python 实现。 |
| [sql/count-sdk-worker.sql](sql/count-sdk-worker.sql) | CI 门禁及运行验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/insert-sdk-api-key.sql](sql/insert-sdk-api-key.sql) | CI 门禁及运行验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-latest-sdk-job-status.sql](sql/select-latest-sdk-job-status.sql) | CI 门禁及运行验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-sdk-job-diagnostics.sql](sql/select-sdk-job-diagnostics.sql) | CI 门禁及运行验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-sdk-worker-diagnostics.sql](sql/select-sdk-worker-diagnostics.sql) | CI 门禁及运行验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [validate-flyway-schema.sh](validate-flyway-schema.sh) | Flyway 迁移静态校验。 |
| [validate-kafka-topics.sh](validate-kafka-topics.sh) | 核对 Kafka topic 字面量与权威常量的对应关系。 |

- [全部 GitHub Actions 工作流](../../.github/workflows/README.md)：按触发条件和职责查找每条 CI/交付流程。
- [共享 Actions](../../.github/actions/README.md)：构建环境、Python 依赖和变更范围复用步骤。

- [CI 历史基线](baselines/README.md)：内联 SQL、Java 抑制与高风险操作预算及其收紧规则。


## 辅助资产与本轮守卫测试

- [FlywayMigrate.java](FlywayMigrate.java)：在隔离CI数据库执行正式迁移的Java启动工具；不属于Shell/Python头注释格式检查。
- [dependency-check-suppressions.xml](dependency-check-suppressions.xml)：依赖扫描的精确抑制配置，按原评审与有效期维护。
- `python3 -m unittest scripts/ci/tests/test_check_script_governance.py scripts/ci/tests/test_check_docs_structure.py scripts/ci/tests/test_document_naming_policy.py scripts/ci/tests/test_sql_config_boundaries.py scripts/ci/tests/test_detect_change_scope.py`：覆盖漏字段、纯英文说明、失效/漏登记链接、日期分类、基线搬迁和跨目录范围路由。已接入现有PR/Full/local测试组。
- Pipeline源码清单改用Git可版本化文件，包含未跟踪生产源码，排除被忽略的历史副本；正反例见 `tests/test_check_pipeline_summary_keys.py`，不能以移除真实源码命中获得通过。
- Pipeline摘要键R2只识别Java代码中的真实键常量引用：屏蔽字符串、字符和文本块；裸常量须来自已知键类的静态导入或键类自身，导入声明本身不算读写。状态枚举和同名展示文本不算键引用；显式限定、静态导入及通配静态导入的真实混用仍须拦截。规则回归运行 `python3 -m unittest scripts/ci/tests/test_check_pipeline_summary_keys.py`。

### SIM 导入前工作簿准备回归

`FULL_SIM_XLSX_FIXTURE_TEST` 同时执行 `test_fix_fixture_xlsx.py`、`test_sim_tenant_fixture.py` 与 `test_config_package_fixture_generators.py`，覆盖 schema、规范渠道键、host/docker 私有上传副本、内容指纹和退出清理。原 Compose 模板不得写回宿主地址。新增的三组清理操作仅针对本轮 mktemp 目录和 O_EXCL 创建的精确文件，已审查目标、失败清理与邻居保留测试后逐项登记；未全量刷新破坏性预算。


### Helm 双 Release 监控范围回归

`python3 -m unittest scripts/ci/tests/test_helm_service_monitor_scope.py` 渲染同命名空间两个 Release,
核对应用与 Collector 的 ServiceMonitor 只匹配本 Release 的 Service, 并核对 Console/应用 target
使用同一维护范围。需要本地 Helm; 不操作真实集群, 不替代 Prometheus 采集或最终收件验证。
