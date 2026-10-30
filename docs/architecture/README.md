# 架构文档索引

系统架构、模型、原语、资产定义、边界、运行主链与 ADR；长期资产原位更新，单次核查记录放 audit。

> **第一次接触系统？** 先看 [`project-structure.md`](./project-structure.md) 确认工程边界，再看 [`system-flow-overview.md`](./system-flow-overview.md) 理解端到端链路。

Workflow 新根与系统 Pipeline 的实施范围见[整合方案](../plans/workflow-daily-integration-plan-2026-10-14.md)。本目录按当前开发分支代码更新；历史表/列或辅助实现保留不代表仍是新部署入口，也不代表正式验收全部完成。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [project-structure.md](./project-structure.md) | 当前 Maven reactor、平台运行时模块、Console 分层、SDK、文档与脚本目录边界 | 入门 / 加模块 / 查目录归属 |
| 02 | [system-flow-overview.md](./system-flow-overview.md) | 端到端业务流程总览（10+ Mermaid 图，含 BFF / 观测栈 / Workflow DAG / DLQ 子图） | 入门必看 |
| 03 | [core-model.md](./core-model.md) | 实例 / 状态 / 上下文 / 恢复模型的单一权威定义 | 写 orchestrator 状态机相关代码前 |
| 04 | [runtime-module-communication.md](./runtime-module-communication.md) | trigger / orchestrator / worker / console-api 模块间运行时通信拓扑 | 排查跨模块调用问题 |
| 05 | [kafka-topic-plan.md](./kafka-topic-plan.md) | Kafka Topic 命名 / 分区 / PATTERN 订阅规范 | 加新 topic 前 |
| 06 | [worker-plugins.md](./worker-plugins.md) | Worker 平台框架 + IMPORT / EXPORT / DISPATCH 插件扩展机制 | 写新 Worker 类型前 |
| 07 | [workflow-dependency-guide.md](./workflow-dependency-guide.md) | 租户 Workflow 完整运行流程图、运行态对象、DAG / GATEWAY / joinMode / CONDITION 编排指南 | 配 Workflow 或排查节点推进前 |
| 12 | [pipeline-vs-workflow-boundary.md](./pipeline-vs-workflow-boundary.md) | `pipeline_*` 与 `workflow_*` 职责边界（命名相似但不重叠） | 看到 pipeline / workflow 表名困惑时 |
| 13 | [governance-tables-boundary.md](./governance-tables-boundary.md) | `config_approval` vs `approval_command` / `subscription_rule` vs `alert_routing_config` 职责边界 | 看到 governance 表对疑似重叠时 |
| 14 | [notification-and-audit-boundary.md](./notification-and-audit-boundary.md) | 通知、技术告警、运行日志、业务审计的统一职责与复用边界 | 判断告警/通知/审计归属时 |
| 15 | [outbox-architecture.md](./outbox-architecture.md) | Outbox 主表 + 副表（retry / delivery_log）+ 兄弟表（trigger_outbox_event）总览 | 排障 / 改 outbox 子系统前 |
| 16 | [architecture-truth.md](./architecture-truth.md) | 当前架构事实的稳定入口与权威源清单 | 核对版本、模块、端口或迁移事实 |
| 17 | [adr/](adr) | 架构决策记录（决策结论只追加；实现状态可按证据更新） | 想知道"为什么这么做" |
| 19 | [application-governance.md](./application-governance.md) | 超时、运行时兼容、故障注入、告警、供应链五项应用治理入口 | 复核横切治理是否有代码和证据 |
| 20 | [bounded-context-rules.md](./bounded-context-rules.md) | bounded context 依赖方向和例外规则 | 调整跨域依赖前 |
| 21 | [event-routing-policy.md](./event-routing-policy.md) | 事件路由与 topic 选择规则 | 新增或迁移事件前 |
| 22 | [runtime-compatibility-contract.md](runtime-compatibility-contract.md) | Java、Shell、容器和协议运行时兼容契约 | 升级运行时前 |
| 23 | [engineering-maturity-roadmap.md](./engineering-maturity-roadmap.md) | 当前工程成熟度路线图,串联 Java、DB、运维、CI 和前端协作治理 | 规划下一批治理 / 上线准入复核 |
| 26 | [heavy-workload-guarantees.md](./heavy-workload-guarantees.md) | 重任务容量保障设计与已验证边界；目标环境容量仍需实测 | 重任务上线 / 定容 / 资源隔离前 |
| 27 | [security-model.md](./security-model.md) | 资产、信任边界、攻击者假设和安全验证入口 | 新增外部入口、执行能力或安全控制前 |

## 治理与撤销方案

工程治理要求见[工程成熟度路线图](engineering-maturity-roadmap.md)，容量边界见[容量治理](../runbook/production-capacity-governance.md)，当前事项以[TODO Master](../plans/todo-master.md)为准。旧评估、重复路线图和工作量分类中的有效内容已并入现行文档。

Quartz 时间轮替换提案已撤销；当前决策与原因见 [ADR-033](adr/ADR-033-quartz-to-wheel-scheduler.md)，运行容量基线见 [Quartz 容量基线](../runbook/quartz-capacity-baseline.md)。

## 角色路径

| 角色 | 路径 |
|---|---|
| 新人入门 | 01 → 02 → 03 |
| 运维 / SRE | [重任务保障](heavy-workload-guarantees.md) → [工程治理路线](engineering-maturity-roadmap.md) → [`../runbook/`](../runbook/README.md) |
| 业务开发 | 07 → 06 → [`../design/`](../design/README.md) |
| 架构改动 | [职责边界](pipeline-vs-workflow-boundary.md) → [ADR 索引](adr/README.md) → 写新 ADR |

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `architecture/`（本目录） | 工程向 / 运行态：流程图、模块通信、横切关注点、ADR |
| [`../design/`](../design/README.md) | 业务向 / 静态：数据模型 DDL、能力评估、链路设计 |
| [`../runbook/`](../runbook/README.md) | 运维向：部署、监控、灰度、应急、巡检 |
| [`../testing/`](../testing/README.md) | 质量向：测试计划、覆盖矩阵、release-gate |
| [`../plans/`](../plans/README.md) | 当前方案、事项与验收入口 |
| [`../api/`](../api/README.md) | 前后端契约：Console API 协议 + OpenAPI |
| [`../archive/`](../archive/README.md) | 历史快照：不再维护 |

## 本次分支资产核查

[五类系统资产逐文件核查](../audit/architecture-and-contract-assets-review-2026-10-24.md)记录 architecture/design/api/sdk/dict 的覆盖、修正与未验证边界；当前系统定义仍以各原文档为准，不转移到增量审计报告。

## 系统边界资产

| 文档 | 职责 |
|---|---|
| [system-scope-boundary.md](system-scope-boundary.md) | 系统职责范围分析 — 批量调度系统的边界守护 |
