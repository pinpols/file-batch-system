# ADR 索引（架构决策记录）

不可变的"为什么这么做"。**新决策只追加，旧 ADR 不改**（出现新结论就写新 ADR 引用旧 ADR）。

## ADR 列表（编号即时间序）

| # | 文件 | 决策摘要 |
|---|---|---|
| 001 | [ADR-001-dual-orm.md](./ADR-001-dual-orm.md) | 持久层用 MyBatis（运行态）+ Spring Data JDBC（配置态），不引入 JPA |
| 002 | [ADR-002-transactional-outbox.md](./ADR-002-transactional-outbox.md) | 使用事务性 Outbox 模式发布 Kafka，避免双写不一致 |
| 003 | [ADR-003-launch-t1-t2-split.md](./ADR-003-launch-t1-t2-split.md) | `launch()` 拆 T1/T2 两事务 + CGLIB 自注入解决 `@Transactional` 自调用 |
| 004 | [ADR-004-worker-lifecycle-template.md](./ADR-004-worker-lifecycle-template.md) | Worker 生命周期用模板方法模式，子类只填扩展点 |
| 005 | [ADR-005-partition-count-resolver-chain.md](./ADR-005-partition-count-resolver-chain.md) | 分区数解析用责任链（job override → tenant default → global default） |
| 006 | [ADR-006-compensation-requires-new.md](./ADR-006-compensation-requires-new.md) | 补偿 / 重试方法用 `REQUIRES_NEW`，避免外层事务 rollback 把补偿也回滚 |
| 007 | [ADR-007-dual-datasource.md](./ADR-007-dual-datasource.md) | 单 PG 实例双 schema 隔离 platform / business |
| 008 | [ADR-008-god-class-decomposition.md](./ADR-008-god-class-decomposition.md) | God Class 分解为子服务 + Facade 模式（实例：`DefaultLaunchApplicationService`）|
| 009 | [ADR-009-workflow-param-dsl.md](./ADR-009-workflow-param-dsl.md) | Workflow 节点间参数串联 DSL（JSONPath-like `$.nodes.X.output.fileId`，分 4 stage 落地，~3 人天） |
| 010 | [ADR-010-trigger-async-decoupling.md](./ADR-010-trigger-async-decoupling.md) | Trigger → Orchestrator 异步解耦（trigger_outbox + Kafka，复用 ADR-002 模式，~7-8 人天分 7 stage） |

## 写新 ADR 的姿势

1. **看上下文**先翻 [`../architecture-truth.md`](../architecture-truth.md) 和相关现有 ADR
2. **新决策**编号 +1，不改老 ADR；如果推翻老 ADR，在新 ADR 里 explicit "Supersedes ADR-NNN"
3. ADR 模板：背景 / 决策 / 理由 / 后果（含负面）/ 替代方案为什么不选

## 相关入口

| 主题 | 文档 |
|---|---|
| 当前架构基线 | [`../architecture-truth.md`](../architecture-truth.md) |
| 系统总流程 | [`../system-flow-overview.md`](../system-flow-overview.md) |
| 模块通信拓扑 | [`../runtime-module-communication.md`](../runtime-module-communication.md) |
