# Quartz 替换为时间轮的可执行方案

> **状态**：决策依据 + 起步设计；阶段 0(零成本动作)可立刻做,阶段 1(实施)等业务量级到拐点再开干。
>
> **关联文档**:
> - [`docs/runbook/feature-switches.md`](../runbook/feature-switches.md) §3.4(原 quartz-datasource 开关移除说明)
> - [`docs/architecture/rework-classification.md`](./rework-classification.md) Phase 2 章节
> - [`docs/architecture/scalability-assessment.md`](./scalability-assessment.md) §6 路线图

---

## 0. 一句话结论

> 业务量级接近 1000 万 fire/天 拐点时,**直接用 Netty `HashedWheelTimer` 替换 Quartz**,跳过"Quartz 独立库"中间过渡。内核被 Pulsar / Dubbo / Curator 用了多年,生产级。
>
> **工程量(2026-04-25 经外部 review 校准)**:1.5-2 人月开发 + 1 人月 staging/灰度观察 = 真实从开工到全量切换 2-3 个月。原 "≤ 800 行 / 1 人月" 估算偏乐观,**未计入** fire 强约束 / 滑动窗口去重 / failover fast-path / 双引擎防护 / cron 一致性等生产风险回退。
>
> ⚠️ **动工前必读**:[`quartz-replacement-design.md`](./quartz-replacement-design.md)(详细实施设计 + 5 项风险回退 + Pre-flight Checklist)。本文档(evaluation)只承载战略决策,设计层细节全部在 design.md。

---

## 1. 当前 Quartz 在项目里的角色

```
┌─────────────────────────────────────────────────────────────┐
│ Quartz (定时 cron fire)            ← Quartz 只做这一件事    │
│   ↓ HTTP                                                    │
│ TriggerSchedulerFacade                                      │
│   ↓                                                         │
│ TriggerReconciler / TriggerRequestStore (持久化)           │
└─────────────────────────────────────────────────────────────┘
                          │ HTTP /internal/launch
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Orchestrator                                                │
│   ├─ LaunchService (写 job_instance + outbox + partition)  │
│   ├─ Workflow / DAG 引擎                                    │
│   ├─ Worker pool 管理 (worker_registry)                    │
│   └─ Quota / Retry / Dispatch 策略                         │
└─────────────────────────────────────────────────────────────┘
                          │ Outbox → Kafka
                          ↓
              Worker (consume + claim + execute)
```

**Quartz 在整个系统里非常窄**:给定 cron 表达式,到点了 callback 一次 HTTP。**不**承担:
- 任务路由 / 分片(orchestrator 自己做)
- 工作流编排(workflow_run / workflow_node 自己做)
- worker 池管理(worker_registry 自己做)
- 重试 / quota / 限流(自己做)
- 派发(Kafka + outbox 自己做)

**这个判断决定了选型方向**:替换的范围只有"cron 到点 fire 一次回调"这一小块,**不是全栈调度框架替换**。XXL-Job / PowerJob / DolphinScheduler / Temporal 这些方案的 worker / workflow / 执行器层会和现有架构 overlap,因此都不推荐。

---

## 2. Quartz 的 4 个根本设计限制

到亿级 fire/天 量级遇到的**协调机制瓶颈**,不是 DB 容量瓶颈——这是为什么"换库 / 加索引 / 分库分表"都解不了。

### 2.1 集群模式靠 `QRTZ_LOCKS` 两行抢锁(最致命)

`QRTZ_LOCKS` 表只有 2 行:`TRIGGER_ACCESS` / `STATE_ACCESS`。任何 scheduler 节点 acquire trigger / fire / 状态更新前都要 `SELECT * FROM QRTZ_LOCKS WHERE LOCK_NAME=? FOR UPDATE`。

```
节点 A → 抢 TRIGGER_ACCESS → acquire/fire → 释放
节点 B → 等                                  → 拿到 → 同样流程
节点 C → 等                                            → 拿到 → ...
```

整集群 serialized 在这 2 行。增加 trigger 实例 ≠ 增加吞吐;锁等待随集群规模线性恶化。**这个锁不能分片**——是 Quartz 引擎语义需求,不是性能优化空间。

### 2.2 Trigger acquisition 是 polling 模型,不是事件驱动

```sql
SELECT TRIGGER_NAME FROM QRTZ_TRIGGERS
 WHERE NEXT_FIRE_TIME <= ? AND STATE='WAITING'
 ORDER BY NEXT_FIRE_TIME LIMIT ?
FOR UPDATE
```

每 ~30s 跑一次。亿级 fire/天 = 千万级活跃 trigger,这个 SELECT 在单库怎么调都慢。事件驱动调度框架(时间轮)根本不查 DB。

### 2.3 没有水平分片的概念

Quartz cluster = "全副本 + leader 抢锁"模型,所有节点看完整 trigger 集合。不是 partition / sharding 模型。增加节点 ≠ 增加吞吐。

### 2.4 misfire 风暴

海量 trigger + 短 misfire threshold → 集群短暂卡顿(GC / 网络 / DB 慢)→ 大批 trigger 同时 misfire → scheduler 同时处理 misfire 抢同一行锁 → 雪上加霜。

---

## 3. 为什么"换库"不是解药

| 替代方案 | 解决了什么 | 没解决 |
|---|---|---|
| Quartz 表搬独立 PG 实例(原 Phase 2 那个开关) | 5% — WAL 隔离 | 2.1 / 2.2 / 2.3 / 2.4 全没动 |
| Quartz 表分库分表 | 单库容量 | 引擎不知道分片,acquire 还要看全集 |
| PG 换 TiDB / CockroachDB | 单点写入瓶颈 | `FOR UPDATE` 在分布式 PG 上更慢 + 还是行锁 |
| 加 read replica 给 Quartz | 读写分离 | Quartz 几乎全是 SELECT FOR UPDATE,无读副本可用 |

**问题在协调机制,不在数据存储**。换 DB 这一层的任何优化都是治标。**这就是 Phase 2 quartz-datasource 开关在 2026-04-25 被撤销的根本理由**——它解决的是 5% 的次要瓶颈,且终态(时间轮)不需要它。

---

## 4. 候选方案对比

### 4.1 推荐:方案 A —— trigger 模块自研时间轮

| 方案 | 引入复杂度 | 替换范围 | 适合本项目 |
|---|---|---|---|
| **A. trigger 模块自研时间轮 + ShedLock** | 🟢 轻 | 只替换 Quartz | ⭐⭐⭐⭐⭐ |
| B. XXL-Job 只用调度层(admin → HTTP 回调) | 🟡 中 | 替换 Quartz + 引入 admin 中间件 | ⭐⭐⭐ 可行 |
| C. PowerJob / DolphinScheduler / Temporal | 🔴 重 | 全栈替换 | ❌ workflow / worker overlap,推倒重做 |
| D. JDK ScheduledThreadPoolExecutor | 标准库 | 不是时间轮 | ❌ DelayQueue O(log n) 插入慢,万级 task 失速 |

### 4.2 为什么不推荐 B/C

**XXL-Job(B)**:运维多一个 admin server + 它的 DB(MySQL,PG 不直接支持需 fork);XXL-Job 内部还是 polling + DB 锁,只是把 Quartz 瓶颈往后推一档;国际化 / 长期维护比 Apache 项目差。

**PowerJob / Apache DolphinScheduler / Cadence / Temporal(C)**:它们自带 workflow / DAG 引擎,跟本项目 `workflow_run` / `workflow_node` 严重 overlap;依赖 MongoDB(PowerJob)/ Cassandra(Temporal)等新中间件;真要替换 = 重写 worker 端为 activity 模型。把 5% 的事换了顺便覆盖 50% 的事 = 推倒重做,不划算。

---

## 5. 方案 A 详细设计

### 5.1 推荐内核:Netty `HashedWheelTimer`

| 维度 | 信息 |
|---|---|
| Maven 坐标 | `io.netty:netty-common`(Spring Boot 大概率通过 reactor-netty 已传递依赖) |
| 主类 | `io.netty.util.HashedWheelTimer`(单类,~700 行) |
| 生产用户 | Netty 自身、Apache Pulsar、Dubbo、RocketMQ broker、Apache Curator |
| API | `timer.newTimeout(task, delay, TimeUnit)` 一行 |
| 默认精度 | 100ms tick,512 buckets |

```java
// 用起来就这么简单
HashedWheelTimer timer = new HashedWheelTimer(
    new DefaultThreadFactory("trigger-wheel"), 100, MILLISECONDS, 512);

timer.newTimeout(timeout -> {
  triggerLaunchService.fire(triggerCode);
}, delayMillis, MILLISECONDS);
```

### 5.2 内核两个限制 + 应对

#### 限制 1:单层时间轮,不擅长跨度大的延迟
100ms × 512 buckets = 一圈 ~51s。延迟超过 51s 时 task 绕圈,圈越多 CPU 越浪费。

**应对:滑动窗口扫库**。不一次性把所有未来 trigger 都 push 进去。每分钟扫一次"未来 5 分钟内 next-fire 的 trigger",push 进 wheel。这样 wheel 里永远只有近期 task,绕圈次数可控。

```
DB 持久化全集 (10 万活跃 trigger)
        ↓ 每分钟滑动扫库
 时间轮内存 (~500 个近期 task)
        ↓ tick 触发
 fire callback → HTTP 调 LaunchService
```

#### 限制 2:不持久化,JVM 重启丢失
**应对:复用项目现有 trigger_request 表 + `TriggerReconciler`**。leader 启动时从 DB 重建滑动窗口。

### 5.3 完整组件:已有 + 新增

| 层 | 实现 | 项目里现成吗 |
|---|---|---|
| 时间轮内核 | Netty `HashedWheelTimer` | 引入 1 行依赖(可能已传递) |
| Cron 解析 | Spring `CronExpression`(Spring 5+ 内置) | ✅ 已有 |
| 集群协调 leader-elect | `RedisShedLockProvider`("trigger-leader" 锁) | ✅ 已有 |
| 持久化 | `trigger_request` / `job_definition.schedule_expr` | ✅ 已有 |
| 启动期 reconciler | `TriggerReconciler` 同步注册 | ✅ 已有,改造接口 |
| 时区 | `BatchTimezoneProvider` | ✅ 已有 |
| Misfire / Catch-up | `CatchUpPolicyType`(NONE / AUTO / MANUAL_APPROVAL) | ✅ 已有 |

**真正要写的只有内核 wrapper(~300 行)+ 滑动窗口扫库 scheduler(~200 行)+ 适配现有接口(~200 行)**。其他都是接现成。

### 5.4 起步代码骨架

```java
@Component
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
public class HashedWheelTriggerScheduler implements TriggerScheduler {

  private final HashedWheelTimer wheel;
  private final ShedLock shedLock;
  private final TriggerStore store;
  private final TriggerLaunchService launchService;
  private final BatchTimezoneProvider tzProvider;

  @PostConstruct
  void onStart() {
    // 启动期重建滑动窗口,避免 JVM 重启丢失
    slidingWindow();
  }

  /** 每 60s 扫库一次,把未来 5 min 内的 fire 推进 wheel。 */
  @Scheduled(fixedDelay = 60_000)
  public void slidingWindow() {
    if (!shedLock.tryLock("trigger-leader", Duration.ofMinutes(2))) {
      return; // 非 leader 实例不工作
    }
    try {
      Instant horizon = Instant.now().plus(Duration.ofMinutes(5));
      for (TriggerRecord t : store.findEnabledFiringBefore(horizon)) {
        long delay = ChronoUnit.MILLIS.between(Instant.now(), t.nextFireTime());
        if (delay < 0) {
          // 已经过期,按 CatchUpPolicy 决定立即 fire 还是跳过
          handleMisfire(t);
          continue;
        }
        wheel.newTimeout(timeout -> fire(t), delay, MILLISECONDS);
      }
    } finally {
      shedLock.extendOrRelease();
    }
  }

  private void fire(TriggerRecord t) {
    try {
      launchService.launch(t);
      store.advanceNextFireTime(t, computeNext(t.cronExpr(), tzProvider.zoneFor(t)));
    } catch (Exception e) {
      log.warn("trigger fire failed: {}", t.code(), e);
      // 不抛异常,下次扫库重新 push 进 wheel
    }
  }

  private Instant computeNext(String cronExpr, ZoneId zone) {
    return CronExpression.parse(cronExpr).next(ZonedDateTime.now(zone)).toInstant();
  }

  private void handleMisfire(TriggerRecord t) {
    switch (t.catchUpPolicy()) {
      case NONE -> store.advanceNextFireTime(t, computeNext(t.cronExpr(), tzProvider.zoneFor(t)));
      case AUTO -> { launchService.launch(t); store.advanceNextFireTime(t, ...); }
      case MANUAL_APPROVAL -> store.markPendingApproval(t);
    }
  }
}
```

### 5.5 容量上限

| 量级 | 是否够用 |
|---|---|
| ~10 万活跃 trigger | ✅ 512MB heap 充裕,wheel 内存占用可忽略 |
| ~100 万活跃 trigger | ✅ 1-2GB heap,分桶 + slab 优化 |
| 单实例 fire 吞吐 | ✅ 5000-10000 fire/s(time wheel tick 是 O(1)) |

足够本项目走到 1 亿 fire/天 拐点(~1150 fire/s 平均)。

### 5.6 风险与缓解

| 风险 | 缓解 |
|---|---|
| Leader 单点:GC pause 1s 全集群延迟 1s | 业务调度精度 SLA 不到秒级,可接受 |
| Failover 冷启动:leader 挂 → 新 leader 重建 wheel 几秒~几十秒 | misfire catch-up 回退(`CatchUpPolicyType.AUTO`)能补回 |
| ShedLock TTL 期间 leader 漂移导致重复 fire | TTL 设置长于 fire 处理时长(默认 2 min);LaunchService 已有幂等(`trigger_request` 唯一键 `trigger_code + biz_date`) |
| Cron 表达式解析差异(Spring vs Quartz 5/6 字段) | Spring `CronExpression` 是 6 字段(秒级),与项目历史保持一致(`docs/changelog.md` 2026-04-24 已统一) |

---

## 6. 演进路线图

### 阶段 0:零成本预备(立刻做)

| 动作 | 工作量 | 价值 |
|---|---|---|
| ✅ 删 Phase 2 quartz-datasource 半成品开关 | 已完成(2026-04-25) | 认知一致,减少误导 |
| 加 Quartz health metric(下一节 §7) | 几小时 | 拐点预警窗口 2-4 周 |
| 用 `load-tests` 模块跑 Quartz 容量压测 | 半天 | 实测真实拐点,不靠经验值估 |

### 阶段 1:实施(业务量级接近 1000 万 fire/天 时启动)

| 动作 | 工作量 |
|---|---|
| 1. 加 `batch.trigger.scheduler-impl` 配置项(quartz / wheel 二选一,默认 quartz **→ 2026-04-26 切默认 wheel**) | 0.5 天 |
| 2. 实现 `HashedWheelTriggerScheduler`(§5.4 骨架) | 5 天 |
| 3. 实现 `TriggerStore`(基于 trigger_request 表) | 2 天 |
| 4. 实现 misfire / catch-up(对接现有 `CatchUpPolicyType`) | 2 天 |
| 5. 单测 + IT(testcontainer + Redis + 模拟 leader 漂移) | 5 天 |
| 6. 灰度切换(staging 跑 1 周 → 生产先开 1 个 trigger 实例) | 5 天 |
| **小计** | **~1 人月** |

### 阶段 2:清理(时间轮稳定运行 2 周后)

| 动作 | 工作量 |
|---|---|
| 删 Quartz 依赖(`spring-boot-starter-quartz`) | 0.5 天 |
| 删 `batch_platform.quartz` schema + 11 张 `QRTZ_*` 表 | 0.5 天 |
| 删 `TriggerReconciler` 里 Quartz 适配代码 | 1 天 |
| 删 `batch.trigger.scheduler-impl` 二选一开关(只剩 wheel) | 0.5 天 |

---

## 7. 拐点预警:Quartz 四个 health metric

阶段 0 立刻做。micrometer 自定义 gauge 暴露:

| 指标 | 数据来源 | 黄色阈值 | 红色阈值 |
|---|---|---|---|
| `quartz.fire.rate.qps` | 滑动窗口计数 fire 调用 | > 10 QPS | > 50 QPS |
| `quartz.lock.wait.ms.p99` | `QRTZ_LOCKS` 抢锁耗时 P99 | > 100ms | > 500ms |
| `quartz.misfire.count.5m` | 每 5 分钟 misfire 数量 | > 0 持续 5 分钟 | > 100 |
| `quartz.wal.byte.ratio` | `pg_stat_database` Quartz schema 写入字节占主库比 | > 30% | > 60% |

到拐点前这 4 个指标会先动,预警窗口至少 2-4 周——足够团队排期阶段 1。

Grafana panel 可加进 `docker/observability/grafana-dashboard-batch-coverage.json`。

---

## 8. 决策树:什么时候启动阶段 1

```
                        ┌─────────────┐
                        │ 当前业务    │
                        │ < 100 万/天 │
                        └──────┬──────┘
                               │
                  ┌────────────┼────────────┐
                  │            │            │
            预测 1 年内      预测 1-2 年    业务量级
            到 100 万/天    到 1000 万/天   突破 1000 万/天
                  │            │            │
                  ↓            ↓            ↓
            阶段 0 已完成  阶段 0 已完成  立刻启动
            继续观察      health metric  阶段 1
                          告警黄色       (1 人月)
                          → 启动阶段 1
```

---

## 9. 不做什么(明确拒绝)

| 不做 | 原因 |
|---|---|
| Quartz 独立 PG 实例(原 Phase 2 开关) | 解 5% 问题,终态时变孤儿,长期运维负担 |
| Quartz cluster mode(多 trigger 节点) | QRTZ_LOCKS 行锁是天花板,加节点 ≠ 加吞吐 |
| 引入 PowerJob / Temporal / DolphinScheduler | Workflow 引擎 overlap,推倒重做 |
| 现在就开始写时间轮 | 业务量级远未到拐点,YAGNI;先做阶段 0 预警 |
| 自研分层时间轮(秒/分/时/天 多层) | 单层 + 滑动窗口够用到亿级 fire/天;真要多层时再升级 |

---

## 10. 参考资料

- Netty `HashedWheelTimer` 源码:`io.netty.util.HashedWheelTimer`
- Apache Kafka 分层时间轮:`kafka.utils.timer.SystemTimer` / `TimingWheel`(Scala)
- 论文:G. Varghese & A. Lauck《Hashed and Hierarchical Timing Wheels》(1987)
- Spring `CronExpression`(JDK 8+,Spring 5.3+ 内置)
- ShedLock 项目:https://github.com/lukas-krecan/ShedLock

---

## 11. 修订历史

| 日期 | 改动 |
|---|---|
| 2026-04-25 | 文档创建,与 Phase 2 quartz-datasource 半成品撤销同步落地 |
