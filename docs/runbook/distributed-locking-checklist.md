# 分布式锁选型与运维 Checklist

> 项目里有 4 种"分布式协调"机制，**用法不能混淆**。本文档锁死边界，给后续维护者一个 decision tree。
>
> 配套阅读：[ADR-002 transactional-outbox](../architecture/adr/ADR-002-transactional-outbox.md)、[CLAUDE.md §模块边界 / §架构硬约束](../../CLAUDE.md)。

---

## 一、四种机制总览

| 机制 | 实现 | 典型场景 | 互斥强度 | 性能 |
|---|---|---|---|---|
| **ShedLock + Redis** | `RedisShedLockProvider` SET NX + Lua CAS 解锁 | 定时任务跨实例互斥（archive / reconcile / settle / metrics） | **软互斥**（短窗双跑可能，幂等业务必须） | 高（亚毫秒） |
| **PG 行级锁** `FOR UPDATE SKIP LOCKED` | mapper XML | 多实例并发扫表派发（outbox / trigger_outbox / 队列消费） | 强（PG 事务级）| 中（依赖事务）|
| **乐观锁 CAS** `version` 列 + `updateWithCas` | mapper SQL `UPDATE ... WHERE version=:expected` | 状态机表（job_instance / job_partition / quota_runtime_state / batch_day_instance）| 强（DB 唯一性回退） | 高（无阻塞） |
| **PG advisory lock** `pg_try_advisory_lock` | 当前**未使用**，留作回退候选 | 必须严格单跑且业务非幂等的任务（金融对账、序列号生成） | 强（PG session 级） | 中 |

---

## 二、Decision Tree — 该用哪个？

```
要做的事是什么？
├─ 跨实例的"周期性维护"任务（@Scheduled）
│   ├─ 业务幂等？（重复执行不写异常数据 / 不发重复消息）
│   │   └─ ✅ ShedLock
│   └─ 业务非幂等？（必须严格单跑）
│       └─ ⚠️ ShedLock 不够 → PG advisory lock
│
├─ 多实例从同一队列/表"竞争消费"
│   ├─ 队列在 PG 里？
│   │   └─ ✅ FOR UPDATE SKIP LOCKED
│   └─ 队列在 Kafka？
│       └─ ✅ Kafka consumer group（不要再加锁）
│
├─ 状态机表的"读-改-写"（一行）
│   └─ ✅ 乐观锁 CAS（version 列 + updateWithCas）
│       OptimisticLockingFailureException 由调用方决定 retry
│
└─ 计数 / 限流 / 幂等键（不是真"锁"）
    └─ ✅ Redis 单 key 原子操作（SET NX / INCR / Lua）
```

---

## 三、ShedLock 使用红线

### 红线 A — 必须满足"业务幂等"前提

ShedLock 文档原话：**"Don't use ShedLock if locking has any direct functional consequences."**

**为什么**：当前实现 = 单 master Redis SET NX，**没用 Redlock**。Redis 主从切换 + 复制延迟窗口里，理论可能两节点同时持锁。ShedLock 故意不实现强互斥（依赖 `lockAtMostFor` 回退），**这是 ShedLock 的设计哲学**，不是 bug。

| ✅ 适合 ShedLock | ❌ 不适合 ShedLock |
|---|---|
| archive/reconcile/settle 类幂等运维 | 金融对账、写账（重复执行造成余额错误）|
| 指标采集、健康检查 | unique 序列号生成（重复造成冲号）|
| outbox cleanup（DELETE 行级锁回退）| 一次性事件触发（重复触发用户感知到）|
| 缓存预热 | 计费扣费 |

### 红线 B — `lockAtMostFor` 必须 > 任务历史 P99 × 5

否则 JVM GC pause / 慢 SQL / 网络抖让任务跑超时 → 锁过期 → 另一节点接管 → 并发执行。

**审计 checklist**（每个 `@SchedulerLock` 都要满足）：

```bash
# 找出所有 @SchedulerLock 注解
grep -rn "@SchedulerLock" --include='*.java' batch-*/src/main
```

对每条记录：

| 字段 | 要求 |
|---|---|
| `name` | 全局唯一（带模块前缀更佳）|
| `lockAtMostFor` | ≥ 历史 P99 × 5（archive 类设 PT2H 没问题；高频小任务设 PT1M 够）|
| `lockAtLeastFor` | 防止快速 re-fire；建议 `lockAtMostFor / 4` 或 fix interval / 2 |

### 红线 C — `lockAtMostFor` 不是续约（lease extension）

当前实现一次设 TTL，整个执行期不续约。如果你的任务**实际可能跑 2 小时**，TTL 就要设 `PT2H30M`，不能寄希望于"任务跑完前会续约"。

如果某个任务执行时长不可预测（取决于数据量），**改用 PG advisory lock + 自己控制释放时机**，不要依赖 ShedLock 的固定 TTL。

### 红线 D — environment 前缀必须正确

[RedisShedLockProvider:42](batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/redis/RedisShedLockProvider.java:42) 用 `BatchRedisKeys.shedLock(environment, name)` 生成键，environment 来自 `${spring.application.name:batch-orchestrator}`。**如果 dev / staging / prod 共用一套 Redis，这个前缀必须区分环境**，否则跨环境串号。

部署前检查：

```bash
# 在每个环境的容器里
echo $SPRING_APPLICATION_NAME  # 或 application.yml 的 spring.application.name
# 三个环境必须返回不同的值（或 redis URL 本身不共用）
```

---

## 四、PG SKIP LOCKED 使用红线

### 适用场景

| ✅ | ❌ |
|---|---|
| outbox / trigger_outbox 多实例并发投递 | 跨表协调（用 advisory lock 或乐观锁）|
| 工作队列（任务表 + 状态列） | 高频读路径（行锁会阻塞读 — 用乐观锁）|
| 死信队列处理 | 跨服务（用 ShedLock 或 PG advisory）|

### 必须满足

1. **`SELECT ... FOR UPDATE SKIP LOCKED LIMIT N`**：必须有 LIMIT，否则单实例锁全表
2. **必须在事务内**：commit 或 rollback 释放锁
3. **`status IN (...)` 索引**：`WHERE status='PENDING'` 要走 partial index（如 `idx_*_pending`），否则全表扫
4. **失败 → 不更新 status**：让下次 SKIP LOCKED 自然重试；不要 catch 后写 status='RETRY'，状态机会复杂化

### 当前正例

[TriggerOutboxEventMapper.xml:56](batch-trigger/src/main/resources/mapper/TriggerOutboxEventMapper.xml:56) `FOR UPDATE SKIP LOCKED` + `idx_trigger_outbox_pending` partial index，标准实现。

---

## 五、乐观锁 CAS 使用红线

### 适用场景

状态机表（一行有 `status` + `version`，多个并发可能写入）。

```sql
-- 标准模式
UPDATE job_instance
   SET instance_status = 'RUNNING',
       version = version + 1,
       started_at = :now
 WHERE id = :id
   AND tenant_id = :tenantId
   AND version = :expectedVersion
```

返回 `affected_rows`：
- `1` → 成功
- `0` → 冲突，**调用方决定 retry**（典型：重新读 + 重新计算 + 重试 N 次）

### 必须满足

1. **每张状态机表都有 `version` 列**（默认 0，每次更新 +1）
2. **mapper 方法必须叫 `updateWithCas` 系列**（项目惯例 — `markRunning` / `markFinished` / `withRefresh` 等内部走 CAS）
3. **抛 `OptimisticLockingFailureException`** 而不是返回 `false` —— 让上层 retry 循环（外层 `REQUIRES_NEW` + 重试 N 次，参 [`LaunchBatchDayService.upsertBatchDayInstance`](../../batch-orchestrator/src/main/java/com/example/batch/orchestrator/service/LaunchBatchDayService.java)）
4. **不要在 CAS 失败时静默返回**：把 happy path 和 conflict 用不同异常类型区分

### 反例

| 反模式 | 后果 |
|---|---|
| `UPDATE ... WHERE id=:id`（不带 version）| 多实例 race，状态机错乱 |
| CAS 失败时 `return false`，上层 if-else 分支 | 漏处理某个分支就漏一份记录 |
| `version` 列默认 NULL（不是 0）| 第一次 CAS 永远失败 |

---

## 六、运维监控 Checklist

### 1. ShedLock 锁获取失败监控

**当前**：[RedisShedLockProvider:54-57](batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/redis/RedisShedLockProvider.java:54) 把 Redis 错误当"未拿到锁"处理，仅 `log.warn`。

**风险**：Redis 长时间不可用 → 所有 scheduler 静默不跑 → 运维不见得发现。

**该补**：

- [ ] Prometheus counter `shed_lock_acquire_failed_total{reason="redis_error"}`，区分 "正常 contend miss" vs "Redis 不可达"
- [ ] Grafana 告警：`rate(shed_lock_acquire_failed_total{reason="redis_error"}[5m]) > 0` 持续 5 分钟 → P2 告警
- [ ] 加一条独立 `health/lockprovider` endpoint，每 30s 主动 acquire+release 一次伪锁，失败计入 `lockprovider.health` gauge

### 2. ShedLock 任务执行时长追踪

**目标**：发现哪个 `@SchedulerLock` 实际执行时长接近 `lockAtMostFor`，提前预警 TTL 不足。

**该补**：

- [ ] 给每个 scheduler 加 `Timer` 指标 `scheduler_run_duration_seconds{name="..."}`
- [ ] Grafana 告警：`histogram_quantile(0.99, ...) > lockAtMostFor × 0.5` → P3 告警

### 3. PG 锁等待监控

**目标**：发现 `FOR UPDATE` 锁等待 / 死锁。

**该补**：

- [ ] PG 慢查询日志（`log_min_duration_statement = 1000`）+ `pg_stat_activity` 监控阻塞
- [ ] `idx_*_pending` partial index 命中率 / scan 类型监控（`EXPLAIN ANALYZE` 抽查）

### 4. 乐观锁冲突频次

**目标**：CAS 冲突过多 = 业务热点行 / 客户端 retry 风暴。

**该补**：

- [ ] Counter `optimistic_lock_conflict_total{table="job_instance",action="markRunning"}`
- [ ] Grafana 告警：单 action 每分钟冲突 > 100 持续 5 分钟 → 可能业务热点

---

## 七、常见误用 - 不要这样做

### 误用 1：用 ShedLock 保证金融对账单跑

```java
@Scheduled(cron = "0 0 1 * * ?")
@SchedulerLock(name = "daily_settlement", lockAtMostFor = "PT4H")
public void runSettlement() {
    // 写交易表、扣款...  ← 这是非幂等业务！
}
```

**正确**：改用 PG advisory lock + idempotency-key 表 + DB 事务边界包裹整个对账流程。

### 误用 2：拿 ShedLock 当业务限流

```java
@SchedulerLock(name = "user_xxx_export", lockAtMostFor = "PT1H")  // ← 一个用户对应一个锁名？
```

**正确**：业务级别限流用 [`SlidingWindowRateLimiter`](../../batch-console-api/src/main/java/com/example/batch/console/support/ratelimit/SlidingWindowRateLimiter.java) 或 quota 表（`RedisQuotaRuntimeStateService`）。ShedLock 只为定时任务设计。

### 误用 3：状态机表加 ShedLock 而不是 CAS

```java
@SchedulerLock(name = "job_instance_advance", lockAtMostFor = "PT1M")  // ← 全局锁，所有 job_instance 串行？
public void advanceJobInstances() {
    for (JobInstanceEntity ji : pending) {
        ji.setStatus("RUNNING");
        mapper.update(ji);  // ← 没用 version，会 race
    }
}
```

**正确**：用 `markRunning` + 乐观锁 CAS，每行独立，并发跑没问题。

### 误用 4：跨服务的 ShedLock

```java
// trigger 模块
@SchedulerLock(name = "launch_dispatch", ...)
public void dispatchLaunch() { ... }

// orchestrator 模块（同一锁名，但目的完全不同！）
@SchedulerLock(name = "launch_dispatch", ...)
public void recoverLaunches() { ... }
```

**正确**：锁名加模块前缀（`trigger.launch_dispatch` / `orchestrator.launch_recover`），或干脆用 PG advisory lock + 独立的 lock id 命名空间。

---

## 八、快速参考

| 场景 | 选什么 | 一句话 |
|---|---|---|
| 定时维护任务（archive / reconcile / metrics）| `@SchedulerLock` | 业务幂等才能用 |
| 多实例消费同一队列 | PG `FOR UPDATE SKIP LOCKED` | LIMIT + partial index |
| 单行状态机切换 | `version` 乐观锁 | 失败抛 OptimisticLockingFailureException |
| 必须严格单跑的非幂等任务 | PG `pg_try_advisory_lock` | 项目目前没用，需要时新加 |
| 计数 / 限流 / 幂等键 | Redis SET NX / INCR / Lua | 不是"锁"，是"原子标记" |
| Worker 消费 task | Kafka consumer group | 不再加锁，靠 group 分配 |

---

## 九、相关 ADR / 文档

- [ADR-002 transactional-outbox](../architecture/adr/ADR-002-transactional-outbox.md) — outbox + Kafka 主链路，PG SKIP LOCKED 在投递端的标准用法
- [ADR-006 compensation-requires-new](../architecture/adr/ADR-006-compensation-requires-new.md) — 重试 / 补偿走 `REQUIRES_NEW`，与乐观锁 retry 循环协作
- [ADR-010 trigger-async-decoupling](../architecture/adr/ADR-010-trigger-async-decoupling.md) — trigger outbox + Kafka，相同 SKIP LOCKED 模式复用
- [docs/runbook/feature-switches.md](feature-switches.md) — 配置开关运维
- [CLAUDE.md §架构硬约束](../../CLAUDE.md) — 状态主机 / outbox 同事务约束
