# batch-worker-process + batch-worker-dispatch 审计报告 — 2026-05-23

> 范围:`batch-worker-process` + `batch-worker-dispatch`(~120 个 Java 源文件)
> 维度:资源池 / 设计模式 / 抽象 / 集合 / 多线程异步
> 模式:read-only

## P0 — Bug 风险 / 资源泄漏 / 并发安全(4 条)

**[P0]** `batch-worker-process/.../infrastructure/ProcessStepBeanRegistrar.java:36-37` — `@EventListener(ApplicationReadyEvent.class)` 方法上直接标注 `@Transactional(propagation = Propagation.REQUIRES_NEW)`,违反 CLAUDE.md 规则 4「禁止非 Service 公共方法加事务」且「禁 `REQUIRES_NEW` 等非默认传播」。对比 dispatch 侧已抽取到 `AbstractStepBeanRegistrar`,process 侧绕开基类独立实现了 62 行逻辑。**建议**:扩展 `AbstractStepBeanRegistrar` 支持多 bean 类型,process 侧继承;事务逻辑下沉到 Mapper/Service 层。

**[P0]** `batch-worker-dispatch/.../channel/SftpDispatchChannelAdapter.java:236-248` — `DISCONNECT_EXECUTOR` 是 `static final ScheduledExecutorService`,无 `@PreDestroy` 关闭,进程退出时非 daemon 核心线程可能阻塞 JVM。更严重:`ThreadFactory` 内部 `private int n = 0` 是匿名类字段,多线程并发调用 `newThread()` 时 `++n` 非原子操作,存在数据竞争。**建议**:`n` 改为 `AtomicInteger`;将 `DISCONNECT_EXECUTOR` 改为 Spring 托管 bean 并加 `@PreDestroy shutdown()`。

**[P0]** `batch-worker-process/.../sql/SqlTransformComputePlugin.java:144-185` — `compute()` 方法无事务注解,staging INSERT(`jdbc.update(stageSql, params)`)与溢出判断后的 DELETE 是两个独立 DB round-trip。INSERT 成功、进程在 DELETE 之前崩溃时,溢出行残留 staging,该批 batchKey pipeline 已返回 failure 不会触发 COMMIT 清理,仅依赖 orphan cleaner(默认 24h 后)才清。若 orchestrator reclaim 重派且 pre-DELETE 路径恰好因 batchKey 不同而未命中,staging 残留会被 COMMIT 误搬到 target。**建议**:将 INSERT + overflow DELETE 包入 `@Transactional(transactionManager = "processBusinessTransactionManager")`,保证原子性;或改为 COUNT-first-then-INSERT 策略,COUNT 超限直接拒绝不写入。

**[P0]** `batch-worker-dispatch/.../channel/DispatchChannelGateway.java:31-36` — `String.valueOf(channelConfig.get("channel_type"))` 当 key 不存在时得到字符串 `"null"`(非 Java `null`),各 adapter 的 `supports("null")` 均返回 false,导致 `orElseThrow()` 抛 `IllegalStateException("unsupported channel type: null")`,被执行器包装为 INFRA_ERROR 而非数据配置错误,掩盖根因。此外每次分发都做 `adapters.stream().filter().findFirst()` O(n) 线性扫描,无缓存。**建议**:提前用 `channelConfig.get("channel_type") == null` 判断,返回业务失败 `DispatchResult(false, ..., "channel_type missing")`;将 adapter 按 channelType 预建 `Map<String, DispatchChannelAdapter>` 路由表(参考 CLAUDE.md 规则 9:≥3 分支改 Map 路由)。

---

## P1 — 架构走偏 / 中期技术债(9 条)

**[P1]** `batch-worker-process/.../infrastructure/ProcessStepBeanRegistrar.java:24-70` vs `batch-worker-dispatch/.../infrastructure/DispatchStepBeanRegistrar.java:14-25` — 两模块步骤注册器实现不对等:dispatch 侧 10 行继承 `AbstractStepBeanRegistrar`,process 侧 62 行独立实现,且多了 `ProcessComputePlugin` 注册逻辑。若基类演进,process 侧无法受益,也无法统一强制执行注册语义(如去重检测、日志格式)。**建议**:扩展 `AbstractStepBeanRegistrar` 支持 `List<Class<?>>` 多类型注册,process 侧改为继承。

**[P1]** `batch-worker-process/.../route/ProcessWorkerRouteAdapter.java` + `batch-worker-dispatch/.../route/DispatchWorkerRouteAdapter.java` — 两个接口定义完全同构(均只声明 `buildDefaultRoute()`),各自仅一个实现类,无多态需求,属于典型单实现接口冗余(CLAUDE.md 抽象层次维度)。**建议**:合并到 `batch-worker-core` 定义统一的 `WorkerRouteAdapter`,或直接删除接口用 `@Component` 类。

**[P1]** `batch-worker-process/.../config/ProcessWorkerConfiguration.java` + `batch-worker-dispatch/.../config/DispatchWorkerConfiguration.java` — 两个 record 字段完全相同(7 个字段),仅 `@ConfigurationProperties` 前缀不同。`WorkerConfiguration` 接口已在 `batch-worker-core`,但每个 worker 各自重新定义 record 实现。**建议**:在 `batch-worker-core` 提供通用 `BaseWorkerProperties` 类或 record,worker 模块直接引用并通过前缀绑定。

**[P1]** `batch-worker-dispatch/.../stage/DeliverDispatchStep.java:80-178` + `RetryDispatchStep.java:89-161` — 两个 Step 中「执行 dispatch → markSent/markFailed → 写 context 属性」约 60 行代码高度重复,差异仅在前置条件(`retryRequested` 标志)和后置跳转目标。未来新增渠道字段时需双处同步。**建议**:抽取 `doDispatchAndRecord()` 私有 helper,两个 Step 共用,仅 override 跳转逻辑。

**[P1]** `batch-worker-dispatch/.../channel/HttpDispatchChannelAdapter.java:102-107` — 每次 `dispatch()` 调用时执行 `okHttpClient.newBuilder().dns(...).build()` 动态创建子 Client,子 Client 共享父 ConnectionPool 但没有生命周期管理;且父 Client 未配置 `callTimeout`(只配了 connect/read/write),若上游挂起 response 不返回则持续阻塞。对比 `DispatchReceiptPollScheduler.OkHttpClient` 有 `callTimeout(30s)` + `@PreDestroy`。**建议**:构造时固定父 Client 并加 `callTimeout`;DNS resolve-and-validate 逻辑内嵌到 `okhttp3.Dns` 接口实现,避免每次请求重建 Client。

**[P1]** `batch-worker-dispatch/.../channel/DispatchChannelHealthScheduler.java:16-22` — `probe()` 调度在 Spring 默认单线程 `taskScheduler` 上运行,`probeConfiguredChannels()` 串行处理最多 `MAX_PROBE_CHANNEL_BATCH=1000` 条渠道,每条含 SFTP/OSS 网络 I/O(SFTP connect timeout 30s)。极端情况下调度线程被阻塞数百分钟,后续调度全部积压。**建议**:在 `probeConfiguredChannels()` 内部使用带超时的固定线程池并行处理探针,或将 `MAX_PROBE_CHANNEL_BATCH` 降至合理值(如 50)并缩短 probe 间隔。

**[P1]** `batch-worker-process/.../sql/SqlTransformComputeSpec.java:234-239` — `WriteMode.valueOf(rawValue)` 和 `EmptyResultPolicy.valueOf(rawValue)` 在配置值非法时抛 `IllegalArgumentException`,被 `DefaultProcessStageExecutor.executeOneStep()` 的 `catch(Exception)` 捕获后包装为 INFRA_ERROR,掩盖配置错误根因;ERROR 日志无 traceId/businessId(违反 CLAUDE.md 规则 7)。**建议**:用 try-catch 包住 `valueOf()`,转抛 `BizException.of(ResultCode.INVALID_ARGUMENT, "error.process.invalid_write_mode", value)`。

**[P1]** `batch-worker-process/.../cleanup/ProcessStagingOrphanCleaner.java:44-50` — 类同时标注 `@Component` 和 `@Configuration`,`@Configuration` 导致 Spring 对该类做 CGLIB 代理,对一个只包含调度逻辑的 `@Component` 无必要,增加启动代理开销;`@EnableConfigurationProperties` 通常应放在独立 `@Configuration` 类上。**建议**:去掉 `@Configuration`,将 `@EnableConfigurationProperties(ProcessStagingCleanupProperties.class)` 移到 `ProcessWorkerConfiguration` 所在的 Configuration 类。

**[P1]** `batch-worker-process/.../stage/ProcessStageExecutor.java:8-13` 和 `batch-worker-dispatch/.../stage/DispatchStageExecutor.java:8-13` — 两个接口均暴露 `List<PipelineStepTemplate> defaultStepDefinitions()`,该方法是框架初始化细节(启动时向平台注册步骤),不属于"执行"契约,耦合了 `*StepExecutionAdapter` 调用方,使接口职责不单一。**建议**:将 `defaultStepDefinitions()` 从接口移除,通过 `AbstractStageExecutor` 的 `@PostConstruct` 或 `ApplicationReadyEvent` 自动注册,接口只保留 `execute()`。

---

## P2 — 风格 / 可读性(4 条)

**[P2]** `AckDispatchStep.java:21`, `CompleteDispatchStep.java:23`, `CompensateDispatchStep.java:21`, `DeliverDispatchStep.java:30`, `RetryDispatchStep.java:28` — 五个 Step 各自声明 `private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper()`,共 5 个游离于 Spring 容器之外的 `ObjectMapper` 实例,不受全局 Jackson 配置管控。`AbstractStageExecutor` 中已有 `ERROR_OBJECT_MAPPER` 常量。**建议**:Step 类继承或引用 `AbstractStageExecutor.ERROR_OBJECT_MAPPER`,或注入 Spring 托管的 `ObjectMapper`。

**[P2]** `batch-worker-process/.../sql/SqlTransformComputeSqlValidator.java:38-42` — `"batch.process_staging"` 硬编码在 Validator,与 `SqlTransformComputePlugin` 的 SQL 字面量(`"DELETE FROM batch.process_staging"`,出现 3 次)形成多处重复硬编码,表名变更需同步修改 4+ 处。**建议**:在 `SqlTransformComputePlugin` 抽取 `public static final String STAGING_TABLE = "batch.process_staging"`,Validator 和所有 SQL 字面量统一引用。

**[P2]** `batch-worker-process/.../route/DefaultProcessWorkerRouteAdapter.java` — 缺少类 Javadoc,与 `DefaultDispatchWorkerRouteAdapter`(有注释)不一致,违反两模块对等原则。**建议**:补充中文类注释说明路由适配器用途。

**[P2]** `batch-worker-dispatch/.../infrastructure/DispatchReceiptPollScheduler.java:162-164` — `!response.isSuccessful()` 分支直接 `return` 无日志无计数器,HTTP 非 2xx 响应静默忽略,`pollFailures` 不增长,Micrometer 告警不触发,排障时无法发现上游回执接口故障。**建议**:增加 `log.warn("receipt poll got non-2xx: status={}, externalRequestId={}", response.code(), externalRequestId)` + `pollFailures.incrementAndGet()`。

---

## 总结

| 严重度 | 数量 |
|--------|------|
| **P0** | **4** |
| **P1** | **9** |
| **P2** | **4** |
| **合计** | **17** |
