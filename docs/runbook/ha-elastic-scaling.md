# 水平 HA + 弹性缩容兼容性手册

## TL;DR

P1/P2/P3（已完成部分）**整体 HA-safe**，可以直接部署 3-5 实例 orchestrator + 多 worker，
支持 k8s HPA 弹性扩缩。

- **0 个 🔴 Broken**
- **8 个 ✅ 完全 HA-safe**
- **2 个 ⚠️ 有可控副作用**（SSE 实时流断流 / WorkerCache 5s 窗口期可能选已下线 worker）

落地前**必做的 4 件事**写在文末 §部署前 checklist。

## 兼容性矩阵

| 改动 | HA 状态 | 关键证据 | 备注 |
|---|---|---|---|
| **P1-1 Outbox SQL 归档** | ✅ | 纯 SQL cron job，外部调度 | 无需协调 |
| **P1-2 SuccessInstance SQL 归档** | ✅ | 同上 | 无需协调 |
| **P1-3 配置 cache Redis pub/sub** | ✅ | console 改配置 → publish；多实例订阅广播 evict | Redis pub/sub 原子 |
| **P1-4 观测 dashboard** | ✅ | Prometheus scrape 各实例独立 | 多实例聚合 |
| **P1-5 Worker 自动重启** | ✅ | k8s `restart: unless-stopped` / watchdog | 容器编排管 |
| **P2-1 Quota Redis Lua** | ✅ | `RedisQuotaRuntimeStateService` 单 EVAL 原子 read-modify-write | 中央化、无 race |
| **P2-2 Quartz dual-DS** | ✅ | `application.yml:30 isClustered: true` | Quartz 行级锁 cluster-aware |
| **P2-3 WorkerSelector cache（5s TTL）** | ⚠️ | `WorkerRegistryCache` per-instance Redis 缓存 | 5s 窗口期可能选已下线 worker，CLAIM 阶段失败重试自愈 |
| **P2-4 Read replica routing** | ✅ | `ReadReplicaRoutingDataSource` per-instance `AtomicInteger consecutiveFailures` + `volatile quarantineUntilMillis` | 每实例独立判定降级，互不干扰 |
| **P2-5 Kafka topic routing** | ✅ | Worker `@KafkaListener(topicPattern=...)` PATTERN 模式 | consumer-group rebalance 自动 |
| **P3-3 WorkflowArchiveScheduler** | ✅ | `@SchedulerLock(name="workflow_archive", lockAtMostFor=PT30M, lockAtLeastFor=PT1M)` | ShedLock + Redis |
| **P3-3 SuccessInstanceArchiveScheduler** | ✅ | `@SchedulerLock(name="success_instance_archive", lockAtMostFor=PT2H, lockAtLeastFor=PT5M)` | ShedLock + Redis |
| **P3-3 OutboxArchiveScheduler** | ✅ | `@SchedulerLock(name="outbox_archive", lockAtMostFor=PT30M, lockAtLeastFor=PT1M)` | ShedLock + Redis |
| **OutboxPollScheduler（核心写路径）** | ✅ | `LockingTaskExecutor` + 动态分片名 `outbox_poll_shard_N` | 支持横向分片 |
| **TenantActionRateLimiter** | ✅ | `TokenBucketRateLimiter:59` `redis.incrementWithinWindow()` | **限额是集群级**，N 实例不会让总限额翻 N 倍 |
| **配置 cache（OrchestratorConfigCacheService）** | ✅ | Redis 5min TTL，无本地缓存 | 各实例独立读 |
| **观测三件套（Loki / Tempo / Prometheus）** | ✅ | 每实例独立 OTLP 上报 | otel-collector 聚合 |
| **ConsoleRealtimeEventHub（SSE）** | ⚠️ | 本地 `CopyOnWriteArrayList<Subscription>` 订阅列表 | 实例死亡 → SSE 断开；replay buffer 在 Redis 重连可续 |

## ⚠️ 两个可控副作用

### 1. SSE 实时流断流
**现象**：用户连到实例 A，A 被 k8s 下线 → SSE 流断 → 浏览器自动 reconnect 落到实例 B → 从 Redis replay buffer 续上数据。

**用户感知**：1-3 秒断流闪烁，无数据丢失。

**缓解**（按需）：
- 前端做断流动画 + 自动 reconnect（已就位）
- Ingress 加 `sessionAffinity: ClientIP` 让同一客户端尽量粘到同一 pod，减少触发频率（不是 HA 要求）

### 2. WorkerSelector cache 5s 窗口期
**现象**：worker 下线 → 5s 内不同 orchestrator 实例 cache 还显示在线 → 选中已下线 worker → CLAIM 失败 → 自动重试到下一个。

**用户感知**：极少数 task 多走一次 CLAIM 重试（毫秒级延迟）。

**缓解**：CLAIM 本身就是悲观锁兜底，**不需要修**。如果想更敏感，TTL 调到 2s（`batch.scheduler.worker-cache.ttl-seconds`）。

## 弹性缩容的 4 个隐藏雷

### 🟡 ShedLock `lockAtMostFor` 是双刃剑

| Scheduler | lockAtMostFor | 实例 SIGKILL 后多久幸存实例能接手 |
|---|---|---|
| OutboxPoll（写路径关键） | 短（PT30S 量级） | 接近实时，影响小 |
| OutboxArchive | PT30M | 最长等 30 分钟 |
| WorkflowArchive | PT30M | 最长等 30 分钟 |
| SuccessInstanceArchive | **PT2H** | **最长等 2 小时** |

**关键缓解**：让 pod **优雅退出**而不是 SIGKILL。优雅退出时 ShedLock 会主动 release。

→ 见下方 §部署前 checklist 的 `terminationGracePeriodSeconds`。

### 🟡 Outbox 分片 rebalance 时的微小重投
- 实例数变化 → 分片重分配 → 短暂窗口里新实例接手了未释放的分片
- 可能产生**重复发布**少量 outbox 事件
- **保护**：下游按 `idempotencyKey = (eventType, eventKey)` 去重，业务侧不会重复执行

### 🟡 Quartz misfire
- 实例死亡时正在执行的触发器标 misfire
- 默认 `withMisfireHandlingInstructionFireNow` → 幸存实例立即补跑
- **影响**：调度晚到一点（秒级）但不丢

### 🟡 Worker 优雅下线
- Worker 进程收到 SIGTERM 触发 `AbstractWorkerLoop:137 @PreDestroy` → `workerRuntimeFacade.shutdown(workerId)` 上报下线
- Kafka consumer 关闭触发 `GracefulKafkaShutdown` → 主动 leave consumer group → rebalance 立即生效
- 已 CLAIM 但未完成的 task：上报下线时 orchestrator 应释放该 worker 的所有未完成 CLAIM 给其他 worker
- **验证**：缩容时观察 worker `LastSeenAt` 是否立即更新；若有 task stuck 在该 worker → 检查 `worker.shutdown()` 调用链

## 部署前 checklist（**必做**）

### 1. Redis 必须高可用 ⚠️
所有协调机制（ShedLock / 限流 / 配额 / 配置 cache / SSE replay）都靠 Redis。**单点 Redis 挂 = 整集群 HA 失效**。生产用：
- AWS ElastiCache cluster mode
- 自建 Sentinel（≥3 节点）
- 自建 Redis Cluster（≥6 节点）

### 2. Helm chart `terminationGracePeriodSeconds` ✅（已落地）
`_helpers.tpl` 提供 `gracefulShutdownPod` / `gracefulShutdownLifecycle` helper，
`values.yaml` 每个组件已配 `gracefulShutdown:` 默认值：

| 组件 | grace 默认 | preStop sleep | 理由 |
|---|---|---|---|
| console-api | 90s | 15s | SSE drain + Spring graceful（默认 60s） |
| trigger | 90s | 15s | Quartz cluster 注销 + transfer drain |
| **orchestrator** | **150s** | 15s | **ShedLock 主动释放 + outbox shard rebalance；Spring `BATCH_SHUTDOWN_TIMEOUT` 默认 120s 加 30s 缓冲** |
| worker-dispatch | 120s | 15s | Kafka leave + 完成在跑 task |
| worker-import | 180s | 15s | 长 task（>1 min 常见）需要 |
| worker-export | 180s | 15s | 大文件生成更长，超时仍由 PartitionLeaseReclaim 兜底 |

**单 task 平均执行时间长于上面默认值时**，要在 values 里调高对应组件，否则 SIGKILL
仍会强杀 in-flight task（虽然有 reclaim 兜底，但产生不必要的重派）。

helm rendered 验证：6 个 deploy/sts 全部正确渲染 `terminationGracePeriodSeconds` +
container `lifecycle.preStop` block。

### 3. DB max_connections 容量核算 ⚠️
N 实例 × 单实例 pool = 总连接数，不能超 PG `max_connections`。

| 部署规模 | orchestrator | console-api | trigger | 总连接 | PG 最低 |
|---|---|---|---|---|---|
| 默认 (千万/天) | 5 × 50 = 250 | 5 × 16 = 80 | 5 × 10 = 50 | **380** | 500 |
| 大规模 | 10 × 50 = 500 | 10 × 16 = 160 | 10 × 10 = 100 | **760** | 1000 |

PG 默认 `max_connections=100`，**必须**调大或加 PgBouncer（transaction mode 推荐）。

### 4. Kafka metadata 刷新 🟡
默认 `consumer.metadata.max.age.ms=300000`（5 分钟），新 tenant/priority topic 上线后 worker 最长 5 分钟才发现。
- 如果你**频繁动态创建 tenant/priority topic** → 调到 60s
- 一次性预创建好的话 → 默认 OK

## 验证步骤（部署后跑一遍）

```bash
# A. 多实例 ShedLock 不冲突
kubectl scale deployment orchestrator --replicas=3
sleep 60
# 看每个调度器只在一个 pod 跑（按 lock 名搜日志）
for pod in $(kubectl get po -l app=orchestrator -o name); do
  kubectl logs $pod | grep "outbox_archive.*acquired" | tail -3
done
# 期望：只有一个 pod 拿到 lock 跑

# B. 限流是集群级
# 用 5 个并发模拟器同时打满 launch（每个超过单租户 limit）
# 期望：总 RPS 被限到 BATCH_RATE_LIMIT_MAX_NEW_PER_TENANT_PER_MINUTE / 60，不是 5×

# C. Worker 下线 task 转移
kubectl delete pod $(kubectl get po -l app=worker-import -o name | head -1)
# 看该 pod 上未完成 task 是否在 ~10s 内被其他 pod CLAIM
# 监控：grep "task_id=X claim" 在所有 worker 日志中

# D. Replica 故障 fail-open
kubectl delete pod postgres-replica-0
# console-api 查询应继续工作（降级到主库）
curl http://console-api/api/console/queries/job-instances?tenantId=t1
# 期望：200 OK，不是 503
```

## 相关文件

- `batch-orchestrator/.../config/ShedLockConfiguration.java` — `RedisShedLockProvider`
- `batch-orchestrator/.../application/ratelimit/TokenBucketRateLimiter.java` — Redis 限流实现
- `batch-orchestrator/.../scheduler/*ArchiveScheduler.java` — 三个 ShedLock'd archive 调度器
- `batch-worker-core/.../support/AbstractWorkerLoop.java:137` — Worker `@PreDestroy`
- `batch-worker-core/.../infrastructure/GracefulKafkaShutdown.java` — Kafka 优雅停消费
- `batch-console-api/.../config/ReadReplicaRoutingDataSource.java` — Read replica 路由 + per-instance quarantine
- `helm/batch-platform/templates/_helpers.tpl` — `gracefulShutdownPod` / `gracefulShutdownLifecycle` helper
- `helm/batch-platform/values.yaml` — 每个组件 `gracefulShutdown:` 默认值

## 相关参考

- `docs/runbook/observability-stack.md` — 三件套监控（看 ShedLock 抢锁、Worker 上下线、SSE 重连都靠它）
- `docs/runbook/read-replica.md` — Read replica 部署与切换
- `docs/architecture/scalability-assessment.md` §6 — 海量场景的下一步分库分表路线
