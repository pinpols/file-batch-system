# batch-worker-core + import + export 审计报告 — 2026-05-23

> 范围:`batch-worker-core` + `batch-worker-import` + `batch-worker-export`
> 维度:资源池 / 设计模式 / 抽象 / 集合 / 多线程异步
> 模式:read-only

## 资源 / 池 / 队列 / 线程模型(4 项)

**[P1]** `batch-worker-core/.../infrastructure/DefaultTaskExecutionWrapper.java:122-133` — **watchdog 直接 new `Executors.newSingleThreadScheduledExecutor`,游离于统一线程池管控之外**。该 bean 是单例,watchdog 线程命名为 `worker-task-cancel-watchdog-N`,`@PreDestroy` 会 `shutdownNow()`,生命周期尚可接受。但在测试或多实例场景中容易泄漏,且无法通过 `TaskExecutionPool` 或 `spring.task.scheduling.*` 统一调整线程数/优先级。**建议**:将 watchdog 提取为 `@Bean ScheduledExecutorService`。

**[P1]** `batch-worker-core/.../support/AbstractTaskConsumer.java:69` — **`maxConcurrentTasks` 用 `@Value` 字段注入**,违反 CLAUDE.md §Java 编码细则第 3 条(依赖注入只用构造器)。`semaphore` 在 `ensureSemaphore()` 内懒初始化:若任何路径(如 Spring AOP 代理、测试替身)在字段注入完成前触发 `doConsume`,`maxConcurrentTasks=0` → `Math.max(1,0)=1`,**并发上限静默降为 1**,无日志告警,背压阈值被永久破坏。**建议**:移入构造器参数并在 `@PostConstruct` 初始化。

**[P1]** `batch-worker-import/.../config/PlatformDataSourceConfiguration.java` 与 `batch-worker-export/.../config/PlatformDataSourceConfiguration.java` — **import 和 export 两个模块各自复制了一份完全相同的双数据源配置**。两个 worker 进程部署在同一 PG 实例时,连接池数量实际翻倍(import platform pool + export platform pool 各自独立),可能触及 PostgreSQL `max_connections`。**建议**:将双数据源自动配置提取到 `batch-worker-core` 的 `WorkerCoreConfiguration`。

**[P2]** `batch-worker-core/.../infrastructure/WorkerTaskLeaseRenewer.java:68,158` — **主续租 `@Scheduled(fixedDelayString=...10000)` 和快速重试 `@Scheduled(fixedDelayString=...2000)` 共用 Spring 默认 `taskScheduler`(单线程)**。若主续租因大量 lease 的批量 HTTP 耗时超过 2 s(如 orchestrator 短暂慢响应),fast-retry 调度会被推迟,快速恢复窗口失效。**建议**:为续租调度配置独立 2 线程池。

---

## 设计模式(4 项)

**[P1]** `batch-worker-export/.../stage/DefaultExportStageExecutor.java:46-55` — **构造器直接注入 `MeterRegistry`,而非 `ObjectProvider<MeterRegistry>`**。`DefaultTaskExecutionWrapper`、`DefaultHeartbeatService` 等 core 内均用 `ObjectProvider`,export 侧的硬依赖导致在 test-slice(无 Micrometer)或 `@SpringBootTest(excludeAutoConfiguration=MicrometerAutoConfiguration.class)` 场景中启动失败。**建议**:改为 `ObjectProvider<MeterRegistry>` 注入,与 core 统一。

**[P1]** `batch-worker-import/.../stage/DefaultImportStageExecutor.java:205-235` 与 `batch-worker-export/.../stage/DefaultExportStageExecutor.java:206-235` — **`buildDefaultStepDefinitions()` 逻辑在两处逐行对称复制,仅枚举类型不同**。`AbstractStageExecutor` 模板方法已统一主循环骨架,却未提供 `defaultStepDefinitions` 的泛型工厂,导致 pipeline 步骤顺序调整时需同步两处修改。**建议**:在基类提供 `protected <S extends StageStep<?, ?>> List<PipelineStepTemplate> buildStepTemplates(List<S> orderedStages)` 默认实现。

**[P1]** `batch-worker-import/.../stage/LoadStep.java:85-254` — **`executeStreaming` 与 `executeLegacy` 共约 120 行高度重复**:`resolveLoadTargetRef`、`buildLoadContext`、`resolveChunkSize`、`flushChunk`、`runtimeRepository.updateFileStatus(...)` 的 Map 参数构造在两分支中几乎逐行相同。Legacy 路径无法平等享受新特性(分区切片、干跑等),变更时需改两处。**建议**:提取公共 `commit()` 方法,legacy 路径应在下一版本废弃。

**[P2]** `batch-worker-export/.../stage/DefaultExportStageExecutor.java:109-119` — **`executeOneStep` 中 `BizException` 用 `log.error` 记录,而 import 侧同位置(`DefaultImportStageExecutor.java:104`)用 `log.warn`**,import 注释明确写"业务错误→WARN"。两侧不一致:export 的业务级错误(如模板未配 exportDataRef)会产生 error 级告警,干扰运维阈值。**建议**:对齐改为 `log.warn`。

---

## 抽象层次(4 项)

**[P1]** `batch-worker-import/.../stage/ValidateStep.java:340-348` — **`recordValidationError` 在部分路径上直接抛出 `IllegalStateException` 来控制流程(非可跳过记录 / `FAIL_BATCH`),破坏"Step 只返回 `ImportStageResult`、不抛未检查异常"的约定**。调用方 `processChunk` 必须同时处理"正常 continue"和"通过异常停止"两种语义。实际执行路径:异常从 `processValidationBatch` 内的 try-with-resources 逸出 → 被外层 catch(Exception) 捕获 → 走"validate failed"失败路径,而非本意的"skip threshold exceeded"路径,**错误码被抹成 `IMPORT_VALIDATE_FAILED`**。**建议**:改为通过 `ChunkProcessResult` 的 failure 返回值通知停止,彻底移除 throw。

**[P1]** `batch-worker-import/...` 与 `batch-worker-export/...` 两个模块的 `PlatformDataSourceConfiguration` 和 `BusinessDataSourceConfiguration` — **Infrastructure 细节不在 `batch-worker-core` 统一抽象,而是散落在各 worker,属于 DDD 分层反向依赖**。`WorkerCoreConfiguration` 本可提供自动配置扩展点,但目前每个 worker 模块均绕过 core 独自配置。

**[P2]** `batch-worker-import/.../stage/ValidateStep.java:358-409` / `LoadStep.java:329-409` / `ParseStep.java`(各处均含) — **`numberValue`、`stringValue`、`resolveChunkSize`、`deleteQuietly`、`createStagingFile`/`createValidatedFile` 等私有工具方法在三个 Step 中独立实现,内容完全一致(共约 100 行)**。**建议**:提取到 `AbstractImportStageStep` 基类或 `ImportStageSupport` 工具类。

**[P2]** `batch-worker-import/.../preprocess/ImportPreprocessPipeline.java:65` — **工具类持有 `private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules()`,与 Spring 容器管理的全局 `ObjectMapper` bean 脱节**。若项目注册了自定义 Module,行为将静默不一致。**建议**:通过调用方传入 `ObjectMapper` 参数,或将其声明为 `@Component`。

---

## 集合(4 项)

**[P0]** `batch-worker-import/.../infrastructure/ImportRecordGovernanceService.java:324-340` — **`badRecords()` 的取-拷贝-return 逻辑导致第 2 条及之后的坏记录全部丢失**。首次调用时,`existing=null`,创建 `created`(空 ArrayList),put 入 attributes,返回 `created`;`recordBadRecord` 调用 `created.add(badRecord)`——此时 `created` 和 attributes 里的引用相同,记录正常写入。**第二次调用时**,`existing` 是含 1 条记录的 List,方法创建 `resolved`(副本),`if (!resolved.isEmpty()) return resolved`——返回副本但未 put 回 attributes;`recordBadRecord` 向 `resolved`(副本)add,而 attributes 里仍是旧的只含 1 条的 List。**结果:`finalizeErrorOutput` 遍历的永远是第一次写进去的那个 List,只有第 1 条坏记录,其余全部静默丢失。坏记录统计(`badRecordCount`)、阈值判断在数量 > 1 时出现数据不一致。** **建议**:若 existing 已是正确类型的 List,直接返回原引用而非副本。

**[P1]** `batch-worker-core/.../infrastructure/WorkerTaskLeaseRenewer.java:50,94` — **`consecutiveFailures` 是 `ConcurrentHashMap<String, AtomicInteger>` 无上界增长**,且快速重试路径(`fastRetryFailedLeases`)不执行裁剪。主续租在 `renewActiveTaskLeases` 中有 `removeIf` 裁剪,但与 `fastRetryFailedLeases` 并发运行时存在竞争窗口:主续租裁剪 key A,fast-retry 同时对 key A 执行 `computeIfAbsent` 重建,导致已失效 lease 的计数器重新进入 Map 并永久残留。**建议**:在 `ActiveTaskLeaseRegistry.remove(taskId)` 时同步清理 `consecutiveFailures.remove(taskId)`。

**[P1]** `batch-worker-import/.../stage/LoadStep.java:177-253` — **`executeLegacy` 将整个 `customerPayloads` 列表用 `objectMapper.convertValue` 全量转换为 `Map<String, Object>` 后才分 chunk 处理**。`customerPayloads` 是从 context attributes 中取出的原始 `List<CustomerImportPayload>`,量级可能达数万条,全量在堆内存中同时存在(原始对象 + 转换后 Map)直到整个方法返回,**peak 内存 = 2× 原始数据量**。**建议**:legacy 路径逐条 convertValue 后即加入 chunk,满 chunkSize 后 flush + clear,与 streaming 路径对齐。

**[P2]** `batch-worker-core/.../support/AbstractTaskConsumer.java:457-462` — **`WORKER_CODE_KEYWORD_TOPIC` 使用 `List<Map.Entry<String, String>>` 而非 `LinkedHashMap` 保证顺序匹配语义**,两者均不可变(`List.of`),但 `List<Entry>` 语义上是 Map 却用了 List,可读性偏差。

---

## 多线程异步(5 项)

**[P0]** `batch-worker-import/.../infrastructure/ImportRecordGovernanceService.java:324-340` — **(与集合 P0 同根)** 坏记录的 attributes 写入在 pipeline 执行线程中串行操作,本身无并发问题,但由于 `badRecords()` 每次返回副本(非存入 attributes 的实际引用),从多线程视角看:如果将来 stage 执行并发化,多线程同时调用 `badRecords()` 会各自得到副本并各自 add,最终 attributes 里的 list 仍是旧的——并发安全性依赖于"当前 pipeline 串行"的隐性假设,该假设没有任何 Java 内存模型层面的保障。**建议**:修复同集合 P0 条目,顺便用注释说明"仅限 pipeline 执行线程调用"。

**[P1]** `batch-worker-core/.../infrastructure/ActiveTaskLeaseRegistry.java:77-86` — **`remove()` 先在 `shutdownLock.writeLock()` 内修改 `activeTaskLeases`,释放锁后再在 `synchronized(drainMonitor)` 内 `notifyAll()`**。`awaitDrain()` 在 `synchronized(drainMonitor)` 内读 `activeTaskLeases.isEmpty()`,但两把锁不互斥,存在 ABA 窗口。逻辑回退到位,不会出现"map 非空但 drain 认为干净"。**真实 P1 风险**:`lostLeases.remove(taskId)` 在 writeLock 外、drainMonitor sync 外执行,若极端情况下 ConcurrentHashMap.remove 与 awaitDrain 的 isEmpty 检查并发,`lostLeases` 的残留不影响 drain 语义,但从代码审阅角度,将 `lostLeases.remove` 移入 writeLock 块内可提升一致性。

**[P1]** `batch-worker-core/.../reportoutbox/WorkerReportOutboxCoordinator.java:71-98` — **`pollDeferredReports` 内部 for 循环连续 claim + HTTP submit + delete,整个 batch 在同一调度线程内同步执行**。若 orchestrator 响应慢(如 500ms/条 × batch=20),单次调度占用线程 10 s,阻塞下一调度周期和 `recoverStalePublishing`。调度器单线程时,两个 `@Scheduled` 方法串行化,stale 恢复会被 poll 拖延。**建议**:对 HTTP submit 异步化(提交 CompletableFuture 后在 callback 中 delete),或将 batch 上限配置化并加总超时熔断。

**[P1]** `batch-worker-core/.../infrastructure/DefaultTaskExecutionWrapper.java:161-186` — **`report` 失败时(HTTP 5xx,非 outbox 配置覆盖),异常向上传播给 `AbstractTaskConsumer.doConsume` 的业务异常 catch 块**。该 catch 区分了 orchestrator transient(5xx/网络)vs 不可恢复,transient 路径 `return false`(不提交 offset,Kafka 重投 dispatch 消息),但 task 的 `activeTaskLeaseRegistry.remove` 已在 finally 里执行,lease 已经清除。Kafka 重投后同一 task 会被重新 CLAIM 并执行,**造成 task 双执行**(第一次执行结果已实际写入数据库,第二次 CLAIM 可能成功或幂等失败,取决于 orchestrator CAS 策略)。**建议**:`report` 失败时应走 outbox 回退(`WorkerReportOutboxCoordinator.enqueue`),而不是依赖 Kafka 重投触发 re-CLAIM。

**[P2]** `batch-worker-core/.../support/AbstractWorkerLoop.java:110-141` — **`ensureStarted()` 用 `synchronized(this)` + `AtomicBoolean started` 实现 DCL,`registration` 字段为 `volatile`,内存模型正确**。心跳方法 `doHeartbeat()` 先调用 `ensureStarted()` 获取 `current`,再调用 `workerRuntimeFacade.heartbeat(current.getWorkerId())`,两步之间若 `registration` 被 `@PreDestroy shutdown()` 并发置 null 不会 NPE。在 drain 期间心跳与 shutdown 并发是预期行为,当前处理合理,属于可读性改进项。

---

## 总结

| 严重度 | 数量 |
|--------|------|
| **P0** | **2** |
| **P1** | **11** |
| **P2** | **10** |
| **合计** | **23** |

### 最高优先处置顺序

1. **P0 集合 — `ImportRecordGovernanceService.badRecords()`** :第 2 条及之后坏记录全部静默丢失。一行修复:若 existing 已是正确类型的 List,直接返回原引用而非副本。
2. **P1 多线程 — `report` 失败走 Kafka 重投导致双执行**(`DefaultTaskExecutionWrapper.java:162-179`)
3. **P1 资源 — `@Value` 字段注入 `maxConcurrentTasks`**(`AbstractTaskConsumer.java:69`)
4. **P1 设计 — `DefaultExportStageExecutor` 直接注入 `MeterRegistry`**
5. **P1 集合 — `consecutiveFailures` 无界增长**(`WorkerTaskLeaseRenewer.java:50`)
