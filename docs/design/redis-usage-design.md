# Redis 使用设计

## 现有用途

| 模块 | 用途 | 机制 |
|------|------|------|
| batch-console-api | 跨实例 SSE 事件广播 | Redis Pub/Sub |

---

## 待引入的场景

### 优先级一：正确性问题

#### 1. 集群级别速率限制

**问题**

`TokenBucketRateLimiter` 使用 `ConcurrentHashMap` 维护 per-instance 令牌桶。部署 N 个 orchestrator 实例时，租户实际可用配额变为配置值的 N 倍，速率限制失去意义。

**涉及文件**

- `batch-orchestrator/.../ratelimit/TokenBucketRateLimiter.java`
- `batch-orchestrator/.../ratelimit/TenantActionRateLimiter.java`

**方案**

使用 Redis `INCR` + `EXPIRE` 实现固定窗口计数器：

```
key:   ratelimit:{tenantId}:{action}:{windowStartEpochSecond}
value: 当前窗口已消耗次数
TTL:   窗口大小（秒）
```

消费时执行 `INCR`，返回值超过阈值则拒绝，key 不存在时同时设置 TTL。原子性由 Redis 单线程保证，无需本地锁。

---

#### 2. Outbox 发布熔断器共享状态

**问题**

`OutboxPublishCircuitBreaker` 用 `volatile AtomicInteger` 存储连续失败次数和熔断截止时间，状态仅在当前实例内有效。Kafka 故障时，触发熔断的实例停止发布，其余实例仍继续压，无法起到保护作用。

**涉及文件**

- `batch-orchestrator/.../mq/OutboxPublishCircuitBreaker.java`

**方案**

使用 Redis Hash 存储共享熔断状态：

```
key:    circuit:outbox_publish
fields: failedPolls, openUntilMs
TTL:    熔断最大持续时间（防孤儿 key）
```

更新操作用 Lua 脚本保证原子性，避免并发写竞争。

---

### 优先级二：性能优化

#### 3. ShedLock 切换 Redis Provider

**问题**

ShedLock 当前使用 JDBC Provider，所有带 `@SchedulerLock` 的定时任务（orchestrator 中 6 个以上）在每次执行时都需要对 `shedlock` 表加锁，产生额外 DB 写入。

**方案**

系统已依赖 Redis，直接切换 ShedLock provider：

```java
// ShedLockConfiguration.java
// 将 JdbcLockProvider 替换为 RedisLockProvider
@Bean
public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    return new RedisLockProvider(connectionFactory, "batch-orchestrator");
}
```

无需改动任何 `@SchedulerLock` 注解，改动范围仅 1 个配置类。

---

#### 4. 配置类数据缓存

**问题**

以下表数据变更频率极低，但在每次分区调度时被反复查询：

| 表 | 调用位置 |
|----|---------|
| `job_definition` | `WaitingPartitionDispatchScheduler` 每条候选分区 |
| `business_calendar` | `BatchDayCutoffScheduler` |
| `batch_window` | dispatch 路径 |
| `tenant_quota_policy` | 速率限制判断 |
| `workflow_definition` | 工作流触发路径 |

**方案**

Cache-Aside 模式：

```
key:   config:{tenantId}:{type}:{code}
value: JSON 序列化的实体
TTL:   5 分钟
```

失效策略：控制台更新配置时，在同一事务 AFTER_COMMIT 后发布 Redis `DEL` 失效。不需要主动推送，TTL 兜底保证最终一致。

---

#### 5. 文件治理指标缓存

**问题**

`FileGovernanceScheduler` 每 60 秒执行聚合查询（到达延迟违规数、最大延迟秒数、样本数据），结果用于监控看板，允许一定滞后。

**涉及文件**

- `batch-orchestrator/.../file/FileGovernanceScheduler.java`

**方案**

查询结果写入 Redis Hash，TTL 60 秒，看板接口优先读缓存，缓存不存在时降级查 DB 并回填。

```
key:    metrics:file_governance:{tenantId}
fields: arrivalDelayViolations, maxArrivalDelaySeconds, processingDelayViolations
TTL:    60s
```

---

## 暂不引入的场景

| 场景 | 原因 |
|------|------|
| Worker 心跳走 Redis | Worker 新增 Redis 直连依赖，收益不抵引入的耦合 |
| Partition 租约改 Sorted Set | DB 是状态主机，两边同步比 15s 轮询更复杂，不值得 |
| Quartz 换 Redis JobStore | Quartz JDBC Store 稳定可靠，换掉风险高收益低 |
| 幂等 key 走 Redis | DB unique constraint 已满足需求，多一层反而多维护 |

---

## 落地顺序

```
第一批（改动小，修正正确性）
  ├── TokenBucketRateLimiter 集群化
  └── OutboxPublishCircuitBreaker 共享状态

第二批（改动小，收益稳定）
  └── ShedLock 切换 Redis Provider

第三批（需要 Cache-Aside 基础设施）
  ├── 配置类数据缓存
  └── 文件治理指标缓存
```
