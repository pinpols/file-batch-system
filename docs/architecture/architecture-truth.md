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
| 生产安全与运行参数 | [`helm/values-prod.yaml`](../../helm/values-prod.yaml)；网络拓扑由部署环境私有 values 注入，字段模板见 [`helm/values-site.example.yaml`](../../helm/values-site.example.yaml) |
| 数据库迁移版本 | [`db/migration/`](../../db/migration) |
| 当前待办 | [`plans/todo-master.md`](../plans/todo-master.md) |

2026-04-09 的完整架构快照已移至
[`archive/architecture/architecture-truth-2026-04-09.md`](../archive/architecture/architecture-truth-2026-04-09.md)，
仅用于审计历史判断，不得作为当前上线依据。

## 当前开发分支的模型事实

- 顶层运行统一为 WorkflowRun；受管单作业和组合DAG共用显式Workflow目标，JOB节点按需创建JobInstance。旧根Job包装、旧TASK派发与FILE_STEP不能因枚举/历史列仍在就视为新图支持。
- Job类型通过SystemPipelineCatalog选择固定文件Pipeline；租户Pipeline定义写入退役，实例/阶段观测保留。当前不支持嵌套Workflow。
- BatchDay是租户/日历/业务日的治理对象；开日登记义务与覆盖，不预建全部作业实例。业务日、区间和配置按运行冻结。
- Orchestrator维护状态与Outbox事务，主执行链仍是DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT。批量传输不合并逐项语义。
- 源码实现与发布验收分别判断：当前工作区的静态事实不能作为main已交付或目标环境已通过证明；验收进度以当前矩阵与TODO为准。

原语、资产身份与层级见[核心模型 §3](core-model.md#3-统一实例模型)，本轮逐份核查见[资产文档审计](../audit/architecture-and-contract-assets-review-2026-10-24.md)。
