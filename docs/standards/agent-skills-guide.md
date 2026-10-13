# 后端 Agent Skill 使用指南

本指南说明在 `file-batch-system` 与配对前端 `batch-console` 中如何选择、组合和验收 Agent Skill。它是使用入口，不复制各 `SKILL.md` 的完整流程；当前代码、契约、CI、`AGENTS.md` 和对应 Skill 才是执行依据。

## 选择顺序

1. 前后端周期治理、全量深扫、规范防退化或代码腐化盘点：使用 [`cross-repo-governance-review`](../../.agents/skills/cross-repo-governance-review/SKILL.md)。
2. 后端单仓项目级架构、质量、风险与维护评估：使用 [`project-engineering-review`](../../.agents/skills/project-engineering-review/SKILL.md)。
3. 单个 PR 或 diff 的正确性、可读性和验证复核：使用 [`code-review-and-gates`](../../.agents/skills/code-review-and-gates/SKILL.md)。
4. 跨信任边界、恶意输入和多故障组合的系统审查：仅在明确需要时使用 [`adversarial-system-review`](../../.agents/skills/adversarial-system-review/SKILL.md)。
5. 已明确的领域任务直接进入专项 Skill；提交、PR、合并和清理额外使用 [`git-pr-workflow`](../../.agents/skills/git-pr-workflow/SKILL.md)。

总控 Skill 保证覆盖面，专项 Skill 提供领域判断。普通小改动不需要加载所有技能，也不能因为加载了总控就自动获得外部状态变更授权。

## 总控与交付路由

| 任务 | 首选 Skill | 说明 |
|---|---|---|
| 月度/季度双仓治理、首次全量基线 | `cross-repo-governance-review` | 固定覆盖两仓 20 个治理领域 |
| 后端全面工程评估 | `project-engineering-review` | 单仓架构、质量、风险、治理与维护性 |
| PR/diff 审查 | `code-review-and-gates` | 聚焦本次完整 diff，不混入无关历史债务 |
| 威胁与故障路径深审 | `adversarial-system-review` | 仅用于系统级攻击面和故障组合 |
| 分支、提交、PR、合并、清理 | `git-pr-workflow` | 各动作分别授权，核实远端 head 和 required checks |

## 专项 Skill 目录

| 领域 | Skill | 关注点 |
|---|---|---|
| 批量调度领域 | `batch-scheduling-engineering` | 业务日、状态一致性、并发恢复、容量语义 |
| 调度正确性 | `scheduler-correctness` | Trigger、misfire、readiness、pause/resume、时区 |
| Worker 链路 | `worker-pipeline-review` | 五类 Worker、claim/report、lease、outbox、恢复 |
| SDK 契约 | `sdk-contract-governance` | 五语言协议、fixture、传输生命周期、版本发布 |
| 前后端契约 | `frontend-backend-contract` | Console API、OpenAPI、权限、导航和交互契约 |
| 数据库迁移 | `database-migration-safety` | Flyway、事务、RLS、约束、归档和恢复 |
| SQL 性能 | `sql-query-performance` | PostgreSQL 查询、索引、分页、JSONB 与证据 |
| 模块配置边界 | `module-config-boundaries` | 模块所有权、配置生命周期和依赖方向 |
| 配置治理 | `configuration-governance` | 开关、env、Compose/Helm、动态配置、密钥 |
| 对象存储 | `object-storage-governance` | S3 兼容、本地存储、加密、校验和与 `.chk` |
| 脚本与 SQL | `script-sql-governance` | 运行环境、转义、幂等、退出码和可发现性 |
| 性能容量 | `performance-validation` | 负载、指标、环境、基线和验收标准 |
| 灾备恢复 | `disaster-recovery-validation` | 备份、PITR、HA、RPO/RTO 和业务一致性 |
| 验收证据 | `acceptance-validation` | 本地、真实依赖、目标环境和结果分层 |
| CI 治理 | `ci-governance` | PR/Full/Staging、required checks、失败处置 |
| 安全扫描 | `security-scan-governance` | 本地扫描模式、CI 覆盖、例外和证据边界 |
| 文档治理 | `documentation-governance` | README、索引、Changelog、归档和漂移 |

## 前后端周期治理的调用方式

`cross-repo-governance-review` 提供月度增量、季度存量、发布前、事故后和首次全量基线五种模式。可直接提出：

- `用 $cross-repo-governance-review 做前后端首次全量治理基线`
- `用 $cross-repo-governance-review 做本月增量治理`
- `用 $cross-repo-governance-review 做季度代码腐化和规范退化复核`
- `用 $cross-repo-governance-review 做发布前治理审查`
- `用 $cross-repo-governance-review 复盘事故并补防复发守卫`

总控输出必须包含两仓基线、发现、20 域覆盖矩阵、实际验证、防复发闭环和下一周期基线。`SKIP`、报告型结果、静态推断、历史报告和局部测试不能写成完整通过。配对前端指南见 [`batch-console/docs/engineering/agent-skills-guide.md`](../../../batch-console/docs/engineering/agent-skills-guide.md)。

## 常见组合

### 修改 Console API

后端使用 `frontend-backend-contract` 核对 Controller、DTO、权限和 OpenAPI；配对前端使用 `frontend-api-contract` 重新生成类型并检查调用方。涉及配置包再组合前端模板 Skill；两仓分别运行适用门禁并分别创建 PR。

### 修改 Worker 或调度行为

领域设计使用 `batch-scheduling-engineering`，时序进入 `scheduler-correctness`，Worker/消息/恢复进入 `worker-pipeline-review`，SDK 可观察行为进入 `sdk-contract-governance`。数据库、性能和灾备只在实际涉及对应风险时加载。

### 新增或强化治理门禁

使用 `ci-governance`，并按变更主题组合安全、配置、文档或代码审查 Skill。先验证应拦和应放行案例，明确 `enforce/report/ignore`、owner、期限和失败处置，再同步 `scripts/ci/README.md`、CI runbook、漂移守卫总账和变更日志。不得扩大基线、弱化检查或重复建设同义门禁。

## 证据和权限边界

- 多模块 Maven 验证按依赖闭包使用 `-am`；陈旧 reactor 产物不能直接归因于源码。
- 静态扫描、单测、真实依赖集成、sim、staging 和生产演练是不同层级。
- 只有本轮实际完成的检查可以写成 `PASS`；`SKIP`、`QUEUED`、`IN_PROGRESS` 和历史绿灯不是通过。
- 审查命令不授权修复；修复不授权提交；提交 PR 不授权合并或清理。
- 历史 audit/review 只用于提出复发假设，必须在当前 SHA 上重新核实。

## 维护规则

- 新增、删除或重新定位 Skill 时，同步 `AGENTS.md`、本指南和文档索引。
- 选择和组合方式写在本指南；具体领域检查只维护在相应 `SKILL.md`。
- 可机器判断、事实源稳定、误报可控且有正反例的规则才进入阻断门禁；其余使用报告、增量或周期审查。
- 长期指南不记录测试数量、扫描告警数量、耗时或某次 PR 状态。
