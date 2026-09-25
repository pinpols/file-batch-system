# docs/ 文档总入口

整个 `docs/` 目录的导航。**新人从这里开始**。

> 根目录 `AGENTS.md` 提供协作指引；`agent-baseline.md` 与 `coding-conventions.md` 是项目工程约束的权威，本目录提供展开与实践材料。

## 顶层 3 个文件（不含本 README）

| # | 文件 | 作用 | 受众 |
|---|---|---|---|
| 01 | [agent-baseline.md](./agent-baseline.md) | AI agent 编程基线（设计原则 / 模块边界 / 持久层 / 命名 / 安全旁路）| 通用 AI 编码协作 |
| 02 | [coding-conventions.md](./coding-conventions.md) | 编码规约（22 章：方法参数 / FQN 禁令 / 异常 / API / 删除规范 / 时区 / 编码 / 安全旁路 / 代码模式实战）| 全员开发 |
| 03 | [changelog.md](./changelog.md) | 工程基线规范条款变化日志（按日期倒序）| 想知道"规范什么时候变的" |

## 主要子目录

| # | 目录 | 视角 | 关键入口 |
|---|---|---|---|
| 01 | [architecture/](./architecture/README.md) | 工程向 / 运行态架构 | [运行时兼容约束](./architecture/runtime-compatibility-contract-2026-09-01.md) / `system-flow-overview.md` |
| 02 | [design/](./design/README.md) | 业务向 / 静态设计 | `database-schema-guide.md` 表目录与关系图 |
| 03 | [api/](./api/README.md) | 前后端契约 | `console-api-protocol.md` + OpenAPI |
| 04 | [runbook/](./runbook/README.md) | 运维 SOP（应急 / 部署 / 容量 / 灰度 / 观测）| `incident-response.md` + `feature-switches.md` |
| 05 | [testing/](./testing/README.md) | 测试计划 / 覆盖矩阵 / release-gate | `full-project-test-plan.md` |
| 06 | [analysis/](./analysis/README.md) | 演进向：问题 / 修复 / 加固三件套 + 长期治理方案 + 项目评估 | `deep-issue-analysis.md` |
| 07 | [dict/](./dict/README.md) | **Reference dict**（错误码 / 配置键，自动生成）| `error-codes.md` + `config-keys.md` |
| 08 | [compliance/](./compliance/README.md) | 第三方依赖许可 + SBOM | `THIRD-PARTY-LICENSES.md` |
| 09 | [audit/](./audit/README.md) | 专项审计报告 + 漂移防护总账 | [convention-drift-guard-index.md](./audit/convention-drift-guard-index.md) |
| 10 | [backlog/](./backlog/README.md) | 专题治理背景与历史执行计划 | 当前待办仍以 `analysis/todo-master.md` 为准 |
| 11 | [plans/](./plans/README.md) | 阶段计划 | SDK roadmap / HA / 多租隔离计划 |
| 12 | [review/](./review/README.md) | 评审结论 | code review / project deep review |
| 13 | [sdk/](./sdk/README.md) | SDK 使用与接入 | BYO worker / quickstart |
| 14 | [stats/](./stats/README.md) | 规模统计 | LoC / 文档体量统计 |
| 15 | [test-data/](./test-data/test-full-coverage-import-suite/README.md) | 测试数据（Excel 配置包）| E2E 准备 |
| 16 | [verifications/](./verifications/README.md) | 验证记录 | 实测 / drill / go-live evidence |
| 17 | [standards/](./standards/README.md) | 文档状态、待办和归档治理 | 文档复扫与状态校准 |
| 18 | [governance/](./governance/README.md) | 机器可读治理契约 | CI 规则输入 |
| — | [archive/](./archive/README.md) | 历史快照（**不再维护**） | 仅审计参考 |
| — | [spike/](./spike/README.md) | Spike 实验记录 | 临时技术验证 |

## 常用角色路径

| 角色 | 推荐顺序 |
|---|---|
| 新人入门 | 顶层 02 coding-conventions → 子目录 01 architecture (`system-flow-overview.md` / `project-structure.md`) → 04 runbook (`local-development.md`) |
| 配置维护者 | 04 runbook (`first-tenant-config-quickstart.md` 手把手建第一个租户配置) → 下载 Excel 配置模板的「字段说明 / 五类Worker示例」sheet → `credential-matrix.md`(渠道/密码等凭据怎么存、怎么注入) |
| 业务开发 | 02 design → 03 api → 02 coding-conventions §22 代码模式 |
| 运维 / SRE | 04 runbook (`credential-matrix.md` 上线前逐行核对凭据 + prod fail-fast 项) → 01 architecture (`scalability-assessment.md`)|
| 救火值班 | 04 runbook (`incident-response.md` → `troubleshooting-decision-tree.md`) |
| 上线评审 | 05 testing (`release-gate.md`) → 04 runbook (`docker-deployment.md` 或 `feature-switches.md`)|
| 架构改动 | 01 architecture (`project-structure.md` / adr) → 顶层 03 changelog 追规范 |
| 规范复扫 / PR 审核 | 09 audit (`convention-drift-guard-index.md`) → `agent-baseline.md` → 顶层 02 coding-conventions → `scripts/ci/README.md` |
| 文档维护 / 目录调整 | 17 standards (`document-governance.md`) → 本 README → 受影响子目录 README → `scripts/ci/check-docs-structure.py` |
| AI 协作 | 顶层 01 agent-baseline → 02 coding-conventions |

## 维护约束（来自 agent-baseline.md）

- **API 文档同步**：改 `batch-console-api` 控制层，必须同步 `api/console-api-protocol.md` + `api/console-api.openapi.yaml`
- **规范变更日志**：修改 `agent-baseline.md` 已有规范条款，必须追加 `changelog.md`
- **Feature / Bug / 运维事件不进规范基线**：以 git commit + 对应模块文档（`architecture/*` / `runbook/*` / `analysis/*`）为权威
- **archive/ 只读**：归档文件不再维护，新内容写到主干目录
- **待办唯一入口**：当前待办以 [`analysis/todo-master.md`](./analysis/todo-master.md) 为准；`archive/` 中的待办只代表历史时点
- **文档状态治理**：详见 [`standards/document-governance.md`](./standards/document-governance.md)
- **索引同步**：新增、移动、归档文档时，必须同步本 README、对应子目录 README 和必要的根 README 入口；`check-docs-structure.py` 会检查主目录 README 覆盖和仓库内链接。
