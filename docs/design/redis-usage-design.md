# Redis 使用设计

## 现有用途

| 模块 | 用途 | 机制 |
|------|------|------|
| batch-console-api | 跨实例 SSE 事件广播 | Redis Pub/Sub |
| batch-console-api | SSE 断线补偿回放缓冲 | Redis List + TTL |
| batch-console-api | 控制台单会话版本控制 | Redis String + TTL |
| batch-console-api | 控制台高频查询缓存 | Redis JSON String + TTL |
| batch-orchestrator | 集群级租户速率限制 | Redis String(`INCR` + `EXPIRE`) |
| batch-orchestrator | Outbox 发布熔断共享状态 | Redis Hash + Lua |
| batch-orchestrator | ShedLock 集群锁 | Redis String(`SET NX PX`) |
| batch-orchestrator | 低频配置缓存 | Redis JSON String + TTL |
| batch-orchestrator | 文件治理指标缓存 | Redis Hash + TTL |

### 当前 Key 设计

| Key Pattern | 用途 | 备注 |
|-------------|------|------|
| `batch:console:realtime` | 控制台 realtime Pub/Sub 频道 | 所有 `console-api` 实例都订阅 |
| `batch:console:realtime:buffer:{tenantId}:{stream}` | tenant + stream 维度 replay buffer | 默认 `20_000` 条，TTL `24h` |
| `batch:console:auth:session:{tenantId}:{username}` | 控制台登录单会话版本号 | 结合 JWT `sessionVersion` 校验 |
| `console:cache:dashboard:{tenantId}:*` | 控制台 dashboard / outbox 统计缓存 | 默认 TTL `10s` |
| `console:cache:diagnostic:{tenantId}:*` | 控制台集群诊断缓存 | 默认 TTL `10s` |
| `console:cache:kafka-lag:{groupId|all}` | Kafka lag 查询缓存 | 默认 TTL `10s` |
| `console:cache:snapshot:{tenantId}:history:{limit}` | 调度快照历史缓存 | 默认 TTL `30s` |
| `console:cache:workers:{tenantId}:*` | Worker 自托管 / 指纹只读缓存 | 默认 TTL `10s` |
| `ratelimit:{tenantId}:{action}:{windowStartEpochSecond}` | 固定窗口速率限制 | 60s 窗口 |
| `circuit:outbox_publish` | Outbox 熔断状态 | fields: `failedPolls/openUntilMs` |
| `shedlock:{environment}:{name}` | ShedLock 分布式锁 | value 为随机 token |
| `config:{tenantId}:{type}:{code}` | 配置类缓存 | TTL `5m` |
| `metrics:file_governance:{tenantId}` | 文件治理指标缓存 | TTL `60s` |

---

## 已落地场景

### 1. 集群级别速率限制

`TokenBucketRateLimiter` 已切换为 Redis 固定窗口计数器，`TenantActionRateLimiter` 不再使用单 JVM `ConcurrentHashMap`。

```
key:   ratelimit:{tenantId}:{action}:{windowStartEpochSecond}
value: 当前窗口已消耗次数
TTL:   60s
```

实现文件：

- `batch-orchestrator/.../ratelimit/TokenBucketRateLimiter.java`
- `batch-orchestrator/.../ratelimit/TenantActionRateLimiter.java`

### 2. Outbox 发布熔断器共享状态

`OutboxPublishCircuitBreaker` 已改为 Redis Hash + Lua 原子更新，多实例共享同一熔断状态。

```
key:    circuit:outbox_publish
fields: failedPolls, openUntilMs
TTL:    cooldown 和最小保底窗口中的较大值
```

实现文件：

- `batch-orchestrator/.../mq/OutboxPublishCircuitBreaker.java`

### 3. ShedLock 切换 Redis Provider

orchestrator 的 `@SchedulerLock` 已切换到自定义 `RedisShedLockProvider`，不再依赖 JDBC `shedlock` 表。

```
key:   shedlock:{environment}:{name}
value: 随机 token
TTL:   lockAtMostFor
```

实现文件：

- `batch-orchestrator/.../config/ShedLockConfiguration.java`
- `batch-orchestrator/.../redis/RedisShedLockProvider.java`

### 4. 配置类数据缓存

以下对象已通过 `OrchestratorConfigCacheService` 落地 Cache-Aside：

- `job_definition`
- `workflow_definition`
- `business_calendar`
- `batch_window`
- `tenant_quota_policy`

```
key:   config:{tenantId}:{type}:{code}
value: JSON
TTL:   5m
```

失效策略也已落地：console 配置变更后，通过 `AFTER_COMMIT` 删除对应 key。

实现文件：

- `batch-orchestrator/.../redis/OrchestratorConfigCacheService.java`
- `batch-console-api/.../ConsoleConfigCacheInvalidationService.java`

### 5. 文件治理指标缓存

文件治理延迟指标已按租户缓存，并收敛到 `GET /internal/files/governance/latency-metrics` 读取链路。

```
key:    metrics:file_governance:{tenantId}
fields: tenantId, arrivalDelayViolations, maxArrivalDelaySeconds,
        processingDelayViolations, maxProcessingDelaySeconds,
        arrivalDelaySamples, processingDelaySamples
TTL:    60s
```

当前聚合查询已改为 tenant-aware，不再扫描全局表后再按缓存 key 分租户。

实现文件：

- `batch-orchestrator/.../file/FileGovernanceScheduler.java`
- `batch-orchestrator/.../redis/FileGovernanceMetricsCacheService.java`
- `batch-orchestrator/.../file/FileGovernanceRepository.java`
- `batch-orchestrator/.../mapper/FileGovernanceMapper.xml`

### 6. 控制台 realtime 回放缓冲

Pub/Sub 本身不提供重放，因此控制台额外用 Redis List 维护最近事件缓冲，SSE 订阅时按 `cursor` 做补发。

```
key:   batch:console:realtime:buffer:{tenantId}:{stream}
value: ConsoleRealtimeStreamEnvelope JSON 列表
TTL:   batch.console.realtime.replay-ttl
裁剪:   batch.console.realtime.replay-max-entries
```

默认配置：

- `replay-max-entries = 20_000`
- `replay-ttl = 24h`

实现文件：

- `batch-console-api/.../ConsoleRealtimeReplayStore.java`
- `batch-console-api/.../ConsoleRealtimeProperties.java`

### 7. 控制台单会话版本控制

控制台登录签发 JWT 时，会把当前 `sessionVersion` 写入 token；Redis 中保存每个 `tenant + username` 的最新版本号，用于单会话校验和踢旧 token。

```
key:   batch:console:auth:session:{tenantId}:{username}
value: 当前有效 session version
TTL:   batch.console.security.session-state-ttl
```

实现文件：

- `batch-console-api/.../ConsoleSessionRegistry.java`
- `batch-console-api/.../ConsoleJwtService.java`

### 8. 控制台高频查询缓存

`ConsoleQueryCacheService` 统一承接控制台读热点缓存，Redis 不可用时 fail-open 到原查询链路。

当前已接入：

- `/api/console/ops/summary`
- `/api/console/dashboard/{job-stats,trigger-stats,worker-load,alert-trend,sla-compliance,sla-report,tenant-usage}`
- `/api/console/ops/outbox/stats`
- `/api/console/ops/kafka-lag`
- `/api/console/ops/cluster-diagnostic/**`
- `/api/console/scheduler/snapshot`
- `/api/console/scheduler/snapshot/history`
- `/api/console/my-workers/**`
- `/api/console/workers/fingerprints/**`

默认 TTL：

- dashboard / outbox stats / Kafka lag / cluster diagnostic / worker 指纹：`10s`
- scheduler snapshot / history：`30s`
- meta enums：`30m`
- meta options：`5m`

写操作主动失效：

- 配置变更：清理 meta options / orchestrator config cache。
- Outbox cleanup / republish：清理对应租户 `dashboard:{tenantId}:*` 缓存。

实现文件：

- `batch-console-api/.../support/cache/ConsoleQueryCacheService.java`
- `batch-console-api/.../domain/observability/web/ConsoleDashboardController.java`
- `batch-console-api/.../domain/ops/web/ConsoleOpsController.java`
- `batch-console-api/.../domain/ops/web/ConsoleClusterDiagnosticController.java`
- `batch-console-api/.../domain/job/web/ConsoleSchedulerSnapshotController.java`

## Redis 监控设计

### 业务级指标

控制台 realtime 已补充以下 Micrometer 指标：

| 指标名 | 类型 | 含义 |
|--------|------|------|
| `batch.console.realtime.subscriptions.active` | Gauge | 当前活跃 SSE 订阅数 |
| `batch.console.realtime.replay.events{stream}` | Counter | replay buffer 实际补发事件数 |
| `batch.console.realtime.replay.cursor.miss{stream}` | Counter | 前端 cursor 在缓冲区中找不到的次数 |
| `batch.console.realtime.replay.decode.failures{stream}` | Counter | replay buffer JSON 解码失败次数 |
| `batch.console.realtime.pubsub.decode.failures` | Counter | Redis Pub/Sub 消息解码失败次数 |
| `batch.console.realtime.pubsub.handle.failures{stream,eventType}` | Counter | Pub/Sub 消息进入业务处理后失败次数 |

实现文件：

- `batch-console-api/.../ConsoleRealtimeMetrics.java`
- `batch-console-api/.../ConsoleRealtimeEventHub.java`
- `batch-console-api/.../ConsoleRealtimeReplayStore.java`
- `batch-console-api/.../ConsoleRealtimeRedisPubSubConsumer.java`

### 基础设施级指标

Redis 本身仍通过 `redis-exporter` 暴露基础指标，Prometheus 重点关注：

- `redis_connected_clients`
- `redis_memory_used_bytes`
- `redis_commands_processed_total`
- `redis_keyspace_hits_total`
- `redis_keyspace_misses_total`
- `redis_expired_keys_total`

### 推荐告警 / 看板关注点

- SSE 活跃连接数异常升高：看 `batch.console.realtime.subscriptions.active`
- replay 命中率下降：结合 `replay.events` 与 `replay.cursor.miss`
- Pub/Sub 载荷异常：看 `pubsub.decode.failures`
- 业务处理异常：看 `pubsub.handle.failures`
- Redis 内存接近上限：看 `redis_memory_used_bytes`
- Redis 连接数异常：看 `redis_connected_clients`

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
已完成
  ├── TokenBucketRateLimiter 集群化
  ├── OutboxPublishCircuitBreaker 共享状态
  ├── ShedLock 切换 Redis Provider
  ├── 配置类数据缓存
  ├── 文件治理指标缓存
  ├── console realtime replay buffer
  └── console 单会话 session registry

持续演进
  ├── Redis 业务指标告警规则
  └── Redis key 容量 / TTL 巡检自动化
```
