# P2 项评估(2026-06-03)

> 深扫报告 P2 中 3 项涉及"批量改动 / 写 ADR / 文档化",评估 ROI 决定是否落地。

## P2-1:批量给查询 Service 加 `@Transactional(readOnly = true)` — **不做**

**判定**:不做。

**理由**:
- 项目持久化栈是 **MyBatis + JdbcTemplate**,不是 Hibernate。`readOnly = true` 在 MyBatis 路径下**不触发 flush mode = NEVER** 这类 ORM 优化,实际收益≈ 0。
- 读写分离 CLAUDE.md 明定**仅 `batch-console-api`**;`batch-trigger` / `batch-orchestrator` / `batch-worker-*` 禁引入(状态机依赖 read-after-write 强一致)。
- `batch-console-api` 已经基于自定义机制(`@ConsoleReadReplica` 注解 + AOP)路由读副本,**不依赖 `@Transactional(readOnly = true)`**。
- 批量铺开会引入认知负担:"为什么这些 readOnly 不生效"。新人易误以为 readOnly = 走副本。

**替代**:保持现状。若个别 console-api 查询要明确走副本,继续用 `@ConsoleReadReplica`,语义清晰。

## P2-2:`DbRowExistsSensorPolicy` 隔离语义文档化 — **做(轻)**

**判定**:做。1 段文档加进 `docs/design/workflow-sensors.md`(若无则新建)。

**理由**:
- 多租 sensor 等待业务行出现,**租户隔离 + 锁语义**不写明易被误用:
  - 跨租户读(忘 `WHERE tenant_id = ?`)
  - 长事务持锁(等待 30 分钟,锁住业务表)
  - 与归档进程冲突(冷表移除后误判"行消失")
- 一段文档(50-100 行)讲清楚:租户隔离强制 / 不持锁 / 归档边界 / 重试节奏。
- 已留作 follow-up(本 PR 不写,留给 `docs/design/sensor-policies.md` 单独成文)。

## P2-3:RoutingHints + `@Async` 互动 ADR + CLAUDE.md — **不做**

**判定**:不做。本评估文档作为 archival 记录。

**理由**:
- Routing hints(ADR-027 资源亲和)决定**worker 选择**,在 `outbox_event` INSERT 时就已锁定 hints,后续不可变。
- `@Async` 切线程池**不影响** routing — 因为 routing 在写 outbox 时已决,@Async 方法即使被异步执行,也不会改变已写入的 hints。
- 二者交互**没有真实 conflict surface**;写 ADR 是为不存在的问题立规矩,反而误导后人以为存在风险。
- CLAUDE.md 是高频违反红线集中地,加这条会稀释信号噪音比。

**结论**:已知边界,不立规矩,不写文档。如果未来真遇到问题,再补写。

---

## 总结表

| 项 | 决定 | 理由(一句话) |
|---|---|---|
| P2-1 readOnly 批量加 | 不做 | MyBatis 不享 ORM 优化,console-api 已有专用 `@ConsoleReadReplica` |
| P2-2 sensor 隔离语义文档化 | 做(轻) | 文档成本低,误用风险真实 |
| P2-3 RoutingHints × @Async ADR | 不做 | 无真实 conflict surface,立规矩稀释 CLAUDE.md 信号 |
