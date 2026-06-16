# docs/ 文档总入口

整个 `docs/` 目录的导航。**新人从这里开始**。

> CLAUDE.md（项目根）是规范条款的权威；本目录是该规范的"展开 + 落地"。

## 顶层 4 个文件

| # | 文件 | 作用 | 受众 |
|---|---|---|---|
| 01 | [agent-baseline.md](./agent-baseline.md) | AI agent 编程基线（设计原则 / 模块边界 / 持久层 / 命名 / 安全旁路）| AI 协作（含 Claude） |
| 02 | [coding-conventions.md](./coding-conventions.md) | 编码规约（22 章：方法参数 / FQN 禁令 / 异常 / API / 删除规范 / 时区 / 编码 / 安全旁路 / 代码模式实战）| 全员开发 |
| 03 | [changelog.md](./changelog.md) | CLAUDE.md 规范条款变化日志（按日期倒序，CLAUDE.md 硬引）| 想知道"规范什么时候变的" |

## 7 个子目录（每个都有自己的 README + 编号化清单）

| # | 目录 | 视角 | 关键入口 |
|---|---|---|---|
| 01 | [architecture/](./architecture/README.md) | 工程向 / 运行态架构 | `system-flow-overview.md` 一图看完整链路 |
| 02 | [design/](./design/README.md) | 业务向 / 静态设计 | `data-model-ddl.md` 全表 schema |
| 03 | [api/](./api/README.md) | 前后端契约 | `console-api-protocol.md` + OpenAPI |
| 04 | [runbook/](./runbook/README.md) | 运维 SOP（应急 / 部署 / 容量 / 灰度 / 观测）| `incident-response.md` + `feature-switches.md` |
| 05 | [testing/](./testing/README.md) | 测试计划 / 覆盖矩阵 / release-gate | `full-project-test-plan.md` |
| 06 | [analysis/](./analysis/README.md) | 演进向：问题 / 修复 / 加固三件套 + 长期治理方案 + 项目评估 | `deep-issue-analysis.md` |
| 07 | [dict/](./dict/README.md) | **Reference dict**（错误码 / 配置键，自动生成）| `error-codes.md` + `config-keys.md` |
| 08 | [compliance/](./compliance/README.md) | 第三方依赖许可 + SBOM | `THIRD-PARTY-LICENSES.md` |
| — | [archive/](./archive/README.md) | 历史快照（**不再维护**） | 仅审计参考 |
| — | [test-data/](./test-data/test-full-coverage-import-suite/README.md) | 测试数据（Excel 配置包）| E2E 准备 |

## 常用角色路径

| 角色 | 推荐顺序 |
|---|---|
| 新人入门 | 顶层 02 coding-conventions → 子目录 01 architecture (`system-flow-overview.md`) → 04 runbook (`local-development.md`) |
| 配置维护者 | 04 runbook (`first-tenant-config-quickstart.md` 手把手建第一个租户配置) → 下载 Excel 配置模板的「字段说明 / 四类Worker示例」sheet → `credential-matrix.md`(渠道/密码等凭据怎么存、怎么注入) |
| 业务开发 | 02 design → 03 api → 02 coding-conventions §22 代码模式 |
| 运维 / SRE | 04 runbook (`credential-matrix.md` 上线前逐行核对凭据 + prod fail-fast 项) → 01 architecture (`scalability-assessment.md`)|
| 救火值班 | 04 runbook (`incident-response.md` → `troubleshooting-decision-tree.md`) |
| 上线评审 | 05 testing (`release-gate.md`) → 04 runbook (`docker-deployment.md` 或 `feature-switches.md`)|
| 架构改动 | 01 architecture/adr → 顶层 03 changelog 追规范 |
| AI 协作 | 顶层 01 agent-baseline → 02 coding-conventions |

## 维护约束（来自 CLAUDE.md）

- **API 文档同步**：改 `batch-console-api` 控制层，必须同步 `api/console-api-protocol.md` + `api/console-api.openapi.yaml`
- **规范变更日志**：改 CLAUDE.md 已有规范条款，必须追加 `changelog.md`
- **Feature / Bug / 运维事件不进 CLAUDE.md**：以 git commit + 对应模块文档（`architecture/*` / `runbook/*` / `analysis/*`）为权威
- **archive/ 只读**：归档文件不再维护，新内容写到主干目录
