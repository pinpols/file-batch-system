# docs/ 文档总入口

- [Compose CD → Kubernetes GitOps 路线](runbook/compose-cd-roadmap.md)：前后端统一 digest、staging 自动部署、production 审批、回滚与 GitOps 验收边界。
- [GitOps ops 仓模板](../deploy/gitops/ops-repo-template/README.md)：Argo CD staging Application、Helm 环境 values 和 release manifest digest promotion 工具。

整个 `docs/` 目录的导航。**新人从这里开始**。

> 根目录 `AGENTS.md` 提供协作指引；`agent-baseline.md` 与 `coding-conventions.md` 是项目工程约束的权威，本目录提供展开与实践材料。

## 顶层 3 个文件（不含本 README）

| # | 文件 | 作用 | 受众 |
|---|---|---|---|
| 01 | [agent-baseline.md](./agent-baseline.md) | AI agent 编程基线（设计原则 / 模块边界 / 持久层 / 命名 / 安全旁路）| 通用 AI 编码协作 |
| 02 | [coding-conventions.md](./coding-conventions.md) | 编码规约（22 章：方法参数 / FQN 禁令 / 异常 / API / 删除规范 / 时区 / 编码 / 安全旁路 / 代码模式实战）| 全员开发 |
| 03 | [changelog.md](./changelog.md) | 工程基线规范条款变化日志（按日期倒序）| 想知道"规范什么时候变的" |

## 主要子目录

先看 [文档查找与变更路由](standards/document-routing-guide.md)：按任务找文档，并判断系统更新后该维护哪个原文档、追加哪类证据。

### 当前系统资产（长期维护原文，不带日期）

| 目录 | 放什么 |
|---|---|
| [architecture/](architecture/README.md) | 架构、模型、原语、边界、运行主链与 ADR |
| [design/](design/README.md) | 业务与能力设计、数据库逻辑模型 |
| [api/](api/README.md) | OpenAPI、协议与 API 黄金样本 |
| [sdk/](sdk/README.md) | SDK 接入、通信协议与发布契约 |
| [dict/](dict/README.md) | 错误码与配置键生成参考 |

### 研发、运维与工程规范（长期维护）

| 目录 | 放什么 |
|---|---|
| [runbook/](runbook/README.md) | 构建、部署、配置、监控、故障处置和安全操作 |
| [testing/](testing/README.md) | 测试方法、场景入口、可复用清单和模板 |
| [standards/](standards/README.md) | 编码/文档/治理规范、文档路由、Skill 与守护总账 |
| [governance/](governance/README.md) | 机器可读检查契约和基线；旧人类规则页仅保留兼容跳转 |
| [compliance/](compliance/README.md) | 许可证、SBOM 与合规材料 |
| [stats/](stats/README.md) | 无日期的当前统计/机器清单，历史统计进 archive/stats/ |

### 项目推进（长期台账与阶段方案）

| 目录 | 放什么 |
|---|---|
| [plans/](plans/README.md) | [当前待办](plans/todo-master.md)、[持续硬化](plans/hardening-backlog.md)使用无日期名；阶段方案、矩阵和执行日志保留原基线日期 |

### 每轮增量记录与历史证据

| 目录 | 放什么 |
|---|---|
| [review/](review/README.md) | 每轮代码/PR 审查，按日期新建 |
| [audit/](audit/README.md) | 每轮系统/专项审计与评估，按日期新建 |
| [verifications/](verifications/README.md) | 实测、验收、压测、兼容性、POC 结果，按日期新建 |
| [archive/](archive/README.md) | 被替代、已结束阶段和旧版本资料；保留原日期 |

可执行测试数据位于仓库根 [test-data/](../test-data/README.md)，不属于文档目录；使用说明见 [测试数据指南](testing/test-fixture-guide.md)。

## 常用角色路径

| 角色 | 推荐顺序 |
|---|---|
| 新人入门 | coding-conventions → architecture (`system-flow-overview.md` / `project-structure.md`) → runbook (`local-development.md`) |
| 配置维护者 | runbook (`first-tenant-config-quickstart.md` 手把手建第一个租户配置) → 下载 Excel 配置模板的「字段说明 / 五类Worker示例」sheet → `credential-matrix.md`(渠道/密码等凭据怎么存、怎么注入) |
| 业务开发 | design → api → coding-conventions §22 代码模式 |
| 后端测试类 / fixture | standards (`agent-skills-guide.md`) → `backend-testing-governance` → `coding-conventions.md` §14 / `testing/README.md` |
| 运维 / SRE | runbook (`credential-matrix.md` 上线前逐行核对凭据 + prod fail-fast 项) → architecture (`heavy-workload-guarantees.md`)|
| 救火值班 | runbook (`incident-response.md` → `troubleshooting-decision-tree.md`) |
| 上线评审 | testing (`release-gate.md`) → runbook (`docker-deployment.md` 或 `feature-switches.md`)|
| 架构改动 | architecture (`project-structure.md` / adr) → changelog 追规范 |
| 规范复扫 / PR 审核 | audit (`convention-drift-guard-index.md`) → `agent-baseline.md` → coding-conventions → `scripts/ci/README.md` |
| 开发 / 提交前 CI 预检 | standards (`agent-skills-guide.md`) → `developer-workflow` → 按需 `code-review-and-gates` / `ci-governance` / `git-pr-workflow` |
| 功能 / Bug / Issue 多 PR 交付 | standards (`agent-skills-guide.md`) → `git-pr-workflow` → 盘点依赖、共享文件/契约、合并顺序和交付证据 |
| 只读运维分析 | standards (`agent-skills-guide.md`) → `operations-analysis` → 对应运行手册和只读专项 Skill |
| 开源治理 / 安全响应 | standards (`open-source-governance.md`) → 根 `SECURITY.md` → runbook (`security-scan.md`) |
| 文档维护 / 目录调整 | standards (`document-governance.md`) → 本 README → 受影响子目录 README → `scripts/ci/check-docs-structure.py` |
| AI 协作 | [Agent Skill 使用指南](./standards/agent-skills-guide.md) → agent-baseline → coding-conventions，再按任务层级进入总控或专项 Skill |

## 维护约束（来自 agent-baseline.md）

- **API 文档同步**：改 `batch-console-api` 控制层，必须同步 `api/console-api-protocol.md` + `api/console-api.openapi.yaml`
- **规范变更日志**：修改 `agent-baseline.md` 已有规范条款，必须追加 `changelog.md`
- **Feature / Bug / 运维事件不进规范基线**：以 git commit + 对应模块文档（`architecture/*` / `runbook/*` / `analysis/*`）为权威
- **archive/ 只读**：归档文件不再维护，新内容写到主干目录
- **待办唯一入口**：当前待办以 [`analysis/todo-master.md`](plans/todo-master.md) 为准；`archive/` 中的待办只代表历史时点
- **文档状态治理**：详见 [`standards/document-governance.md`](./standards/document-governance.md)
- **索引同步**：新增、移动、归档文档时，必须同步本 README、对应子目录 README 和必要的根 README 入口；`check-docs-structure.py` 会检查主目录 README 覆盖和仓库内链接。
