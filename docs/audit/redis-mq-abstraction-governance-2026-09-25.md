# Redis / MQ 抽象治理

## 结论

MQ 发布路径已具备低风险薄端口：新增 `MqMessagePublisher` / `MqMessage` / `MqPublishResult`，并把
Orchestrator outbox、Trigger launch relay、Worker DLQ 的发送动作收敛到该端口。Kafka 仍是当前生产实现，
topic、key、payload、headers、ACK、超时、delivery log、offset 提交语义均不改变。

Redis 不抽通用客户端，只按业务能力拆薄端口。本轮已完成 4 类应该抽象的能力：Console 实时事件、
配置失效广播、Console 认证会话、Console 幂等/限流/设计锁。Redis 仍是生产实现，key、TTL、Lua 原子性、
fail-open/fail-closed 语义均不改变。

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
| 配置失效广播 | `ConsoleConfigCacheInvalidationService` | `ConfigInvalidationStore` | 已完成 | application 层只依赖端口；Redis 实现放在 infrastructure |
| Console 实时事件 | `ConsoleRealtimeRedisPublisher`、`ConsoleRealtimeReplayStore` | `RealtimeEventBus` + `RealtimeReplayStore` | 已完成 | SSE hub / summary stream 依赖端口，Redis Pub/Sub 通道不变 |
| Console 认证会话 | `ConsoleSessionRegistry`、`LoginFailureTracker` | `ConsoleSessionStore` / `LoginFailureStore` | 已完成 | 会话版本、TTL、登录失败窗口语义不变 |
| Console 幂等/限流/设计锁 | `ConsoleIdempotencyInterceptor`、`SlidingWindowRateLimiter`、`WorkflowDesignLockService` | `ConsoleIdempotencyStore` / `RateLimitStore` / `DesignLockStore` | 已完成 | Redis Lua 原子脚本移动到 Redis store，业务入口不直连 Redis |

## 本轮 Redis 薄端口

| 能力 | 端口 | Redis 实现 | 保留的关键契约 |
|---|---|---|---|
| 实时跨实例发布 | `RealtimeEventBus` | `ConsoleRealtimeRedisPublisher` | `batch:console:realtime` channel、origin instance 跳过、replay append |
| SSE 断线回放 | `RealtimeReplayStore` | `ConsoleRealtimeReplayStore` | list buffer、LRANGE 回放、cursor miss 语义 |
| 配置失效 | `ConfigInvalidationStore` | `RedisConfigInvalidationStore` | config key 删除、全局 revision、key revision、失效事件发布 |
| 单会话版本 | `ConsoleSessionStore` | `RedisConsoleSessionStore` | `INCR`、TTL、Redis 故障时本地镜像降级 |
| 登录失败风控 | `LoginFailureStore` | `RedisLoginFailureStore` | Sorted Set 滑动窗口、账号/IP 两维度计数 |
| 写请求幂等 | `ConsoleIdempotencyStore` | `RedisConsoleIdempotencyStore` | PENDING/DONE 两阶段、Redis 故障 fail-closed |
| 滑动窗口限流 | `RateLimitStore` | `RedisRateLimitStore` | Lua 原子检查 + 写入，固定 60 秒窗口 |
| 设计器编辑锁 | `DesignLockStore` | `RedisDesignLockStore` | SETNX、持锁人释放/续期 Lua 原子校验 |

## 后续顺序

1. 继续保持 Kafka consumer、offset commit、listener container 原生实现，不在当前阶段抽象。
2. Orchestrator Redis 配额、分片成员、ShedLock 已有对应端口或第三方抽象，暂不重复封装。
3. 后续如果评估替换 Redis，只能按上述能力端口逐项做 conformance，不允许新增泛化 KV 调用。

## 明确不做

- 不改 Kafka topic、consumer group、listener container、offset commit、重试或 DLQ 语义。
- 不把 Redis 抽象成通用 KV 客户端，避免把业务语义退化成散乱 get/set。
- 不在同一 PR 替换 Redis 存储后端；本轮只做 MQ 发布薄端口和四类 Redis 能力薄端口。
