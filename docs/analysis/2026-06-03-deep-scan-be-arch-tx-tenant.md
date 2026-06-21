# 后端深度扫描 — 架构 / 业务 / 运行时三角(2026-06-03)

## 扫描范围

针对 `file-batch-system` 后端,三类违约角度并行深扫:

1. **架构级违约** — 读写分离误用 / outbox+state 不同事务 / 跨 module 边界
2. **业务正确性** — 权限 / 租户 / 审计语义
3. **运行时违约** — 实际触发非默认事务传播,代码上看似合理但 commit 时序错

扫描基线:`origin/main` @ `5c306063`(2026-06-03);在 worktree `.claude/worktrees/scan-arch-2026-06-03` 上扫,不污染 `feature/docker-deploy`。

数据:215 个 `@Transactional` / 99 个文件;Outbox 散落 40+ 文件;读写分离仅在 `batch-console-api`。5 个并行 Explore 子代理 + 关键发现人工 grep 反核。

与同日已存在的报告(`2026-06-03-deep-scan-be-architecture-v2.md` 等)有部分重叠,本报告聚焦上述三角并标注覆盖关系。

---

## P0 — 立即风险

### P0-1 审计 tenant_id 兜底 `"system"` 字符串 → 跨租审计取证断链

**文件**: `batch-console-api/src/main/java/com/example/batch/console/domain/audit/support/AuditAspect.java:246-271`

```java
private static String resolveTenantFallback(String principalTenantId) {
  if (principalTenantId != null && !principalTenantId.isBlank()) return principalTenantId;
  String mdcTenant = MDC.get("tenant");
  if (mdcTenant != null && !mdcTenant.isBlank()) return mdcTenant;
  return "system";   // ← 硬编码兜底
}
```

注释明说兜底是为让 `auth.login` / `auth.logout` 等系统级动作不被 `tenant_id NOT NULL` 拒掉。问题是:

- `ROLE_ADMIN` 的 `ConsolePrincipal.tenantId()` 是 `null`,但它能 update / batchCreate **任意 tenant** 的资源(ConsoleTenantController.update / batchCreate 都异常退出 `@AuditAction`)
- 这些操作的审计行 `tenant_id = "system"`,而不是被改的目标租户
- 取证查询 "谁动了 tenant-X?" 会查不到(漏查),合规链断掉

**修法**:`@AuditAction` 元信息里挂 `targetTenantParam = "tenantId"` 让切面从方法入参/返回值取目标租户,fallback `"system"` 仅在确实是无目标租户的真系统动作(login/logout/healthcheck)使用。

**核验**: ✅ 直接读源码确认

---

## P1 — 高优先

### P1-1 Kafka 异步消费回写 trigger_request 缺少 RLS context

**文件**: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/trigger/TriggerLaunchConsumer.java:88-178`

```java
@KafkaListener(topics = BatchTopics.TRIGGER_LAUNCH_V1, ...)
public void consume(...) {
  String tenantId = request.tenantId() == null ? "unknown" : request.tenantId();
  BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);     // ← 只设 MDC
  ...
  LaunchResponse response = launchApplicationService.launch(request);   // 内部走 RLS
  writeBackTriggerRequestLaunched(tenantId, request.requestId(), response);  // ← 此调用前未 set RlsTenantContextHolder
}
```

`writeBackTriggerRequestLaunched` 在新 Kafka 工作线程上跑,`launchApplicationService.launch` 内部即便设过 holder,执行完返回时也会清掉(或在该方法栈帧结束)。回写的 mapper update 没有 RLS session var。

当前 RLS Phase A 过渡模式策略允许 `app.tenant_id IS NULL` 旁路(见 `RlsPolicyHealthIndicator`),所以暂没失败,但:

- Phase B 切严格模式时,这条 Kafka 路径会全员失败,batch_day → trigger_request → launch 闭环垮
- 在 Phase A 下也丢了纵深防御:伪造 Kafka 消息可改任意租户的 trigger_request 状态

**修法**:Kafka listener 入口加 `RlsTenantContextHolder.runWithTenant(tenantId, () -> { launch + writeBack })`,把整个动作包起来,finally 清。

**核验**: ✅ 直接读 TriggerLaunchConsumer 确认 — 只 set MDC,无 holder

---

### P1-2 调度任务遍历 tenant 不 set RlsTenantContextHolder

**文件**: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/quota/QuotaRuntimeStateSnapshotScheduler.java:53-111`

```java
@Scheduled(fixedDelayString = "${batch.quota.snapshot.interval-millis:300000}")
public void scheduledSnapshot() { snapshot(); }

public void snapshot() {
  List<String> tenantIds = tenantQuotaPolicyMapper.selectDistinctEnabledTenantIds();
  for (String tenantId : tenantIds) {
    snapshotted += snapshotTenant(tenantId);   // ← 整轮没碰过 RlsTenantContextHolder
  }
}

private int snapshotTenant(String tenantId) {
  for (TenantQuotaPolicyEntity p : tenantQuotaPolicyMapper.selectByTenantAndEnabled(tenantId, true)) {
    written += self.writeIfActive(tenantId, "TENANT_JOBS", ...);   // self proxy → @Transactional
  }
  ...
}
```

`writeIfActive` 是 `@Transactional` 内部写 PG,但整个调用栈无 RLS holder。同类问题在 `SensorPollScheduler` / 其他 `@Scheduled` per-tenant 循环里大概率同样存在(批量结论,需逐个核 8 个调度器)。

**Phase A 影响**:策略允许 `IS NULL` 旁路 → 跑得通,无纵深;
**Phase B 影响**:策略 enforce app.tenant_id → 调度任务整轮静默失败,熔断阈值触发前看不出来。

**修法**:`snapshotTenant` 外层 `RlsTenantContextHolder.runWithTenant(tenantId, () -> ...)`。整改面:扫一遍所有 `@Scheduled` 加 `Grep "@Scheduled"` 后用 `selectDistinctEnabled*TenantIds` 模式的循环都套一层。

**核验**: ✅ 直接读 QuotaRuntimeStateSnapshotScheduler 确认

---

### P1-3 @Async + @TransactionalEventListener(AFTER_COMMIT) 推送侧无补偿

**文件**: `batch-console-api/src/main/java/com/example/batch/console/domain/observability/realtime/ConsoleRealtimeEventBridge.java:34-56`

```java
@Async(ConsoleAsyncConfiguration.PUSH_TASK_EXECUTOR)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void onDomainEvent(ConsoleRealtimeDomainEvent event) {
  ...
  realtimeEventHub.publish(sseEvent);     // SSE 推送 — 失败无重试
  redisPublisher.publish(sseEvent);        // Redis Pub/Sub — 失败无补偿
}
```

代码注释承认这是从同步态改造来 — 解决 Tomcat 线程被 Redis 同步 IO 占用的真实问题。但 AFTER_COMMIT 后还挂 `@Async`:

- DB 事务已 commit(状态行已落)
- 事件转到 push pool 异步发,若 Redis 抖动 → `redisPublisher.publish` 抛异常 → 异步线程吃掉
- 业务方写的状态在 DB 里有,SSE 订阅者没收到 → 前端实时面板假死

属于**已知 tradeoff 但缺补偿**。

**修法**:
1. 短期:`redisPublisher.publish` 内部加 retry + dead-letter 队列,失败时落 `console_realtime_failed_events` 表,后台周期重发
2. 长期:把 Redis Pub/Sub 替换为 stream + consumer group(本身提供 at-least-once 语义)

**核验**: ✅ 直接读 ConsoleRealtimeEventBridge 确认结构 + 代码注释已自承

---

### P1-4 Outbox 转发器 Kafka send 在 @Transactional 内 → rollback 不撤回 Kafka 消息

**文件**: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/OutboxPublishCircuitBreaker.java`(子代理引用,需复核精确行号)

**问题**:Kafka producer `send()` 在 outbox 转发 `@Transactional` 内执行,中途网络抖动 / Kafka 短期不可用:
- 消息可能已被 broker 接收(producer ack pending 但实际 leader 已收)
- DB 事务因 IOException 回滚 → outbox 行回到 NEW
- 下一轮转发再次 send → 同一 payload 重复发布到 Kafka topic

依赖**下游 consumer 幂等**(基于 outbox event id / dedupe key)兜底,但当前 SDK Java/Python 路径上**有部分 consumer 未实现去重**(参考 `2026-06-02-java-python-sdk-deep-review.md` 提到的 SDK 一致性问题)。

**修法**:
- 推荐:outbox poller 改成"先在独立 tx 内 mark IN_FLIGHT → send Kafka → 单独 tx mark PUBLISHED",失败靠 IN_FLIGHT 超时回 NEW(state machine 显式)
- 不推荐:Kafka transactional producer(性能开销 + 单分区 throughput 限制)

**核验**: ⚠️ 子代理引用,精确行号需进一步定位 — 但模式属实(全 outbox 转发器架构均如此)

---

### P1-5 DefaultCompensationService handler 路由 map 与 `self` 代理混用

**文件**: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/governance/DefaultCompensationService.java:73,135,564`

**子代理报告**:类构造器初始化 `handlersByType` map 用方法引用 `this::rerunJob`,但 `appendPreInsertFailureLog` 走 `self` 代理(REQUIRES_NEW)。handler 派发时(map.get(type).accept(...))在 `this` 上调,REQUIRES_NEW 失效。

**潜在后果**:command 状态在外层 tx 里 flip 到 RUNNING,handler 中途失败,失败日志若也走 `this` 而非 `self`,REQUIRES_NEW 退化,日志 + 状态一起回滚 → command 卡在初始态。

**核验**: ⚠️ 子代理给出的行号(73/135/564)合理但未亲读全文件;此项需要打开 DefaultCompensationService 完整核 handler 派发路径 + map 注册 + appendPreInsertFailureLog 三处的 proxy 用法是否一致

---

### P1-6 @Transactional + @Scheduled + @SchedulerLock 三层 AOP 顺序未声明

**文件**: 子代理列出 `BatchDayCutoffScheduler:42` / `BatchDayOpenScheduler:59` / `QuotaRuntimeStateSnapshotScheduler:54`(已用 SchedulerLock)等

**问题**:Spring AOP 拦截链顺序若不显式 `@Order`,在 Spring 不同 minor 版本间 `@SchedulerLock` 与 `@Transactional` 的顺序不固定:
- 期望:`Scheduled → SchedulerLock → Transactional`(锁获取后开事务)
- 风险:若 `Transactional` 先于 `SchedulerLock`,事务边界包了锁等待 → DB 连接长占;若锁释放早于 tx commit,下一轮可能并发拿到锁但前轮 DB 行锁未释。

**修法**:`@Order` 显式声明 + 在集成测试断言锁释放时序,或把业务逻辑下沉到内层 `@Service` 让调度器只做编排不开事务。

**核验**: ⚠️ 推断风险,需读对应 scheduler 类 + ShedLock 版本配置确认实际行为(各类配置默认值不同)

---

## P2 — 中等

### P2-1 OperationAuditQueryService 等纯查方法无 `readOnly=true`

**文件**: `batch-console-api/src/main/java/com/example/batch/console/domain/audit/application/OperationAuditQueryService.java`(子代理报告,行号 25 附近)

`@Transactional` 默认 readOnly=false → 路由判定走主库;改 `@Transactional(readOnly = true)` 可下沉到 replica,释放主库连接池。

非紧急,属于性能/容量优化。

**核验**: ⚠️ 模式属实,但全代码库里类似纯查 service 多达数十处,要不要逐个改是策略问题

---

### P2-2 DbRowExistsSensorPolicy 用 `readOnly=true, REQUIRES_NEW` 语义不明

**文件**: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/sensor/DbRowExistsSensorPolicy.java:57`

子代理报告:sensor 在独立只读事务里查 DB,若父事务是 read-write 且 sensor 返回 false,父事务继续写。问题是 sensor 的隔离视图可能滞后于父 tx 未 commit 的修改 — sensor 本意是查"独立可见性",但 REQUIRES_NEW 在 MySQL/PG 不同隔离级别下表现不同(尤其 PG REPEATABLE READ 默认锁定 snapshot)。

**修法**:文档化预期隔离级别 + 测试覆盖 sensor 在父 tx 中插入 / 未 commit 的可见性,或显式 `isolation = READ_COMMITTED`。

**核验**: ⚠️ 子代理引用,需读 DbRowExistsSensorPolicy 实证

---

### P2-3 RoutingHints 是普通 ThreadLocal — 异步分叉后 `@RouteToPrimary` 失效

**文件**: `batch-console-api/src/main/java/com/example/batch/console/config/RoutingHints.java:14-18`

```java
// JavaDoc 自述:
// 设计为简单 ThreadLocal(非 InheritableThreadLocal):跨线程异步任务通常自带独立事务边界,
// 继承父线程 hint 反而引入误路由风险。
private static final ThreadLocal<Boolean> FORCE_PRIMARY = new ThreadLocal<>();
```

这是**明示的设计选择**,作者已论证。但要点:`@RouteToPrimary` 标注的 service 方法若内部 spawn `@Async` 子任务 + 子任务做 read-after-write 假设主库,会读到 replica 的滞后视图。

**当前没踩**(代码里没有 `@RouteToPrimary` 内部直接 `@Async` 的混用),但属于**ADR 应明文记录的注意事项**,放到 CLAUDE.md / ADR-XX 防新增违约。

**核验**: ✅ 直接读 RoutingHints 确认

---

## ❌ 已撤回(子代理报告但反核为误判)

### Console-api 直接 INSERT/UPDATE/DELETE 编排器表

**子代理报告**: P1.1 / P1.2 — console-api `JobDefinitionMapper.xml:113-198` 和 `WorkflowDefinitionMapper.xml:84-157` 有 INSERT/UPDATE/DELETE,造成与 orchestrator 双写。

**反核**: 实际 grep 这两个 XML 文件,**仅有 SELECT**,没有任何 INSERT/UPDATE/DELETE。子代理给出的行号是**幻觉**。

```
$ rg "INSERT INTO|UPDATE\s+\w+|DELETE FROM" batch-console-api/src/main/resources/mapper/JobDefinitionMapper.xml
# (无输出)
$ rg "INSERT INTO|UPDATE\s+\w+|DELETE FROM" batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml
# (无输出)
```

console-api 真正有 INSERT 的 XML 全部是 console 自己的表(`config_approval`、`config_sync_log`、`notification_channel`、`console_operation_audit`、`notification_delivery_log`、`subscription_rule`),不存在跨模块写。

`TenantConfigInitApplyHandlers` 确有 workflow_definition UPSERT,但这是**租户初始化种子路径**,业务上属合理边界(初始化用 console 接入,运行期由 orchestrator 拥有)。

### TriggerOutboxDomainEventPublisher MANDATORY 无外层 tx

**子代理报告**: P0-3 — `TriggerOutboxDomainEventPublisher.publish/publishRaw` 标 `MANDATORY` 但调用方 `TriggerReconciler` 无 `@Transactional`,运行抛 IllegalTransactionStateException。

**反核**: 实际 grep 调用方:
- `DefaultTriggerService.java:228` 调 `publishRaw`,该方法在 `DefaultTriggerService.java:92` 标了 `@Transactional` ✅
- `TriggerOutboxRelay.java:263` 调的是**另一个**(Kafka publisher 的 publish),非 outbox publisher

MANDATORY 契约满足,无运行时风险。

### Outbox + state 不同事务批量违约

**子代理报告**: 整组扫描"未发现违约"(0 findings)。

**反核**: 这一项扫得偏宽松。`DefaultScheduleForwarder` **故意不挂 @Transactional**(为解耦 DB 锁与 Kafka RTT),从代码内联注释看是明确设计,但和 P1-4(send-in-tx)对外语义其实是一致问题的两面 — 子代理把 P1-4 算到"tx propagation" lane 里报了,这一 lane 就剩"无发现"。结论无误,但报告维度有重叠,合并 P1-4 读即可。

---

## 与已存在扫描报告的覆盖关系

| 本报告项 | 重叠的既有报告 | 覆盖情况 |
|---|---|---|
| P0-1 audit 兜底 | `2026-06-03-deep-scan-be-business-ops.md` / 同日安全审计 PR#314 | 既有报告未明确指出 ROLE_ADMIN 跨租审计断链,本报告**新增**风险论证 |
| P1-1 Kafka 缺 RLS | `2026-06-02-deep-review-round-2.md` | 既有报告提过 RLS,但未列出 Kafka listener 入口具体缺陷 |
| P1-2 调度器缺 RLS | `2026-06-03-deep-scan-be-resources-scheduling-v2.md` | 资源调度报告聚焦 JVM/Kafka/OOM,本项**新增** |
| P1-3 SSE 异步无补偿 | `2026-06-02-sdk-atomic-fe-deep-review.md` 侧重 FE,BE 侧未覆盖 | 本报告**新增** |
| P1-4 outbox send-in-tx | `2026-06-03-deep-scan-be-architecture-v2.md` | 建议交叉读,该报告可能已覆盖 |
| P2-3 RoutingHints | 无 | 本报告**新增 ADR 提醒** |

---

## 整改建议优先级

**Sprint 内**:
1. P0-1:审计 tenant 兜底加目标租户提取(2-3d)
2. P1-1:TriggerLaunchConsumer + 其他 Kafka listener 入口加 RLS holder(1-2d × N consumer)
3. P1-2:调度器 per-tenant 循环统一加 RLS holder helper(2-3d 含测试覆盖)

**2 周内**:
4. P1-3:SSE / Redis Pub/Sub 失败补偿(改 stream + consumer group 或落 dead-letter 表)
5. P1-4:outbox send 出事务 — 改 IN_FLIGHT 状态机(影响面较大,需先评 ADR)
6. P1-5:DefaultCompensationService handler map 改走 `self` 代理(需完整核对后定 patch)
7. P1-6:scheduler AOP 顺序 + ShedLock 测试加固

**待评估 / 文档化**:
- P2-1:批量给查询 service 加 readOnly(决策:是否值得整批改)
- P2-2:DbRowExistsSensorPolicy 隔离语义文档化
- P2-3:RoutingHints + @Async 互动写入 ADR + CLAUDE.md

---

## 方法学说明

- 5 个并行 Explore 子代理:RW 拆分 / Outbox+tx / 跨模块边界 / 运行时 tx propagation / 租户+审计
- 高优先发现(P0/P1)做人工 grep + Read 反核 — 撤回 2 项幻觉(console-api 跨模块 INSERT、TriggerOutbox MANDATORY 失守)
- ⚠️ 标记的项是子代理引用 + 模式推断,未完整反核;后续 fix 阶段应先 Read 全文件再动手
- 扫描在 worktree 上完成,`feature/docker-deploy` 未受影响

**下一步**:本报告应在 `feature/docker-deploy` 上开 PR(走 [[code-changes-on-feature-branch]] 规范),per-finding fix 各自走 `fix/<topic>` PR。
