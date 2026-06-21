# batch-common + batch-trigger 审计报告 — 2026-05-23

> 范围:`batch-common` + `batch-trigger`
> 维度:资源池 / 设计模式 / 抽象 / 集合 / 多线程异步
> 模式:read-only

## 资源 / 池 / 队列 / 线程模型(4 项)

**[P0]** `batch-trigger/src/main/java/com/example/batch/trigger/application/TriggerOutboxRelay.java:112-118` — `Executors.newSingleThreadScheduledExecutor` 直接 `new`,未注入 Spring 管理的线程池。该线程池完全游离于 Spring 生命周期之外:若 `start()` 被多次调用(DevTools 热重启)`executor` 非 null 保护生效,但线程本身命名为裸 `trigger-outbox-relay`,无法通过 Actuator/JVM 工具统一监控;更重要的是线程池最大队列为 `Integer.MAX_VALUE`(`ScheduledThreadPoolExecutor` 默认),遭遇 DB 慢时 `poll` 提交堆积会 OOM。建议改为注入 `@Bean ThreadPoolTaskScheduler` 并限制队列上限,或直接复用 Spring 的 `taskScheduler` 并加 `@Scheduled`(与其他 `@Scheduled` 组件一致)。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/HashedWheelTriggerScheduler.java:97-100` — `inFlightFires`(`ConcurrentHashMap`)和 `timeoutRegistry`(`ConcurrentHashMap`)均无容量上限。理论上每次 `claimAndSchedule` 成功向 wheel 推入一个 task 都往 map 里写一条,`finally` 里会 remove;但若 wheel 线程异常崩溃(例如 netty 内部 OutOfMemoryError)导致 `fire()` 永远不执行,两个 map 持续增长直到堆溢出。`scanBatchSize` 默认 1000 且 60s 一轮,实际 unbounded growth 概率低,但机制上缺回退上限。建议在 `scanAndSchedule` 前检测 `inFlightFires.size() >= maxInFlight` 时跳过本轮(或告警)。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/WheelTriggerReconciler.java:60` — `warnedInvalidCron`(`ConcurrentHashMap`)注释自述"平台 trigger 总数 << 10k,不限大小",但此 Map 永不收缩:即使某个 jobCode 被彻底删除,其 `warnedInvalidCron` 条目仍留在内存,因为删除路径仅走 `stateMapper.deleteByJobDefinitionId`,Map 里的 key 无对应清除逻辑。长期运行 + 频繁 job 变更时会产生内存泄漏。建议在"deleted runtime_state"路径中同时清除对应的 `warnedInvalidCron` 条目。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerKafkaProducerConfiguration.java:36` — `Map<String, Object> properties = new HashMap<>()` 被用于构造 `DefaultKafkaProducerFactory`,Kafka Producer 内部连接池(`BufferPool`、`NetworkClient`)参数(`buffer.memory`、`max.block.ms`、`linger.ms`)均未配置,全部走 Kafka 默认值(`buffer.memory=32MB`、`max.block.ms=60000ms`)。Relay 是同步阻塞发送(`.get(timeout, TimeUnit.SECONDS)`),但如果 broker 不可达,`ProducerRecord.send()` 内部会阻塞 `max.block.ms=60s` 才抛 `TimeoutException`,加上外层 Relay 自身的 `sendTimeoutSeconds`,可能导致 relay 线程被 Kafka 内部锁住超过 2 × timeout 秒,期间 ShedLock 到期被其他实例抢走,形成重发竞争。建议显式设置 `max.block.ms` 略小于 Relay 层 sendTimeout。

---

## 设计模式(3 项)

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/domain/TriggerDefinitionLoader.java` + `batch-trigger/.../infrastructure/DatabaseTriggerDefinitionLoader.java` — 典型"单实现接口形式主义"(CLAUDE.md §抽象层次)。`TriggerDefinitionLoader` 接口只有一个 `DatabaseTriggerDefinitionLoader` 实现,且当前及可预见未来没有第二实现(wheel 和 quartz 路径共用同一实现)。接口价值仅剩单测 Mock;但 Mock 成本极低(MyBatis Mapper 本身可 mock),接口反而引入了额外间接层。类似的,`TriggerRegistrationService` 接口的情况有差异——Quartz 实现是 `TriggerSchedulerFacade`,wheel 模式下没有对应 Bean(接口在 wheel 路径下无法注入),意味着 `TriggerRegistrationService` 在 wheel 模式下实际上是死代码引用。建议评估:`TriggerDefinitionLoader` 可直接删接口,`TriggerRegistrationService` 应在 wheel 路径下有对应的空实现或彻底拆分注入路径。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/infrastructure/TriggerGracefulShutdown.java` — `TriggerGracefulShutdown` 直接依赖 Quartz `Scheduler`(`@RequiredArgsConstructor`),在 wheel 模式下(`batch.trigger.scheduler-impl=wheel`)`Scheduler` bean 同样存在(QuartzAutoConfiguration 无条件装配),但语义上 `TriggerGracefulShutdown.startDraining()` 调用 `scheduler.standby()` 是针对 Quartz 的,wheel 模式的停机流程(`HashedWheelTriggerScheduler.shutdown()`)走的是 `@PreDestroy`,与 `TriggerGracefulShutdown` 完全分离。两条停机路径之间没有协调,wheel 模式下 `isDraining()` 的语义(由 Quartz standby 驱动)与实际 wheel 停机状态脱节。建议将 draining 状态抽为独立 `TriggerDrainState` bean,不依赖 Quartz,由两种 scheduler 实现各自响应。

**[P2]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/WheelMetrics.java:57-64` — `recordFireLag` 和其他方法每次调用都 `Timer.builder(...).register(registry)`,Micrometer 的 `register` 内部是"注册或返回已有"的幂等操作,但每次调用都要做 map lookup 和对象创建(`Timer.Builder`),属于不必要的热路径对象分配。fire 路径每次 trigger 触发都会命中此方法。建议将高频 Timer/Counter 在构造时一次性创建并缓存为 field(参考 `publishLatencyOk/Fail` 在 `TriggerOutboxRelay.start()` 的正确做法)。

---

## 抽象层次(4 项)

**[P0]** `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java:90-101` — `createPendingCatchUp` 方法标注了 `@Transactional`(默认 `PROPAGATION_REQUIRED`),但 `loadCalendarDefinition` 在事务内执行多次 DB 查询,与 `persistPending` 在同一事务中,若日历查询触发慢查询,事务持续时间被拉长、锁持有时间增加。建议将 `loadCalendarDefinition` 提到事务外执行(与 `launchScheduled` 的实现方式一致),只保留 `persistPending` 在事务内。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java:195-213` — `insertPendingAndOutboxOrReturnExisting` 方法用了 `final TriggerRequestEntity[] existingHolder = new TriggerRequestEntity[1]` 单元素数组作为闭包变量的 workaround(因为 lambda 要求变量 effectively final)。这是 Java 中规避 lambda 局限性的反模式,代码意图隐晦。更好的写法是将事务逻辑提取为独立方法(如 `private TriggerRequestEntity doInsertInTx(...)` 加 `@Transactional(propagation=REQUIRES_NEW)` 注解),或改用 `AtomicReference`(语义更清晰)。数组 workaround 在审计中常被认定为设计坏味道。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/infrastructure/QuartzLaunchJob.java:98` — 用字符串 `contains("tenant is suspended")` 做异常类型识别(`e.getMessage().contains("tenant is suspended")`)属于脆弱的字符串匹配,违反 CLAUDE.md §业务异常统一格式。`BizException` 已有 `ResultCode` 字段,正确做法是 `if (e.getResultCode() == ResultCode.BUSINESS_ERROR && ...)` 或专门定义一个 `ResultCode.TENANT_SUSPENDED`,通过枚举 code 比较而非 message 文本。当前方案一旦 i18n 改变错误信息文本就会静默失效,导致 Quartz job 不被 pause,持续产生 BizException 日志风暴。

**[P2]** `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java:262-268` — `buildScheduledDedupKey` 方法将 `fireTime`(`Instant`)直接用 `toString()` 拼入字符串作为 dedupKey:`tenantId:jobCode:2026-05-23T10:00:00Z`。`Instant.toString()` 输出纳秒精度,但不同 JVM / 序列化路径下精度可能不一致(有无尾零),导致相同 fire time 产生不同字符串,dedupKey 去重失效,同一次 trigger 被重复插入。建议统一使用 `fireTime.toEpochMilli()` 作为 dedupKey 的时间组件。

---

## 集合(3 项)

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/WheelTriggerReconciler.java:85` — `doReconcile()` 内部构建 `Map<Long, TriggerDescriptor> wantedById = new HashMap<>()`,然后在独立循环中 `for (TriggerDescriptor d : wantedById.values())` 再次遍历插入/更新,同时 `List<TriggerRuntimeStateEntity> allStates = stateMapper.selectAllJobDefinitionIds()` 拉取全表用于删除检测。两次全扫(DB loadAll + selectAllJobDefinitionIds)+ 内存 HashMap 构建,在 reconcile 内是合理的批量操作,但 `HashMap` 的初始容量为默认 16,大租户场景(1k+ triggers)会触发多次扩容。建议 `new HashMap<>(dbDescriptors.size() * 2)` 预分配容量。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java:309-323` — `loadCalendarDefinition` 中用 `Collectors.toSet()` 收集 `holidays` 和 `workdayOverrides`,`toSet()` 返回的是可变 `HashSet`,但随后传入 `CalendarBizDateDefinition` record。Record 字段不可变,但如果 record 将这些 Set 字段暴露出去,调用方可以 `definition.holidays().add(...)` 修改。这符合"collect(toList()) 实际可变"的集合类问题分类。建议改为 `Collectors.toUnmodifiableSet()` 明确语义保证,防止上下游调用方意外修改。

**[P2]** `batch-trigger/src/main/java/com/example/batch/trigger/infrastructure/TriggerGracefulShutdown.java:68-80` — `status()` 方法返回 `new LinkedHashMap<>()` 填充后返回可变 map,调用方(`TriggerManagementController`)若直接暴露此 map 到 API 响应,外部代码可以修改返回值(理论上 HTTP 序列化后无影响,但内部调用链中不安全)。建议改为 `Collections.unmodifiableMap(status)` 或 `Map.copyOf(status)`。

---

## 多线程异步(5 项)

**[P0]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/HashedWheelTriggerScheduler.java:264-290` — `fire()` 方法在 Netty `HashedWheelTimer` 的 worker 线程执行,该线程调用 `triggerService.launchScheduled(command)`,而 `DefaultTriggerService.launchScheduled` 内部调用 `insertPendingAndOutboxOrReturnExisting`,后者通过 `TransactionTemplate` 开启 `PROPAGATION_REQUIRES_NEW` 事务。**真正问题**:Netty `HashedWheelTimer` 默认只有 **1 个 worker 线程**(`DefaultThreadFactory("trigger-wheel")`),必须串行处理所有 timeout callback。每次 `fire()` 包含 DB 查询(`loadDescriptor`)+ DB 事务(`launchScheduled`)+ DB 更新(`advanceAfterFire`),合计可能 100ms-500ms,而 wheel tick 是 100ms。当并发 trigger 数量大时(滑动窗口一批 1000 个 trigger 全部在同一秒 fire),单 worker 线程将严重落后,所有 timer callback 堆积在 wheel 的延迟队列里,实际 fire 延迟远超 misfire 阈值(60s),导致大量触发器进入 misfire 路径。建议:将 `fire()` 内的 DB 操作提交到独立线程池(`ExecutorService`)异步执行,Netty worker 只负责 dispatch,避免阻塞 wheel tick。

**[P0]** `batch-trigger/src/main/java/com/example/batch/trigger/infrastructure/scheduler/BatchDayCutoffScheduler.java:37-38` — `@Transactional` 和 `@Scheduled` 同时标注在 `scheduledCutoff()` 方法上。`@SchedulerLock` 同样包裹了此方法,ShedLock 的 AOP 拦截器与 `@Transactional` 代理的执行顺序不确定,可能出现"锁内无事务"或"事务内锁已释放"的竞争。`scheduledCutoff()` 上的 `@Transactional` 包了整个扫描循环(包括全量 `selectOpenCutoffCandidates`),长事务持有时间过长。建议去掉 `scheduledCutoff()` 上的 `@Transactional`,在 `batchDayInstanceMapper.markCutoff` 对应的 Mapper 方法层面单独加事务(或在 `cutoff()` 内按条分开事务)。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/HashedWheelTriggerScheduler.java:159` — `doSlidingWindow()` 是 `public` 方法(注释说"让 IT 直接调,绕开 `@SchedulerLock` proxy"),但在生产路径中由 `@Scheduled` + `@SchedulerLock` 保护的 `slidingWindow()` 调用,这意味着 `wasLeader.getAndSet(true)` 被 `doSlidingWindow()` 执行,而当另一实例抢到 leader 后,旧 leader 实例的 `wasLeader` 仍然是 `true`(因为 `slidingWindow()` 不再被调用,`wasLeader` 没有 reset 逻辑)。leader 丢失时(ShedLock 不续期,其他实例获得锁)`wasLeader` 不重置,该实例恢复成 leader 后 `onLeaderAcquire()` 不会再次执行(因为 `wasLeader.getAndSet(true)` 返回 `true`),fast-path catch-up scan 被跳过。建议在 `slidingWindow()` 方法(加 `@SchedulerLock` 的入口)开头检测 ShedLock 是否实际持有并据此控制 `wasLeader` 状态,或在 leader 切换时通过 ShedLock 的 `LockExtender` 回调重置。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/wheel/CatchUpThrottle.java:62-66` — `acquire()` 调用 `Thread.sleep(sleepMillis)`,此代码在 Netty `HashedWheelTimer` 的 **worker 线程**中执行(由 `handleMisfire` → `catchUpThrottle.acquire()` 调用链路,`handleMisfire` 被 `fire()` 调用,`fire()` 在 wheel 回调中执行)。在 Netty worker 线程中 `Thread.sleep` 会阻塞整个 wheel 的 tick 处理,导致其他所有等待 fire 的 task 都延迟。`ratePerSecond=10` 意味着每次 misfire AUTO catch-up 之间最多等 100ms,若同时有 100 个 misfire AUTO 触发器,总阻塞时间可达 10s,远超 misfire 阈值(60s)。根本问题是 catch-up 限流逻辑不应在 wheel worker 线程中阻塞(见 P0 fire 异步化建议)。

**[P1]** `batch-trigger/src/main/java/com/example/batch/trigger/application/TriggerOutboxRelay.java:131-132` — `start()` 中有两个 `@EventListener(ApplicationReadyEvent.class)` 方法(`start` 和 `auditOnReady`),Spring 文档明确指出**同一类中多个相同事件的监听器执行顺序不保证**。`start()` 创建 executor 并启动 poll,`auditOnReady()` 读取 `mapper.countByStatuses`,两者若并发执行(Spring 可能在不同线程分发事件),`auditOnReady` 与第一轮 `poll` 并发访问 DB,虽然幂等,但 `pendingEvents.set(...)` 和 `poll()` 内的 `sampleBacklog()` 形成竞争写(AtomicLong 是线程安全的,不是 bug),但两个 `@EventListener` 在 `start()` 的 `executor != null` 检查和 executor 创建之间存在 TOCTOU:若两个事件回调在不同线程并发执行,第一个线程过了 `if (executor != null) return` 检查后,第二个线程也过了检查,导致两个 executor 被创建,后者覆盖前者,前者泄漏。建议将 `auditOnReady` 合并进 `start()` 或加 `synchronized`。

---

## 总结

| 严重度 | 数量 |
|--------|------|
| P0     | 3    |
| P1     | 11   |
| P2     | 3    |
| **合计** | **17** |

**P0 速览:**
- `TriggerOutboxRelay` 自建 `ScheduledExecutorService`,unbounded queue 风险
- `HashedWheelTriggerScheduler.fire()` 在 Netty 单 worker 线程同步执行 DB 操作,大批量触发时 wheel 严重阻塞导致 misfire 雪崩
- `BatchDayCutoffScheduler` `@Transactional` 包裹全量扫描循环,长事务 + `@SchedulerLock` 顺序不确定

**高优先级 P1 集中在:** leader 切换时 `wasLeader` 不重置(fast-path 被跳过)、`CatchUpThrottle.acquire()` 阻塞 wheel worker 线程、`TriggerOutboxRelay` 双 `@EventListener` 并发初始化竞争、`QuartzLaunchJob` 用字符串匹配识别租户暂停异常的脆弱性、`WheelTriggerReconciler.warnedInvalidCron` 内存泄漏、`Instant.toString()` 作为 dedupKey 的精度不一致问题。
