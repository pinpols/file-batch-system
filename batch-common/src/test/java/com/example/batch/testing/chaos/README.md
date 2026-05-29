# Chaos / Toxiproxy IT 框架

## 设计

在 `AbstractIntegrationTest` 基础上加一层 Toxiproxy 代理,使 PG / Kafka / Redis 的连接经过代理,
测试中可动态注入故障(latency / slice / bandwidth limit / down)。

```
[App] ──(spring datasource url 指向 toxiproxy port)──> [Toxiproxy] ──> [真 PG container]
```

## 待补 IT(r3 validation-infra)

| IT 类 | 测什么 | 故障注入 |
|---|---|---|
| `KafkaLatencyToxicIT` | producer 超时 + 熔断打开 + retry path | `latency(toxic=500ms)` on kafka:9092 |
| `RedisDownToxicIT` | ShedLock 降级路径(`BATCH_SHEDLOCK_PROVIDER=jdbc` fallback) | `bandwidth(rate=0)` on redis:6379 |
| `PgConnectionResetToxicIT` | HikariCP 自愈 + outbox 不丢消息 | `reset_peer()` 中断连接 |

## 实现指引

1. 新建 `AbstractChaosIntegrationTest` 继承 `AbstractIntegrationTest`,在 `@BeforeAll` 起 Toxiproxy 容器并 wire `pg` / `kafka` / `redis` 代理
2. 提供 helper `withLatency(target, ms, block)` / `withSlice(target, block)` 等 lambda 接口
3. 测试方法只测一种故障,断言:错误码 / 重试次数 / 状态机最终态

## 参考

- Testcontainers Toxiproxy: https://java.testcontainers.org/modules/toxiproxy/
- 仓内现有 chaos 雏形:`OrchestratorWireMockSupport`(只 mock HTTP,本框架补 broker/db 层)
