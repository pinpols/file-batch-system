# CI 脚本说明

本目录存放 GitHub Actions 与本地均可复用的 CI 门禁脚本。

## 完整守护清单

下表是 `check-*` / `validate-*` 可执行守护的登记源；`check-script-governance.py` 会阻止新增守护漏登记。

| 方向 | 守护 |
|---|---|
| 应用与架构 | `check-application-governance.py`、`check-dependency-boundaries.py`、`check-no-enable-preview.sh` |
| 文档与变更 | `check-docs-structure.py`、`check-code-doc-references.py`、`check-changelog-sync.py`、`check-readiness-doc-sync.py` |
| 脚本与仓库 | `check-shell-scripts.sh`、`check-script-governance.py`、`check-repository-hygiene.py`、`check-env-file-shell-safety.py`、`check-hardcoded-runtime-config.sh` |
| 配置与部署 | `check-config-defaults-sync.py`、`check-feature-switch-registry.py`、`check-helm-env-sync.py`、`check-production-overlay-safety.py`、`check-version-alignment.sh`、`validate-kafka-topics.sh` |
| 数据库与 SQL | `check-biz-table-tenant-rls.py`、`check-db-comment-coverage.sh`、`check-db-scripts-safety.sh`、`check-migration-safety.sh`、`check-no-positional-insert-select-star.py`、`check-postgres-client-fallback.sh`、`check-sql-config-boundaries.py`、`check-sql-config-boundaries.sh`、`validate-flyway-schema.sh` |
| API 与兼容 | `check-console-openapi-paths.py`、`check-openapi-breaking.sh` |
| Java 质量 | `check-empty-checks.py`、`check-java-readability.py`、`check-java-suppression-registry.py`、`check-mapof-null-values.py`、`check-required-java-docs.sh` |
| 测试完整性 | `check-e2e-run-completeness.sh`、`check-e2e-shard-coverage.sh`、`check-module-test-coverage.sh`、`check-no-silent-disabled-tests.sh` |
| 安全与许可 | `check-dependency-licenses.sh`、`check-license-compliance.sh`、`check-trivy-ignore-expiry.py` |
| 观测 | `check-helm-prometheusrule-sync.sh`、`check-log-lifecycle.sh`、`check-observability-contract.py` |

`check-sql-config-boundaries.py` 是同名 `.sh` 稳定入口的实现，两者都登记，避免包装层与实现层单独漂移。

## PR 按需路由

`pr-gate.yml` 始终启动，以保证 ruleset required Job 在每个 PR 上稳定回报状态；
`changes` Job 再按 `java`、`database`、`scripts`、`docs`、`config`、`api`、`ci`
文件域决定 static-checks 内的专项步骤。SDK 使用 `sdk-contract-parity.yml` 的独立路径路由。
Secret scan 属于跨域检查，仍对所有非 Draft PR 执行，因为凭据可能出现在任意文件类型中。

## `check-code-doc-references.py`

校验后端 Java、XML、POM 和配置文件注释中引用的仓库内 `docs/`、`sdk/` 路径存在，避免文档归档或目录迁移后代码注释继续指向失效路径。

```bash
python3 scripts/ci/check-code-doc-references.py
```

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
协议文档或 readiness 相关测试。该脚本是轻量同步守护，不替代 IT / E2E 语义验证。

```bash
python3 scripts/ci/check-readiness-doc-sync.py
python3 scripts/ci/check-readiness-doc-sync.py --base origin/main
```

已接入 `pr-gate.yml`、`full-ci-gate.yml` 和本地 `scripts/local/pre-push-sdk-checks.sh`。

## `check-trivy-ignore-expiry.py`

校验 `.trivyignore` 中每组 CVE 白名单必须带 `owner`、`reason`、`expires: YYYY-MM-DD`，
且 `expires` 未过期。安全漏洞豁免只允许作为有期限的临时措施，不能长期静默留在仓库。

```bash
python3 scripts/ci/check-trivy-ignore-expiry.py
python3 scripts/ci/check-trivy-ignore-expiry.py --today 2026-09-13
```

已接入 `pr-gate.yml`、`full-ci-gate.yml` 和本地 `scripts/local/pre-push-sdk-checks.sh`。

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

## `collect-flaky.sh` / `collect-flaky.py`

汇总 surefire / failsafe 报告里 `<flakyFailure>` / `<flakyError>`(`rerunFailingTestsCount=2` 自动重跑产生的 flaky-but-pass 用例),输出人读 summary 和(GH Actions 下)`$GITHUB_STEP_SUMMARY` Markdown 表。已在 `run-full-regression.sh` 末尾自动调用,**恒以 0 退出**,不阻断 CI。治理流程见 [`docs/runbook/ci.md`](../../docs/runbook/ci.md#flaky-治理)。

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

**依赖**：Python 3、PyYAML。

```bash
python3 -m pip install pyyaml
python3 scripts/ci/check-console-openapi-paths.py
```

成功时打印路由数量并以退出码 `0` 结束；不一致时打印「仅 OpenAPI」与「仅代码」的差异并以 `1` 结束。

**接入的 workflow**（均在 checkout 之后、Java 构建之前执行）：`.github/workflows/pr-gate.yml`、`.github/workflows/full-ci-gate.yml`、`.github/workflows/staging-gate.yml`。

## `check-dependency-boundaries.py`

校验依赖边界约束：

- `batch-common` 不得新增对象存储、OTEL exporter、AI SDK、Excel 处理等运行时重依赖
- 全业务模块不得引入 `spring-boot-starter-data-jdbc`（持久层统一 MyBatis；见 ADR-001）
- `batch-console-api` 与 `batch-orchestrator` 须在运行时 POM 中同时出现 `spring-boot-starter-jdbc` 与 `mybatis-spring-boot-starter`（脚本会校验）

```bash
python3 scripts/ci/check-dependency-boundaries.py
```

成功时打印 `OK: dependency boundaries satisfied.` 并以退出码 `0` 结束；违反约束时打印错误并以 `1` 结束。

## `check-helm-env-sync.py`

校验 Helm templates 注入的 `BATCH_*` 环境变量是否能被应用消费，避免生产 Chart 变量名写错后静默失效；同时确认生产一等开关入口（限流、请求签名等）已显式渲染。

生产一等开关入口从 `docs/runbook/feature-switch-registry.yml` 读取；新增公共开关时先登记 registry，再补 Compose / Helm / 运维说明。

```bash
python3 scripts/ci/check-helm-env-sync.py
```

成功时打印 `Helm BATCH_* env 与应用消费入口一致` 并以退出码 `0` 结束；违反约束时列出未知变量或缺失入口并以 `1` 结束。

## `check-db-scripts-safety.sh`

补 `check-migration-safety.sh`(squawk 只扫 `db/migration`)的盲区:`scripts/db/**`(尤其 `business/` 不走 Flyway、`partition-migration/`)下的手工 DDL 脚本同样能跑危险变更。

- **WARN**(不阻断):脚本含 `ON CONFLICT` → 提示核对幂等契约是否受约束变更影响。
- **FAIL**:脚本含关键约束级危险 DDL(改 UNIQUE/PK 列集 / `DROP TABLE` / `DROP CONSTRAINT`)**且**文件头部无禁令标记(🔴/⚠/DANGER/禁止执行/破坏性…)→ 强制要求头注释显式声明风险与前置条件。

接入 `pr-gate.yml` 的 `static-checks` job。排除 `*-seed`。

```bash
bash scripts/ci/check-db-scripts-safety.sh
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

校验版本一致性:根 `pom.xml <revision>` ↔ helm `Chart.yaml appVersion`(预发态下 appVersion 合法地停在上一 GA,仅 GA 态强制相等)↔ `load-tests/pom.xml`(独立 reactor,版本手工同步,CLAUDE.md 点名高危点);并校验 `.env.*` 的 `*_IMAGE_TAG` 不漂移。接入 `pr-gate.yml` 的 `static-checks` job。

```bash
bash scripts/ci/check-version-alignment.sh
```

## `check-e2e-run-completeness.sh`

e2e 运行侧闭环守护。`-Dsurefire.failIfNoSpecifiedTests=false` 会把「shard 清单列了某 `*E2eIT`、但因路径/改名没被选中」静默吞成绿色。本脚本在每个 e2e shard 跑完后比对:该 shard 实际产出的 surefire testsuite report 数 == 清单声明的类数,不等则 fail。与 `check-e2e-shard-coverage.sh`(静态:清单 ⊇ 仓库实际)互补。接入 `full-ci-gate.yml` / `staging-gate.yml` 的 `e2e-shard` job。

```bash
bash scripts/ci/check-e2e-run-completeness.sh "<逗号分隔类名>" <surefire-reports-dir>
```

## `install-upstream-modules.sh`

将 E2E / staging 所需的上游 Maven 模块安装到 `~/.m2`。它保持 `batch-e2e-tests`
排除和 `-DskipTests` 约束不变，并对 Maven Central 的临时 5xx / 传输失败使用 Maven
强制更新、传输层重试和最多三轮退避重试，避免依赖下载瞬时失败被误报为 E2E 业务失败。

```bash
bash scripts/ci/install-upstream-modules.sh
```

## `check-java-readability.py`

检查生产 Java 主代码的可读性边界：禁止使用隐式 `var`，并要求普通 Spring
`@Configuration` 显式声明 `proxyBeanMethods = false`。前者让业务、状态机和基础设施边界
显式表达类型；后者避免没有 bean 方法互调需求的配置类重新引入 CGLIB 代理。
脚本会忽略注释、Javadoc、字符串、字符字面量和测试代码，不会误报文档示例或业务文本。

```bash
python3 scripts/ci/check-java-readability.py
```

## `check-java-suppression-registry.py`

检查生产 Java 的 `@SuppressWarnings` 是否使用已审核的精确规则。它不要求 suppression 数量归零，
但会阻断未登记的新规则，防止静态检查例外在重构中无理由扩散。规则用途和移除条件见
`docs/standards/java-suppression-registry.md`。

```bash
python3 scripts/ci/check-java-suppression-registry.py
```

## `report-java-readability-inventory.py`

生成 Java 可读性治理候选快照，覆盖 CGLIB 自注入、大类、public Map 契约、
`@SuppressWarnings`、配置类和宽参数显式例外。该脚本只报告候选，不自动改写源码，
也不把数量本身判定为缺陷。

```bash
python3 scripts/ci/report-java-readability-inventory.py
python3 scripts/ci/report-java-readability-inventory.py \
  --output docs/analysis/java-readability-inventory-2026-08-12.md
python3 scripts/ci/report-java-readability-inventory.py \
  --check docs/analysis/java-readability-inventory-2026-08-12.md
```

`--check` 是阶段 7 的防漂移门禁：生产 Java 文件增删或候选统计变化时，必须重新生成并审核快照，
不能让过期报告继续作为验收证据。

## `check-docs-structure.py`

校验 `docs/` 一级目录入口、`docs/README.md` 覆盖、当前文档的仓库内相对链接，以及禁止提交的
Finder 元数据和带日期本机验收报告。归档正文和外部 URL 不联网检查，避免历史快照或第三方站点
波动造成误报。该检查已接入 PR gate 和 full gate。

```bash
python3 scripts/ci/check-docs-structure.py
```
