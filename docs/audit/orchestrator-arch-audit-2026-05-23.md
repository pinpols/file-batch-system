# batch-orchestrator 审计报告 — 2026-05-23

> 范围:`batch-orchestrator` 模块(状态机主机)
> 维度:资源池/队列/线程模型 + 设计模式合理性 + 抽象层次
> 模式:read-only 审计,无代码改动

## 资源 / 池 / 队列(7 项)

- **[P1]** `batch-orchestrator/src/main/resources/application.yml:38` — HikariCP `maximum-pool-size` 默认 30,但 orchestrator 并发运行多个高频调度路径(outbox-poll、waiting-dispatch、partition-reclaim、SLA、file-governance × 4 路、quota-snapshot、archive × 3 路),每路 Scheduler tick 都可能在事务提交前占住连接。积压场景下连接池极易耗尽触发 `SQLTransientConnectionException`。建议生产环境调高至 50,并在配置注释中给出连接需求估算基线(调度线程数 × 平均事务持续时长 / 池最小空闲保留)。

- **[P1]** `batch-orchestrator/.../mq/OutboxPollScheduler.java:109-119` — `onApplicationReady()` 内直接 `new ScheduledThreadPoolExecutor(1, ...)` 实例化私有 executor,游离于 Spring 容器管理之外。CLAUDE.md §架构硬约束禁止覆盖 `batch-common` 基础设施 bean,而自建 executor 无 Micrometer 指标、无统一线程命名治理策略,且通过设置 `setDaemon(true)` 让调度线程成为守护线程,JVM `shutdown` 时该线程会被直接终止,`@PreDestroy` 中的 `awaitTermination(30s)` 实际上可能等不到正常结束。建议改用 Spring `ThreadPoolTaskScheduler` Bean(或 `taskScheduler`)注入,交由容器统一管理生命周期。

- **[P1]** `batch-orchestrator/.../scheduler/DefaultResourceScheduler.java:239-288` — `enrichFairnessScore()` 在每次 `schedule()` 和 `blockedDecision()` 调用中触发 4 次无缓存 DB count 查询(`countActiveByTenant`、`countActiveByTenant`(分区)、`countActiveByTenantAndQueueCode`、`countActiveByTenantAndWorkerGroup`)。`WaitingPartitionDispatchScheduler` 每 10s 批量处理若干 partition,每个 partition 各自调用 `schedule()`,一个 tick 内高并发场景会打出 N×4 次 COUNT 查询。建议在 tick 级别把 per-tenant 活跃计数结果缓存到局部 Map,同一批次内复用,消除重复 DB round-trip。

- **[P1]** `batch-orchestrator/.../scheduler/DefaultResourceScheduler.java:243,250,266,278` — 上述 4 处均以 `(int) longValue` 强制窄化转型,而 `countActiveByTenant` / `countActiveByTenantAndQueueCode` 等 Mapper 返回 `long`。当单租户活跃 partition/job 计数超过 `Integer.MAX_VALUE` 时(极端积压)发生算术溢出,fairnessScore 计算结果错误,调度排序紊乱。建议改为 `(int) Math.min(count, Integer.MAX_VALUE)` 或直接以 `long` 参与 `resolveFairnessScore` 运算。

- **[P2]** `batch-orchestrator/src/main/resources/application.yml:42` — `leak-detection-threshold: 30000`(30s)。`SuccessInstanceArchiveService.archiveOnce()` 跨 12 张表做 `INSERT SELECT + DELETE`(单批 200 行),在 PG 压力较高时正常执行耗时可合理超过 30s,导致大量虚假"连接泄漏"WARN 噪音,淹没真实泄漏告警。建议调高至 60000ms,或仅在开发 profile 开启。

- **[P2]** `batch-orchestrator/.../redis/OrchestratorConfigCacheService.java:41-42` — `negativeCache` 使用 `ConcurrentHashMap` 作本地 negative TTL 缓存,注释声明"容量上限 10000,到达后整体重置",但翻阅全文件未见对 `NEGATIVE_CACHE_MAX` 进行计数检查并执行重置的实现代码。若 tenant/code 对数量急剧增长(测试注入或攻击性请求),map 无界增长直至 OOM。建议确认重置实现存在(或补充),并添加 Micrometer `gauge` 监控 map 大小。

- **[P2]** `batch-orchestrator/.../mq/OutboxPollScheduler.java:67-69` — ShedLock `LOCK_AT_MOST=120s` 与 `publishingTimeoutSeconds`(默认 120s)完全对齐,存在以下竞争窗口:持锁实例在 GC 停顿或 Kafka 超时期间锁过期被另一实例抢走,原实例继续运行与新实例并发处理同一批 outbox 事件(outbox `UNIQUE(tenant_id, event_key)` ON CONFLICT 可兜底但会产生噪音)。建议在 Javadoc 中明确记录此竞争窗口与幂等兜底语义,或将 `LOCK_AT_MOST` 设为 `publishingTimeoutSeconds + 10s`(130s)以缩小竞争窗口。

---

## 设计模式(7 项)

- **[P0]** `batch-orchestrator/.../scheduler/BatchDayCutoffScheduler.java:42` / `BatchDayOpenScheduler.java:59` / `StaleCompensationCommandReconciler.java:42` / `ResultVersionRetentionScheduler.java:56` — `@Transactional` 直接标注在 `@Scheduled` 方法(或非 Service 公共方法)上,违反 CLAUDE.md 规则 #4("`@Transactional` 只放 Service 公共方法,不放 Controller / Mapper")。具体风险:`BatchDayCutoffScheduler.scheduledAdvance()` 同时有 `@Transactional`、`@Scheduled`、`@SchedulerLock` 三个注解叠加,ShedLock AOP 与事务 AOP 的代理嵌套顺序依赖 Bean 加载时序,不同 Spring Boot 版本行为差异可能导致锁在事务提交前释放(反之亦然)。业务事务逻辑应下沉至被调用的 Service/Component 方法,Scheduler 只负责触发和异常隔离。(4 处同类违反,建议统一整改。)

- **[P1]** `batch-orchestrator/.../statemachine/DefaultStateMachine.java:52-65` — 状态解析使用反射兜底:对未实现 `Stateful` 的类型依次尝试 `getMethod(name)` + `invoke()` 6 次(`getInstanceStatus`/`getPartitionStatus`/`getTaskStatus`/`getRunStatus`/`getNodeStatus`/`getStatus`),每次调用均无 Method 对象缓存,在高频调度热路径上有可见性能损耗,且对方法名拼写错误无编译期保护。建议要求所有参与状态机的实体强制实现 `Stateful` 接口(可借助 ArchUnit 在测试期守护),彻底移除反射路径,或为已知类型做一次 `ConcurrentHashMap<Class<?>, Method>` 启动期缓存。

- **[P1]** `batch-orchestrator/.../idempotency/DatabaseIdempotencyGuard.java:23-33` — `executeOnce()` 内,占位成功后执行 `action.execute()` 但未将 result 回写到 `idempotency_record`(插入时 result 字段传 null)。后续 `isAlreadyExecuted()` / `selectResultByKey()` 返回 null,调用方无法通过接口拿到上次执行结果,幂等语义不完整("已执行"但结果丢失)。建议在 action 执行成功后调用 `updateResult(tenantId, idempotencyKey, result)` 持久化结果,并在 `@Transactional` 边界内完成,保证 result 与占位行同事务落库。

- **[P1]** `batch-orchestrator/.../mq/OutboxPublishCircuitBreaker.java:99-114` — 半开探测逻辑边缘情况:`allowNow()` 的慢路径在 `redis.evalLong(ALLOW_SCRIPT, ...)` 返回 null 时,`resolvedOpen = 0`,熔断器被强制置为"关闭"态(`state = new CircuitState(0L, ...)`)。若此时 Redis 本身不可用(返回 null 是因网络故障),熔断器将在 Redis 故障期间持续放行所有轮次,失去保护作用。建议区分"Redis 返回 0(正常关闭)"与"Redis 返回 null(不可达)",后者应使用上次缓存的 `state` 而非强制关闭。

- **[P1]** `batch-orchestrator/.../infrastructure/file/FileGovernanceRepository.java` — `@Repository` 注解的 `FileGovernanceRepository` 持有 `FileGovernanceMapper` 并在其上做薄封装,实质上为同一表(文件治理相关表)构造了 Mapper + Repository 双层入口,违反 CLAUDE.md §持久化"同一表同一写路径禁双主入口"约定。建议将 `FileGovernanceRepository` 的职责拆分:纯 DAO 操作(`params()` 封装 + Mapper 调用)保留为内部组件,业务校验(`FileStateMachine.assertTransition`、`BizException` 抛出)上移至 `DefaultFileGovernanceService`,并删除 `@Repository` 注解以明确其定位。

- **[P2]** `batch-orchestrator/.../service/LaunchValidationService.java` + `DefaultLaunchValidationService.java` — 接口仅有一个实现且无扩展点规划,属 CLAUDE.md §抽象层次"只用一处的接口"形式主义。同类情形:`WorkerRoutingPolicy` + `DefaultWorkerRoutingPolicy`(`infrastructure/router`),`WorkerRouter` + `DefaultWorkerRouter`(`infrastructure/router`)。若无多态需求,可直接暴露实现类为 `@Component`,减少无谓的间接层。(建议合并 3 对,以降低认知负担。)

- **[P2]** `batch-orchestrator/.../scheduler/TenantSchedulerSnapshotRecorder.java:45-68` — `persist()` 在 for 循环内对每个 tenant 逐条执行 `snapshotMapper.insert(row)` 及 `workerRegistryMapper.countByTenantAndStatus(tenantId, ...)` 查询。当启用租户数量较大(> 50)时产生 2N 次 DB 往返。建议将 `countByTenantAndStatus` 改为一次 `GROUP BY tenant_id` 聚合查询,insert 改为批量 INSERT,可显著降低调度器对 DB 的冲击。

---

## 抽象层次(6 项)

- **[P1]** `batch-orchestrator/.../infrastructure/pipeline/DefaultPipelineExecutor.java:58-62` — `executeStep()` 在 `stepRegistry.find()` 返回 `Optional.empty()` 时静默返回空 `StepResult()`,无日志、无异常。stepCode 拼写错误或 Bean 未注册会被静默捕获并抑制,调用方看到"步骤执行成功但结果全空",极难排查。建议至少在此处打 WARN 日志(`stepCode={} not found in registry`),与配置错误的可观测性要求对齐;严格模式下可直接抛 `BizException`(`STEP_NOT_FOUND`)。

- **[P1]** `batch-orchestrator/.../application/service/sensor/` — `SensorPolicyRegistry` 与 `SensorPolicy` 接口定义在 `application.service.sensor`(应用层),但 `KafkaOffsetSensorPolicy` 明显依赖 Kafka client(infrastructure concern),与 DDD 分层约定(`application` 不依赖具体 infrastructure)冲突。`FileArrivalSensorPolicy` 同理依赖 MinIO。建议将接口和枚举保留在 `application.service.sensor`,具体策略实现移至 `infrastructure.sensor`,符合 CLAUDE.md §DDD 分层。

- **[P1]** `batch-orchestrator/.../infrastructure/file/FileGovernanceRepository.java:402-407` — `params(Object... pairs)` 方法以可变参数交替存放 key/value,无奇偶校验(缺少 `if (pairs.length % 2 != 0) throw`)。调用方传奇数个参数会在运行时抛出 `ArrayIndexOutOfBoundsException`,无编译期防护。该方法全文件调用约 30 次,任意一处配对缺失即触发。建议增加 `Preconditions.checkArgument(pairs.length % 2 == 0)` 或改用强类型 builder(如 `MapBuilder.of("key1", v1).and("key2", v2).build()`)。

- **[P2]** `batch-orchestrator/.../application/plan/PartitionCountResolver.java`(接口)及其 4 个实现 — 接口和实现均位于 `application.plan` 包,未区分平台内置与用户可扩展的策略边界。`DefaultSchedulePlanBuilder` 通过 `List<PartitionCountResolver>` 按 `@Order` 驱动策略链,设计合理,但接口缺乏文档标注策略注册约定(如"不要在无 ADR 批准的情况下新增策略实现")。建议在接口 Javadoc 中明确扩展点约定,或将实现移至 `infrastructure.plan` 以体现"内置实现属于基础设施"语义。

- **[P2]** `batch-orchestrator/.../scheduler/WorkerRegistryCache.java:112-117` — `evictTenantWorkerSelectors()` 硬编码 `"IMPORT"`、`"DEFAULT"`、`"IT"`、`"_"` 4 个 workerGroup 名称,作为 IT 测试专用驱逐方法嵌入生产 Bean。这是测试辅助逻辑渗入生产代码的典型问题:若 workerGroup 命名约定变化(新增 `"EXPORT"` 等),该方法悄悄失效无编译警告。建议通过测试基础设施(`AbstractIntegrationTest`)直接调用 `evict(tenant, group)` 逐个清除,删除 `evictTenantWorkerSelectors()` 或将其移至测试工具类。

- **[P2]** `batch-orchestrator/.../infrastructure/sharding/` — `ShardAssignmentProvider` 接口有两个实现:`StaticShardAssignmentProvider`(无 `@Component`,由配置类条件化注册)和 `RedisShardAssignmentProvider`(有 `@Scheduled` 但本身也无 `@Component`)。`RedisShardAssignmentProvider` 上的 `@Scheduled(heartbeat)` 要求 Bean 被 Spring 管理,但如果配置类没有显式 `@Component`,`@Scheduled` 注解不会生效,心跳永不触发,导致 DYNAMIC 模式下 Redis sorted set 无成员,所有实例退化为单实例模式而无任何告警。建议在类上添加 `@Component` 或在文档/配置类中明确说明注册方式,并添加启动期自检(heartbeat 至少成功一次才启动分片逻辑)。

---

## 总结

| 档级 | 数量 |
|------|------|
| 严重 (P0) | 1 项 |
| 重要 (P1) | 11 项 |
| 优化 (P2) | 8 项 |
| **合计** | **20 项** |

**总体评分:中**

最高优先级行动项(建议 1-2 周内修):
1. P0:统一移除 4 处 Scheduler 方法上的 `@Transactional`,业务事务下沉 Service
2. P1:`DatabaseIdempotencyGuard.executeOnce()` 补写 result 持久化
3. P1:`DefaultResourceScheduler.enrichFairnessScore()` 去掉 4 处 `long→int` 窄化转型
4. P1:`FileGovernanceRepository.params()` 增加奇偶校验
5. P1:`OutboxPollScheduler` 私建 ScheduledThreadPoolExecutor 改为注入 Spring 管理 Bean
