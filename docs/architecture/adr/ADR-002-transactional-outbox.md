# ADR-002: 使用事务性 Outbox 模式发布 Kafka 事件，不直接写 Kafka

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

批量作业的分区分发完成后，需要向 Kafka 发布 `batch.partition.dispatched` 事件，Worker 节点监听该 topic 并认领任务。核心约束：

- **原子性**：分区记录写入数据库与事件发布必须同时成功或同时失败，不允许"数据库已有分区，但 Kafka 未收到事件"或反向情形。
- **至少一次**：网络瞬断后，消息必须可重试投递，不能丢失。
- **Worker 侧幂等**：Worker 收到重复消息时能够安全跳过（认领操作已有 CAS 保护）。

备选方案：

1. **直接在 `@Transactional` 方法中调用 `KafkaTemplate.send()`**：简单，但 Kafka 发送成功与数据库提交不原子；事务回滚后消息已发出无法撤回。
2. **CDC（Change Data Capture，如 Debezium）**：完全解耦，但引入额外基础设施（Kafka Connect 集群）；运维复杂度超出当前团队能力。
3. **事务性 Outbox**：在同一数据库事务中写入 `outbox_event` 表；独立 `OutboxForwarder` 轮询未投递记录并发布 Kafka，成功后标记 `published`。

## 决策

**采用方案 3（事务性 Outbox）**。

实现细节：

- `batch.outbox_event` 表结构：`id, topic, payload (jsonb), status (PENDING/PUBLISHED/FAILED), created_at, published_at, retry_count`
- `PartitionDispatchService.dispatch()` 在同一 `@Transactional` 方法中同时写 `job_partition` / `job_task` 和 `outbox_event`，原子提交。
- `OutboxForwarder`（`batch-orchestrator` 内的 `@Scheduled` bean）每 200ms 批量查询 `status = PENDING` 的记录，调用 `KafkaTemplate.send()`，成功后 UPDATE `status = PUBLISHED`。
- 发送失败时递增 `retry_count`，超过阈值标记 `FAILED` 并告警。

## 后果

**正面**：
- 分区写入与事件发布原子一致，无需分布式事务协议。
- Kafka 不可用时系统降级为"积压 outbox"，不影响数据库写入，重启后自动追发。
- Outbox 记录可作为事件审计日志。

**负面**：
- 引入额外的 `outbox_event` 表和 `OutboxForwarder` 组件。
- 端到端延迟增加约 0–200ms（轮询间隔）。
- `OutboxForwarder` 多实例部署时需要行级锁（`SELECT ... FOR UPDATE SKIP LOCKED`）防止重复投递。

## 测试覆盖

- `OutboxForwarderE2eIT`：验证正常路径下 Outbox 记录在轮询后被投递并标记 PUBLISHED。
- `OutboxForwarderRetryE2eIT`：使用 `@MockitoBean KafkaTemplate` 模拟发送失败，验证重试计数递增、最终标记 FAILED。
