# ADR 索引

这里收纳批量调度系统的架构决策记录。

## ADR 列表

- [ADR-001-dual-orm.md](./ADR-001-dual-orm.md) - 使用 MyBatis + Spring Data JDBC 双 ORM，不引入 JPA
- [ADR-002-transactional-outbox.md](./ADR-002-transactional-outbox.md) - 使用事务性 Outbox 模式发布 Kafka 事件
- [ADR-003-launch-t1-t2-split.md](./ADR-003-launch-t1-t2-split.md) - `launch()` 的 T1/T2 事务拆分与 CGLIB 自注入
- [ADR-004-worker-lifecycle-template.md](./ADR-004-worker-lifecycle-template.md) - Worker 生命周期使用模板方法模式
- [ADR-005-partition-count-resolver-chain.md](./ADR-005-partition-count-resolver-chain.md) - 分区数量解析使用责任链模式
- [ADR-006-compensation-requires-new.md](./ADR-006-compensation-requires-new.md) - 补偿/重试方法使用 `REQUIRES_NEW`
- [ADR-007-dual-datasource.md](./ADR-007-dual-datasource.md) - 单 PostgreSQL 实例双 Schema 隔离
- [ADR-008-god-class-decomposition.md](./ADR-008-god-class-decomposition.md) - God Class 分解为子服务 + Facade 模式

## 使用建议

- 先看 `architecture-truth.md` 和 `implementation-status.md`，再回头看 ADR 能更快理解决策上下文
- 新增架构决策时，优先补 ADR，再回写总索引
