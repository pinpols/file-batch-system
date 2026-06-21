# Plan #1 — Chaos / Toxiproxy IT 框架

> r3 validation-infra · 优先级 P0 · 估时 1 天

## 目标
在现有 Testcontainers IT 基础上加 Toxiproxy 代理层,让 PG / Kafka / Redis 的连接经代理而不直连容器,
测试中可动态注入 latency / slice / bandwidth limit / down 等故障,验证应用的熔断 / 降级 / 自愈路径。

## 价值
- 现有 23+ `*E2eIT` 验**业务路径**,但不验**基础设施故障下的行为**(Kafka 延迟、PG 断连、Redis 宕)
- 现有 `OrchestratorWireMockSupport` 只 mock HTTP,broker / db 层零覆盖
- 上线后这类故障一旦发生 → 复盘没单测兜底无法 reproduce → 修复后无回归保护

## 范围

**In scope**:
- `AbstractChaosIntegrationTest` 基类(继承 `AbstractIntegrationTest`)
- Toxiproxy 容器编排 + PG/Kafka/Redis 代理 wiring
- helper lambda:`withLatency(target, ms, block)` / `withSlice(target, block)` / `withDown(target, block)`
- 3 个示范 IT(覆盖 3 类最常发故障)

**Out of scope**:
- 不覆盖应用层故障(那是单测/E2eIT 的事)
- 不做长时压测(走 #3 Soak)
- 不模拟跨数据中心 / 网络分区(更高维场景,后续)

## 步骤拆解

### Step 1 — 基础设施(2h)
- [ ] `AbstractChaosIntegrationTest.java`:
  - 共享 Toxiproxy 容器(static field + reuse)
  - `@BeforeAll` 创建 3 个 proxy:`pg_proxy: 5432 → pg:5432`,`kafka_proxy: 9092 → kafka:9092`,`redis_proxy: 6379 → redis:6379`
  - override Spring DataSource / KafkaTemplate / RedisConnectionFactory 的 URL 指向 toxiproxy 端口
  - `@AfterEach` 自动 reset 所有 toxic
- [ ] helper API:
  ```java
  void withLatency(Proxy target, Duration latency, Runnable block);
  void withSlice(Proxy target, int bytesPerSlice, Runnable block);
  void withDown(Proxy target, Runnable block); // ban all bytes
  ```

### Step 2 — 3 个示范 IT(6h)

#### IT-A:`KafkaLatencyToxicIT`(2h)
- 场景:producer send 期间 kafka 端注入 500ms latency
- 断言:
  - `outbox_event.publish_status` 在 timeout 阈值后转为 `FAILED`(走 retry 路径)
  - `OutboxPublishCircuitBreaker` 开闸(熔断器打开)
  - 故障消除后下一轮 poll `markPublished` 成功

#### IT-B:`RedisDownToxicIT`(2h)
- 场景:ShedLock 持锁中 Redis 全断
- 断言:
  - 应用不挂(ShedLock 抛但被 catch)
  - `BATCH_SHEDLOCK_PROVIDER=jdbc` fallback 路径下自动切 jdbc(需启动 profile 显式打开)
  - 故障恢复后 redis 再次接管不重复触发同任务

#### IT-C:`PgConnectionResetToxicIT`(2h)
- 场景:HikariCP 持连期间 PG 端 RST
- 断言:
  - HikariCP 自动 invalidate + 新建连接
  - outbox 写入路径不丢消息(走 retry CAS)
  - `job_instance.lease_owner` 不残留死连接锁

## 验收标准
- [ ] `mvn verify -pl batch-common,batch-orchestrator -Dit.test='*ToxicIT'` 三个 IT 全部通过
- [ ] CI `full-ci-gate` 跑通(不进 pr-gate,Toxiproxy 起容器较慢)
- [ ] 每个 IT 写 `@DisplayName` 中文 + 故障注入意图说明
- [ ] `chaos/README.md` 补"如何写新 Toxic IT"指引

## 风险 / 依赖
- **依赖**:Testcontainers Toxiproxy 1.21.4(已加 pom test scope)
- **风险**:Toxiproxy 容器 +30s 启动时间 → CI 单 IT 4-6 min,只放 `full-ci-gate` 不放 pr-gate
- **可控**:复用容器(`reuse=true` + label),本地连跑 1.5 min

## 检查点 / 里程碑
| 里程碑 | 产出 |
|---|---|
| M1 | `AbstractChaosIntegrationTest` 基类 + 1 个 hello-world IT 跑通 |
| M2 | 3 个示范 IT 全部通过 |
| M3 | CI 接入 + README 文档化 |
