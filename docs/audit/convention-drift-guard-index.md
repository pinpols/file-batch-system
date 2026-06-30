# 约定约束与漂移防护总账

> 维护日期: 2026-07-01
> 定位: 统一记录项目约定、约束、审计、审核、复扫与 CI 守卫入口,用于后续扫描时防止规范漂移。

本文不是新的规范来源,也不替代 `CLAUDE.md`、编码规约、ADR 或 runbook。它只做一件事:把分散在代码、文档、脚本、CI、审计报告里的约束集中成一张可复扫的总账。

## 权威层级

| 层级 | 权威入口 | 作用 | 漂移风险 |
|---|---|---|---|
| 1 | 根目录 `CLAUDE.md` | 项目硬约束、红线、模块边界、数据库与测试基本纪律 | 新增硬规则后只改代码或口头约定,没有进入根规范 |
| 2 | [docs/coding-conventions.md](../coding-conventions.md) | 编码细则、命名、异常、事务、API、删除规范、安全旁路 | 代码风格、事务边界、参数/命名习惯逐步发散 |
| 3 | [docs/architecture/adr/](../architecture/adr/) | 已决策的架构边界和取舍 | 代码实现和 ADR 方向不一致,或新增例外没有补 ADR |
| 4 | [docs/api/](../api/) | Console / Orchestrator / SDK 契约 | 前后端、SDK、worker transport 契约漂移 |
| 5 | [docs/runbook/](../runbook/) + [docs/testing/](../testing/) | 运维操作、上线门槛、测试覆盖矩阵 | 能跑测试但不能运维救火,或验收证据缺失 |
| 6 | [docs/audit/](./) + [docs/analysis/](../analysis/) + [docs/review/](../review/) | 审计、深扫、复盘、阶段评审 | 发现的问题没有闭环到规范、脚本、测试或 runbook |
| 7 | `scripts/ci/` + `.github/workflows/` | 可执行守卫 | 文档写了但 CI 不拦,或 CI 新增后没人知道 |

## 当前快照

| 类别 | 当前规模 | 主要入口 | 说明 |
|---|---:|---|---|
| ADR | 47 个 ADR 文件 | [docs/architecture/adr/](../architecture/adr/) | 包含架构边界、SDK、checkpoint、capacity、依赖调度等决策 |
| 专项审计报告 | 7 个文件 | [docs/audit/](./) | 包含 2026-05-23 全仓架构审计和后续后端深扫 |
| CI 守卫脚本 | 34 个文件 | [scripts/ci/README.md](../../scripts/ci/README.md) | 覆盖租户隔离、迁移、OpenAPI、配置、版本、许可、测试完整性等 |
| GitHub Actions | 12 个 workflow | `.github/workflows/` | PR gate、full CI、staging、SDK parity、CodeQL、workflow lint |
| SDK 契约 fixture | 30 个 case | [docs/api/sdk-contract-fixtures/](../api/sdk-contract-fixtures/) | 覆盖注册、心跳、claim、renew、report、Kafka schema 兼容等 |
| 顶层规范文档 | 3 个核心入口 | [docs/README.md](../README.md) | `agent-baseline`、`coding-conventions`、`changelog` |

## 守卫矩阵

| 方向 | 必看规范 | 可执行守卫 | 典型漂移 |
|---|---|---|---|
| 多租户与数据库 | `CLAUDE.md`; [bounded-context-rules.md](../architecture/bounded-context-rules.md); ADR-017/020/024 | `check-biz-table-tenant-rls.py`; `check-migration-safety.sh`; `validate-flyway-schema.sh`; `check-no-positional-insert-select-star.py`; 相关租户/归档 ArchTest | 漏 `tenant_id`; `ON CONFLICT` 幂等守卫退化; archive schema 和热表漂移; 位置列插入导致错列 |
| API 契约 | [console-api-protocol.md](../api/console-api-protocol.md); [console-api.openapi.yaml](../api/console-api.openapi.yaml); [orchestrator-internal.openapi.yaml](../api/orchestrator-internal.openapi.yaml) | `check-console-openapi-paths.py`; `check-openapi-breaking.sh`; 前端 `gen:api:check` | Controller 改了但 OpenAPI/前端类型没同步; 返回体字段和页面假设不一致 |
| Worker / SDK | ADR-035/036/037/038; [sdk-contract-fixtures](../api/sdk-contract-fixtures/) | `run-sdk-live-transport-gate.sh`; `run-sdk-orchestrator-e2e.sh`; workflow `sdk-contract-parity.yml`; `sdk-orchestrator-e2e.yml`; `sdk-release-validation.yml` | conformance 绿但生产 transport 不通; 五语言 SDK 行为不一致 |
| 编码与架构 | [coding-conventions.md](../coding-conventions.md); `CLAUDE.md`; [project-structure.md](../architecture/project-structure.md) | PMD; Spotless; `check-dependency-boundaries.py`; `check-no-enable-preview.sh` | 构造注入退回字段注入; 事务放错层; common 引入重依赖; 预览特性混入主线 |
| 配置与环境 | [runbook/](../runbook/); [dict/config-keys.md](../dict/config-keys.md); ADR-039 | `check-config-defaults-sync.py`; `check-env-prod-sync.sh`; `check-version-alignment.sh`; `check-helm-prometheusrule-sync.sh`; `validate-kafka-topics.sh`; `check-cron-quartz-only.sh`; `check-sql-config-boundaries.sh` | yml、docker、helm、env、topic、PrometheusRule 不一致; SQL 和配置混在 shell |
| 测试与验收 | [testing/](../testing/); [verifications/](../verifications/) | `check-e2e-shard-coverage.sh`; `check-e2e-run-completeness.sh`; `check-module-test-coverage.sh`; `check-no-silent-disabled-tests.sh`; `select-affected-tests.py`; workflow `full-ci-gate.yml`; `staging-gate.yml`; `strict-verify.yml` | 只保留 happy path; disabled test 静默增加; sim/IT 覆盖和业务场景脱节 |
| 安全与合规 | [compliance/](../compliance/); `CLAUDE.md` 安全红线 | `security-scan.sh`; `check-license-compliance.sh`; `check-dependency-licenses.sh`; workflow `codeql.yml`; `workflow-lint.yml` | 依赖许可不清; bypass 开关 fail-open; workflow 权限过大; secret 泄露到日志 |
| 运维与恢复 | [runbook/incident-response.md](../runbook/incident-response.md); [runbook/troubleshooting-decision-tree.md](../runbook/troubleshooting-decision-tree.md); ADR-042/044 | `scripts/ops/inspect-all.sh`; `scripts/ops/heal-stuck-workflows.sh`; 相关 sim / drill / staging gate | Console 只能看不能救; DLQ/outbox/卡实例缺少幂等恢复动作 |

## 复扫频率

| 场景 | 建议复扫动作 | 通过标准 |
|---|---|---|
| 每个 PR | 运行 PR gate、OpenAPI 路径检查、SQL/配置边界检查、迁移安全检查、PMD/Spotless | 新增变更不破坏既有规范; 失败项要么修复,要么有明确 ADR/文档例外 |
| 改 Console API | 同步 OpenAPI、协议文档、前端 `api.generated.ts` 和调用方 | 前后端类型一致; 页面不依赖不存在的字段 |
| 改 DB 迁移 | 复扫 tenant/RLS、archive schema、Flyway schema、迁移安全、位置列插入 | 租户隔离不退化; 迁移可前向执行; archive 与热表规则一致 |
| 改 worker / SDK transport | 跑 SDK fixture、live transport、orchestrator e2e | Java/Python/Go/JS/Rust 行为不漂移; conformance 和生产链路一致 |
| 改配置、镜像、依赖版本 | 跑版本对齐、env/prod 同步、许可、SBOM、安全扫描 | 本地、CI、部署、测试容器版本统一; 许可风险明确 |
| Release 前 | 跑 full CI、staging gate、关键 sim、runbook 演练证据 | 不只有单测绿,还要有运维和真实链路证据 |
| 新增硬规则 / ADR 例外 | 更新 `CLAUDE.md` 或 ADR,追加 `docs/changelog.md`,同步本文 | 人读入口和机器守卫都能找到该规则 |

## 同步清单

| 如果改了 | 必须同步 |
|---|---|
| `/api/console/**` Controller / DTO | `docs/api/console-api.openapi.yaml`; `docs/api/console-api-protocol.md`; 前端 `../batch-console/src/types/api.generated.ts`; 前端调用方 |
| Orchestrator 内部 API 或 worker transport | `docs/api/orchestrator-internal.openapi.yaml`; `docs/api/sdk-contract-fixtures/`; `docs/api/sdk-shared-constants.yaml`; SDK parity 测试 |
| 批量核心表或归档表 | Flyway migration; archive mirror; RLS/tenant guard; mapper XML; runbook 里的诊断 SQL |
| Kafka topic / PrometheusRule / Helm 值 | `validate-kafka-topics.sh`; `check-helm-prometheusrule-sync.sh`; docker canonical 配置; 相关 runbook |
| 依赖版本 / 镜像版本 | Maven version property; testcontainers 镜像; docker compose; helm appVersion; 许可和 SBOM |
| 新 CI gate / 新审计脚本 | `scripts/ci/README.md`; 本文守卫矩阵; 对应 workflow 或 PR gate |
| 新 ADR / 硬约束例外 | ADR 目录; `docs/changelog.md`; 本文权威层级或守卫矩阵 |

## 当前缺口

| 缺口 | 风险 | 建议 |
|---|---|---|
| 本文统计仍是人工快照 | 脚本、workflow、ADR 增删后可能忘记更新 | 后续补 `scripts/ci/collect-guard-inventory.*`,从文件系统自动生成快照 |
| 部分守卫散落在测试类中 | 审计时不容易一次性定位 | 后续为关键 ArchTest / IT 建一个 `docs/testing/guard-tests-index.md` |
| 跨前后端漂移需要两个仓库共同验证 | 后端 CI 绿不代表前端页面可用 | Console API 变更默认要求前端 codegen check 和页面 smoke 证据 |
| 运维恢复脚本和 Console 操作边界仍需持续对齐 | 可能出现“脚本能救、Console 不能救”或反向漂移 | 运维闭环能力变更时同步 runbook、Console 页面和脚本 |

## 维护规则

1. 新增或删除 CI 守卫脚本,必须更新本文的快照和守卫矩阵。
2. 新增 ADR、硬约束或架构例外,必须能从本文追到权威入口。
3. 审计报告发现的系统性问题,必须至少闭环到以下一项:代码修复、测试守卫、CI gate、ADR、runbook 或本文。
4. 如果本文和 `CLAUDE.md`、ADR、OpenAPI、runbook 冲突,以后者对应权威文档为准,并立即修正本文。
