# Chaos / Toxiproxy IT 框架

## 设计

在 `AbstractIntegrationTest` 基础上加一层 Toxiproxy 代理,使 PG / Kafka / Redis 的连接经过代理,
测试中可动态注入故障(latency / slice / bandwidth limit / down)。

```
[App] ──(spring datasource url 指向 toxiproxy port)──> [Toxiproxy] ──> [真 PG container]
```

## 已落地 IT(r3 validation-infra)

| IT 类 | 测什么 | 故障注入 |
|---|---|---|
| `KafkaLatencyToxicIT` | 短 delivery-timeout producer 超时(熔断器输入信号) | `withLatency(KAFKA, 800ms)` |
| `RedisDownToxicIT` | Lettuce / RedisLockProvider 快速失败,可被 jdbc fallback 接管 | `withDown(REDIS)` |
| `PgConnectionResetToxicIT` | 借出连接抛 SQLException + 故障消除后 Hikari 池自愈 | `withDown(PG)` |

## 实现

`AbstractChaosIntegrationTest` 继承 `AbstractIntegrationTest`,`@BeforeAll` 起 Toxiproxy 容器、
wire `pg_proxy` / `kafka_proxy` / `redis_proxy` 三个代理;`@AfterEach` 自动清空所有 toxic。

helper API(lambda 块自动 try/finally 回收 toxic):

```java
withLatency(ProxyTarget.KAFKA, Duration.ofMillis(500), () -> { ... });
withSlice(ProxyTarget.PG, 64, () -> { ... });
withDown(ProxyTarget.REDIS, () -> { ... });
```

端点 getter:`pgProxiedJdbcUrl()` / `kafkaProxiedBootstrapServers()` /
`redisProxiedHost():redisProxiedPort()` — 业务客户端连这些 URL/host 才走代理。

## 网络拓扑

Toxiproxy 容器与 PG/Kafka/Redis 不共享 Docker network。通过
`Testcontainers.exposeHostPorts(...)` 把后端容器的 host 端口暴露给 toxiproxy 容器,
upstream 设为 `host.testcontainers.internal:<host port>`:

```
[Test JVM] ─→ localhost:<toxiproxy mapped> ─→ [Toxiproxy] ─→ host.testcontainers.internal:<backend mapped> ─→ [真容器]
```

## 写新 Toxic IT 指引

1. `extends AbstractChaosIntegrationTest`
2. 业务客户端的 URL/host 用 `pgProxiedJdbcUrl()` / `kafkaProxiedBootstrapServers()` /
   `redisProxiedHost():redisProxiedPort()`,**不要**直连父类的 `platformJdbcUrl()` 等(不走代理)
3. 用例方法体内**先做健康路径预热**(无 toxic 时一次成功调用),防止用例本身不可达造成误报
4. 故障注入用 lambda block;block 退出后立即可断言"自愈"(toxic 已被 try-finally 移除)
5. `@DisplayName` 中文写**故障注入意图**(测什么路径,而非测什么 API)
6. client 配置短 timeout(Kafka delivery 2s / Lettuce command 2s / Hikari connection 2s),
   避免单 IT 卡 30s+

## 参考

- Testcontainers Toxiproxy: https://java.testcontainers.org/modules/toxiproxy/
- 仓内现有 chaos 雏形:`OrchestratorWireMockSupport`(只 mock HTTP,本框架补 broker/db 层)
