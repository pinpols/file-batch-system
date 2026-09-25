# Redis / MQ 抽象治理

## 结论

MQ 发布路径已具备低风险薄端口：新增 `MqMessagePublisher` / `MqMessage` / `MqPublishResult`，并把
Orchestrator outbox、Trigger launch relay、Worker DLQ 的发送动作收敛到该端口。Kafka 仍是当前生产实现，
topic、key、payload、headers、ACK、超时、delivery log、offset 提交语义均不改变。

Redis 触点目前不宜一次性替换。它承载的是多类不同能力：分布式锁、配额运行态、分片成员心跳、配置缓存失效、
SSE replay/pubsub、Console 会话/幂等/限流/设计锁。下一步应按能力域拆端口，不应抽一个泛化
`RedisClient`。

## 本轮 MQ 薄端口

| 调用方 | 原依赖 | 现依赖 | 运行语义 |
|---|---|---|---|
| Orchestrator `KafkaOutboxPublisher` | `KafkaTemplate` | `MqMessagePublisher` | outbox 状态更新、delivery log、trace restore 不变 |
| Trigger `KafkaTriggerEventPublisher` | `KafkaTemplate` / `ProducerRecord` | `MqMessagePublisher` | launch header、send timeout、失败重试信号不变 |
| Worker `DeadLetterPublisher` | `KafkaTemplate` | `MqMessagePublisher` | DLQ 5 秒限时、不 ack 触发 redelivery、metrics 不变 |

Kafka 适配器保留在各模块 infrastructure：

- `batch-orchestrator/.../KafkaMqMessagePublisher`
- `batch-trigger/.../KafkaMqMessagePublisher`
- `batch-worker/core/.../KafkaMqMessagePublisher`

这只是发布端抽象，不代表 Kafka consumer、offset commit、listener container 已可替换。

## Redis 能力盘点

| 能力域 | 当前实现位置 | 建议端口 | 优先级 | 说明 |
|---|---|---|---|---|
| ShedLock 调度互斥 | `BatchShedLockAutoConfiguration` / `ShedLockProviderFactory` | 保持 ShedLock 原生 `LockProvider` | P0 已足够 | 已有 JDBC / Redis provider 抽象，不重复封装 |
| Orchestrator 配额运行态 | `RedisQuotaRuntimeStateService` | `QuotaRuntimeStateService` 已存在 | P0 已足够 | 已有 DB fallback 实现，后续只需补一致性验证 |
| Orchestrator 动态分片成员 | `RedisShardAssignmentProvider` | `ShardAssignmentProvider` 已存在 | P0 已足够 | 已有 static / redis 实现，不扩大改造 |
| 配置失效广播 | `OrchestratorConfigInvalidationSubscriber`、`ConsoleConfigCacheInvalidationService` | `ConfigInvalidationBus` | P1 | 需要统一 publish / subscribe / local-evict 语义 |
| Console 实时事件 | `ConsoleRealtimeRedisPublisher`、`ConsoleRealtimeRedisPubSubConsumer`、`ConsoleRealtimeReplayStore` | `RealtimeEventBus` + `RealtimeReplayStore` | P1 | SSE 业务应依赖事件总线与 replay store，不依赖 Redis |
| Console 认证会话 | `ConsoleJwtService`、`ConsoleSessionRegistry`、`LoginFailureTracker` | `ConsoleSessionStore` / `LoginFailureStore` | P1 | 涉及安全语义，需单独测试覆盖 TTL、撤销、并发登录 |
| Console 幂等/限流/设计锁 | `ConsoleIdempotencyInterceptor`、`SlidingWindowRateLimiter`、`WorkflowDesignLockService` | `IdempotencyStore` / `RateLimitStore` / `DesignLockStore` | P2 | 可拆，但先不要改变 Redis 原子 Lua 语义 |

## 后续顺序

1. 保持本轮 MQ 发布端口为唯一新增运行改动，先观察 CI 与现有用例。
2. Redis 先从 Console 实时事件拆起：边界清晰、与核心 DB→Outbox→Kafka→Worker 主链隔离。
3. 配置失效广播第二批拆：需要兼顾 Console 发布、Orchestrator 订阅、多实例一致性指标。
4. 认证/幂等/限流/设计锁最后拆：涉及安全和并发契约，必须配套 TTL / Lua / 并发抢占测试。

## 明确不做

- 不改 Kafka topic、consumer group、listener container、offset commit、重试或 DLQ 语义。
- 不把 Redis 抽象成通用 KV 客户端，避免把业务语义退化成散乱 get/set。
- 不在同一 PR 替换 Redis 存储后端；本轮只做 MQ 发布薄端口和 Redis 治理盘点。
