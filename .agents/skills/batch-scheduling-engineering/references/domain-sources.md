# 批量调度领域来源

这些路径是阅读入口，不替代当前代码。按具体任务阅读相关章节，避免加载无关文档。

| 领域 | 入口 |
|---|---|
| 核心架构和模块所有权 | `docs/architecture/README.md`、`docs/architecture/project-structure.md`、`docs/architecture/system-flow-overview.md`、`docs/architecture/core-model.md` |
| Trigger 与业务日 | `docs/runbook/trigger-operations.md`、`docs/design/batch-day-timezone-dst-optimized-design.md`、`docs/architecture/adr/README.md` 中仍有效的 Trigger/日历 ADR |
| Outbox 与消息路由 | `docs/architecture/outbox-architecture.md`、`docs/architecture/kafka-topic-plan.md`、`docs/architecture/event-routing-policy.md` |
| Worker、pipeline 与 SDK | `docs/architecture/worker-plugins.md`、`docs/architecture/runtime-module-communication.md`、`docs/sdk/README.md`、`docs/api/sdk-contract-fixtures/` |
| 数据模型、状态和迁移 | `docs/design/README.md`、`docs/architecture/architecture-truth.md`、`db/migration/` 与对应 Mapper/Repository |
| 恢复、运维和验收 | `docs/runbook/README.md`、`docs/runbook/incident-response.md`、`docs/testing/README.md`、`docs/verifications/README.md` |
| 当前事项 | `docs/analysis/todo-master.md`、`docs/analysis/hardening-backlog.md` |
| 历史教训 | `docs/audit/README.md`、`docs/review/README.md`、`docs/analysis/README.md` 和 `docs/archive/README.md`；归档审计只作为历史案例，必须回到当前实现复核 |

若文档与实现冲突，记录冲突并核实有效 ADR、迁移、运行配置和当前测试；不要默默把旧文档升级为现状。
