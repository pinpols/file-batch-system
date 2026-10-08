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
| 脚本与仓库 | `check-shell-scripts.sh`、`check-shell-linux-portability.py`、`check-script-governance.py`、`check-destructive-ops-governance.py`（删除类命令增量基线）、`check-repository-hygiene.py`、`check-env-file-shell-safety.py`、`check-hardcoded-runtime-config.sh`、`check-utf8-encoding.py`（全仓 UTF-8 字节扫描）、`check-testcontainers-reuse-label.py`（Testcontainers 复用容器清理谓词）、`check-github-action-pinning.py`（外部 Action 固定 40 位 SHA并保留版本注释）、`check-soft-gate-governance.py`（软门禁责任与期限） |
| 配置与部署 | `check-config-defaults-sync.py`、`check-config-governance.py`、`check-direct-config-key-access.py`、`check-env-variable-governance.py`、`check-feature-switch-registry.py`、`check-five-worker-parity.py`、`check-keda-autoscaling.py`、`check-helm-env-sync.py`、`check-infrastructure-utf8.py`（Compose/Dockerfile/Helm/Testcontainers locale 与数据库编码）、`check-production-capacity-governance.py`、`check-production-overlay-safety.py`、`check-version-alignment.sh`、`validate-kafka-topics.sh`（全仓 topic 字面量 ↔ `BatchTopics.java`：env 模板 / `batch-defaults.yml` / helm / init 脚本 / load-tests） |
| 数据库与 SQL | `check-biz-table-tenant-rls.py`、`check-db-comment-coverage.sh`、`check-db-scripts-safety.sh`、`check-migration-safety.sh`、`check-mybatis-generated-key-columns.py`、`check-no-positional-insert-select-star.py`、`check-postgres-client-fallback.sh`、`check-schema-governance-assets.py`、`check-sql-config-boundaries.py`、`check-sql-config-boundaries.sh`、`validate-flyway-schema.sh` |
| API 与兼容 | `check-console-openapi-paths.py`、`check-openapi-breaking.sh` |
| Java 质量 | `check-empty-checks.py`、`check-java-lombok-injection.py`、`check-java-logging-governance.py`、`check-java-readability.py`、`check-java-text-block-style.py`、`check-java-structured-string-concat.py`（增量阻止结构化多行模板拼接）、`check-java-suppression-registry.py`、`check-mapof-null-values.py`、`check-pipeline-summary-keys.py`（stage 摘要键 / 续跑回灌键 / 前端计数键契约）、`check-required-java-docs.sh` |
| 测试完整性 | `check-e2e-run-completeness.sh`、`check-e2e-shard-coverage.sh`、`check-integration-test-coverage.py`、`check-module-test-coverage.sh`、`check-no-silent-disabled-tests.sh`、`check-test-conventions.py`（中文 `@DisplayName` 类级/方法级 + 测试方法命名，增量拦截）、`check-flaky-test-governance.py`、`check-diff-coverage.py`、`run-critical-mutation.sh`、`report-ci-quality-trends.py` |
| 安全与许可 | `check-dependency-licenses.sh`、`license-allowlist.sh`、`check-license-compliance.sh`、`check-sbom-sync.sh`、`check-trivy-ignore-expiry.py` |
| 观测 | `check-helm-prometheusrule-sync.sh`、`check-log-lifecycle.sh`、`check-observability-contract.py` |

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

Git hook 与 GitHub workflow 的门禁入口统一输出 `状态 | code | gate | exit_code | action`；跳过结果在固定字段后追加 `reason`。具体诊断仍由检查脚本输出，最终状态行由 `scripts/lib/gate-result.sh` / `scripts/ci/run-gate.sh` 生成。workflow 中不要直接调用门禁脚本；多项检查要逐项调用 `gate_run`，避免一项失败掩盖同一步其余门禁的独立状态。扫描器报告、测试清单和运行进度不是门禁状态行，不强行改成该格式。

PR 与 Full Gate 的静态检查 job 设置 `BATCH_GATE_COLLECT=1`。业务门禁失败时
`gate_run` 会记录错误并继续执行后续检查，末尾由 `gate_assert_collected` 汇总全部失败后
统一返回非零；checkout、运行时安装等前置环境步骤仍保持立即失败。本地钩子未设置该变量，
继续采用首错即停。

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

预推送模块选择由 `tests/test_pre_push_module_selection.py` 守护：保留 `batch-e2e-tests`
等带数字的完整模块名、同一 Worker 聚合模块去重，不再静默截断超过五个的改动模块。
此测试接入本地 pre-commit 和 PR / Full 的 CI 质量守护组。
`test_daily_validation_workflow.py` 在 PR / Full 的 CI 质量守护组执行，
防止 strict 改回隐式成功依赖或吞掉失败退出码。

```bash
python3 -m unittest scripts/ci/tests/test_daily_validation_workflow.py
```

`check-sql-config-boundaries.py` 是同名 `.sh` 稳定入口的实现，两者都登记，避免包装层与实现层单独漂移。

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

探测器不属于某个具体业务门禁，后续 workflow 应复用该 composite action 和输出契约；不要复制
`dorny/paths-filter` 或在 YAML 中新增另一套路径白名单。GitHub ruleset 中应同时要求
两个 scope check、`sdk-contract-required` 以及 PR 快速门禁；未分类路径必须走全量/保守门禁。

```bash
# 本地查看当前分支相对 main 的范围
python3 scripts/ci/detect-change-scope.py --base origin/main --head HEAD --json
```

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

检查源码、配置、脚本和 SQL/Flyway diff 中新增的说明性注释是否为简体中文。机器指令、标准许可证声明和代码示例不判为说明性注释；检查范围仅包括新增行，便于逐步治理已存在的历史内容。可用 `--paths` 对指定目录执行存量扫描。

```bash
python3 scripts/ci/check-comment-language.py --staged
python3 scripts/ci/check-comment-language.py --base-ref origin/main
python3 scripts/ci/check-comment-language.py --paths scripts load-tests/scripts load-tests/sql
```

当前作为本地增量预检接入 `scripts/local/pre-commit-checks.sh`，仅检查暂存 diff 新增的说明性注释，不扫描存量文件。PR/Full CI 暂不阻断；后续评估历史迁移 checksum 风险及误报率后，再单独启用 CI 门禁。Flyway checksum 校验仍独立生效，不能因改动仅涉及注释就跳过：已应用迁移文件内容变化会造成校验和差异，部署前必须按迁移治理流程处理。

## `check-empty-checks.py`

检查生产 Java 本次 diff 新增的 `null`、字符串空、集合空判断。统一入口为
`batch-common` 的 `EmptyChecks`：`isNull/isNotNull`、`isEmpty/isNotEmpty`、
`isBlank/isNotBlank` 分别表达对象、字符串和集合的语义。脚本只检查新增行，历史
代码由专项迁移逐步收口，不会因为一次接入产生大范围机械改动。

```bash
python3 scripts/ci/check-empty-checks.py
python3 scripts/ci/check-empty-checks.py --base origin/main
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

PR / Full Gate 的各 Java shard 在现有测试后生成 JaCoCo XML，并由 `check-diff-coverage.py` 校验本 shard 变更可执行行覆盖率不低于 80%。每周或手动 Full Gate 通过 `run-critical-mutation.sh` 对文件状态机和生命周期映射器运行 PIT，不扩展到全仓。

Full Gate 汇总 Surefire/Failsafe 报告与最近 30 次工作流运行，使用 `report-ci-quality-trends.py` 输出 JSON 和 Markdown，保存 90 天。

```bash
bash scripts/ci/collect-flaky.sh
bash scripts/ci/collect-flaky.sh -- --json build/flaky.json --warn-threshold 3
```

## `security-scan.sh`

本地 / CI 安全扫描一键入口：先打包 `security-scan` 独立 Java 模块，再按参数执行 secret、依赖、SAST、文件系统、镜像和 ZAP 扫描。默认执行全量扫描，可通过 `--mode` 收窄范围。

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

校验 Import / Export / Process / Dispatch / Atomic 五类内建 Worker 在 topic、租户 ACL、Kafka lag 告警、消费路由、sim 恢复和配置包示例中的横向完整性。Pipeline stage 仍只覆盖前四类；Atomic 是单任务执行器，并由独立隔离模板部署。

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

## `check-test-conventions.py`

按 `docs/coding-conventions.md` §14.4 / §14.5 检查 `src/test/java` 下 Java 测试的两族约定：

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
被误报为缺失。只判形状与是否存在，不评判措辞质量。

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
python3 -m unittest scripts/ci/tests/test_check_destructive_ops_governance.py
```

## `check-db-comment-coverage.sh`

校验本次新增或修改的 Flyway 迁移：新建 `batch` / `archive` 业务表必须在同文件包含
`COMMENT ON TABLE`；`batch` 表中名称命中状态、策略、载荷、幂等、密钥引用、哈希、超时、窗口、
时区、版本、重试、优先级、目标或来源引用等关键语义的新增字段必须包含 `COMMENT ON COLUMN`。
`archive` 镜像字段继承源表语义，只维护表级说明，避免双份字段注释漂移。历史对象的补齐基线和豁免
口径见 `docs/audit/database-and-class-comment-audit-2026-08-21.md`。

```bash
bash scripts/ci/check-db-comment-coverage.sh origin/main
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

e2e 运行侧闭环守护。`-Dsurefire.failIfNoSpecifiedTests=false` 会把「shard 清单列了某 `*E2eIT`、但因路径/改名没被选中」静默吞成绿色。本脚本在 Full/Staging 六片中的每个 e2e shard 跑完后比对：该 shard 实际产出的 surefire testsuite report 数必须等于清单声明的类数。与 `check-e2e-shard-coverage.sh`（静态双向集合校验）互补。

```bash
bash scripts/ci/check-e2e-run-completeness.sh "<逗号分隔类名>" <surefire-reports-dir>
```

### Java 治理测试完整性

`check-java-governance-test-coverage.py` 统一发现 `*ArchTest` 和 `*ConventionTest`。PR、
Full 和 Staging Gate 的 `java-governance` job 单独执行这些测试；业务 unit/IT 分片不再重复执行。

```bash
python3 scripts/ci/check-java-governance-test-coverage.py
python3 scripts/ci/check-java-governance-test-coverage.py --verify-reports
```

第二条命令必须在 Maven 执行后运行，任一源码类没有对应 `TEST-<FQCN>.xml` 就失败。
实际执行统一使用 `bash scripts/ci/run-java-governance-tests.sh`；该入口同时接入本地 pre-push，
防止本地与三个在线 workflow 的模块清单和 Maven 参数漂移。pre-commit 只运行源码清单检查，
避免每次提交承担 Maven 构建成本。

## `install-upstream-modules.sh`

将 E2E / staging / Trivy 扫描所需的上游 Maven 模块安装到 `~/.m2`。Full Gate 和 PR 的 Trivy 文件系统扫描会先运行本脚本，缓存依赖元数据并安装本地 reactor 产物，避免扫描器重复向 Maven Central 请求内部模块 POM。它保持 `batch-e2e-tests`
排除和 `-DskipTests` 约束不变，并对 Maven Central 的临时 5xx / 传输失败使用 Maven
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

```bash
python3 scripts/ci/check-java-text-block-style.py
```

## `check-java-structured-string-concat.py`

阻止新增的多行结构化字符串拼接。检查 JSON、XML、YAML、SQL、Markdown、CSV、PEM、INI、properties、Lua 和 Shell 模板；只报告相对基线新增的候选，
避免存量一次性阻塞，也不把签名串、普通动态消息或循环生成内容机械改成文本块。

```bash
python3 scripts/ci/check-java-structured-string-concat.py --base-ref origin/main
python3 -m unittest scripts/ci/tests/test_check_java_structured_string_concat.py
```

本地预提交会对暂存 Java 文件相对 `HEAD` 检查；PR CI 相对目标分支检查，Full Gate 同样运行该门禁及其测试。

## `check-java-suppression-registry.py`

检查生产 Java 的 `@SuppressWarnings` 是否使用已审核的精确规则。它不要求 suppression 数量归零，
但会阻断未登记的新规则，防止静态检查例外在重构中无理由扩散。规则用途和移除条件见
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
  --output docs/analysis/java-readability-inventory-2026-08-12.md
bash scripts/python.sh scripts/ci/report-java-readability-inventory.py \
  --check docs/analysis/java-readability-inventory-2026-08-12.md
```

`--check` 是阶段 7 的防漂移门禁：生产 Java 文件增删或候选统计变化时，必须重新生成并审核快照，
不能让过期报告继续作为验收证据。

## `check-docs-structure.py`

校验 `docs/` 一级目录入口、子目录 README 覆盖、当前文档的仓库内相对链接、根 README 文档导航入口、
`docs/README.md` 文档治理入口，以及禁止提交的 Finder 元数据和带日期本机验收报告。归档正文和外部
URL 不联网检查，避免历史快照或第三方站点波动造成误报。该检查已接入 PR gate 和 full gate。

```bash
bash scripts/python.sh scripts/ci/check-docs-structure.py
```

## `check-doc-timestamp-policy.py`

校验 `docs/` 文件名是否符合“当前事实稳定命名、一次性报告带日期、历史材料归档”的策略。
当前事实目录（如 `architecture/design/runbook/sdk`）不允许继续新增带日期文件名；报告、审计、
复核、验证、backlog、plan 默认应带日期或阶段标识。存量历史例外集中登记在脚本白名单里，
便于后续逐步归档。

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
