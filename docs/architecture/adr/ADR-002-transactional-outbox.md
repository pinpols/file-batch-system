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

---

## 当前状态（2026-05-01）

> ⚠️ **本节是真实代码事实，与上文"决策"段在字段名 / 状态机 / 类名 / 轮询机制上不一致。原 ADR 是初版决策意图，五周演化后实现已显著扩展。改动 outbox 子系统时以本节为准；上文保留作为历史决策追溯。**

### 表结构（V7 + V80 / 实际）

`batch.outbox_event`（业务事件 outbox，dispatch / retry / reclaim 全走它）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | 自增主键 |
| `tenant_id` | VARCHAR(64) | 多租户隔离键 |
| `aggregate_type` | VARCHAR(64) | `JOB_TASK` 等聚合类型 |
| `aggregate_id` | BIGINT | 关联实体主键 |
| `event_type` | VARCHAR(64) | 事件类型，由 `BatchTopicResolver.resolve(eventType, msg)` 推导 Kafka topic |
| `event_key` | VARCHAR(256) | **幂等键**，`uk_outbox_event_key UNIQUE (tenant_id, event_key)` 回退；`insert ... on conflict do nothing` |
| `payload_json` | JSONB | `TaskDispatchMessage` v2 序列化 |
| `publish_status` | VARCHAR(32) | **5 态**：`NEW / PUBLISHING / PUBLISHED / FAILED / GIVE_UP` |
| `publish_attempt` | INTEGER | 进入 PUBLISHING 时 +1（语义"尝试发送次数"） |
| `next_publish_at` | TIMESTAMPTZ | 下次重试时间，selectPending 用 `<= current_timestamp` 过滤 |
| `trace_id` | VARCHAR(128) | 链路追踪 |
| `created_at / updated_at` | TIMESTAMPTZ | 行创建/更新时间 |

旁路审计表：
- `event_outbox_retry`：每次失败一行，记录 attempt / next_retry_at / reason
- `event_delivery_log`：每次 Kafka send 一行，记录 topic / partition / offset / latency

V80 另起 `batch.trigger_outbox_event`（trigger → orchestrator 异步 launch 链路专用，schema 同构 outbox_event 但独立表）。

### 状态机

```
            insert
        ┌──────────────────────────────┐
        │                              ▼
     [NEW]                        markPublishing (CAS, NEW|FAILED→PUBLISHING)
        │                              │  publish_attempt += 1
        │                              ▼
        │                         [PUBLISHING] ──── Kafka send 阶段三 ────┐
        │                              │                                  │
        │                              │ stale > 120s                     │
        │                              │ resetStalePublishing             │
        │                              ▼                                  │
        │                         [FAILED] ◄──── markFailed (next_publish_at = now + backoff)
        │                              │
        │                              │ publish_attempt >= 5
        │                              ▼
        │                         [GIVE_UP] ── 终态，需手工 republish
        │
        ▼
   [PUBLISHED] ── 终态，归档/清理 cutoff 后删除
```

`selectPending` 拉 `NEW + FAILED`（PUBLISHING 不拉，由 `resetStalePublishing` 拨回）。GIVE_UP 转入条件：`publishAttempt >= maxRetryAttempts(=5)`。

### 关键类（替代 ADR 决策段的 `OutboxForwarder` / `PartitionDispatchService`）

| 角色 | 类 | 备注 |
|---|---|---|
| Outbox 写入（同事务 MANDATORY） | `TaskDispatchOutboxService.writeDispatchEvent()` | 7 个调用点：`DefaultPartitionDispatchService` / `DefaultWorkflowNodeDispatchService` / `DefaultFileGovernanceService` / `DefaultRetryGovernanceService`(2 处) / `WaitingPartitionDispatchScheduler` / `PartitionLeaseReclaimScheduler` |
| 调度轮询 | `OutboxPollScheduler` | 自适应 `ScheduledExecutorService`（min `pollIntervalMillis.min` ~ max `pollIntervalMillis`，backoff 1.5x，busy 时立即下一轮，idle 退避） |
| 三阶段推送 | `DefaultScheduleForwarder.advance()` | ①批量 markPublishing → ②并行 `outboxPublisher.publish()` Kafka 异步 → ③`allOf().join()` 等齐 → 批量回写 PUBLISHED / FAILED / GIVE_UP |
| Kafka 发布器 | `OutboxPublisher` 接口 / `KafkaOutboxPublisher` 实现 | 异步 `CompletableFuture<Boolean>`；topic 由 `BatchTopicResolver` 按 eventType 动态解析 |
| 熔断 | `OutboxPublishCircuitBreaker` | 失败率超阈值时整轮跳过 advance |
| 多实例并发 | ShedLock + 应用层 sharding | **不用** `SELECT ... FOR UPDATE SKIP LOCKED` |
| 归档 | `OutboxArchiveService` + `OutboxArchiveScheduler` | PUBLISHED / GIVE_UP 行 cutoff 后归档到 `archive.outbox_event_archive`（同步搬 retry / delivery log） |
| 运维 | `OutboxOpsController` | console-api 通过 `ConsoleOrchestratorProxyService` 调；支持 `/cleanup`（按 retainDays 删 PUBLISHED+GIVE_UP）+ `/republish`（FAILED/GIVE_UP → NEW） |

### 多实例并发控制

ShedLock + 应用层 sharding 协作（**不用** SKIP LOCKED）：

- **STATIC sharding**：`OutboxProperties.shardIndex/shardTotal` 从 ENV 注入；多 instance 各自配 `shardIndex`，selectPending SQL 用 `(hashtext(tenant_id) & 2147483647) % shardTotal = shardIndex` 物理分流
- **DYNAMIC sharding**：`RedisShardAssignmentProvider` 周期心跳（POD_NAME / hostname 注入），rebalance 时短暂重叠由 `markPublishing` 的 CAS 回退
- **ShedLock 锁名**：`shardTotal=1` → `outbox_poll`；`shardTotal>1` → `outbox_poll_shard_<index>`，每 shard 独立锁，instance 间不互锁
- **锁参数**：`LOCK_AT_MOST=120s`（与 `publishingTimeoutSeconds` 对齐，确保锁过期时 stale 重置已生效）/ `LOCK_AT_LEAST=200ms`（让闲置轮询不长占锁）

### 重试与退避（2026-05-01 hardening）

- `OutboxProperties.maxRetryAttempts=5` / `retryDelaySeconds=60`（基础值）
- `retryBackoffMultiplier=2.0` / `retryMaxDelaySeconds=600` / `retryJitterRatio=0.2` ← 2026-05-01 加，避免 thundering herd
- 重试间隔公式：`min(base × multiplier^(attempt-1), max) × (1 ± jitter)`
- 例：attempt 1=60s ± 12s, 2=120s ± 24s, 3=240s ± 48s, 4=480s ± 96s, 5(=GIVE_UP 前最后一次)=600s ± 120s

### 死信 / GIVE_UP

- **outbox 自身 GIVE_UP**：5 次失败后转终态，无主动告警 ❌（依赖 metric pull）
- **任务消费 DLQ**：`BatchTopics.TASK_DEAD_LETTER` topic 已定义，`worker-core/DeadLetterPublisher` 实现，但 **orchestrator 端尚未配 listener 消费**（依赖 `uk_job_instance_tenant_dedup` 防重复）

### 测试覆盖（实际类名，替代 ADR 决策段的 `OutboxForwarderE2eIT` / `OutboxForwarderRetryE2eIT`）

- `OutboxPublishIntegrationTest` — 端到端正常路径
- `OutboxEventToKafkaDispatchIntegrationTest` — outbox 写入 → Kafka 派发链路
- `OutboxPollSchedulerTest` — 轮询调度
- `KafkaOutboxPublisherTest` — Kafka 发布器
- `OutboxPublishCircuitBreakerTest` — 熔断
- `OutboxArchiveServiceTest` — 归档
- `TaskDispatchOutboxServiceMandatoryTest` — MANDATORY 事务传播
- `JobTypeOutboxChainIntegrationTest` — 4 worker type × outbox 链路
- `DefaultScheduleForwarderRetryBackoffTest`（2026-05-01 加）— 指数退避 + jitter

### 演化时间线（追溯历史决策）

| 日期 | 演化 |
|---|---|
| 2026-03-25 | 初版决策（PENDING/PUBLISHED/FAILED 三态，`OutboxForwarder` 200ms 固定调度，`SELECT FOR UPDATE SKIP LOCKED`） |
| ~2026-04-01 | 状态机改 5 态（加 PUBLISHING + GIVE_UP），`OutboxPollScheduler` 自适应化 |
| ~2026-04-10 | 引入 ShedLock + 应用层 sharding（弃用 SKIP LOCKED） |
| ~2026-04-15 | 加 `OutboxPublishCircuitBreaker` + stale PUBLISHING 重置 |
| ~2026-04-20 | 加 archive 冷归档 + retry/delivery 审计副表 |
| 2026-04-30 | V80 加 `trigger_outbox_event`（ADR-010 trigger 异步链路） |
| 2026-05-01 | 加指数退避 + jitter + GIVE_UP counter + ShedLock 时长校准 + 中心化 EventKeyGenerator |
