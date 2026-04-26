# 架构文档索引

工程向运行态架构：系统流程、模块通信、扩展性评估、ADR 决策。

> **第一次接触系统？** 直接看 [`system-flow-overview.md`](./system-flow-overview.md)。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [system-flow-overview.md](./system-flow-overview.md) | 端到端业务流程总览（10+ Mermaid 图，含 BFF / 观测栈 / Workflow DAG / DLQ 子图） | 入门必看 |
| 02 | [architecture-truth.md](./architecture-truth.md) | 当前真实架构基线 + 与设计的差距清单 | 想知道"现在长什么样" |
| 03 | [core-model.md](./core-model.md) | 实例 / 状态 / 上下文 / 恢复模型的单一权威定义 | 写 orchestrator 状态机相关代码前 |
| 04 | [runtime-module-communication.md](./runtime-module-communication.md) | trigger / orchestrator / worker / console-api 模块间运行时通信拓扑 | 排查跨模块调用问题 |
| 05 | [kafka-topic-plan.md](./kafka-topic-plan.md) | Kafka Topic 命名 / 分区 / PATTERN 订阅规范 | 加新 topic 前 |
| 06 | [worker-plugins.md](./worker-plugins.md) | Worker 平台框架 + IMPORT / EXPORT / DISPATCH 插件扩展机制 | 写新 Worker 类型前 |
| 07 | [workflow-dependency-guide.md](./workflow-dependency-guide.md) | DAG / GATEWAY / joinMode (ALL / ANY / N_OF_M) / CONDITION 边的编排指南 | 配 Workflow 前 |
| 08 | [scalability-assessment.md](./scalability-assessment.md) | 千万级承载力评估（绿/黄/红 + 改造路线图，2026-04-25） | 容量规划 / 上量评估 |
| 09 | [rework-classification.md](./rework-classification.md) | scalability 评估的"改什么"分类（代码 / 配置 / 数据 / 运维 / SQL / 部署 / 文档） | 决定哪些项目立项、哪些当下办 |
| 10 | [quartz-replacement-design.md](./quartz-replacement-design.md) | Quartz → HashedWheelTimer 生产级实施设计 | 调度器选型 / 上线 |
| 11 | [quartz-replacement-evaluation.md](./quartz-replacement-evaluation.md) | Quartz 替换可行性评估 + 落地路径 | 同上，看决策上下文 |
| 12 | [adr/](./adr/) | 架构决策记录（不可变） | 想知道"为什么这么做" |

## 角色路径

| 角色 | 路径 |
|---|---|
| 新人入门 | 01 → 02 → 03 |
| 运维 / SRE | 08 → 09 → [`../runbook/`](../runbook/README.md) |
| 业务开发 | 07 → 06 → [`../design/`](../design/README.md) |
| 架构改动 | 12（全 ADR）→ 写新 ADR |

## 与其他子目录的分工

| 目录 | 视角 |
|---|---|
| `architecture/`（本目录） | 工程向 / 运行态：流程图、模块通信、横切关注点、ADR |
| [`../design/`](../design/README.md) | 业务向 / 静态：数据模型 DDL、能力评估、链路设计 |
| [`../runbook/`](../runbook/README.md) | 运维向：部署、监控、灰度、应急、巡检 |
| [`../testing/`](../testing/README.md) | 质量向：测试计划、覆盖矩阵、release-gate |
| [`../analysis/`](../analysis/README.md) | 演进向：深度问题分析 + 修复报告 + 硬化 backlog |
| [`../api/`](../api/README.md) | 前后端契约：Console API 协议 + OpenAPI |
| [`../archive/`](../archive/README.md) | 历史快照：不再维护 |
