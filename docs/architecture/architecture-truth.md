# 当前架构事实入口

本文件保留稳定路径，但不再复制容易随版本漂移的模块数量、依赖版本、端口、迁移版本和测试统计。
当前事实按以下权威源读取：

| 主题 | 权威源 |
|---|---|
| 工程结构与模块边界 | [project-structure.md](./project-structure.md) |
| 端到端运行链路 | [system-flow-overview.md](./system-flow-overview.md) |
| 核心领域与状态模型 | [core-model.md](./core-model.md) |
| 模块间通信 | [runtime-module-communication.md](./runtime-module-communication.md) |
| 依赖和 Java 基线 | [`pom.xml`](../../pom.xml) |
| 环境变量与本地默认值 | [`.env.example`](../../.env.example) |
| 生产部署参数 | [`helm/values-prod.yaml`](../../helm/values-prod.yaml) |
| 数据库迁移版本 | [`db/migration/`](../../db/migration/) |
| 当前待办 | [`analysis/todo-master.md`](../analysis/todo-master.md) |

2026-04-09 的完整架构快照已移至
[`archive/architecture/architecture-truth-2026-04-09.md`](../archive/architecture/architecture-truth-2026-04-09.md)，
仅用于审计历史判断，不得作为当前上线依据。
