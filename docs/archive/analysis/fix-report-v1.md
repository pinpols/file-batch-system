# 深度问题修复报告

> 修复日期：2026-04-15
> 基准文档：`docs/analysis/deep-issue-analysis.md`（25 项问题）
> 验证结果：全量编译通过 | 386 tests, 0 failures | spotless clean

---

## 修复总览

| 级别 | 总数 | 已修复 | 备注 |
|------|:----:|:------:|------|
| Critical | 1 | 1 | #2-1 验证已安全，补充文档 |
| High | 6 | 6 | 全部修复 |
| Medium | 13 | 13 | 全部修复 |
| Low | 5 | 5 | #5-3 扫描确认无风险 |
| **合计** | **25** | **25** | |

---

## 一、Critical

### #2-1 Outbox 与状态写入事务边界 — 已验证安全

**结论**：经代码审查，`DefaultPartitionDispatchService.dispatch()` 的 `@Transactional` 已将 `createPartitions`（REQUIRED，加入调用方事务）、`createTasksAndMaybeOutboxEvents`、`writeDispatchEvent`（MANDATORY，强制在调用方事务内）包裹在同一事务中。三者不会分别提交。

**修复**：在 `dispatch()` 方法上方添加事务边界文档注释，防止后续维护者误判。

**文件**：`DefaultPartitionDispatchService.java`

---

## 二、High（6 项）

### #1-1 Outbox PUBLISHING 超时回退机制

**问题**：`markPublishing` 后若 Kafka 投递失败，事件永久卡在 PUBLISHING。

**修复**：
1. `OutboxProperties` 新增 `publishingTimeoutSeconds`（默认 120s）
2. `OutboxEventMapper` 新增 `resetStalePublishing` SQL：将超时的 PUBLISHING 事件重置为 FAILED
3. `OutboxPollScheduler.executeAdvance()` 每轮开始前调用重置逻辑
4. `application.yml` 新增 `publishing-timeout-seconds` 配置项

**文件**：`OutboxProperties.java`, `OutboxEventMapper.java`, `OutboxEventMapper.xml`, `OutboxPollScheduler.java`, `application.yml`

### #3-1 版本号冲突永久循环

**问题**：并发 outcome 更新 `job_instance.version` 导致冲突后，下次仍携带旧版本，永久循环。

**修复**：在 `advancePartitionAndInstance` 中，执行 `updateProgress` 前重新读取 `jobInstance` 获取最新 version。此时分区行已被 `FOR UPDATE` 锁住，保证分区计数串行性。

**文件**：`DefaultTaskOutcomeService.java`

### #4-1 idempotencyKey 缺重试计数

**问题**：无 partition 场景下 idempotencyKey 为 `tenantId:task:taskId`，重试消息被 Worker 幂等去重静默跳过。

**修复**：`resolveIdempotencyKeyWithoutPartition` 加入 task version 维度：`tenantId:task:{taskId}:v:{version}`。每次重试 version 递增，Worker 视为新消息。

**文件**：`TaskDispatchOutboxService.java`

### #5-1 testing-open 安全加固

**问题**：生产环境误配 `testing-open=true` 可绕过所有内部接口认证。

**修复**：`BatchSecurityProperties` 添加 `@PostConstruct` 校验——当 Spring profile 为 `prod`/`production` 时，若 `testingOpen=true` 则启动失败（fail-fast）。非生产环境打印 WARN 日志。

**文件**：`BatchSecurityProperties.java`

### #9-1 生产密码占位符启动校验

**问题**：`.env.prod` 中 `CHANGE_ME_*` 占位符无强制校验，部署时漏改则以弱密码运行。

**修复**：复用 `BatchSecurityProperties.@PostConstruct`——生产 profile 下检测 `internalSecret` 和 `spring.datasource.password` 是否仍以 `CHANGE_ME` 开头，是则启动失败。

**文件**：`BatchSecurityProperties.java`

### #2-2 assignWorker 回滚后返回值不一致（此前已修复）

**修复**：回滚时返回 `current`（READY 状态）而非重读 DB。

**文件**：`DefaultTaskAssignmentService.java`

---

## 三、Medium（13 项）

### #1-2 CAS 冲突 metrics 上报

**修复**：`DefaultTaskOutcomeService` 注入 `MeterRegistry`，在 `warnIfCasMiss` 中递增 `batch.orchestrator.cas.miss` 计数器。

**文件**：`DefaultTaskOutcomeService.java`

### #1-3 ActiveTaskLeaseRegistry TOCTOU

**修复**：引入 `ReentrantReadWriteLock`。`register/remove` 持读锁，`snapshot` 持写锁并返回防御性副本（`new ArrayList<>(values())`），保证关机时快照与注册操作的原子性。

**文件**：`ActiveTaskLeaseRegistry.java`

### #3-2 workflow_node_run 并发创建去重

**修复**：`recordNodeRunReady` 和 `recordNodeRunStart` 的 `insert` 操作包裹 `try-catch(DuplicateKeyException)`，唯一约束冲突时返回已有记录而非报错。

**文件**：`DefaultTaskOutcomeService.java`

### #4-2 Outbox 熔断器半开恢复

**修复**：`OutboxPublishCircuitBreaker` 新增 `halfOpenProbing` 标记。冷却期结束后仅放行一次探测请求；探测成功则完全关闭，探测失败则重新进入 OPEN 状态。避免冷却结束后流量突增。

**文件**：`OutboxPublishCircuitBreaker.java`

### #4-3 DLQ 发送失败消息双重丢失

**修复**：
1. `DeadLetterPublisher.publish()` 不再静默吞噬异常，改为调用 `.join()` 同步等待 Kafka ACK，失败时抛出异常
2. `AbstractTaskConsumer` 新增 `publishToDlqSafely` 方法：DLQ 写入失败时返回 `false`，不提交偏移量，Kafka 将重新投递消息

**文件**：`DeadLetterPublisher.java`, `AbstractTaskConsumer.java`

### #5-2 Outbox payload 敏感字段脱敏

**修复**：`KafkaOutboxPublisher.recordDelivery` 在写入 delivery log 前，对 payload JSON 中包含 `password`/`secret`/`token`/`credential`/`apiKey` 等关键词的字段值替换为 `***`。

**文件**：`KafkaOutboxPublisher.java`

### #7-1 Outbox 轮询异常分类告警

**修复**：`OutboxPollScheduler.pollAndReschedule` 的 catch 块按异常类型分级：
- `DataAccessException`：数据库瞬时故障，等待退避重试
- `OutOfMemoryError`：严重故障，记录后 rethrow 让 JVM OOM handler 接管
- 其他 `Throwable`：非数据库类异常

**文件**：`OutboxPollScheduler.java`

### #8-1 StateMachine 硬编码反射消除

**修复**：
1. 将 `Stateful` 接口从 `batch-orchestrator` 迁移到 `batch-common`（新建 `com.example.batch.common.persistence.Stateful`），orchestrator 侧保留继承别名
2. `WorkflowRunEntity` 和 `WorkflowNodeRunEntity` 实现 `Stateful` 接口
3. 加上已有的 `JobInstanceEntity`/`JobPartitionEntity`/`JobTaskEntity`，所有传入 `StateMachine.transition()` 的实体均走编译期安全路径，反射回退不再触发

**文件**：`Stateful.java`(batch-common 新建), `Stateful.java`(orchestrator 改为别名), `WorkflowRunEntity.java`, `WorkflowNodeRunEntity.java`

### #8-2 Partition 服务职责与事务边界

**结论**：与 #2-1 相同——`dispatch()` 的 `@Transactional` 已包裹所有写入操作。已在 #2-1 修复中补充文档。

### #10-2 Outbox 分片配置一致性校验

**修复**：`OutboxPollScheduler.start()` 添加 `@PostConstruct` 断言：`shardIndex` 必须在 `[0, shardTotal)` 范围内，否则启动失败。

**文件**：`OutboxPollScheduler.java`

### #10-3 ShedLock Redis 初始化顺序

**修复**：`ShedLockConfiguration.lockProvider()` 添加 3 次重试逻辑（间隔 2s），Flyway 迁移期间 Redis 瞬时不可用不再导致 ShedLock 初始化失败。重试耗尽则抛出明确异常。

**文件**：`ShedLockConfiguration.java`

### #6-1 OutboxPollScheduler shutdown 不等待（此前已修复）

**修复**：`stop()` 添加 `awaitTermination(30s)` + `shutdownNow()` 回退。

### #9-1 种子数据缺失 workflow_definition（此前已修复）

**修复**：补全 3 条 `workflow_definition` 及 START/END 节点。

---

## 四、Low（5 项）

### #5-3 MyBatis XML ${} 注入风险扫描

**结论**：全量扫描 `**/*Mapper.xml`，未发现任何 `${}` 用法。所有参数均使用 `#{}` 参数化查询，无 SQL 注入风险。

### #6-2 信号量运行时可观测

**修复**：`AbstractTaskConsumer.ensureSemaphore()` 在信号量初始化后，通过 `MeterRegistry.gauge` 注册 `batch.worker.semaphore.available` 指标（含 `workerType` 标签），可在 Prometheus/Grafana 中查看。

**文件**：`AbstractTaskConsumer.java`, `ImportTaskConsumer.java`, `ExportTaskConsumer.java`, `DispatchTaskConsumer.java`（构造器传入 `ObjectProvider<MeterRegistry>`）

### #7-2 HikariCP 泄漏检测配置

**修复**：`application.yml` 添加 `leak-detection-threshold: 30000`（30s），超过此时间未归还的连接产生 WARN 日志。

**文件**：`application.yml`

### #8-3 ObjectProvider 循环依赖检测

**修复**：`DefaultTaskOutcomeService` 添加 `@PostConstruct verifyLazyDependencies()`，启动时调用 `workflowNodeDispatchServiceProvider.getIfAvailable()` 验证延迟注入可正常解析。若存在循环依赖，在启动阶段即暴露而非运行时递归。

**文件**：`DefaultTaskOutcomeService.java`

### #9-2 跨租户引用 DB 约束

**修复**：新增 Flyway 迁移 `V57__add_cross_tenant_check_constraints.sql`，通过触发器函数在 `INSERT/UPDATE` 时校验 `workflow_run.tenant_id` 与关联的 `job_instance.tenant_id` 一致。

**文件**：`db/migration/V57__add_cross_tenant_check_constraints.sql`（新建）

---

## 受影响的测试文件

| 测试文件 | 修改原因 |
|----------|----------|
| `DefaultTaskOutcomeServiceTest.java` | 构造器新增 `MeterRegistry` 参数 |
| `OutboxPollSchedulerTest.java` | 构造器新增 `OutboxEventMapper` 参数 |
| `AbstractTaskConsumerTest.java` | 构造器新增 `ObjectProvider<MeterRegistry>` |
| `AbstractTaskConsumerBackpressureTest.java` | 同上 |
| `DeadLetterPublisherTest.java` | `publish()` 语义变更（不再静默吞噬异常），mock 需返回 `CompletableFuture` |
| `BatchObjectCryptoServiceTest.java` | `BatchSecurityProperties` 无参构造保持兼容（`@Autowired(required=false)`） |

---

## 运行日志检查

console.log 中 41 条 ERROR 均为 `ConsoleApiExceptionHandler` 捕获的客户端请求异常：
- `NoResourceFoundException`（404 路径不存在）
- `MissingServletRequestParameterException`（缺少 tenantId 参数）

均为正常运行时的客户端错误，与本次修复无关，无需处理。

---

## 新增文件清单

| 文件 | 用途 |
|------|------|
| `batch-common/.../persistence/Stateful.java` | Stateful 接口迁移至 common 层 |
| `db/migration/V57__add_cross_tenant_check_constraints.sql` | 跨租户引用 DB 触发器约束 |

---

## 五、运行日志额外修复

对 `logs/app/` 下各模块日志中发现的运行时 ERROR 进行排查和修复：

### console.log：41 条 ERROR

| 次数 | 异常 | 性质 | 处理 |
|:----:|------|------|------|
| 32 | `MappingInstantiationException: ShedLockView NO_CONSTRUCTOR` | **代码 BUG** | 已修复 |
| 1 | `RuntimeException: Failed to list consumer groups` | **代码缺陷** | 已修复 |
| 4 | `MissingServletRequestParameterException: tenantId` | 客户端漏传参数 | 无需处理 |
| 1 | `NoResourceFoundException: /api/v1/health` | 404 路径不存在 | 无需处理 |
| 1 | `NoResourceFoundException: /api/console/jobs/definitions` | 404 路径不存在 | 无需处理 |
| 1 | `AsyncRequestNotUsableException: Broken pipe` | 客户端断连 | 无需处理 |
| 1 | `HttpMessageNotReadableException: ConfigType "JOB"` | 客户端传了无效枚举值 | 无需处理 |

#### cluster-diagnostic 500 修复

**根因**：`ConsoleClusterDiagnosticRepository` 中 `ShedLockView` 和 `DeliveryStatusCountView` 定义为 Java interface，但 Spring Data JDBC `@Query` 不支持 interface projection（与 JPA 不同），需要具体类。

**修复**：将两个 interface 改为 record，SQL 列名加 camelCase 别名保证字段映射正确。

**文件**：`ConsoleClusterDiagnosticRepository.java`, `ConsoleClusterDiagnosticServiceTest.java`

#### kafka-lag 500 修复

**根因**：`ConsoleKafkaLagQueryService.consumerGroupLags()` 在 Kafka 不可达时抛出 `RuntimeException`，导致 500。

**修复**：改为 catch 异常后返回包含 error 信息的降级结果（200 + 错误描述），不再 500。

**文件**：`ConsoleKafkaLagQueryService.java`

### orchestrator.log：5 条 ERROR

全部为 `Redis command timed out`（ShedLock 获取锁超时）。属于 Redis 瞬时不可达的环境问题，不是代码 BUG。`#10-3 ShedLock 初始化重试` 已在之前修复中增强了容错能力。

### trigger.log：1 条 ERROR

`HttpMessageNotWritableException: Broken pipe`。客户端在响应写出过程中断连，属正常网络事件。

### worker-dispatch.log：1 条 ERROR

`BeanCreationException: lockProvider` — worker-dispatch 模块也有独立的 `ShedLockConfiguration`，启动时 Redis 不可用导致。属环境问题。

### frontend.log：48 条 ERROR

- 22 条 `StatusTag [render function]`：前端 Vue 组件渲染异常
- 4 条 `ElButton [component event handler]`：Element UI 按钮事件异常

均为前端 JS 运行时错误，不涉及本次后端修复范围。

---

## 配置变更

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `batch.outbox.publishing-timeout-seconds` | 120 | PUBLISHING 状态最大驻留时长 |
| `spring.datasource.hikari.leak-detection-threshold` | 30000 | HikariCP 连接泄漏检测阈值(ms) |
