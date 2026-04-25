# Quartz 替换为时间轮 — 生产级实施设计

> **配套文档**：[`quartz-replacement-evaluation.md`](./quartz-replacement-evaluation.md)（战略决策层:为什么换、何时换);本文档是战术实施层(怎么换的每一处坑)。
>
> **状态**：设计文档,实施前必读;阶段 1 启动后按本文档对应章节落代码 + 测试。
>
> **审视阶段**：经过外部 review 后的修订版,补齐了 5 项原方案低估的生产风险(fire 幂等强约束 / 滑动窗口去重 / 时间精度 SLA / failover 快速补偿 / 工程量校准)。

---

## 0. 与 evaluation.md 的差异 — 修正了哪些"理想化判断"

| evaluation.md 原文 | 修正 / 强化 | 原因 |
|---|---|---|
| "LaunchService 已有幂等" | 加 DB 强约束 `UNIQUE(trigger_id, scheduled_fire_time)` | 单纯应用层幂等防不住 GC pause + 锁过期场景的双 leader |
| 滑动窗口扫库每分钟一次 | 加内存 dedup set,push 前查重 | 5 min 窗口下同一 trigger 会被多次 push,fire 多次 |
| 工程量 ~1 人月 | **校准为 1.5-2 人月**(含压测) | 真正耗时在分布式一致性 / failover 测试 / 重复 fire 验证 |
| Failover "几秒~几十秒" | 加 onLeaderAcquire 立即扫一次窗口 | 10 万 trigger 时冷启动可能 30s,期间任务全 delay |
| 时间精度只提"100ms tick" | 明确 SLA 矩阵(批处理 OK / 实时交易 NO) | 业务方对时间精度的期望要写明,避免上线发现"不准" |

**这 5 项不是 nice-to-have,是动工前必备**。

---

## 1. 整体架构与责任边界

```
┌────────────────────────────────────────────────────────────────────┐
│  trigger 模块 (1 ~ N 实例,ShedLock 选 1 leader)                  │
│                                                                    │
│  ┌─ 非 leader 实例 ──────────────────────┐                        │
│  │ HashedWheelTimer 启动但空转           │                        │
│  │ slidingWindow @Scheduled tryLock 失败 │                        │
│  │ 不读 DB / 不 fire / 不消耗资源        │                        │
│  └────────────────────────────────────────┘                        │
│                                                                    │
│  ┌─ leader 实例 ──────────────────────────────────────────────┐  │
│  │                                                              │  │
│  │  ┌─ slidingWindowScheduler @Scheduled 60s ──────────────┐  │  │
│  │  │ 1. tryLock("trigger-leader", lockAtMostFor=2m)       │  │  │
│  │  │ 2. select trigger_runtime_state                      │  │  │
│  │  │      where next_fire_time < now() + 5min             │  │  │
│  │  │      and scheduled_fire_marker is null               │  │  │
│  │  │ 3. for each row:                                     │  │  │
│  │  │      ├─ in-memory dedup check (Set<TriggerId+FireTime>) │  │
│  │  │      ├─ wheel.newTimeout(...)                        │  │  │
│  │  │      └─ UPDATE scheduled_fire_marker = leader_id     │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  │                                                              │  │
│  │  ┌─ HashedWheelTimer (Netty) ────────────────────────────┐  │  │
│  │  │ tick=100ms, buckets=512, ~5 min worth of tasks       │  │  │
│  │  │ tick → fire(trigger):                                │  │  │
│  │  │   ├─ INSERT trigger_request (UNIQUE 约束兜底)        │  │  │
│  │  │   ├─ 唯一键冲突 → 跳过(其他 leader 已 fire)          │  │  │
│  │  │   ├─ 成功 → HTTP 调 LaunchService                    │  │  │
│  │  │   └─ UPDATE trigger_runtime_state.next_fire_time     │  │  │
│  │  │       为 cron.next(),scheduled_fire_marker = NULL    │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  │                                                              │  │
│  │  ┌─ leader 切换 fast-path (onLeaderAcquire) ───────────┐  │  │
│  │  │ 1. wheel.clear() (本实例之前可能有残留)             │  │  │
│  │  │ 2. 立即跑一次 slidingWindow,覆盖 now() ~ +1min      │  │  │
│  │  │ 3. 后续走 60s 周期                                  │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
                              │ HTTP /internal/launch
                              ↓
┌────────────────────────────────────────────────────────────────────┐
│  Orchestrator LaunchService (现有)                                │
│   ├─ 检查 trigger_request 唯一键(双层幂等)                         │
│   ├─ 写 job_instance + outbox + partition                         │
│   └─ ...                                                           │
└────────────────────────────────────────────────────────────────────┘
```

**关键责任**:
- **trigger 模块** — 仅负责"到点 fire 一次回调",不参与业务调度
- **trigger_runtime_state 表(新增)** — 唯一权威源,存 next_fire_time + 调度 marker
- **trigger_request 表(已有)** — 幂等闸门,UNIQUE 约束防双 fire
- **LaunchService(已有,零改动)** — 接收 fire 回调,继续走现有逻辑

---

## 2. 状态持久化层(关键 — 没这个表就跑不起来)

### 2.1 现状

`job_definition` 表只存配置(schedule_expr / timezone / enabled),**没有** `next_fire_time` / `last_fire_time`。这些状态目前在 Quartz `QRTZ_TRIGGERS.NEXT_FIRE_TIME` 维护。

时间轮替换后,**必须新增持久化表存这个状态**。原因:
- Wheel 是内存结构,JVM 重启丢失
- Leader 漂移时新 leader 必须从 DB 重建窗口
- 多实例只读 job_definition 算不出"我应该跳过哪些"

### 2.2 新增表 schema(必备)

```sql
-- V100__create_trigger_runtime_state.sql
CREATE TABLE IF NOT EXISTS batch.trigger_runtime_state (
    id                       BIGSERIAL PRIMARY KEY,
    job_definition_id        BIGINT       NOT NULL REFERENCES batch.job_definition(id) ON DELETE CASCADE,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_code                 VARCHAR(128) NOT NULL,
    next_fire_time           TIMESTAMPTZ  NOT NULL,
    last_fire_time           TIMESTAMPTZ,
    last_fire_status         VARCHAR(32),  -- FIRED / FAILED / SKIPPED_DUPLICATE / MISFIRE_CATCH_UP
    -- 调度占位(防 race):某 leader 把这一条推进 wheel 时写自己的 instance_id;
    -- fire 完毕清回 NULL。其他 leader 扫库时跳过 marker != NULL 的行。
    scheduled_fire_marker    VARCHAR(128),
    scheduled_at             TIMESTAMPTZ,
    -- marker 持有上限(防 leader 崩溃后 marker 永久占位)
    -- 扫库时:marker != NULL AND scheduled_at + 5min < now() → 视为 stale,可重新接管
    misfire_count            BIGINT       NOT NULL DEFAULT 0,
    version                  INTEGER      NOT NULL DEFAULT 1,  -- 乐观锁
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trigger_runtime_state_job_def UNIQUE (job_definition_id),
    CONSTRAINT ck_last_fire_status CHECK (
        last_fire_status IS NULL OR last_fire_status IN
        ('FIRED', 'FAILED', 'SKIPPED_DUPLICATE', 'MISFIRE_CATCH_UP')
    )
);

CREATE INDEX idx_trigger_runtime_state_next_fire
    ON batch.trigger_runtime_state (next_fire_time)
    WHERE scheduled_fire_marker IS NULL;

-- 给"stale marker 接管"扫描用
CREATE INDEX idx_trigger_runtime_state_marker_stale
    ON batch.trigger_runtime_state (scheduled_at)
    WHERE scheduled_fire_marker IS NOT NULL;
```

### 2.3 数据生命周期

```
job_definition INSERT (enabled=true)
        ↓ TriggerReconciler 30s 周期同步
trigger_runtime_state INSERT
  next_fire_time = cron.next(now())
  scheduled_fire_marker = NULL

        ↓ leader slidingWindowScheduler 扫到
UPDATE scheduled_fire_marker = 'leader-instance-1', scheduled_at = now()

        ↓ wheel tick 触发 fire
UPDATE next_fire_time = cron.next(scheduled_fire_time + 1ms)
       last_fire_time = scheduled_fire_time
       last_fire_status = FIRED
       scheduled_fire_marker = NULL  -- 释放占位
       version = version + 1

        ↓ job_definition UPDATE enabled=false
TriggerReconciler 删除对应 trigger_runtime_state 行(CASCADE)
```

### 2.4 为什么不用 Redis ZSET 替代

候选方案:用 Redis ZSET 存 `next_fire_time`,score = epoch ms,member = trigger_id。

**否决原因**:
- Redis 不是持久化权威源(项目已有 RedisShedLockProvider 但不存业务状态)
- 跨实例一致性需要 Redis Cluster + AOF,运维复杂度增加
- ZSET 没有原生唯一约束,fire 去重还要靠 Lua
- DB 表方案对接现有 MyBatis / Repository 模式更顺,query / 审计 / 备份零额外工作

**Redis 留给 ShedLock 做 leader-elect 即可**,业务状态走 PG。

---

## 3. ⭐ Fire 幂等强约束(R-1:重复 fire 风险)

### 3.1 风险场景(必须防的)

```
T+0s   Leader A 拿 ShedLock("trigger-leader", TTL=2min)
T+1s   A 扫库,把 trigger T (next_fire_time=T+30s) 推进 wheel
T+30s  wheel tick → A 准备 fire
       但 A 在 GC pause 中(JVM full GC 3s)
T+33s  A 的 ShedLock TTL 已过期(因为 GC 时无法 extend)
       Leader B 抢到 ShedLock,扫库,看到 next_fire_time=T+30s 已过(misfire 处理)
       B fire 一次,UPDATE next_fire_time = T+60s
T+33s  A GC 结束,继续执行 fire(T)
       此时 LaunchService 收到第二次 launch(scheduled_fire_time=T+30s)
```

**单纯应用层幂等不够**,因为:
- A 不知道自己的 ShedLock 过期了(回到代码不会自检)
- B 已经 fire 过,但 A 自己的 wheel 内 task 还在
- 高频 cron(秒级 / 分钟级)同 biz_date 会撞同一日期键,需要更细粒度

### 3.2 解决方案 — DB 强约束

`trigger_request` 表加 **fire 幂等列 + 唯一约束**:

```sql
-- V101__add_trigger_request_fire_dedup.sql
ALTER TABLE batch.trigger_request
    ADD COLUMN scheduled_fire_time TIMESTAMPTZ,
    ADD COLUMN trigger_runtime_state_id BIGINT REFERENCES batch.trigger_runtime_state(id);

-- 关键约束:同一 (trigger_runtime_state_id, scheduled_fire_time) 全局唯一
CREATE UNIQUE INDEX uk_trigger_request_fire_dedup
    ON batch.trigger_request (trigger_runtime_state_id, scheduled_fire_time)
    WHERE trigger_runtime_state_id IS NOT NULL;
-- partial index:只对时间轮 fire 出来的 trigger_request 生效;
-- 历史 API/MANUAL trigger 不受影响(那些列为 NULL)
```

### 3.3 fire 流程的强约束兜底

```java
// HashedWheelTriggerScheduler.fire()
private void fire(TriggerRecord t, Instant scheduledFireTime) {
  TriggerRequestRecord req = TriggerRequestRecord.builder()
      .triggerRuntimeStateId(t.runtimeStateId())
      .scheduledFireTime(scheduledFireTime)
      .triggerType(TriggerType.SCHEDULED)
      .jobCode(t.jobCode())
      .tenantId(t.tenantId())
      .dedupKey(buildDedupKey(t, scheduledFireTime))   // 也写 dedup_key 兼容已有约束
      .requestId(generateRequestId(t, scheduledFireTime))
      .requestStatus(TriggerRequestStatus.ACCEPTED)
      .build();

  try {
    triggerRequestRepository.insertSelective(req);  // ← 唯一键冲突时抛 DuplicateKeyException
  } catch (DuplicateKeyException e) {
    // 已被其他 leader fire 过,记 metric,不抛
    metrics.duplicateFireSkipped(t.jobCode());
    log.info("fire skipped (duplicate): job={} scheduledFireTime={}",
             t.jobCode(), scheduledFireTime);
    advanceNextFireTime(t, scheduledFireTime);  // 推进自己的 next_fire_time 不要卡住
    return;
  }

  // 真正 fire 到 LaunchService
  launchService.launch(req);
  advanceNextFireTime(t, scheduledFireTime);
}
```

### 3.4 为什么 dedup_key 老路径不够用

`trigger_request` 已有 `uk_trigger_request_tenant_dedup (tenant_id, dedup_key)`,但:
- `dedup_key` 通常构造为 `tenant_id:job_code:biz_date`,**biz_date 粒度是天**
- 高频 cron(每 5 分钟一次)一天 288 次 fire,288 次都写同 `dedup_key` → 后 287 次都报 DUPLICATE → 业务断了
- 现状只是因为目前 cron 大多是天级,撞键概率低,所以没暴露

新约束 `(trigger_runtime_state_id, scheduled_fire_time)` 把粒度细化到"具体哪一次 fire",才能撑住高频。

### 3.5 兼容旧 dedup_key

```java
String buildDedupKey(TriggerRecord t, Instant scheduledFireTime) {
  // 老 cron(天级)保持原 dedup_key 行为(对外契约不变)
  // 高频 cron(秒/分钟级)用 epoch ms 后缀让 dedup_key 也唯一
  if (isDayLevelCron(t.cronExpression())) {
    return t.tenantId() + ":" + t.jobCode() + ":" + bizDate(scheduledFireTime);
  }
  return t.tenantId() + ":" + t.jobCode() + ":" + scheduledFireTime.toEpochMilli();
}
```

> 这一改动**也修复了**当前 Quartz 时代隐藏的"dedup_key 高频撞键"潜在 bug——附带收益。

---

## 4. ⭐ 滑动窗口去重(R-2:重复加载 trigger)

### 4.1 风险场景

每 60s 扫库,push 未来 5 min 内的 trigger:

```
t=00:00  扫库 → 看到 trigger T (next_fire_time=00:04:30) → push 到 wheel,延迟 4m30s
t=01:00  又扫库 → 还是看到 trigger T (next_fire_time 还没更新,因为还没 fire)
         → 又 push 一次,wheel 里有 2 个 task 指向同一 fire
t=02:00  第三次 push
... 直到 04:30 wheel tick fire,DB 强约束兜住,但 CPU / wheel 内存已被打爆
```

### 4.2 解决方案 — 双层去重

**第一层:DB 调度占位 marker**(已在 §2.2 schema 设计)

扫库 SQL 加 `WHERE scheduled_fire_marker IS NULL`,推到 wheel 后立刻 `UPDATE scheduled_fire_marker = leader_id`。下一次扫库就跳过。

```sql
-- 扫库
SELECT id, job_definition_id, tenant_id, job_code, next_fire_time, version
  FROM batch.trigger_runtime_state
 WHERE next_fire_time < now() + interval '5 minutes'
   AND scheduled_fire_marker IS NULL
   FOR UPDATE SKIP LOCKED  -- 避免 leader 漂移期间撞锁
 LIMIT 1000;

-- 占位
UPDATE batch.trigger_runtime_state
   SET scheduled_fire_marker = ?,  -- leader instance_id
       scheduled_at = now(),
       version = version + 1
 WHERE id = ? AND version = ?;
```

**第二层:内存 dedup set**(防同一 leader 周期内重复)

```java
// ConcurrentHashMap-based set
private final ConcurrentMap<String, Boolean> inFlightFires = new ConcurrentHashMap<>();

void scheduleToWheel(TriggerRecord t, Instant scheduledFireTime) {
  String key = t.runtimeStateId() + ":" + scheduledFireTime.toEpochMilli();
  if (inFlightFires.putIfAbsent(key, Boolean.TRUE) != null) {
    return; // 同实例已经 schedule 过
  }
  long delay = ChronoUnit.MILLIS.between(Instant.now(), scheduledFireTime);
  Timeout timeout = wheel.newTimeout(t -> {
    try {
      fire(t, scheduledFireTime);
    } finally {
      inFlightFires.remove(key);
    }
  }, Math.max(delay, 0), MILLISECONDS);
  // 把 Timeout 引用存起来,trigger disable 时 cancel
  timeoutRegistry.put(t.runtimeStateId(), timeout);
}
```

### 4.3 Stale marker 接管

如果 leader A 拿了 marker 后崩溃,marker 不会自动释放。新 leader 扫到 stale marker 应该接管:

```sql
-- 周期跑(每 2 min),清理 stale marker(超过 5 min 未 fire 的占位)
UPDATE batch.trigger_runtime_state
   SET scheduled_fire_marker = NULL, scheduled_at = NULL, version = version + 1
 WHERE scheduled_fire_marker IS NOT NULL
   AND scheduled_at + interval '5 minutes' < now();
```

阈值 5 min = 滑动窗口大小;超过这个时间 marker 还没清,说明持有者已经死了。

---

## 5. ⭐ 时间精度 SLA 评估(R-3:抖动)

### 5.1 时间轮的精度本质

```
tick = 100ms, buckets = 512, 一圈 = 51.2s
精度 = ±100ms ~ ±200ms(取决于 task 落在哪个 bucket)

加上滑动窗口扫库延迟(最坏 60s),fire 实际延迟可能:
  ┌─ 设定 fire 时间      00:00:00.000
  ├─ 滑动窗口扫到        最早 t-60s  ─ 最晚 t (上次扫库刚过)
  ├─ wheel tick          ±100~200ms
  ├─ HTTP 调 LaunchService    几 ms ~ 几十 ms(网络)
  └─ LaunchService 写库       几十 ms
最坏总延迟:fire 比预定晚 ~250ms ~ 500ms
最坏前置:fire 比预定早 ~0ms(wheel 不会提前 tick)
```

### 5.2 SLA 适配矩阵

| 业务场景 | 精度要求 | 适配本方案? |
|---|---|---|
| 日终批处理(每天某时段跑一次) | ±5min 都 OK | ✅ 完全适合 |
| 小时级 cron(每小时整点) | ±5s 内 | ✅ 完全适合 |
| 分钟级 cron(每 5 分钟一次) | ±2s 内 | ✅ 适合 |
| 秒级 cron(每 30 秒一次) | ±500ms | 🟡 临界,看业务能否接受 250-500ms 抖动 |
| **每 5 秒 fire 一次** | ±100ms | 🔴 不适合,抖动 = fire 间隔 |
| **实时交易触发**(订单到点扣款) | ±100ms | 🔴 不适合,实时交易不该用 cron |

### 5.3 在 design.md 落实的硬约束

**实施前必须**:

1. 排查 `job_definition.schedule_expr` 中所有秒级 cron(`* */N * * * ?` 中 N < 60),按 §5.2 分类
2. 红色场景的 trigger 必须**先迁移到事件驱动 / Spring Scheduler 直接 polling**,不通过时间轮
3. 文档级 SLA 写明:**时间轮替换后,所有 cron 的精度承诺为 "±2s 内"**,业务方要求更高精度走单独通道

### 5.4 配置项

```yaml
batch:
  trigger:
    wheel:
      tick-millis: 100        # 默认 100ms
      bucket-count: 512       # 默认 512
      sliding-window-seconds: 300   # 默认 5 min;短时间内 fire 多的可调小到 60s
      sliding-window-scan-interval-seconds: 60   # 默认 60s 扫一次
```

---

## 6. ⭐ Failover 快速补偿扫描(R-4:恢复时间被低估)

### 6.1 风险场景

```
Leader A 跑了 1 小时,wheel 内有几百个未来 5 min 的 task
A 进程崩溃 (kill -9 / OOM / k8s evict)
ShedLock TTL = 2min → 2min 后 Leader B 抢到锁
B 等下一次 @Scheduled(60s) 才扫库 → 最坏再 60s
总 delay = 2min + 60s = 3min 内任何 fire 都 miss
```

如果 trigger 数 10万,B 第一次扫库本身要几秒到几十秒(`SELECT FOR UPDATE SKIP LOCKED LIMIT 1000`,分批),delay 进一步放大。

### 6.2 解决方案 — onLeaderAcquire fast-path

```java
@Component
public class HashedWheelTriggerScheduler {

  private volatile boolean isCurrentLeader = false;

  @Scheduled(fixedDelay = 60_000)
  @SchedulerLock(name = "trigger-leader", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
  public void slidingWindow() {
    boolean wasLeader = isCurrentLeader;
    isCurrentLeader = true;

    if (!wasLeader) {
      // ★ Leader 切换检测:本实例第一次拿到锁 → fast-path
      onLeaderAcquire();
    }
    scheduleWindow(Duration.ofMinutes(5));
  }

  private void onLeaderAcquire() {
    log.info("acquired trigger-leader, running fast-path catch-up scan");
    // 1) 清掉本实例 wheel 内可能的残留(虽然不该有,但保险)
    cancelAllScheduledTimeouts();
    inFlightFires.clear();
    // 2) 立即扫一次"现在到现在 +1min"的 trigger,先把当下要 fire 的捞出来
    scheduleWindow(Duration.ofMinutes(1));
    // 3) 接管 stale marker(可能上一任 leader 崩溃前留下的)
    triggerStateRepository.releaseStaleMarkers(Duration.ofMinutes(5));
    // 4) 大窗口扫库(5 min)留给后续 @Scheduled tick
    metrics.leaderAcquireCount();
  }

  // 类似:on lock release(降级为非 leader 时清掉 wheel)
  // ShedLock 没有原生事件,只能在 @Scheduled 进入时检测 wasLeader 翻转
}
```

### 6.3 失败模式覆盖

| 故障模式 | 恢复时间 | 方案 |
|---|---|---|
| Leader 进程 kill -9 | 2min(TTL) + 几 s(fast-path) | onLeaderAcquire 立即扫 |
| Leader GC pause < 30s | 0(extendOrRelease 仍续期) | ShedLock lockAtLeastFor=30s 兜底 |
| Leader GC pause > 30s 但 < 2min | 2min(锁过期 → 新 leader 接管) | 同 kill 路径 |
| Leader 网络分区(实例 alive 但连不上 Redis) | 2min(TTL 过期) | 同 kill 路径;但要警惕"分区恢复后老 leader 复活"——见下 |
| 分区恢复后双 leader | 双方 fire 时 DB UNIQUE 兜底 | §3 fire 强约束 |

### 6.4 Leader 切换 metric

```
batch.trigger.leader.acquire.count  Counter, 切换次数
batch.trigger.leader.acquire.duration  Timer, fast-path 耗时
batch.trigger.fast_path.scheduled.count  Histogram, fast-path 调度的 task 数
```

---

## 7. Cron 解析一致性(Quartz vs Spring CronExpression)

### 7.1 必须做的兼容性扫描

Spring `org.springframework.scheduling.support.CronExpression`(Spring 5.3+)**不支持** Quartz 的扩展字符:

| 字符 | Quartz 含义 | Spring 支持? |
|---|---|---|
| `L` | Last(月末 / 周末) | ❌ 不支持 |
| `W` | Weekday nearest | ❌ 不支持 |
| `#` | Nth weekday of month(如 `2#1` = 第一个周一) | ❌ 不支持 |
| `?` | No-specific-value(占位) | 🟡 Quartz 强制要求,Spring 不需要 |

实施前**必须**跑扫描脚本:

```sql
SELECT id, job_code, schedule_expr
  FROM batch.job_definition
 WHERE enabled = true
   AND schedule_type = 'CRON'
   AND (schedule_expr ~ '[LW#]' OR schedule_expr LIKE '%?%');
```

命中的 trigger 必须:
- 改写 cron 表达式(`L` / `W` / `#` 通常都能等价改写)
- 或者保留 Quartz 兼容层(用 `org.quartz.CronExpression` 算 next-fire,不用 Spring)

### 7.2 推荐:保留 Quartz CronExpression 做计算

切换时间轮**只换调度引擎,不换 cron 解析器**。继续用 `org.quartz.CronExpression.getNextValidTimeAfter()` 计算 next-fire-time,避免兼容性风险。Quartz 库即使不用 Scheduler 也可以单独用它的 CronExpression 类。

```java
private Instant computeNextFireTime(String cronExpr, ZoneId zone, Instant after) {
  CronExpression expr = new CronExpression(cronExpr);
  expr.setTimeZone(TimeZone.getTimeZone(zone));
  Date next = expr.getNextValidTimeAfter(Date.from(after));
  return next == null ? null : next.toInstant();
}
```

> **这是关键决策**:不引入新 cron 解析器,沿用 Quartz `CronExpression` 算式。**这一选择决定了即使迁移到时间轮,业务侧 cron 表达式 0 改动**,降低切换风险一个数量级。

### 7.3 工作量影响

工作量从原 800 行 → 增加 ~50 行(Quartz CronExpression 包装类),换来 0 兼容性风险。

---

## 8. 时区处理

```java
ZoneId resolveZone(JobDefinition def) {
  // 优先级:job_definition.timezone > business_calendar.timezone > 平台默认
  if (StringUtils.hasText(def.timezone())) {
    return ZoneId.of(def.timezone());
  }
  if (def.calendarCode() != null) {
    return businessCalendarRepo.findByCode(def.calendarCode())
        .map(c -> ZoneId.of(c.timezone()))
        .orElse(timezoneProvider.defaultZone());
  }
  return timezoneProvider.defaultZone();
}
```

`BatchTimezoneProvider` 已有,直接复用。

---

## 9. Misfire / Catch-up 完整方案

### 9.1 misfire 定义

trigger 应 fire 时间 < now() - threshold(默认 60s,沿用 `BATCH_TRIGGER_MISFIRE_CATCH_UP_THRESHOLD_SECONDS`)即视为 misfire。

### 9.2 处理流程

```java
void handleMisfire(TriggerRecord t, Instant scheduledFireTime) {
  CatchUpPolicyType policy = t.catchUpPolicy();
  switch (policy) {
    case NONE -> {
      // 跳过本次 fire,只推进 next_fire_time
      Instant next = computeNextFireTime(t.cronExpr(), t.zone(), Instant.now());
      stateRepo.updateNextFireTime(t.runtimeStateId(), next, MISFIRE_SKIPPED);
      metrics.misfireSkipped(t.jobCode());
    }
    case AUTO -> {
      // 立即补 1 次,然后推到下一次。不补完所有错过的(避免雪崩)
      fire(t, scheduledFireTime);
      // fire 内部会 advance next_fire_time
      metrics.misfireAutoFired(t.jobCode());
    }
    case MANUAL_APPROVAL -> {
      // 落到 misfire_pending_approval 表,等运维 console 操作
      pendingApprovalRepo.insert(t.runtimeStateId(), scheduledFireTime);
      Instant next = computeNextFireTime(t.cronExpr(), t.zone(), Instant.now());
      stateRepo.updateNextFireTime(t.runtimeStateId(), next, MISFIRE_PENDING);
      metrics.misfirePending(t.jobCode());
    }
  }
}
```

### 9.3 启动期 catch-up throttle

切换时刻可能 100+ trigger 同时 misfire(因为 next_fire_time 还停在切换前)。**必须 throttle**,否则 LaunchService 被打挂:

```java
private void onLeaderAcquire() {
  // ...
  // catch-up throttle:每秒最多 10 个 misfire fire,排队处理
  RateLimiter limiter = RateLimiter.create(10.0);
  for (TriggerRecord t : findMisfired()) {
    limiter.acquire();
    handleMisfire(t, t.nextFireTime());
  }
}
```

### 9.4 新增表(MANUAL_APPROVAL 用)

```sql
CREATE TABLE IF NOT EXISTS batch.trigger_misfire_pending (
  id                          BIGSERIAL PRIMARY KEY,
  trigger_runtime_state_id    BIGINT       NOT NULL REFERENCES batch.trigger_runtime_state(id) ON DELETE CASCADE,
  scheduled_fire_time         TIMESTAMPTZ  NOT NULL,
  status                      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED / EXPIRED
  approved_by                 VARCHAR(64),
  approved_at                 TIMESTAMPTZ,
  reason                      VARCHAR(512),
  created_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_misfire_pending UNIQUE (trigger_runtime_state_id, scheduled_fire_time)
);
```

---

## 10. Trigger CRUD 联动

### 10.1 新增 trigger(`job_definition` INSERT enabled=true)

`TriggerReconciler` 30s 周期同步,扫到 `job_definition` 有但 `trigger_runtime_state` 无 → INSERT runtime_state,设置 `next_fire_time = cron.next(now())`。

### 10.2 禁用 trigger(`job_definition` UPDATE enabled=false)

```java
void onTriggerDisabled(long jobDefId) {
  // 1) 从 wheel 撤销已注册的 timeout(如果在内存中)
  Timeout t = timeoutRegistry.remove(jobDefId);
  if (t != null) t.cancel();
  // 2) DB 删除 runtime_state(CASCADE)或保留但加 disabled 标志
  stateRepo.deleteByJobDefId(jobDefId);
}
```

`TriggerReconciler` 同步:扫到 `job_definition.enabled=false` 但 `trigger_runtime_state` 有 → DELETE runtime_state。

### 10.3 修改 cron(`job_definition` UPDATE schedule_expr)

```java
void onTriggerScheduleChanged(long jobDefId, String newCronExpr) {
  // 1) 从 wheel 撤销旧 timeout
  Timeout old = timeoutRegistry.remove(jobDefId);
  if (old != null) old.cancel();
  inFlightFires.remove(...);
  // 2) DB 重算 next_fire_time
  Instant next = computeNextFireTime(newCronExpr, zone, Instant.now());
  stateRepo.updateScheduleExpr(jobDefId, newCronExpr, next);
  // 3) 下次 slidingWindow 自然 push 新的 timeout
}
```

`TriggerReconciler` 已有 schedule drift detection(2026-04-24 commit),沿用同套对账逻辑。

---

## 11. 灰度切换的双引擎防护

### 11.1 配置开关

```yaml
batch:
  trigger:
    scheduler-impl: ${BATCH_TRIGGER_SCHEDULER_IMPL:quartz}  # quartz / wheel
```

### 11.2 切换时序(必须严格按顺序)

```
切到 wheel 模式(set BATCH_TRIGGER_SCHEDULER_IMPL=wheel,重启 trigger):

1) 启动期检测 scheduler-impl=wheel
2) 启动 Quartz Scheduler 但**立即 pauseAll()**(不让它 fire 任何 trigger)
3) 启动 HashedWheelTriggerScheduler
4) ★ TriggerReconciler 第一次同步:
     - 把 quartz 内所有 trigger 状态 dump 出来
     - 复制到 trigger_runtime_state(包括 next_fire_time)
     - 之后 Reconciler 切换到 wheel 模式对账
5) 启动 ShedLock 抢 leader,fast-path 跑起来

切回 quartz 模式(回滚):

1) 重启 trigger,scheduler-impl=quartz
2) 从 trigger_runtime_state dump next_fire_time 回写到 QRTZ_TRIGGERS
3) HashedWheelTriggerScheduler 不启动
4) Quartz Scheduler.start() / resumeAll()
5) TriggerReconciler 切回 quartz 模式对账
```

**严禁两个引擎同时运行**——任何瞬间只有一个在 fire。

### 11.3 切换的代码门控

```java
@Configuration
public class TriggerSchedulerConfiguration {

  @Bean
  @ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "quartz", matchIfMissing = true)
  public TriggerScheduler quartzTriggerScheduler(...) { ... }

  @Bean
  @ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
  public TriggerScheduler wheelTriggerScheduler(...) { ... }

  // Quartz 在 wheel 模式下也启动,但立即 pause
  @Bean
  public SchedulerFactoryBeanCustomizer pauseQuartzInWheelMode(
      @Value("${batch.trigger.scheduler-impl}") String impl) {
    return sfb -> {
      if ("wheel".equals(impl)) {
        sfb.setAutoStartup(false);  // 不自动 start,避免 Quartz fire
      }
    };
  }
}
```

### 11.4 数据迁移脚本

```sql
-- 切到 wheel 前,把 Quartz 状态复制到 runtime_state
INSERT INTO batch.trigger_runtime_state (job_definition_id, tenant_id, job_code, next_fire_time)
SELECT jd.id, jd.tenant_id, jd.job_code,
       to_timestamp(qt.NEXT_FIRE_TIME / 1000.0)
  FROM batch.job_definition jd
  JOIN quartz.QRTZ_TRIGGERS qt ON qt.JOB_NAME = jd.job_code
 WHERE jd.enabled = true AND qt.NEXT_FIRE_TIME > 0
ON CONFLICT (job_definition_id) DO UPDATE
   SET next_fire_time = EXCLUDED.next_fire_time;

-- 回滚时,把 runtime_state 复制回 Quartz(更复杂,涉及 QRTZ_FIRED_TRIGGERS / QRTZ_LOCKS 重建)
-- 通常推荐:回滚时直接清掉 QRTZ_TRIGGERS 让 TriggerReconciler 30s 内自动重建
```

---

## 12. Graceful shutdown

```java
@PreDestroy
public void shutdown() throws InterruptedException {
  log.info("trigger scheduler shutdown initiated");
  // 1) 主动放弃 leader(cancel 所有 wheel 内 task)
  cancelAllScheduledTimeouts();
  inFlightFires.clear();
  // 2) wheel 停止(Netty HashedWheelTimer.stop())
  wheel.stop();
  // 3) ShedLock 主动释放(让其他实例立即接管)
  shedLockManager.releaseLock("trigger-leader");
  log.info("trigger scheduler shutdown completed");
}
```

**不等 wheel 内 task 跑完**——DB 状态(next_fire_time)还在,新 leader 接管时通过 §6 fast-path 重新调度,不会丢 fire。

---

## 13. Wheel 专属 metric(替换 Quartz metric 之后)

| 指标 | 类型 | 说明 |
|---|---|---|
| `batch.trigger.wheel.tasks.scheduled` | Gauge | 当前 wheel 内 task 数 |
| `batch.trigger.wheel.fire.lag.ms` | Histogram | 实际 fire 时间 - 预期 fire 时间 |
| `batch.trigger.wheel.scan.duration.ms` | Timer | slidingWindow 扫库耗时 |
| `batch.trigger.wheel.scan.task.count` | Histogram | 单次扫库 push 的 task 数 |
| `batch.trigger.fire.duplicate.skipped` | Counter | DB UNIQUE 兜住的重复 fire 数 |
| `batch.trigger.fire.success` | Counter | 成功 fire 数 |
| `batch.trigger.fire.failed` | Counter | LaunchService 调用失败数 |
| `batch.trigger.misfire.handled` | Counter (tag: policy=NONE/AUTO/MANUAL) | misfire 处理数 |
| `batch.trigger.leader.acquire` | Counter | leader 切换次数 |
| `batch.trigger.leader.acquire.duration.ms` | Timer | fast-path 耗时 |
| `batch.trigger.runtime_state.stale_marker.released` | Counter | 接管的 stale marker 数 |

阶段 0 已加的 4 个 Quartz metric 在切换后保留(Quartz 还在但 paused),可以观察 Quartz 是否真的被压制(应该一直 0)。

---

## 14. 测试矩阵

### 14.1 单元测试(覆盖率目标 > 80%)

| 测试类 | 覆盖点 |
|---|---|
| `HashedWheelTriggerSchedulerTest` | slidingWindow 逻辑 / fire 流程 / misfire 分支 |
| `TriggerStateRepositoryTest` | scheduled_fire_marker 占位 / 释放 / stale 接管 |
| `CronExpressionAdapterTest` | Quartz CronExpression 包装,时区,L/W/# 字符 |
| `MisfireCatchUpHandlerTest` | NONE/AUTO/MANUAL_APPROVAL 三分支 |
| `LeaderTransitionTest` | onLeaderAcquire fast-path |

### 14.2 集成测试(testcontainer 跑)

| 测试类 | 覆盖点 |
|---|---|
| `LeaderFailoverIT` | 杀 leader → 新 leader 接管 → fast-path 验证 |
| `DoubleFireDefenseIT` | 模拟双 leader → fire 同 trigger → DB UNIQUE 兜住 |
| `SlidingWindowDedupIT` | 扫库 3 次 → wheel 内只有 1 个 task |
| `ScheduleSwitchIT` | quartz → wheel → quartz 切换,无 fire 漏 / 双 fire |
| `MisfireBatchCatchUpIT` | 模拟 100 个 misfire,验证 throttle 不打挂 LaunchService |
| `CronComputationIT` | 各种 cron 表达式跑 24h,与 Quartz 对比 next-fire-time 完全一致 |

### 14.3 性能测试(load-tests 模块,§4 quartz-capacity-baseline)

| 场景 | 通过标准 |
|---|---|
| 1000 trigger / 5s cron 跑 1h | fire QPS = 200,fire lag P99 < 500ms |
| 10000 trigger / 1min cron 跑 1h | fire QPS = 167,fire lag P99 < 1s |
| 100000 trigger / 5min cron 跑 1h | fire QPS = 333,fire lag P99 < 2s |

如果性能不达标,wheel 参数需要调优(tick / bucket / sliding-window 大小)。

---

## 15. ⭐ 工程量校准(R-5)

### 15.1 原估算 vs 修正

| 阶段 | 原 evaluation 估算 | 本 design 修正 | 多出来的工作 |
|---|---|---|---|
| 1. scheduler-impl 配置项 | 0.5 天 | 0.5 天 | — |
| 2. HashedWheelTriggerScheduler | 5 天 | 7 天 | + dedup map + Timeout registry + leader 检测 |
| 3. **TriggerStateRepository(新增)** | 未估算 | **3 天** | 新表 + DDL + 仓储 + lock-then-update |
| 4. **trigger_request 加 fire 强约束** | 未估算 | **2 天** | DDL + Repository 改造 + 测试 |
| 5. TriggerStore + cron 适配 | 2 天 | 3 天 | + Quartz CronExpression 包装 + L/W/# 扫描 |
| 6. Misfire / catch-up | 2 天 | 4 天 | + throttle + MANUAL_APPROVAL 表 + 启动期 catch-up |
| 7. CRUD 联动 | 含在 5 里 | 2 天 | enable/disable/update 三联动 + Reconciler 改造 |
| 8. **灰度切换的双引擎防护** | 未估算 | **3 天** | Quartz auto-start=false + 数据迁移 SQL + 文档 |
| 9. 单测 + IT | 5 天 | **10 天** | failover IT + 双 fire IT + cron 一致性 IT |
| 10. 灰度上线 | 5 天 | 5 天 | — |
| **小计** | **~1 人月** | **~1.95 人月**(40 天 / 21 天工作日) | **+ 95% 工作量** |

### 15.2 真实风险点(影响排期)

- 灰度切换数据迁移 SQL 在生产 PG 跑可能慢(QRTZ_TRIGGERS 大时几分钟到几十分钟)→ 维护窗口
- failover IT 的 testcontainer 模拟 GC pause / 网络分区比较 tricky → 可能要人工注入故障
- cron 一致性 IT 要跑大量表达式(24h × N 个 cron 算 next-fire-time 跨 Quartz vs 本方案)→ CI 慢

### 15.3 排期建议

```
第 1 周:DDL + Repository + 单测(基础设施)
第 2 周:HashedWheelTriggerScheduler 核心 + CRUD 联动
第 3 周:Misfire + catch-up + 灰度切换 + 单测覆盖
第 4 周:IT 测试 + 性能压测 + 灰度上线 staging
第 5-8 周:staging 跑 2 周观察 → 生产灰度 1 个 trigger 实例 → 全量切换
```

**总人月:1.5-2 人月开发 + 1 人月 staging/灰度观察 = 真实从开工到全量切换 2-3 个月**。

---

## 16. 实施前 Checklist(动工前必须 ✅)

### 16.1 设计层

- [ ] 业务方明确 cron 精度 SLA,所有红色场景(秒级 fire / 实时交易)迁出
- [ ] 跑 §7.1 cron 兼容性扫描,确认 0 个 L/W/# 表达式(或决定改写)
- [ ] 确认 `trigger_runtime_state` 表 schema 通过 DBA review
- [ ] 确认 `trigger_request` 加 fire 唯一约束不冲突现有数据

### 16.2 基础设施层

- [ ] Redis ShedLock 在 trigger 模块就位(目前用 quartz 自己的 cluster lock,需要补)
- [ ] Quartz auto-start=false 的切换路径 staging 验证过
- [ ] 4 个 Quartz health metric(已加,2026-04-25)在 Grafana 显示正常,作为切换前 baseline

### 16.3 测试层

- [ ] 14.2 集成测试矩阵全部 ✅
- [ ] 14.3 性能测试达标
- [ ] failover IT 至少 100 次循环测试无双 fire / 无漏 fire

### 16.4 灰度层

- [ ] staging 环境跑 2 周无回归
- [ ] 生产灰度方案:先选 1 个低优先级 trigger 实例切 wheel,其余实例保持 quartz,观察 1 周
- [ ] 监控告警:fire QPS 异常、lag P99 异常、duplicate skipped 异常 三个 alert 就位

### 16.5 回滚层

- [ ] 配置项 `BATCH_TRIGGER_SCHEDULER_IMPL=quartz` 一键回滚验证过
- [ ] Quartz 数据迁回的 SQL 验证过(从 trigger_runtime_state 回写 QRTZ_TRIGGERS)
- [ ] 回滚时 trigger_runtime_state 表保留(不删,留作审计)

---

## 17. 已知风险(R-1 ~ R-5 之外的)

| 风险 | 严重度 | 缓解 |
|---|---|---|
| ShedLock TTL 过期但 leader 实际还活着(GC pause 长) | 高 | DB UNIQUE fire 约束兜底(R-1);ShedLock lockAtLeastFor 调到 30s |
| Wheel 内存占用随 trigger 数增长 | 中 | 100k trigger × 5 min 窗口 ≈ 几 MB;监控 `tasks.scheduled` gauge |
| HashedWheelTimer.stop() 不释放 wheel 内 task | 中 | shutdown 不依赖 wheel 内 task 完成,DB 状态权威 |
| `trigger_runtime_state.scheduled_fire_marker` 长期不释放(stale) | 中 | 周期性 release(§4.3),阈值 5 min |
| Cron 表达式被改 → 旧 cron 计算的 next_fire_time 还在 DB | 低 | TriggerReconciler 监听 schedule drift,自动重算(§10.3) |
| Quartz 历史 trigger 表数据无人清(切到 wheel 后) | 低 | 阶段 2 显式 drop QRTZ_* schema |
| 时区切换(夏令时进入/退出)导致 next_fire_time 错乱 | 中 | Quartz CronExpression 已处理夏令时,沿用即可 |

---

## 18. 与原 evaluation.md 的对照引用

| 本文档章节 | evaluation.md 对应 |
|---|---|
| §1 整体架构 | §5.1, §5.2 |
| §2 状态持久化 | **新增**(evaluation 未涉及) |
| §3 fire 强约束 | **强化** evaluation §5.6 风险 |
| §4 滑动窗口去重 | **强化** evaluation §5.2 限制 1 |
| §5 时间精度 SLA | **新增** |
| §6 Failover fast-path | **强化** evaluation §5.6 |
| §7 cron 一致性 | **新增**(evaluation 未涉及) |
| §15 工程量校准 | **修正** evaluation §6 阶段 1 估算 |
| §16 Checklist | **新增**(动工前必备) |

---

## 19. 修订历史

| 日期 | 改动 |
|---|---|
| 2026-04-25 | 文档创建,基于 evaluation.md 外部 review 后的强化版,补 5 项关键风险(R-1 ~ R-5)+ 完整 schema + 接口契约 + 测试矩阵 + 工程量校准 |
