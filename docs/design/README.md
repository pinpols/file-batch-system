# 设计文档索引

业务向静态设计：数据模型、链路、能力评估。沿用原 8000 行《批量调度系统设计说明》的章节号切分。
完整 mega 版已归档为 [`../archive/design/system-design-2026-03-21.md`](../archive/design/system-design-2026-03-21.md)（仅历史快照，**不**再维护）。

## 文件清单（编号即推荐阅读顺序）

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [data-model-ddl.md](./data-model-ddl.md) | 全表 DDL（job_instance / outbox / workflow_* 等核心表）| 改 schema / 排查数据 |
| 02 | [batch-day-design.md](./batch-day-design.md) | 批次日（business_calendar / batch_day_instance）设计 | 配批次窗口 |
| 03 | [file-pipeline-design.md](./file-pipeline-design.md) | 文件处理链路（preprocess / receive / dispatch / publish）| 写新 file step |
| 04 | [redis-usage-design.md](./redis-usage-design.md) | Redis 使用清单（quota / rate-limit / cache / pub-sub / SSE replay）| 改任何 Redis 相关代码 |
| 05 | [logging-architecture.md](./logging-architecture.md) | 日志架构（MDC / 结构化 / Loki 分发） | 加日志埋点前 |
| 06 | [delete-strategy.md](./delete-strategy.md) | 删除策略（job_instance / file_record / outbox archive 等）| 删数据前 |
| 07 | [capability-assessment.md](./capability-assessment.md) | 系统能力矩阵评估 | PD 问"我们能做 X 吗" |
| 08 | [project-structure-pom.md](./project-structure-pom.md) | 模块结构 / POM 依赖关系 | 加新模块前 |
| 09 | [runtime-default-parameters.md](./runtime-default-parameters.md) | 运行时默认参数基线（pool / timeout / batch size 等）| 调参前看默认值 |
| 10 | [console-sidebar-menu-tree.md](./console-sidebar-menu-tree.md) | 控制台侧边栏菜单树 + 角色可见性 | 加 console 页面 |
| 11 | [api-gap-analysis.md](./api-gap-analysis.md) | Console API 设计与实现差距分析 | 补接口前 |

## 与 architecture/ 的分工

| 目录 | 视角 |
|---|---|
| `design/`（本目录） | 业务向 / 静态设计：DDL、链路、能力 |
| [`../architecture/`](../architecture/README.md) | 工程向 / 运行态：流程图、模块通信、ADR |

## 与其他子目录的关系

| 主题 | 静态设计 | 运行态架构 | 运维 SOP |
|---|---|---|---|
| 数据模型 | 01 data-model-ddl | [`../architecture/core-model.md`](../architecture/core-model.md) | [`../runbook/pg-table-partitioning.md`](../runbook/pg-table-partitioning.md) |
| 文件链路 | 03 file-pipeline-design | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §4 | [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) |
| Redis | 04 redis-usage-design | — | [`../runbook/feature-switches.md`](../runbook/feature-switches.md) |
| 日志 | 05 logging-architecture | — | [`../runbook/observability-stack.md`](../runbook/observability-stack.md) |
