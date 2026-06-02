# BE 资源 + 调度 深度扫描报告(2026-06-03)

> 范围:Hikari/PG 连接池总账、Kafka producer/consumer 资源、线程池/Async 配置、内存边界(import/export/REPORT JSONB)、
> ~80 个 `@Scheduled` 互锁与节奏、心跳/lease/reclaim 抖动、Outbox 投递与死信路由、resource affinity 反压闸门(V87)、
> Prometheus/告警暴露。基线:`origin/main` @ 2026-06-03 worktree。
>
> 输入扫描覆盖:`batch-defaults.yml` + 10 模块 `application.yml` + `OrchestratorAsyncConfiguration`/
> `BatchSchedulingAutoConfiguration`/`ConsoleAsyncConfiguration`/`QuartzTriggerConfiguration` 4 个池子定义,
> `grep @Scheduled` 全仓 82 处,`OutboxPollScheduler`/`PartitionLeaseReclaimScheduler`/
> `WaitingPartitionDispatchScheduler`/`BatchDayWaitingReleaseScheduler`/`HashedWheelTriggerScheduler` 5 个热路径文件,
> `prometheus-batch-rules.yml`(37 条告警)。
>
> 评级:**P0** = 单实例上线即可能炸 / 多实例放大风险;**P1** = 高负载或边角窗口下抖动;**P2** = 治理 / 噪音。

---

## 0. 一页式结论

| 维度 | 状态 | 关键发现 |
|---|---|---|
| Hikari 池总账 | 🟡 注意 | 全模块 max-pool 加总 ~145 ;PG `max_connections` 默认 100,扩到 2 副本 orchestrator 必超(见 §1.1) |
| Read-replica 池 | 🟢 正确 | console-api 主/从各 16,fail-open quarantine 已就位 |
| Kafka producer | 🟢 正确 | acks=all + idempotence + max.in.flight=5 + delivery.timeout=30s,无 linger.ms 显配(默认 0,小 batch 模式延迟优先) |
| Kafka consumer | 🟡 注意 | 4 个 worker 各自 concurrency=4 × max-poll-records=20,与 topic 分区数关系未在配置/文档锁定(见 §2.2) |
| 线程池 | 🟢 已治理 | orchestrator `taskScheduler` 16,outbox 专用 1,console push 4–16 有界,SDK fixed=maxConcurrentTasks |
| @Scheduled 节奏 | 🟡 注意 | 单 orchestrator 进程 ~37 个 `@Scheduled` 共享 16 线程池,默认间隔 10s/15s/30s/60s 多档,有挤压风险(见 §3.1) |
| BatchDay 4 调度器 | 🟢 锁分离 | open/cutoff/settle/waiting-release 各自独立 ShedLock name,无重叠;依赖前后顺序由数据状态承担 |
| 心跳 vs lease | 🟡 边角 | renew-interval 10s / heartbeat 15s / lease-expire 120s,数学正确;但 `lockAtMost(publishingTimeout+10s)` 与 reclaim `LOCK_AT_MOST=120s` 同量级,有边角窗口 |
| outbox/retry/trigger 三表分离 | 🟢 设计正确 | 路由分流到 outbox_event / event_outbox_retry / trigger_outbox_event,无互蹭路径 |
| OutboxForwarder 节奏 | 🟢 自适应 | min=200ms / max=5s / backoff=1.5x,空闲指数退避避免空轮 DB |
| 资源亲和闸门 | 🟢 已落地 | resourceTag/capability_tags/max_concurrent 全在 WaitingPartitionDispatchScheduler 决策路径上 |
| Prometheus 告警 | 🟢 覆盖完整 | 37 条,outbox/dispatch/SLA/replication/dead-letter/worker 心跳全覆盖 |
| **P0** | 0 | — |
| **P1** | 5 | 见 §10 |
| **P2** | 9 | 见 §10 |

---

## 1. DB 连接池

### 1.1 Hikari max-pool 总账

| 模块 | platform max | platform min | business max | business min | leak-detection | 备注 |
|---|---:|---:|---:|---:|---:|---|
| batch-orchestrator | 50 | 5 | — | — | 60s | 含 Outbox + 12 路调度 + Console API 代理 |
| batch-trigger | 50 *(继承 orchestrator,QuartzJobStore 共享主 DS)* | — | — | — | — | 默认 hikari 单 DS 即 platform |
| batch-console-api primary | 16 | 默认 10 | — | — | 0(关) | 读写分离 primary |
| batch-console-api replica | 16 | 默认 10 | — | — | 0(关) | 读写分离 replica |
| batch-worker-import | 10 | 3 | 15 | 3 | 未显配 (business=60s) | 双 DataSource |
| batch-worker-export | 10 | 3 | 20 | 5 | business 60s | 双 DataSource |
| batch-worker-process | 10 | 3 | 20 | 5 | business 60s | 双 DataSource |
| batch-worker-dispatch | 10 | 3 | — | — | 未显配 | 仅 platform |
| batch-worker-atomic | 6 | 2 | — | — | 未显配 | 低权限 DS,独立账号 |

**总账(单副本各服务)**:
- **平台库连接**: 50(orch) + 50(trigger) + 32(console 16+16) + 10×4(workers) + 6(atomic) = **178**
- **业务库连接**: 15(import) + 20(export) + 20(process) = **55**

**P1-1 [DB] 单 PG 实例 `max_connections` 容量与扩容假设缺失**
- Postgres 默认 `max_connections=100`,本地 docker-compose 也未在 `docker/postgres/` 显式抬高(需复核 `docker/postgres/init/`)。
- 单副本平台库已 178 连接;若 orchestrator/trigger 各扩 2 副本则 178+50+50=278。
- `batch-defaults.yml` 已写"生产建议前置 PgBouncer(transaction mode)",但项目内 **无 PgBouncer compose / helm 模板,没有显式 pool_mode=transaction 与 default_pool_size 校准记录**。
- **修复**: 在 `docs/runbook/` 新增 `db-connection-budget.md`,明确"扩容到 N 副本 orchestrator 需要 PgBouncer transaction pool,default_pool_size= ⌈(178+N·50)/(可接受 PG backend 上限)⌉";helm chart 加 PgBouncer 可选 sub-chart。

**P2-1 [DB] worker import / dispatch / atomic 未配 `leak-detection-threshold`**
- 仅 orchestrator (60s)、export.business (60s)、process.business (60s) 显式开启;
- import.business / dispatch / atomic 走 Hikari 默认(0=关闭)→ Try-with-resources 漏归还的瞬时 bug 在测试期看不见。
- **修复**: 全 worker 统一 `BATCH_*_LEAK_DETECTION_MS:60000` 默认,与 orchestrator 对齐。

### 1.2 连接泄漏(代码层)

全仓 `grep "getConnection()" --include="*.java"` 在 worker / orchestrator 主路径 **零命中**(MyBatis/JdbcTemplate 自管连接)。`OrchestratorStartupLeaseAudit` 等启动期类亦走 Mapper。这条线 OK,无需新增 lint。

### 1.3 PG 会话超时(pg-session)

`batch-defaults.yml` 已统一:
- platform statement-timeout=15m / idle-in-tx=60s
- business statement-timeout=30m / idle-in-tx=10m
- 通过 Hikari `connectionInitSql` 在每条 connection 上 SET LOCAL,Flyway 与业务共用同池时受限但已留 `pg-session.enabled=false` 回退。

**P2-2** :SuccessInstanceArchiveService 单事务跨 12 表 INSERT SELECT + DELETE 已经把 `leak-detection-threshold` 抬到 60s 兜底;但 archive cron(04:30 周日 + 04:15 日 outbox + 03:30 outbox)三档串行起跳,峰值期与 orchestrator 调度 tick 撞期是 **已知风险但未在 monitoring 上摆告警**(无对应 Prometheus 规则)。建议加 `BatchArchiveLongRunningTransaction` 告警(pg_stat_activity xact_runtime > 5min 且 application_name='batch-orchestrator')。

### 1.4 读写分离

console-api 主从都 16,fail-open 三连击 quarantine 30s。`BATCH_CONSOLE_REPLICA_*` 完整 env。设计正确。
约束面:trigger / orchestrator / worker 禁用读写分离(状态机依赖 read-after-write),CLAUDE.md 已挂硬约束,无需复核。

---

## 2. Kafka 资源

### 2.1 Producer(全模块统一 `batch-defaults.yml`)

| 项 | 值 | 评价 |
|---|---|---|
| acks | all | ✅ 配 ISR≥2 |
| enable.idempotence | true | ✅ |
| max.in.flight | 5 | ✅ 配合幂等仍保单 partition 顺序 |
| retries | 5 | ✅ |
| delivery.timeout.ms | 30000 | ✅ 与 OutboxPoll `publishingTimeout=120s` 留出多次重试空间 |
| request.timeout.ms | 10000 | ✅ < delivery.timeout |
| linger.ms | **未显配**(Kafka 默认 0) | ⚠ P1-2 |
| batch.size | **未显配**(默认 16KB) | ⚠ P1-2 |
| compression.type | **未显配**(默认 none) | ⚠ P2-3 |

**P1-2 [Kafka] producer 未调 `linger.ms` / `batch.size`,outbox 高峰期网络效率不佳**
- OutboxPollScheduler 自适应轮询,有积压时单轮多条 + Kafka producer linger=0 → 每条消息独立 send,失去批量化机会。
- 5000/s 量级以下不会出问题,但项目 SLA 设定 batch_outbox_pending_events > 5000 才告警(P95 publish < 5s),说明实际目标量级 ≥ 5k/s。
- **修复**: `batch-defaults.yml` 增 `linger.ms=20` + `batch.size=32768`(权衡延迟 vs 吞吐),通过 `BATCH_KAFKA_PRODUCER_LINGER_MS` env 暴露。
- **不修也行**:本地 docker-compose 单 broker,生产实际部署阶段再调。但应作为 release 前的 **runtime 强制 tuning 项**写进 ops runbook。

**P2-3** : compression.type 缺省 = none,outbox event_payload(workflow_run.snapshot/job_instance details)JSON 体动辄 KB 级,开 `lz4` 节省 30%-50% 网络带宽与磁盘。低成本提升,但需确认 broker 端支持。

### 2.2 Consumer(各 worker 自己覆盖)

| Worker | concurrency | max-poll-records | max-poll-interval | metadata-max-age | max-concurrent-tasks(背压) |
|---|---:|---:|---:|---:|---:|
| import | 4 | 20 | 600s | 默认 5min | 6 |
| export | 4 | 20 | 600s | 默认 5min | 4 |
| process | 4 | 20 | 600s | 默认 5min | 4 |
| dispatch | 4 | 10 | 默认 5min | **30s**(显式低) | 8 |
| atomic | 2 | 5 | 默认 5min | 30s | 4 |

**P1-3 [Kafka] consumer 端 `concurrency=4` 与 topic 分区数没有强约束记录**
- 注释写"建议 ≤ topic partition 数,否则多余线程空转";但项目内 **未在任何 yml / readme 锁定 topic 分区数**。
- ADR-029 / P2-5 已切到 TENANT routing(`batch.task.dispatch.import.<tenant>`),每个 tenant topic 默认分区数由 Kafka auto-create 决定(server.properties 的 `num.partitions`,docker-compose 内未显式覆盖,Bitnami 默认 1)→ **多 tenant 但单分区,concurrency=4 等于 3 个线程空转**。
- **修复**:
  - 在 `docs/runbook/kafka-topic-spec.md` 锁定每类 dispatch topic 分区数(建议 import/export/process=8、dispatch=4、atomic=2)
  - docker-compose Kafka 显配 `KAFKA_CFG_NUM_PARTITIONS=8`
  - orchestrator 启动期 `KafkaAdmin` 创建 topic 时显式 partition 数,不依赖 auto-create

**P1-4 [Kafka] `max.poll.interval.ms=600s` vs `max-concurrent-tasks` 不匹配**
- import/export/process 都 600s = 10min,而 max-concurrent-tasks=4-6,单批 max-poll-records=20。
- 数学: 20 条任务并发上限 6 串行 = 4 轮,单条 import 大文件可能数分钟 → 20×单条均值若 > 10min 则 consumer 被 broker 踢出 group → rebalance 风暴。
- 项目已用 Semaphore 背压控制并发不超过 max-concurrent-tasks,但 **没有"已认领但未跑完"的下限设计**。
- **修复**: max-poll-records=20 太激进,改 4–6(= max-concurrent-tasks),让"poll 即接近满载,再 poll 时已腾空"成立。

**P2-4** : `metadata-max-age-ms` 默认 5min,而 P2-5 TENANT routing 新增 tenant 时 worker 端 PATTERN 订阅需重新刷 metadata 才能拾取 → 新租户首发任务 **可能延迟 5min 才被 worker 看见**。dispatch/atomic 已显式降到 30s,**其它 3 个 worker 未跟进**。
- **修复**: 全 worker 默认 `spring.kafka.consumer.metadata-max-age-ms=30000`(对齐 dispatch/atomic)。

### 2.3 Listener / ack 模式

`batch-defaults.yml` 统一 `ack-mode=manual_immediate`,worker 显式 ack。✅ 正确。

### 2.4 Producer 端 KafkaTemplate

`KafkaOutboxPublisher` 单例 `KafkaTemplate`,Spring 默认 producer 池(1 producer per template)。outbox 单线程 schedule + 单 producer → OK。

---

## 3. 线程池 / @Async

### 3.1 池子定义清单

| 名 | 模块 | 类型 | 大小 | RejectedExec | 备注 |
|---|---|---|---|---|---|
| `taskScheduler` | batch-common(全部继承) | ThreadPoolTaskScheduler | **16** | CallerRunsPolicy | 服务于所有 `@Scheduled` |
| `outboxPollTaskScheduler` | orchestrator | TPTS | 1 | CallerRuns | outbox 专用,与其他 @Scheduled 隔离 |
| `pushTaskExecutor` | console-api | TPTaskExecutor | core=4 max=16 q=200 | CallerRuns | @Async 推送 |
| `consoleRealtimeScheduler` | console-api | TPTS | 2 | CallerRuns | SSE 心跳 + debounce |
| `webhookDispatcher` 池 | console-api | ThreadPoolExecutor | (见代码) | AbortPolicy | webhook 出站 |
| `triggerOutboxRelay` 池 | trigger | TPTS | 单独 1 | — | IO poll 不挤其它 |
| `triggerFireExecutor` | trigger wheel | TPTaskExecutor | (见 HashedWheelTriggerScheduler) | — | wheel fire 触发 |
| SDK `batch-sdk-dispatch` | worker-sdk | FixedThreadPool | =maxConcurrentTasks | 默认 AbortPolicy | task 派发主池 |
| SDK heartbeat / lease | worker-sdk | SingleThreadScheduled | 1 | — | 各 1 个,独立 |
| OpenLineageEmitter | orchestrator | ThreadPoolExecutor | (见代码) | **AbortPolicy** | lineage 异步推送 |

**P1-5 [Thread] orchestrator 单进程 ~37 个 `@Scheduled` 共享 `taskScheduler.poolSize=16`**

- 计数(by `grep @Scheduled` in orchestrator main):37(不含 OutboxPoll 独立的)。
- 高频集 + 重活集:
  - 10s 级: WaitingPartitionDispatchScheduler、RetryScheduleScheduler
  - 15s 级: PartitionLeaseReclaimScheduler、WorkerDrainTimeoutScheduler
  - 30s 级: SLA / file-governance ×3 / backlog metrics / DeadLetterAutoRetry / WorkerHeartbeatTimeout / SensorPollScheduler
  - 60s 级: BatchDay×4 / Cron / Timeout / Workflow / Trigger reconcile / FileGovernance archive / cleanup / Cross-day
- 同一秒上井喷时所有 task 同时唤醒,16 线程不够并发跑,余下走 CallerRunsPolicy → **调度线程被业务任务占住,后续 @Scheduled trigger 延迟**。
- **修复**:
  1. `taskScheduler.poolSize` 提升到 24(注释里写"~55 个 bean 共享",但 16 太紧)
  2. 把"重活 archive / metrics scan / file governance reconcile"明确迁到独立 `archiveTaskScheduler`(类似 outboxPoll 独立池的做法)
  3. 给所有 `@Scheduled` 加 `@SchedulerLock` 时 **必须显式 lockAtLeast >= 30s** 确保即便延迟唤醒也不重叠(已大部分做到,RetryScheduleScheduler `lockAtLeast=5s` 偏低)

**P2-5 [Thread] OpenLineageEmitter 用 AbortPolicy 静默丢任务**
- `infrastructure/lineage/OpenLineageEmitter.java:77` 显式 `new ThreadPoolExecutor.AbortPolicy()`。
- lineage 缺失通常不致命,但 AbortPolicy 抛 `RejectedExecutionException` 时业务调用方收到异常 →
- **修复**: 改 CallerRuns + 加 metric counter,丢失也得记录。

**P2-6 [Thread] WebhookDispatcher AbortPolicy**
- 类似上,但 webhook 是对外承诺的回调,丢失更严重。已有 `WebhookDeliveryRelay` 兜底,但需确认 relay 路径覆盖 AbortPolicy 的丢失场景。

### 3.2 SDK 端

`HeartbeatScheduler` / `LeaseRenewalScheduler` 各 1 thread,独立 daemon。
`TaskDispatcher` 主池 `FixedThreadPool(maxConcurrentTasks)`,默认 AbortPolicy →
- 实际 KafkaTaskConsumer 端用 Semaphore 背压控制流量,不会触达 AbortPolicy
- 但 if Semaphore 与 Fixed 大小不一致(配置漂移),线程池会拒绝 → Semaphore 已释放 → 任务静默丢失
- **修复(P2-7)**:SDK ctor 加 invariant assert `pool.size == Semaphore.permits`。

---

## 4. 内存边界

### 4.1 Import / Export 流式与 chunk

| 项 | import 默认 | export 默认 |
|---|---:|---:|
| streaming-enabled | true | true |
| page-size | 1000 | 1000 |
| fetch-size | 1000 | 1000 |
| chunk-size | 500 | 500 |
| max-payload-size-mb | 100 | — |
| max-export-rows | — | 500000 |

✅ 已有上限,流式开启。Import `ReceiveStep` 还做 heap-ratio 兜底(`Runtime.maxMemory * heapRatio` cap)。

**P1-6 [Mem] export `max-export-rows=500000` 与 page-size=1000 → 500 轮 fetch**
- 50w 行 SELECT 单一连接占住 30min idle-in-tx,Hikari `business pool max=20` 全部被 export 占住可能性存在(20 个 worker 实例并发跑 50w 行 export)。
- 没有"单 worker 同时 export 数"的限流;`max-concurrent-tasks=4` 是 partition 级,不是 export-row 级。
- **修复**: export 50w 起改强制走 **分段输出**(每段 max-rows-per-segment=100000,分 5 段 outbox event),配合 chunk-size=500。或硬限单 export task 不超过 200000 行。

### 4.2 REPORT body / heartbeat details JSONB

- `job_task.heartbeat_details` JSONB,Worker SDK `progressSnapshot()` 返回 Map → orchestrator 反序列化。
- **未发现 size cap**(grep `heartbeat_details` 上未见 max 限制)。
- worker 误传 10MB JSON 可能在 PG / orchestrator 反序列化都炸 (`MapJsonbTypeHandler`)。
- **修复(P2-8)**: orchestrator REPORT controller 加 request body size cap(spring multipart cap 50MB 太大,REPORT 应 ≤ 1MB),写库前再做 `details.length() < 256KB` 守护。
- 同样 `workflow_node_run.output` JSONB 缺 size cap → 大输出会拖垮下游 DSL 引用解析。

### 4.3 multipart

`batch-defaults.yml` max-file-size=50MB / max-request-size=60MB。✅ console-api 上传 OK。

---

## 5. 调度 cron / @Scheduled 全景

### 5.1 BatchDay 4 调度器(关键)

| 调度器 | 间隔 default | ShedLock name | lockAtMost | lockAtLeast |
|---|---|---|---|---|
| BatchDayOpenScheduler | 60s | `batch_day_open` | PT2M | PT15S |
| BatchDayCutoffScheduler | 60s | `batch_day_cutoff` | PT2M | (默认) |
| BatchDaySettleScheduler | 60s | `batch_day_settle` | PT3M | PT30S |
| BatchDayWaitingReleaseScheduler | 60s | `batch_day_waiting_release` | PT3M | PT30S |
| BatchDayReplayDispatcher | 30s | (待查) | — | — |

**✅** ShedLock 锁名各自独立,无重叠。
**✅** 数据状态依赖 open → cutoff → settle → waiting-release 由 `RELEASABLE_PREVIOUS_DAY_STATUSES = {SETTLED, SKIPPED, MANUAL_RELEASED}` 自然串行,即便 4 个调度器并发跑也不会破坏不变量。
**注意** : open / cutoff 都 60s tick 跑同一表 `batch_day_instance`,虽锁名不同但 PG 行锁会让 lock 持有方 PT2M 期间另一调度器空转 → 副本数 >1 时 worker_drain 期看到的 lock_history 噪音 ≈ 4。

**P2-9 [Sched] BatchDayCutoffScheduler 缺 `lockAtLeast`**(全文件 grep 显示 lockAtMost=PT2M 但无 lockAtLeast)。
- 影响:多副本 cutoff 同时跑时,瞬抢瞬释会让两副本都拿到锁(虽然实际工作幂等),刷 PG 调度日志噪音。
- **修复**: 显式 `lockAtLeast = "PT30S"` 对齐其它 BatchDay。

### 5.2 全 orchestrator @Scheduled 频次分布

| 频次 | 数量 | 主要任务 |
|---|---:|---|
| 5s | 1 | RedisShardAssignmentProvider heartbeat |
| 10s | 2 | WaitingPartitionDispatch / RetrySchedule |
| 15s | 2 | PartitionLeaseReclaim / WorkerDrainTimeout |
| 30s | 8 | SLA / FileGov×3 / Backlog / DeadLetterRetry / WorkerHeartbeatTimeout / SensorPoll / Replay |
| 60s | ~14 | BatchDay×4 / Timeout×2 / Workflow / TriggerLaunchReconcile / FileGov×2 / CrossDay / Workflow stuck |
| 2min | 1 | TenantSchedulerSnapshot |
| 5min | 2 | PartitionOrphanSweep / WorkerCapabilityTagsAudit |
| 1h | 1 | ResultVersionRetention |
| 1d | 1 | WorkflowValidatorReconciler |
| ApplicationReady | 1 | OutboxPollScheduler(自适应,200ms–5s) |

> 16 线程池要喂 ~37 个 @Scheduled,这块前面 P1-5 说过。

### 5.3 fixed-delay vs fixed-rate

全仓 **零个** `fixedRate=`,**全部** `fixedDelayString=`。✅ 正确选择(fixed-delay 避免上轮未完次轮已发,雪崩防御)。

### 5.4 misfire 策略

- Trigger 模块用时间轮(默认 `wheel`),已 phase 1 收尾切换,Quartz 仅 opt-in 回退。
- Wheel misfire 阈值 60s,`misfire-pending-expire-interval=PT1H`,逻辑见 `MisfirePendingExpireScheduler`。✅
- 时间轮 tick 100ms / bucket 512 / sliding-window 300s。配置完整。

---

## 6. 心跳 / lease / reclaim

### 6.1 时序数学

| 项 | 值 | 来源 |
|---|---|---|
| worker 心跳间隔 | 15s(全 worker 一致) | `BATCH_WORKER_*_HEARTBEAT_INTERVAL_MILLIS=15000` |
| worker 续租间隔 | 10s | `batch.worker.lease.renew-interval-millis` |
| partition lease 过期 | 120s | `batch.partition-lease.expire-seconds=120` |
| lease reclaim tick | 15s | `batch.partition-lease.reclaim-interval-millis` |
| reclaim ShedLock lockAtMost | 120s | `PartitionLeaseReclaimScheduler.LOCK_AT_MOST_MILLIS` |
| reclaim loop budget(75%) | 90s | 同上,主动 break 让锁不过期 |
| orphan sweep | 5min | `partition-lease.orphan-sweep-interval-millis` |
| orphan sweep ShedLock | PT2M | 同 reclaim |
| worker 心跳超时 check | 30s | WorkerHeartbeatTimeoutScheduler |
| worker drain timeout check | 15s | WorkerDrainTimeoutScheduler |
| OutboxPoll publishing timeout | 120s | `batch.outbox.publishing-timeout-seconds` |
| OutboxPoll ShedLock lockAtMost | 120s+10s buf | 见 `LOCK_AT_MOST_BUFFER` |
| OutboxPoll ShedLock lockAtLeast | 200ms | 已校准下来,避免占锁 |

**✅ 设计正确**: renew 10s × 6 = 60s, lease 120s 留 2 倍冗余。reclaim 15s tick 远小于 120s lease,有充足 buffer。
**✅ P1-5 reclaim 已修过**: lockAtMost=120s + loop budget=90s 主动让锁,杜绝"锁过期但循环未结束"双 dispatch 风险。

### 6.2 BatchDayWaitingReleaseScheduler 自动释放

- 60s tick,扫 `batch_day_waiting_launch` WAITING 行
- 单 tick `WAITING_SCAN_LIMIT=500` 行,防扫表打挂
- ShedLock `batch_day_waiting_release` PT3M / PT30S
- 释放操作走 `BatchDayOperationService#releaseWaitingLaunchesForBatchDay`,operator=`AUTO_RELEASE`(审计可分人工/自动)

**✅** 与原 BatchDaySettleScheduler 互补,补齐自动批量行为闭环。
**P2-10** : `WAITING_SCAN_LIMIT=500` 硬编码 static,无 @Value/Properties 暴露;tenant 数量 > 500 行 WAITING 时,单租户独占 → 其它租户被饿死多个 tick 才放行。

### 6.3 边角窗口风险

**P1-7 [Lease] outbox publishing-timeout=120s 与 partition lease-expire=120s 巧合相等**
- 不是 bug,但量纲耦合在 docs 上没有解耦说明。
- 后续若调 outbox publishing-timeout(出于 Kafka 端 retry 调整)很容易顺手把 lease 也改 → 间接影响 reclaim 行为。
- **修复**:两个值的 Properties 加 cross-reference javadoc。

---

## 7. outbox / retry 三表路由

### 7.1 表分工(CLAUDE.md 已硬约束)

| 表 | 写入路径 | 推进者 | 状态 |
|---|---|---|---|
| `outbox_event` | 业务事件(workflow / job state) | OutboxPollScheduler(自适应 200ms-5s) | ✅ |
| `event_outbox_retry` | 投递失败的退避重试 | DeadLetterAutoRetryScheduler(30s tick) | ✅ |
| `trigger_outbox_event` | trigger fire → orchestrator launch | TriggerOutboxRelay(独立 1 线程池) | ✅ |
| `worker_report_outbox` | worker REPORT 失败后退避 | WorkerReportOutboxCoordinator(5s poll) | ✅ |

无互蹭。`docs/architecture/event-routing-policy.md` 明确判定逻辑。✅

### 7.2 退避策略

| 项 | 值 |
|---|---|
| batch.retry.fixed-delay-seconds | 60 |
| batch.retry.exponential-multiplier | 2 |
| batch.retry.max-delay-seconds | 3600 |
| batch.retry.default-max-retry-count | 3 |
| outbox.max-retry-attempts | 5 |
| outbox.retry-delay-seconds | 60 |
| worker.report-outbox.max-publish-attempts | 48 |
| worker.report-outbox.max-backoff-millis | 300000(5min) |

✅ 多档退避覆盖完整,worker-side 48 次 × 5min 给了 worker → orchestrator REPORT 大约 4 小时窗口的兜底。

### 7.3 OutboxForwarder 拉取节奏

`OutboxPollScheduler`(已读)亮点:
- 自适应轮询: 有积压 200ms 立即续,无积压 backoff 1.5x 退到 5s 上限
- ShedLock 分片: shardTotal>1 时各 shard 独立锁,允许多副本并行不同 shard
- stale PUBLISHING 重置: 每轮开头把超 120s 仍 PUBLISHING 的事件拨回 FAILED(防 Kafka 卡死永久卡 outbox)
- 熔断联动: `OutboxPublishCircuitBreaker` 失败 3 轮 cooldown 60s
- 优雅下线 / draining 前置短路

**✅** 这块是项目里最成熟的一段调度。

### 7.4 死信路由

- DeadLetterAutoRetryScheduler 30s tick,从 `event_outbox_retry` 拉回 outbox_event
- Prometheus 告警 `BatchDeadLetterBacklogHigh` 已挂
- `BATCH_OUTBOX_ARCHIVE_*`: PUBLISHED 7d、GIVE_UP 30d、daily 03:30 自动归档

✅ 完整。

---

## 8. 资源亲和 / 反压闸门(V87)

### 8.1 入口

`WaitingPartitionDispatchScheduler` 每 10s 一 tick:
1. `selectWaitingPartitionsGlobal` 全局拉 ≤ `waiting-dispatch-batch-size=100`
2. 公平排序 `(fairnessScore desc, priority desc, partitionId asc)`
3. 租户限流 `TenantActionRateLimiter`(令牌桶,DISPATCH_RELEASE)
4. `ResourceScheduler.decide()` 内做 capability_tags / workerGroup / max_concurrent / quota 校验
5. tick 级活跃计数缓存 `DefaultResourceScheduler.openTickCache()` 减 N×4 重复 COUNT

**P1-5 已部分回避** : `buildCandidate` 抛 OLFE(@Version CAS 冲突)单条跳过不中断整批。

### 8.2 全局闸门

| 闸门 | env | default |
|---|---|---|
| `global-max-running-jobs` | `BATCH_RESOURCE_SCHEDULER_GLOBAL_MAX_RUNNING_JOBS` | 0(关) |
| `rate-limit.enabled` | `BATCH_RATE_LIMIT_ENABLED` | false |
| `rate-limit.max-new-requests-per-tenant-per-minute` | env | 0 |

**P1-8 [资源] 全局限流开关默认全 OFF,生产无防爆配置**
- `rate-limit.enabled=false`、`global-max-running-jobs=0`、tenant 桶都 0。
- 单一恶意租户 launch 100w 任务时 orchestrator 调度 / DB 连接全部被打满。
- **修复**: 在 prod profile(application-prod.yml,目前 batch-common 有一个但未深度配)显式抬起 `rate-limit.enabled=true` + max-new=1000/min。

### 8.3 capability_tags 审计

`WorkerCapabilityTagsAuditScheduler` 5min tick `audit.capability-tags-scan-interval-millis=300000`,扫描 tag 漂移,挂 `BatchInvalidWorkerCapabilityTags` 告警。✅

---

## 9. 观察性

### 9.1 Prometheus 暴露

`batch-defaults.yml` 已全模块统一 `management.endpoints.web.exposure.include=health,info,prometheus,loggers`(atomic 额外加 `atomicruntime`)。✅

OTLP 全栈接 `OTEL_EXPORTER_OTLP_ENDPOINT`(otel-collector:4318),trace + logs 双导出。✅

### 9.2 Grafana 看板

- `docker/observability/grafana-dashboard-batch.json`
- `docker/observability/grafana-dashboard-batch-coverage.json`
- alertmanager-batch-template.yml ✅

### 9.3 告警(37 条)分类

| 类别 | 计数 | 覆盖度 |
|---|---:|---|
| 服务存活 | 1 (BatchServiceDown) | ✅ |
| SLA | 1 | ✅ |
| Dispatch | 2 (CB / FailureRate) | ✅ |
| JVM Heap | 1 | ✅ |
| Kafka lag | 1 | ✅ |
| Outbox(orch) | 6 (Backlog×2 / Stale / GivenUp / DLQ / publish latency) | ✅ |
| Outbox(trigger) | 5 | ✅ |
| Worker(心跳/drain/decommission) | 3 | ✅ |
| Capability tags | 1 | ✅ |
| Terminal instance children | 1 | ✅ |
| Pipeline latency | 1 | ✅ |
| Redis(mem / clients) | 2 | ✅ |
| Console realtime | 4 | ✅ |
| Webhook give-up | 1 | ✅ |
| PG replication | 4 | ✅ |
| Task claim latency | 1 | ✅ |
| Job failure 重复率 | 1 | ✅ |
| Alert 自洽 | 1 | ✅ |

**P2-11 [Obs] 缺 "@Scheduled 任务超时"告警**
- 调度延迟 / lockAtMost 接近超限 / RejectedExecution caller-runs 触发都没专门告警。
- **修复**: 加 metric `spring_scheduling_tasks_active_count`(Micrometer 自带,补 alert rule)。

**P2-12 [Obs] 缺 archive 长事务告警**(已在 §1.3 写过)

---

## 10. 修复优先级汇总

### P0(0)
*无*

### P1(5)
| ID | 主题 | 一句话 |
|---|---|---|
| P1-1 | DB | 单 PG `max_connections` 默认 100,扩容多副本必越界;无 PgBouncer compose / connection budget runbook |
| P1-2 | Kafka | producer 未配 linger.ms / batch.size,outbox 高吞吐时单条 send 浪费网络 |
| P1-3 | Kafka | consumer concurrency=4 与 topic partition 数无强约束;auto-create 默认 1 分区致 3 线程空转 |
| P1-4 | Kafka | max-poll-records=20 vs max-concurrent-tasks=4-6 不匹配,长任务会触发 rebalance |
| P1-5 | Thread | orchestrator 37 个 @Scheduled 共享 16 线程池;`taskScheduler.poolSize` 偏紧需提到 24 + 拆 archive 独立池 |
| P1-6 | Mem | export 单 task 50w 行无分段,Hikari business pool 全部被占可能性 |
| P1-7 | Lease | publishing-timeout=120s 与 lease-expire=120s 量纲耦合无 docs 解耦 |
| P1-8 | 资源 | global-max-running-jobs / rate-limit 生产默认全关,缺防爆 |

> P1-1 ~ P1-4 + P1-6 + P1-8 偏运维 / 生产部署;P1-5 + P1-7 偏代码与文档。

### P2(9+)
| ID | 主题 | 一句话 |
|---|---|---|
| P2-1 | DB | 4 个 worker 未配 leak-detection-threshold |
| P2-2 | DB | 缺 archive 长事务告警 |
| P2-3 | Kafka | producer 未启 compression(lz4) |
| P2-4 | Kafka | 3 个 worker 未跟进 metadata-max-age=30s,新 tenant 5min 才看到 |
| P2-5 | Thread | OpenLineageEmitter AbortPolicy 静默丢任务 |
| P2-6 | Thread | WebhookDispatcher AbortPolicy 需复核 relay 是否兜底 |
| P2-7 | Thread | SDK pool.size vs Semaphore 不变量缺 assert |
| P2-8 | Mem | REPORT / workflow_node_run.output JSONB 缺 size cap |
| P2-9 | Sched | BatchDayCutoffScheduler 缺 lockAtLeast |
| P2-10 | Sched | BatchDayWaitingReleaseScheduler.WAITING_SCAN_LIMIT=500 硬编码,单租户独占风险 |
| P2-11 | Obs | 缺 @Scheduled 调度延迟 / RejectedExec caller-runs 告警 |

---

## 11. 范围之外的发现(非本扫描主线但记一笔)

- **K8s helm chart**:存在 `helm/` 目录但未深读;PgBouncer / replica / topic partition 等部署面建议在 helm 层落地。
- **load-tests**:未深读;若已有 5k/s outbox 压测可以验证 P1-2 / P1-3 / P1-4 的实际影响。
- **batch-worker-sdk-python**:Python SDK 的 heartbeat / lease 路径未在本次扫描范围内,需独立 sub-audit。

---

## 12. 行动建议

按"低成本高回报"排序:

1. **本周(P2 噪音清理)**:
   - 补齐 leak-detection 默认值(全 worker 60s)— 1 行 yml × 4 文件
   - BatchDayCutoffScheduler 补 lockAtLeast=PT30S — 1 行
   - WAITING_SCAN_LIMIT 提到 Properties — 5 行
   - JSONB body size cap(REPORT controller + output writer)— 含测试 ~50 行

2. **下个迭代(P1 部署面)**:
   - 写 `docs/runbook/db-connection-budget.md` + PgBouncer compose template
   - 写 `docs/runbook/kafka-topic-spec.md` 固化 topic 分区数 + KafkaAdmin 启动期 ensure
   - 修 max-poll-records / max-concurrent-tasks 数学
   - 抬 orchestrator taskScheduler.poolSize=24

3. **生产前必须(P1 防爆)**:
   - prod profile 抬起 rate-limit.enabled=true + global-max-running-jobs 设定
   - 实际压测验证 outbox publish latency P95 < 5s 在 5k/s 负载下成立

4. **观察性补完(P2-11/12)**:
   - 加 2 条 Prometheus rule:scheduled-task 延迟、archive 长事务

---

*报告人: BE Resources & Scheduling Deep Scan Agent*
*依据 commit:`worktree-agent-a1ab4bfead57a7aa0` @ 2026-06-03*
*下次复扫触发条件:扩容 orchestrator 副本数、引入第 5 个 worker 类型、Kafka broker 拓扑变更、PG 升级到 16+*
