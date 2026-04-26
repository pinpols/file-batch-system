# 基础设施性能分析：大规模集群部署

> 分析日期：2026-04-06  
> 最后更新：2026-04-06  
> 分析范围：Redis / MinIO / PostgreSQL / Kafka  
> 适用场景：数百 Worker 实例、百万级文件、高吞吐任务调度

---

## 优先级汇总

| 优先级 | 问题 | 状态 |
|--------|------|------|
| P0 | MinioClient 未复用 | ✅ 已修复 |
| P0 | Outbox 同步发送 | ✅ 已修复 |
| P1 | Redis 无连接池配置 | ✅ 已修复 |
| P1 | PostgreSQL 连接池偏小 | ✅ 已修复 |
| P1 | Kafka max-poll-records = 5 过低 / fetch-min-bytes 未生效 | ✅ 已修复 |
| P1 | Kafka 分区数固定 3 | ✅ 已修复（支持按 topic 独立配置）|
| P2 | Redis Lua 脚本每次轮询都执行 | ✅ 已修复 |
| P2 | MinIO byte[] 方式上传 OOM 风险 | ✅ 已修复 |
| P2 | MinIO listObjects 无界扫描 | ✅ 已修复 |
| P2 | Redis Pub/Sub 用于实时推送 | ⏭ 不适用（已有回放机制，断线丢消息可接受） |
| P3 | Outbox SQL 无显式 LIMIT | ✅ 已修复 |
| P3 | 配置加载在 Java 层过滤 | ✅ 已修复 |
| 架构 | Outbox 单实例轮询吞吐上限 | ✅ 已实现分片轮询 |

---

## 一、Redis

### 问题 1：Pub/Sub 用于实时推送（P2 → ⏭ 不适用）

**位置：** `batch-console-api/.../realtime/ConsoleRealtimeRedisPubSubConsumer.java`

**结论：无需修改。** Console 实时推送场景消息量极低，且系统已内置回放机制（`BATCH_CONSOLE_REALTIME_REPLAY_MAX_ENTRIES=20000` / `REPLAY_TTL=24h`），断线重连后可重放历史消息。Pub/Sub 断线丢消息的风险已被覆盖，迁移 Redis Streams 收益不抵复杂度。Console 不在核心调度链路上，不影响任务正确性。

---

### 问题 2：无 Lettuce 连接池配置（P1 → ✅ 已修复）

**位置：** `batch-common/src/main/resources/batch-defaults.yml`

**修复内容：** 新增显式连接池配置，全部通过环境变量可覆盖：

```yaml
spring:
  data:
    redis:
      timeout: ${BATCH_REDIS_TIMEOUT:2000ms}
      lettuce:
        shutdown-timeout: ${BATCH_REDIS_SHUTDOWN_TIMEOUT:200ms}
        pool:
          max-active: ${BATCH_REDIS_POOL_MAX_ACTIVE:32}
          max-idle: ${BATCH_REDIS_POOL_MAX_IDLE:16}
          min-idle: ${BATCH_REDIS_POOL_MIN_IDLE:4}
          max-wait: ${BATCH_REDIS_POOL_MAX_WAIT:1000ms}
```

---

### 问题 3：Lua 脚本每次 Outbox 轮询都执行（P2 → ✅ 已修复）

**位置：** `batch-orchestrator/.../mq/OutboxPublishCircuitBreaker.java`

**修复内容：** 新增两个 `volatile` 字段在应用层缓存熔断状态：

- `cachedOpenUntilMs`：缓存熔断开启截止时间
- `closedCacheExpiresAt`：关闭状态缓存到期时间（= `pollIntervalMillis`，默认 5s）

**快速路径（不访问 Redis）：**
- 熔断开启中：直接返回 `false`，冷却期内（最长 60s）零 Redis 开销
- 熔断关闭中：缓存有效期内直接返回 `true`

**慢速路径：** 缓存到期才查 Redis，`onAdvanceResult()` 写完 Redis 后立即刷新本地缓存。

同时简化了 `ALLOW_SCRIPT`：由原来返回 1/0 改为直接返回 `openUntilMs` 时间戳，供本地缓存复用。

---

## 二、MinIO

### 问题 1：MinioClient 未复用（P0 → ✅ 已修复）

**位置：** `MinioGovernanceStorage.java`（每次调用 `new MinioClient()`）

**修复内容：**
- 新增 `batch-orchestrator/.../config/MinioConfiguration.java`
- 新增 `batch-worker-export/.../config/MinioConfiguration.java`
- 两处均声明单例 `@Bean MinioClient`，由 Spring 管理生命周期
- `MinioGovernanceStorage` 删除 `private MinioClient client()` 方法，改为注入 bean
- `MinioExportStorage` 删除 `@PostConstruct void initialize()`，改为构造器注入 bean

> Import Worker 已有 `MinioConfiguration`，无需改动。

---

### 问题 2：byte[] 方式上传 OOM 风险（P2 → ✅ 已修复）

**位置：** `batch-worker-export/.../infrastructure/MinioExportStorage.java`

**修复内容：** `writeObject(byte[])` 入口加尺寸守卫：

```java
private static final int MAX_BYTE_UPLOAD_SIZE = 10 * 1024 * 1024; // 10 MB

if (content.length > MAX_BYTE_UPLOAD_SIZE) {
    throw new IllegalArgumentException(
        "content too large for byte[] upload (%d bytes); use writeObject(Path, ...) instead"
            .formatted(content.length));
}
```

超过 10MB 强制调用方改用 `writeObject(Path, ...)` 流式上传。

---

### 问题 3：listObjects 无界递归扫描（P2 → ✅ 已修复）

**位置：** `batch-orchestrator/.../file/MinioGovernanceStorage.java`

**修复内容：** `ListObjectsArgs` 加入 `.maxKeys(limit)`，服务端直接截断，不再拉取全量对象后在应用层截断：

```java
ListObjectsArgs.builder()
    .bucket(properties.getBucket())
    .prefix(prefix)
    .recursive(true)
    .maxKeys(limit)   // ← 新增
    .build()
```

---

## 三、PostgreSQL

### 问题 1：连接池配置偏小（P1 → ✅ 已修复）

**位置：** 各模块 `application.yml`

**修复前后对比：**

| 模块 | 平台库 max（旧） | 平台库 max（新） | 超时（旧） | 超时（新） |
|------|----------------|----------------|----------|----------|
| Orchestrator | 10 | **30** | 3000ms | **5000ms** |
| Import Worker | 5 | **10** | 3000ms | 不变 |
| Export Worker | 5 | **10** | 3000ms | 不变 |

生产环境建议在 Orchestrator 前置 **PgBouncer**（transaction mode），减少 PostgreSQL 实际连接数。

---

### 问题 2：配置加载在 Java 层过滤（P3 → ✅ 已修复）

**位置：** `OrchestratorConfigCacheService.java` + Repository 层

**修复内容：**

`BatchWindowRepository` 新增精确查询方法，Spring Data JDBC 自动生成带 `LIMIT 1` 的 SQL：
```java
Optional<BatchWindowRecord> findFirstByTenantIdAndWindowCodeAndEnabled(
    String tenantId, String windowCode, Boolean enabled);
```

`TenantQuotaPolicyRepository` 新增带 `ORDER BY` 的 `@Query` 方法：
```java
@Query("select * from batch.tenant_quota_policy where tenant_id = :tenantId and enabled = :enabled order by id asc limit 1")
Optional<TenantQuotaPolicyRecord> findFirstEnabledByTenantId(String tenantId, Boolean enabled);
```

`OrchestratorConfigCacheService` 两处缓存 miss 分支替换为新方法，DB 只返回 1 行。

---

### 问题 3：Outbox SQL 无显式 LIMIT（P3 → ✅ 已修复）

**位置：** `OutboxEventMapper.xml` + `OutboxEventQuery`

**修复内容：**
- `OutboxEventQuery` 新增 `Integer batchSize` 字段
- `DefaultScheduleForwarder` 构造查询时传入 `governance.outbox().getBatchSize()`，删除 Java 层 `.stream().limit()`
- Mapper XML 加 `<if test="batchSize != null"> LIMIT #{batchSize} </if>`，DB 侧 Top-N 优化生效

---

## 四、Kafka

### 问题 1：Outbox 同步发送阻塞（P0 → ✅ 已修复）

**位置：** `KafkaOutboxPublisher.java` / `DefaultScheduleForwarder.java`

**修复内容：** `OutboxPublisher` 接口改为返回 `CompletableFuture<Boolean>`，`DefaultScheduleForwarder.advance()` 重构为三段式：

| 阶段 | 操作 | 耗时特征 |
|------|------|---------|
| 阶段一 | `markPublishing × N` + 并行触发所有 Kafka send | DB write × N（快） |
| 阶段二 | `CompletableFuture.allOf().join()` | ≈ **单条最长 RTT**（而非 N × RTT） |
| 阶段三 | `markPublished/Failed × N` | DB write × N（快） |

**性能提升：** 100 条串行 ≈ 100 × 10ms = 1s → 并行 ≈ 10ms（单次 RTT），吞吐提升约 100 倍。

**正确性保障：** 依赖现有 Outbox 模式的 at-least-once 语义，无需 Kafka 事务。Kafka 事务与分片并行化存在冲突（同一 `transactional.id` 不能并发），且额外引入 20-30% 吞吐损耗，不适用本场景。

---

### 问题 2：max-poll-records 未实际生效（P1 → ✅ 已修复）

**位置：** `batch-worker-core/.../config/KafkaConsumerConfiguration.java`

**根本原因：** `KafkaConsumerConfiguration` 手动构造 `ConsumerFactory`，绕过了 Spring Boot 自动配置，导致 `application.yml` 中的 `max-poll-records` / `fetch-min-size` / `fetch-max-wait` 配置**实际上从未生效**。

**修复内容：** 在 `kafkaConsumerFactory()` 中补全 `@Value` 注入并显式写入 `ConsumerConfig`：

```java
properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
properties.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
properties.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);
properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
```

同时更新 import/export worker 默认值：

| 参数 | 旧默认 | 新默认 |
|------|-------|-------|
| `max-poll-records` | 5（未生效） | **20** |
| `fetch-min-size` | 1 byte（未生效） | **1024 bytes** |
| `max-poll-interval-ms` | 未配置（Kafka 默认 5min） | **600000ms（10min）** |

---

### 问题 3：Kafka 分区数固定为 3（P1 → ✅ 已修复）

**位置：** `.env.example` / `scripts/data/init-kafka-topics.sh` / `docker-compose.yml`

**修复内容：** `init-kafka-topics.sh` 新增 `resolve_partitions()` 函数，支持按 topic 类型独立配置分区数：

| 环境变量 | 作用 | 默认值 |
|---------|------|-------|
| `KAFKA_DEFAULT_PARTITIONS` | 全局默认分区数 | 3（本地开发） |
| `KAFKA_PARTITIONS_DISPATCH` | import/export/dispatch 派发 topic | = `KAFKA_DEFAULT_PARTITIONS` |
| `KAFKA_PARTITIONS_RESULT` | 结果回报 topic | = `KAFKA_DEFAULT_PARTITIONS` |
| `KAFKA_PARTITIONS_RETRY` | 重试调度 topic | = `KAFKA_DEFAULT_PARTITIONS` |
| `KAFKA_PARTITIONS_DEAD_LETTER` | 死信 topic | = `KAFKA_DEFAULT_PARTITIONS` |

**生产分区规划公式：** `dispatch 分区数 = 预期最大 Worker 实例数 × concurrency`（如 10 实例 × 4 = 40）

> 注意：Kafka 分区数只能增加不能减少，投产前按容量规划一次性设置到位。

---

## 五、架构：Outbox 分片轮询（✅ 已实现）

**背景：** ShedLock 保证单实例轮询是 Outbox 吞吐的根本瓶颈，多 Orchestrator 副本也无法并行推进。

**实现方案（方案 A）：**

按 `hashtext(tenant_id)` 对 N 取模分片，每个实例负责独立分片，允许多实例并行轮询。

**配置方式（每个 Orchestrator Pod 设置不同的 INDEX）：**

```bash
BATCH_OUTBOX_SHARD_TOTAL=4   # 总分片数
BATCH_OUTBOX_SHARD_INDEX=0   # 当前实例负责第 0 片（其他 Pod 设 1/2/3）
```

**分片 SQL 条件（`OutboxEventMapper.xml`）：**

```sql
<if test="shardTotal != null and shardTotal > 1">
    and (hashtext(tenant_id) &amp; 2147483647) % #{shardTotal} = #{shardIndex}
</if>
```

> 使用位与 `& 2147483647` 而非 `ABS()`，避免 `INT_MIN` 在 int4 下绝对值溢出的边界问题。

**ShedLock 动态 lock name：**

| shardTotal | Lock name | 说明 |
|-----------|-----------|------|
| 1（默认） | `outbox_poll` | 与原行为完全兼容，零迁移成本 |
| N > 1 | `outbox_poll_shard_0` … `outbox_poll_shard_{N-1}` | 多实例并行，各持独立锁 |

`@SchedulerLock` 静态注解改为注入 `LockingTaskExecutor` 编程式加锁，支持运行时动态 lock name。

---

## 六、剩余运维建议（未改代码）

以下问题无需修改代码，通过运维手段解决：

| 建议 | 说明 |
|------|------|
| Orchestrator 前置 PgBouncer | transaction mode，减少 PostgreSQL 实际连接数，应对突发并发 |
| 生产 Kafka 分区按规划设置 | `KAFKA_PARTITIONS_DISPATCH` 等变量在部署时按容量规划配置 |
| 大文件导出改 Multipart Upload | 导出 >100MB 文件时，Worker 端直接用 MinIO SDK multipart，失败只重传失败分片 |
| Orchestrator 多副本分片部署 | 每个 Pod 设置不同 `BATCH_OUTBOX_SHARD_INDEX`，线性扩展 Outbox 吞吐 |

---

*最后核验日期：2026-04-06，全部修改已通过代码审查，无编译错误或逻辑问题。*
