# Design Docs — 文档索引

按章节切分的设计文档（沿用原 8000 行《批量调度系统设计说明》的章节号）。
master 完整版已归档为 [`../archive/design/system-design-2026-03-21.md`](../archive/design/system-design-2026-03-21.md)
（仅作历史快照，不再维护；以下逐章节文件为权威）。

## 章节速查

| 章节主题 | 文件 |
|---|---|
| 批次日（业务日历）设计 | [batch-day-design.md](batch-day-design.md) |
| 系统能力评估 | [capability-assessment.md](capability-assessment.md) |
| 删除策略（job_instance / file_record / outbox 等） | [delete-strategy.md](delete-strategy.md) |
| 数据模型 DDL | [data-model-ddl.md](data-model-ddl.md) |
| 文件处理链路 | [file-pipeline-design.md](file-pipeline-design.md) |
| 日志架构 | [logging-architecture.md](logging-architecture.md) |
| 模块结构 / POM | [project-structure-pom.md](project-structure-pom.md) |
| Redis 使用设计 | [redis-usage-design.md](redis-usage-design.md) |
| 运行时默认参数 | [runtime-default-parameters.md](runtime-default-parameters.md) |
| Console 侧边栏菜单树 | [console-sidebar-menu-tree.md](console-sidebar-menu-tree.md) |
| API gap 分析 | [api-gap-analysis.md](api-gap-analysis.md) |

## 与 architecture/ 的分工

- **design/** = 业务向 / 静态设计（数据模型、链路、能力评估）
- **architecture/** = 工程向 / 运行态架构（系统流程、模块通信、ADR、横切关注点）
