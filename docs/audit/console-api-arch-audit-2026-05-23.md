# batch-console-api 审计报告 — 2026-05-23

> 范围:`batch-console-api`(~16K+ 行,最大模块)
> 维度:资源池 / 设计模式 / 抽象 / 集合 / 多线程异步
> 模式:read-only

## P0 — 安全 / 并发 / 资源泄漏(6 条)

**[P0]** `AuditAspect.java:166` — **SpEL 使用 `StandardEvaluationContext`(全能上下文),存在代码注入风险**。`AuditAspect.resolveAggregateId()` 使用 `StandardEvaluationContext`,允许任意 Java 方法调用、静态类型引用(`T(System).exit(0)`)。对比:`ConsoleCacheInvalidationAspect`(同模块)在 2026-05-16 安全扫描后已改用 `SimpleEvaluationContext + DataBindingPropertyAccessor`,但 `AuditAspect` **未同步修复**,形成不一致的安全基线。**建议**:与 `ConsoleCacheInvalidationAspect.evaluateSpel()` 保持一致,改用 `SimpleEvaluationContext.forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess()).withInstanceMethods().build()`。

**[P0]** `ConsoleApiExceptionHandler.java:73` — **`@Autowired` setter 注入违反 CLAUDE.md §Java 编码细则 #3**。`BizMessageResolver` 使用 setter `@Autowired` 注入,CLAUDE.md 明确「依赖注入只用构造器,禁 @Autowired field/setter 注入」(唯一例外是 `@Lazy @Autowired private SelfType self` AOP 自调用 workaround)。**建议**:改为构造器注入。

**[P0]** `InMemoryTenantConfigPackageExcelImportStore.java:11` — **Excel 会话 `ConcurrentHashMap` 无 TTL / 无上限**。`sessions` 是进程内 `ConcurrentHashMap<String, PackageExcelSession>`,无容量上限,也无 TTL 清理机制。上传后如果用户放弃 preview/apply,session 永远驻留内存。大 Excel 文件(11 个 sheet 完整解析后驻内存)在高并发场景下可积累到 OOM。**建议**:(1) 引入 Caffeine `expireAfterWrite` + `maximumSize` 替代,TTL 建议 30 分钟;(2) 或者改为 Redis 存储;(3) 至少在 `get()` 找不到时返回明确的 `SESSION_EXPIRED` 错误码。

**[P0]** `ConsoleOpsSummaryRealtimeStream.java:40-42` — **`summaryCache` 无界 `ConcurrentHashMap` 按租户无限增长**。每个租户一条缓存,TTL 逻辑仅在 `loadSummary()` 读路径判断是否使用缓存值,但**从不从 Map 中删除条目**。租户数量增长后,所有租户的摘要快照永久驻留内存;`scheduledRefreshes` 同样是无界 `ConcurrentHashMap`。**建议**:改用 `Caffeine.newBuilder().expireAfterWrite(10, SECONDS).maximumSize(1000).build()` 替代两个裸 `ConcurrentHashMap`。

**[P0]** `ConsoleRealtimeEventBridge.java:29` — **`@TransactionalEventListener(fallbackExecution = true)` 在无事务时同步执行 Redis Pub/Sub**,可能阻塞 Tomcat 工作线程。`fallbackExecution = true` 意味着当没有活动事务时,监听方法**在调用方线程上同步执行**。`ConsoleRealtimeRedisPublisher.publish()` 内部做 `redisTemplate.convertAndSend()`(同步网络 I/O)+ `replayStore.append()`(同步 pipelined Redis 命令)。如果调用方线程来自 Tomcat 工作线程且 Redis 抖动,整个请求线程被阻塞直到超时。**建议**:将 `redisPublisher.publish()` 和 `realtimeEventHub.publish()` 提交到专用的有界线程池异步执行。

**[P0]** `ConsoleIdempotencyInterceptor.java:148` — **`setIfAbsent` 失败后的二次 `get` 存在 TOCTOU 窗口,且第 148 行 `get` 未处理 Redis 异常**。第 144 行 `setIfAbsent` 返回 `false` 后,第 148 行再次 `redisTemplate.opsForValue().get(redisKey)` 没有包裹 `DataAccessException` 捕获(第 111-121 行的捕获仅覆盖首次 `get`)。若 Redis 在这两次调用之间抖动,第 148 行会抛出未处理异常,向上传播到 Spring MVC 的 ExceptionHandler,返回 500 而非 503,破坏「fail-closed 返回 503」的语义承诺。**建议**:将第 148 行的 `get` 包裹同等的 `DataAccessException` 捕获。

---

## P1 — 架构走偏 / 中期技术债(10 条)

**[P1]** `ConsoleTenantConfigCopyService.java` — **`@Service` Bean 放在 `web` 包下,违反分层约定**。注入了 15 个 Mapper(`JobDefinitionMapper`、`WorkflowDefinitionMapper` 等),但其包路径是 `com.example.batch.console.web`,属于 Web 层。CLAUDE.md §架构硬约束 要求 Web 层只是薄壳。**建议**:迁移到 `infrastructure/config/DefaultConsoleTenantConfigCopyService.java`,并抽接口到 `application/config/`。

**[P1]** `DefaultConsoleOpsApplicationService.java:44-70` — **Ops Summary 在单次请求中串行发出 9 条独立 DB 查询,无批处理**。`summary()` 按顺序发出 9 条独立 Mapper 调用(`countByStatus` × 2、`countByStatuses` × 2、各 worker/outbox 计数),且**不在同一事务**,每次都拿新连接,HikariCP 连接压力 9x。该方法还被 SSE 摘要流在每次写提交后触发,属于高频调用路径。**建议**:(1) 加 `@Transactional(readOnly = true)` 复用单连接;(2) 合并部分 count 到单 SQL(PostgreSQL `FILTER (WHERE ...)`)。

**[P1]** `ConsoleQueryCacheService.java:46` — **`@Transactional(readOnly = true)` 放在非数据访问的缓存服务上**。`ConsoleQueryCacheService` 是一个纯 Redis 操作服务,没有任何 MyBatis/JDBC 调用。类上标 `@Transactional(readOnly = true)` 实际上会开启/绑定一个数据库事务(占用连接),且在 `getOrLoad()` 的 `loader.get()` 回调中产生事务嵌套/传播语义混乱。**建议**:删除类级 `@Transactional(readOnly = true)`。

**[P1]** `ConsoleRealtimeEventHub.java:62` — **`ScheduledExecutorService` 直接 `new`**(单线程心跳调度)不受 Spring 生命周期管理。`@PreDestroy` 中调用了 `scheduler.shutdownNow()`,但该 ExecutorService 是字段初始化,若 Spring Context 刷新失败导致 `@PreDestroy` 未执行,线程将泄漏。与 `ConsoleOpsSummaryRealtimeStream`、`WebhookDispatcher`、`WebhookRelay` 共同造成 console-api 模块内至少 3-4 个直接 `new` 的 ExecutorService,无 Actuator 可观测性。**建议**:统一迁移到 Spring 管理的 `ThreadPoolTaskScheduler` Bean。

**[P1]** `WebhookDispatcher.java:69-81` — **Webhook 线程池命名工厂匿名,线程名无法区分业务域**。4 个线程同名 `console-webhook-dispatch`,无序号区分。该线程池(`CallerRunsPolicy`)在队列满时会让 Tomcat 工作线程去执行 HTTP 投递,导致 Tomcat 线程被 webhook 超时(8-10s)阻塞。**建议**:`CallerRunsPolicy` 改为 `DiscardOldestPolicy` 或 `AbortPolicy`(依赖持久化 relay 补偿),线程命名加索引(`console-webhook-dispatch-N`)。

**[P1]** `AuditAspect.java:100-113` — **`recordInNewTransaction` 内部每次都 `new TransactionTemplate(transactionManager)`**。`TransactionTemplate` 是轻量对象,但作为 `@Aspect` 单例的频繁调用路径,每次反复 new 属于无谓分配。**建议**:将 `TransactionTemplate` 作为 `@PostConstruct` 初始化的字段(`private final TransactionTemplate requiresNewTemplate`)。

**[P1]** `ConsoleRealtimeReplayStore.java:67` — **Replay 全量 `LRANGE 0 -1` 可能返回超大列表,无分页保护**。`replayMaxEntries` 配置控制写端(`lTrim`),但读端 `replay()` 仍是无条件拉全部。若配置值较大(如 10000),每次断线重连触发 replay 会在请求线程内反序列化并遍历全量数据,可能造成 GC 压力。**建议**:在 `replay()` 中对 `rawEntries` 加 `head(replayMaxEntries)` 截断保护,并在 cursor 匹配后仅取 cursor 之后的数据。

**[P1]** `ConsoleJwtService.java:85-87` — **`redisTemplate` 字段使用 `@Autowired(required = false)` 可选注入**,违反 CLAUDE.md §编码细则 #3。`StringRedisTemplate` 是基础设施依赖,不是 AOP 自调用场景。**建议**:改为构造器注入 + `ObjectProvider<StringRedisTemplate>` 模式(与 `ConsoleSessionRegistry` 中 `ObjectProvider<MeterRegistry>` 一致),在 `authenticate/revoke` 中调 `getIfAvailable()` 判空。

**[P1]** `ConsolePushSender.java:138` — **`pushService.sendAsync()` 返回的 `Future` 在同线程 `.get(8, SECONDS)` 阻塞**。`@Async("pushTaskExecutor")` 方法内部对 web-push 库的 `Future.get(8, SECONDS)` 做同步等待。push 线程池有 16 个线程、队列 200,若所有线程都在等待 HTTP 响应,实际并发 push 上限是 16 个,超出则用 CallerRunsPolicy 让调用方(业务线程)承担,与 `@Async` 的异步化意图矛盾。**建议**:在升级 web-push 6.x 前在 `sendOne()` 中对每个 `Future.get` 使用更短的超时,并在超时后标记该 endpoint 为暂时不可用。

**[P1]** `ConsoleRequestContextFilter.java:50` — **`clientIp` 取 `X-Forwarded-For` 未做防伪造校验,与 JWT IP 哈希绑定存在不一致**。`ConsoleRequestMetadata.clientIp` 信任 XFF header,而 `ConsoleJwtService.hashClientIp()` 注释明确说明「不信任 XFF,只取 RemoteAddr 防伪造」。两处获取 clientIp 逻辑不同:审计记录的 IP 可能是客户端伪造的 XFF,而 JWT 绑定的 IP 是真实 RemoteAddr,导致 `AuditAspect` 里记录的 `ipHash` 与 JWT IP 哈希不可比对。**建议**:统一 clientIp 解析策略。

---

## P2 — 风格 / 可读性(5 条)

**[P2]** `AuditAspect.java:275` — **`sha256short` 使用 `String.format("%02x", ...)` 循环拼接,而 `ConsoleJwtService` 已使用 `HexFormat.of().formatHex()`**。同模块两处 SHA-256 短摘要实现不一致。**建议**:统一使用 `HexFormat.of().formatHex(head)`,并考虑提取到 `batch-common` 工具类避免重复。

**[P2]** `ConsoleOpsSummaryRealtimeStream.java:43-44` — **`Executors.newSingleThreadScheduledExecutor` 等模块内 `Executors.new*` 反模式集群**。合计在 SSE/Webhook/Push 路径有 4 处直接 `new` ExecutorService,规模扩大时难以统一调优。**建议**:在 `ConsoleAsyncConfiguration` 中统一注册为 Spring Bean。

**[P2]** `ConsoleQueryCacheService.java` / `ConsoleConfigCacheInvalidationService.java` — **SCAN 批量 DEL 逻辑在两个类中各自独立实现,代码重复**。两处均实现了「SCAN pattern + 分批 DEL」逻辑,代码几乎一致。**建议**:提取为 `RedisKeyUtils.scanAndDelete(StringRedisTemplate, String pattern, int batchSize)` 静态工具方法。

**[P2]** `ConsoleTenantConfigCopyService` / `DefaultConsoleTenantConfigPackageExcelApplicationService` — **`buildPipelineInsertParams` / `applyJobs` 等方法内大量逐字段 `entity.setXxx(row.get("col"))` 赋值,无结构**。`applyJobs()` 方法约 30 行纯 setter 调用,无辅助方法提取。**建议**:提取 `toJobDefinitionEntity(Map<String,String> row, ApplyContext ctx)` 静态工厂方法。

**[P2]** `ConsoleJwtService.java:331-358` — **`encoder()` / `decoder()` 双重检查锁在 `cachedEncoder` / `cachedDecoder` 未声明 `volatile` 的情况下可能存在有序性问题**。DCL 在 JVM 内存模型下需要 `volatile` 保证可见性,否则另一个线程可能读到部分初始化的对象引用。注释说明的 fallback DCL 路径存在并发风险。**建议**:将 `cachedEncoder` / `cachedDecoder` 声明为 `volatile`。

---

## 总结

| 严重度 | 数量 |
|--------|------|
| **P0** | **6** |
| **P1** | **10** |
| **P2** | **5** |
| **合计** | **21** |
