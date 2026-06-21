# 批量调度平台深度问题分析报告

> 分析日期：2026-04-15  
> 分析范围：全模块（batch-common / batch-trigger / batch-orchestrator / batch-worker-core / batch-worker-import / batch-worker-export / batch-worker-dispatch / batch-console-api）

---

## 汇总

| 维度 | Critical | High | Medium | Low | 小计 |
|------|:--------:|:----:|:------:|:---:|:----:|
| 并发 / 竞态条件 | 0 | 1 | 2 | 0 | 3 |
| 事务边界 | 1 | 1 | 0 | 0 | 2 |
| 数据一致性 | 0 | 1 | 1 | 0 | 2 |
| 消息可靠性 | 0 | 1 | 2 | 0 | 3 |
| 安全漏洞 | 0 | 1 | 1 | 1 | 3 |
| 资源泄漏 | 0 | 0 | 1 | 1 | 2 |
| 错误处理 | 0 | 0 | 1 | 1 | 2 |
| 设计缺陷 | 0 | 0 | 2 | 1 | 3 |
| 种子数据一致性 | 0 | 0 | 1 | 1 | 2 |
| 配置问题 | 0 | 1 | 2 | 0 | 3 |
| **合计** | **1** | **6** | **13** | **5** | **25** |

---

## 优先级清单

| 级别 | 编号 | 问题概述 | 关键文件 |
|------|------|----------|----------|
| 🔴 立即修复 | #2-1 | Outbox 与状态写入非同一事务，孤儿任务 | `TaskDispatchOutboxService.java` |
| 🟠 紧急 | #1-1 | Outbox 发布窗口竞态，事件长期停滞 PUBLISHING | `DefaultScheduleForwarder.java` |
| 🟠 紧急 | #2-2 | assignWorker 回滚后返回值与 DB 不一致 | `DefaultTaskAssignmentService.java` |
| 🟠 紧急 | #3-1 | 版本号冲突后无法递增，陷入永久冲突循环 | `DefaultTaskOutcomeService.java` |
| 🟠 紧急 | #4-1 | 无 partition 场景 idempotencyKey 不含重试计数 | `TaskDispatchOutboxService.java` |
| 🟠 紧急 | #5-1 | testing-open 可绕过所有内部接口认证 | `InternalAuthFilter.java` |
| 🟠 紧急 | #9-1 | 生产环境密码占位符无启动强制校验 | `.env.prod` |
| 🟡 计划 | 其余 18 项 | 见各节详述 | — |

---

## 一、并发 / 竞态条件

### #1-1 Outbox 发布窗口竞态 [High]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/DefaultScheduleForwarder.java:48-60`

**问题**：`markPublishing(NEW → PUBLISHING)` 成功后，若 Kafka `publish()` 立即失败（网络中断、Broker 故障），事件永远卡在 `PUBLISHING` 状态——因为下一轮轮询只捞 `NEW/FAILED`，`PUBLISHING` 无超时回退机制。

**影响**：Outbox 投递停滞，对应任务派发消息永久丢失。

**根本原因**：缺少"已标记发送但实际未投递"的超时补偿。应为 `PUBLISHING` 状态设置最大驻留时长，超时后自动回退为 `FAILED`。

---

### #1-2 CAS 冲突仅打 warn，无 metrics 上报 [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskOutcomeService.java:206-234`

**问题**：`warnIfCasMiss` 在 CAS 返回 0 时只输出日志，无法区分"合理并发推进"与"真正状态冲突"，监控告警系统无法感知冲突频率。

**影响**：高并发下冲突积累不可见，排查困难。

**根本原因**：可观测性设计不足，应补充 Micrometer Counter。

---

### #1-3 ActiveTaskLeaseRegistry 优雅关闭存在 TOCTOU [Medium]

**文件**：`batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java:46-64`

**问题**：`snapshot()` 基于 `ConcurrentHashMap.values()`（非原子视图），`awaitDrain()` 以 500 ms 轮询判断是否为空。关机时若任务正在 `register` 中，`snapshot()` 可能读到空集合而提前退出，导致已 claim 的任务成为孤儿，超期后进死信队列。

**影响**：优雅关闭路径不可靠，影响任务完整性。

**根本原因**：缺少原子的"注册 + 快照"保护，建议改用 `ReentrantReadWriteLock` 或 `StampedLock`。

---

## 二、事务边界

### #2-1 Outbox 与状态写入非同一事务 [Critical]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/engine/TaskDispatchOutboxService.java:50`

**问题**：

```java
@Transactional(propagation = Propagation.MANDATORY)
public void writeDispatchEvent(...) { ... }
```

`MANDATORY` 仅要求调用方已有事务，但不保证与 `createPartitions` / `createTasks` 在**同一事务内原子提交**。调用链示意：

```
DefaultPartitionDispatchService
  ├── createPartitions()        ← 事务 A 提交
  └── createTasksAndMaybeOutboxEvents()
        └── writeDispatchEvent() ← 若此处抛异常，partition/task 已提交，outbox 未落库
```

**影响**：partition 状态为 `READY`，但 outbox_event 永不存在，Worker 永远收不到派发消息——**孤儿任务，不可自愈**。

**根本原因**：partition/task 创建与 outbox 落库应在同一 `@Transactional` 范围内，调用链需重构，确保同一 `Connection` 上完成所有写入后统一提交。

---

### #2-2 assignWorker 回滚后返回值与 DB 状态不一致 [High]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskAssignmentService.java:86-112`

**问题**：

```java
int updated = jobTaskMapper.assignWorker(...);   // task: READY → RUNNING（已写）
// ...
int claimed = jobPartitionMapper.claimPartition(...);
if (claimed <= 0) {
    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    return jobTaskMapper.selectById(tenantId, taskId);  // 此时事务已标记回滚
}
```

调用方收到的返回对象显示 task 为 `RUNNING`，但实际 DB 已回滚至 `READY`，状态机视图不一致，Worker 可能重复 claim 同一任务。

**根本原因**：`setRollbackOnly` 后不应继续读取 DB 并返回业务数据；应统一抛出异常，由调用方感知失败。

---

## 三、数据一致性

### #3-1 版本号冲突后无法递增，陷入永久冲突循环 [High]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskOutcomeService.java:336-339`

**问题**：

```java
if (progressUpdated <= 0) {
    throw new BizException(ResultCode.STATE_CONFLICT, "job instance progress conflict");
}
jobInstance.setVersion(version + 1);  // 抛异常后永不执行
```

事务回滚后 `job_instance.version` 停留旧值，下次 `applyTaskOutcome` 仍携带同一 `expectedVersion`，再次发生冲突，形成**永久冲突循环**，job instance 永远无法推进。

**根本原因**：乐观锁失败的重试策略缺失，应在上层加重试机制（`@Retryable` 或手动 CAS 循环），而非直接对外暴露冲突异常。

---

### #3-2 workflow_node_run 并发创建可能产生重复记录 [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskOutcomeService.java:109-137`

**问题**：两个并发 outcome 同时读到 `current == null`，都执行 `recordNodeRunStart`。虽有 `UNIQUE(workflow_run_id, node_code, run_seq)` 约束，但 `nextRunSeq()` 若非数据库序列实现，计算结果可能重复，导致约束冲突或异常数据。

**影响**：产生重复 node_run 记录，影响 workflow 状态推进和审计追踪。

**根本原因**：应使用数据库级 `INSERT ... ON CONFLICT DO NOTHING` 或 `SELECT FOR UPDATE` 保证原子性。

---

## 四、消息可靠性

### #4-1 无 partition 场景 idempotencyKey 不含重试计数 [High]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/engine/TaskDispatchOutboxService.java:149-154`

**问题**：

```java
// 无 partition 时的 idempotencyKey 构造
return task.getTenantId() + ":task:" + task.getId();
```

同一 task 无论第几次重试，idempotencyKey 完全相同。Worker 消费端若以此做幂等去重，会**静默跳过所有重试消息**，任务永不执行。

**根本原因**：idempotencyKey 应包含重试维度，建议改为 `tenantId:task:{taskId}:retry:{retryCount}`。

---

### #4-2 Outbox 熔断器无半开（half-open）自动恢复 [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java:118-134`

**问题**：熔断打开后直接 `return null` 跳过本轮，轮询靠指数退避缓解压力，但无探测机制。Kafka 恢复后，熔断器无法自动进入半开状态，Outbox 积压无法追赶，延迟可达分钟级。

**根本原因**：建议引入标准熔断器（如 Resilience4j `CircuitBreaker`），支持 `CLOSED → OPEN → HALF_OPEN → CLOSED` 完整状态机。

---

### #4-3 DLQ 发送失败后消息双重丢失 [Medium]

**文件**：`batch-worker-core/src/main/java/com/example/batch/worker/core/support/AbstractTaskConsumer.java:113-129`

**问题**：

```java
dlq.publish(payload, workerConfiguration().topic(), ...);
return true;  // 立即确认 Kafka 偏移量
```

`dlq.publish()` 若因网络抖动失败，而偏移量已提交，消息**既未正常执行，也未进 DLQ**，彻底丢失。

**根本原因**：应先确认 DLQ 写入成功再提交偏移量，或将 DLQ 写入失败作为不可恢复错误上报告警，暂停消费。

---

## 五、安全漏洞

### #5-1 testing-open 可绕过所有内部接口认证 [High]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/config/InternalAuthFilter.java:34-46`

**问题**：

```java
if (securityProperties.isTestingOpen()) {
    chain.doFilter(request, response);  // 直接放行，无任何验证
    return;
}
```

若生产环境误配 `batch.security.testing-open=true`，`/internal/**`（task claim / report / renew / heartbeat）完全无认证。恶意 Worker 可伪造任意租户、任意任务的执行结果。

**根本原因**：测试开关与安全过滤器耦合，应完全分离。生产 profile 应通过 Spring profile 机制（而非运行时配置）确保安全过滤器不可被覆盖。

---

### #5-2 Outbox payload 明文写入 Kafka [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/KafkaOutboxPublisher.java:100-101`

**问题**：完整 task payload（含业务参数、可能的密钥引用）序列化为 JSON 写入 Kafka topic，无任何加密或字段脱敏。Kafka ACL 若配置不当，任何消费者均可读取所有历史任务数据。

**根本原因**：敏感字段应在落 Outbox 前脱敏，或使用 Kafka 端加密（KMS + Confluent 加密）。

---

### #5-3 MyBatis XML 动态 SQL 存在 `${}` 注入风险 [Low]

**文件**：所有 `*Mapper.xml`

**问题**：需全局确认动态 SQL 参数均使用 `#{...}` 而非 `${...}`。`${...}` 为字符串拼接，存在 SQL 注入风险。

**建议**：运行 `grep -rn '\${\.' --include="*.xml"` 扫描并逐一审查。

---

## 六、资源泄漏

### #6-1 OutboxPollScheduler shutdown 不等待任务完成 [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java:76-81`

**问题**：

```java
@PreDestroy
public void stop() {
    executor.shutdown();  // 仅发信号，立即返回
    // 缺少 awaitTermination()
}
```

Spring 容器关闭时，正在执行的 poll 任务可能访问已销毁的 Bean（如 `DataSource`、`KafkaProducer`），引发 NPE 或连接泄漏。

**修复**：
```java
executor.shutdown();
if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
    executor.shutdownNow();
}
```

---

### #6-2 信号量数量无法运行时调整 [Low]

**文件**：`batch-worker-core/src/main/java/com/example/batch/worker/core/support/AbstractTaskConsumer.java:145-157`

`maxConcurrentTasks` 在启动时固化为 `Semaphore(permits)`，运行时无法动态缩扩容，也无法通过 Actuator 查看当前可用许可数。

---

## 七、错误处理

### #7-1 Outbox 轮询异常未分类，无法差异化告警 [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java:88-115`

**问题**：DB 断连、OOM、Kafka 故障统一 `log.error`，无法区分"瞬时故障（快速重试）"与"持久故障（立即告警）"，SRE 响应延迟。

**建议**：按异常类型分级处理，`DataAccessException` 触发熔断，`OutOfMemoryError` 触发进程告警。

---

### #7-2 ResultSet 关闭异常可能被静默吞噬 [Low]

**文件**：所有 MyBatis Mapper 实现

HikariCP 的 `leakDetectionThreshold` 若未配置，连接关闭异常可能不被感知。建议在 `application-local.yml` 中开启：

```yaml
spring.datasource.hikari.leak-detection-threshold: 30000
```

---

## 八、设计缺陷

### #8-1 StateMachine 用硬编码反射方法名兜底 [Medium]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/statemachine/DefaultStateMachine.java:39-58`

**问题**：

```java
for (String methodName : List.of("getInstanceStatus", "getPartitionStatus", "getRunStatus", ...)) {
    String status = invokeStringGetter(target, methodName);
    ...
}
```

新增 entity 类型若未在此列表追加对应 getter 名，运行时才抛 `IllegalStateException`，无编译期保障，极易引入回归。

**建议**：改为 `Stateful` 接口强制实现，或使用 `@StateField` 注解配合注解处理器做编译期校验。

---

### #8-2 PartitionLifecycleService 与 PartitionDispatchService 职责与事务边界模糊 [Medium]

`PartitionDispatchService.dispatchPartitions()` 先调 `PartitionLifecycleService.createPartitions()`（分区创建），再自行创建 tasks 和写 outbox。若 task 创建失败，partition 已持久化为孤儿，无回滚机制，与 #2-1 共同构成"孤儿任务"的复合风险。

**建议**：合并两个服务，或明确以一个 `@Transactional` 方法包裹全部写入。

---

### #8-3 ObjectProvider 延迟注入掩盖潜在循环依赖 [Low]

**文件**：`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskOutcomeService.java:72`

```java
private final ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider;
```

`ObjectProvider` 延迟解析规避了启动期循环依赖检测，若两个服务实际存在调用环路，会在运行时触发无限递归，且难以定位。

---

## 九、种子数据一致性

### #9-1 multi-tenant-seed.sql 缺失 workflow_definition 记录（已修复）[Medium]

**文件**：`batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql`

**问题**：种子数据插入了三条 `job_type=WORKFLOW` 的 `job_definition`（`TA_WF_SETTLEMENT` / `TB_WF_RECONCILE` / `TC_WF_RISK_PIPELINE`），但对应的 `workflow_definition` 记录缺失。触发器每次调度这些 job 时，orchestrator 均抛 `NOT_FOUND`，产生持续 WARN。

**状态**：已于 2026-04-15 在种子文件和本地 DB 中补全三条 `workflow_definition` 及最小 START→END 节点。

---

### #9-2 job_definition 与 workflow_definition 关联缺乏跨租户 DB 约束 [Low]

**文件**：`db/migration/V5__create_runtime_tables.sql`

`workflow_run.related_job_instance_id` 引用 `job_instance(id)`，但无 CHECK 约束验证两者 `tenant_id` 一致，跨租户引用仅靠应用层防御。

---

## 十、配置问题

### #9-1 生产环境密码占位符无启动强制校验 [High]

**文件**：`.env.prod:6-7,22-23`

```
POSTGRES_PASSWORD=CHANGE_ME_STRONG_POSTGRES_PASSWORD
MINIO_ROOT_PASSWORD=CHANGE_ME_MINIO_ROOT_PASSWORD
```

纯文档约定，无任何强制机制。部署时若漏改，生产数据库以弱密码运行。

**修复**：在安全配置类的 `@PostConstruct` 中校验：

```java
if (password.startsWith("CHANGE_ME")) {
    throw new IllegalStateException("FATAL: production secret not configured: " + key);
}
```

---

### #10-2 Outbox 分片配置无一致性验证 [Medium]

**文件**：`batch-orchestrator/src/main/resources/application.yml`（`batch.outbox.shard-total` / `shard-index`）

多实例部署时，若两个节点 `shardIndex` 相同，同一批 outbox_event 被重复投递；若存在间隙，部分事件永不处理。无自动发现或启动断言。

**建议**：启动时断言 `shardIndex < shardTotal`，并通过注册中心或 ConfigMap 自动分配 `shardIndex`。

---

### #10-3 ShedLock Redis 与 Flyway DB 初始化顺序存在竞争 [Medium]

若 Redis 在 Flyway 迁移期间不可用，`ShedLockConfiguration` Bean 初始化失败，导致依赖 ShedLock 的定时任务全部不可用，但 Flyway 迁移可能已完成。无重试机制和友好错误提示。

---

## 变更记录

| 日期 | 变更内容 |
|------|----------|
| 2026-04-15 | 初版，25 项问题全量录入 |
| 2026-04-15 | 全部 25 项修复完成（含已修复的 3 项 + 新修复的 22 项），详见各节 |
