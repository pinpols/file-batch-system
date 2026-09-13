# 工程治理与发布门禁

Console AI 可以回答本仓库工程治理、发布门禁和本地排障入口相关问题，但边界仍是**只读说明与建议**。AI 不代执行脚本、不创建发布 tag、不修改配置、不合并 PR。

## 文档治理
- 当前架构事实入口是 `docs/architecture/architecture-truth.md`。
- 当前待办入口是 `docs/analysis/todo-master.md`。
- 运维文档入口是 `docs/runbook/`，测试覆盖与验收入口是 `docs/testing/`。
- 历史审计、一次性报告和过期快照进入 `docs/archive/`，不作为当前状态引用。
- 文档结构和链接由 `scripts/ci/check-docs-structure.py` 检查；仓库卫生由 `scripts/ci/check-repository-hygiene.py` 检查。

## 变更记录与版本
- 有发布影响的功能、外部契约、配置默认值、部署、迁移、安全修复、重要缺陷或可验证性能变化，必须更新 `CHANGELOG.md` 的 `[Unreleased]`。
- ADR 或架构约束变化还必须同步 `docs/changelog.md`。
- `scripts/ci/check-changelog-sync.py` 会检查上述同步要求。
- 版本升级统一走 `scripts/ci/bump-version.sh <version>`；之后运行 `scripts/ci/check-version-alignment.sh`。
- 后端根 `pom.xml` 的 `<revision>` 是 Maven 版本入口；`load-tests/pom.xml` 需要同步；Helm `Chart.yaml appVersion` 表示已发布应用版本。

## 依赖边界
- `batch-common` 要保持轻量，不新增 AI SDK、Excel 处理等运行时重依赖；历史存在的 S3、HTTP client、OTel exporter 依赖只按专项治理移除，不继续扩张。
- Java SDK 面向租户侧，不依赖平台实现模块。`sdk/java/core` 不依赖任何 `io.github.pinpols.batch` 平台模块；`sdk/java/spring` 与 `sdk/java/testkit` 只允许依赖 `batch-worker-sdk`。
- 平台模块不得在运行时依赖 SDK artifact；测试 scope 可用于契约校验。
- `batch-console-api` 和 `batch-orchestrator` 的持久层是 MyBatis + JDBC，不引入 Spring Data JDBC。
- 依赖边界由 `scripts/ci/check-dependency-boundaries.py` 检查。

## 脚本与环境
- 新增 `scripts/ci/check-*` 或 `validate-*` 守护时，必须登记到 `scripts/ci/README.md`；`scripts/ci/check-script-governance.py` 会检查遗漏。
- Shell 脚本兼容性走 `scripts/ci/check-shell-scripts.sh`，语法错误和 ShellCheck warning 均直接阻断。
- `.env*` 文件若要被 Bash 安全 `source`，包含空白的值必须使用引号；`scripts/ci/check-env-file-shell-safety.py` 会检查。
- 运行时路径、运维旧端口、本机绝对路径和可变镜像入口由 `scripts/ci/check-hardcoded-runtime-config.sh` 与仓库卫生门禁共同检查。

## Readiness、Trivy 与质量守护
- readiness、`AssetPartition`、`ResultVersion` 或 trigger 调度主路径变更时，同一 PR 应同步设计文档、OpenAPI / 协议文档或 readiness 相关测试；`scripts/ci/check-readiness-doc-sync.py` 负责轻量守护。
- `.trivyignore` 中每组 CVE 豁免必须包含 `owner`、`reason`、`expires: YYYY-MM-DD`，且未过期；`scripts/ci/check-trivy-ignore-expiry.py` 会阻断过期豁免。
- 生产 Java 新增空值、空字符串、空集合判断时，优先使用 `batch-common` 的 `EmptyChecks`；`scripts/ci/check-empty-checks.py` 会检查本次 diff 新增判断。
- API 路径与 OpenAPI 对齐由 `scripts/ci/check-console-openapi-paths.py` 检查。

## 本地验证建议
遇到“PR 门禁为什么失败 / 新增脚本为什么被拦 / 版本升级要改哪里”这类问题时，先按报错脚本名查 `scripts/ci/README.md`。需要给建议时，说明应运行的最小命令和对应文档入口，不直接替用户执行发布或合并动作。
