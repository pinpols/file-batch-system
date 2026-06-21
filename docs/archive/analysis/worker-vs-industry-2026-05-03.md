# Worker 实现 vs 业界差距 — 评估报告 2026-05-03

> **范围**：5 个 worker 模块（`batch-worker-core/-import/-export/-process/-dispatch`）的核心机制与业界主流方案对比，识别真缺陷与设计取舍。
> **方法**：源码定位（带 `file:line`）+ 4 个业界方案横向对照（Spring Batch / Temporal / Airflow / Spring Cloud Data Flow），不凭空 brainstorm。
> **配套**：参见同目录 `orchestrator-vs-industry-2026-05-03.md`（orchestrator 侧的姐妹篇）。

---

## 1. 系统定位

本 worker 是 **Kafka-driven + lease-based + stage-orchestrated** 的批处理执行单元，由 orchestrator 统一发号施令。

| 对比对象 | 关键特征 | 跟本系统主要差异 |
|---|---|---|
| **Spring Batch** | JSR-352 标准 chunk processing，单 JVM 内 retry/skip listener | 不分布式；retry 在 worker 内做，本系统在 orch 决策 |
| **Temporal / Cadence** | activity worker long-poll server，server-side cancel + heartbeat | 严格 exactly-once；本系统是 at-least-once + 业务幂等回退 |
| **Apache Airflow** | task instance + executor (Celery/K8s)，per-task subprocess | task 完整隔离 + on_kill；本系统 listener 同步跑 + Semaphore 背压 |
| **AWS Batch / GCP Batch** | 托管 + array job + per-task container | container 级隔离；本系统是同进程 thread-level |

---

## 2. 核心机制现状

| 机制 | 实现概况 | 关键文件 |
|---|---|---|
| **任务路由** | Kafka topic-pattern 订阅（`batch.task.dispatch.*` + `.node.<workerCode>` + tenant/priority 后缀），单 regex 覆盖 SINGLE/TENANT/PRIORITY 三种 producer 模式 | `AbstractTaskConsumer:309` |
| **CLAIM** | Kafka 收到 → HTTP `/internal/tasks/{id}/claim` → orchestrator 用乐观锁（`version + worker_code + lease_expire_at`）原子抢占；4xx → 跳过；**非 SKIP LOCKED 而是 version CAS** | `HttpTaskExecutionClient:69` + `JobPartitionMapper.xml:106` |
| **Task lease 续约** | 主轮询 10s + fast-retry 2s（仅失败 lease），按 taskId 跟踪连续失败计数，≥3 次 log.error + counter，**永不熔断**（任务继续跑） | `WorkerTaskLeaseRenewer:58/96` |
| **Worker heartbeat** | 每 worker 子类 `@Scheduled` 调 orchestrator `/internal/workers/heartbeat`（独立于 task lease） | `AbstractWorkerLoop:82` |
| **执行模型** | Kafka listener 单线程消费 → `Semaphore(maxConcurrentTasks=8)` 背压；满则 `container.pause()`、permit 释放后 `resume()`。**所有 task 在 listener 线程同步跑**，不分线程池 | `AbstractTaskConsumer:69` |
| **Graceful drain** | 三步：(1) 标 worker `DRAINING` (2) `kafkaListenerEndpointRegistry.stop()` (3) `ActiveTaskLeaseRegistry.awaitDrain(120s)`；ReadWriteLock 防 TOCTOU + drain monitor wait/notify | `GracefulKafkaShutdown:75` + `ActiveTaskLeaseRegistry:108` |
| **Retry / 错误** | Worker **不**重试业务失败：捕获异常 → `StageExecutionResult.failure` → `report` 给 orchestrator 决策。HTTP report 自身有指数退避重试（409/5xx/IO）。毒丸消息 → DLQ topic，DLQ 写失败才不 ack | `HttpTaskExecutionClient:148` + `AbstractTaskConsumer:141` |
| **幂等** | `idempotencyKey` 仅放 `ExecutionContext` 透传，**worker 端无去重表**；at-least-once 语义靠业务 SQL `ON CONFLICT DO NOTHING/UPDATE` 回退 | `SqlTransformComputePlugin:508` |
| **观测** | Micrometer：`batch.worker.semaphore.available`、`lease.consecutive_failures`、`lease.fast_retry`、`drain.{duration_seconds,outcome_total,initial_active_leases}`、`worker.report.failed.total`、`batch.worker.report.duration{p50/p95/p99}`；MDC 注入 tenantId/traceId/taskId/workerId/jobInstanceId/workerType/runMode | 全 worker-core |
| **WAP 5-stage（process）** | `AbstractStageExecutor` while 循环跑 PREPARE→COMMIT→FEEDBACK，每 stage 调 `runtimeRepository.startStepRun/finish*`（落 `pipeline_step_run`），失败默认终止；支持 `onSuccessNextStepCode/onFailureNextStepCode` 跳转。**stage 之间无 transaction，COMMIT 单独 `@Transactional(REQUIRES_NEW)`**，FEEDBACK 失败被 swallow + metric | `AbstractStageExecutor:32` + `DefaultProcessStageExecutor:120` |

---

## 3. vs 业界差距矩阵

| 维度 | 本系统 | Spring Batch | Temporal | Airflow |
|---|---|---|---|---|
| **任务下发** | Kafka push + orch CLAIM | DB poll（`JobRepository`） | gRPC long-poll，server 推 | Executor pull/Celery push |
| **抢占机制** | 乐观锁 CAS（version+status） | 无（单 JVM） | server-side workflow lock | DB row-level + state machine |
| **租期续约** | worker 轮询 10s 显式 renew | N/A | activity heartbeat（应用层主动 record） | dag run heartbeat |
| **失败重试归属** | **orch 决策**（worker 不 retry） | step-level `RetryTemplate`（worker 内 retry） | activity-level retry policy（server 端） | DAG-level retries |
| **执行隔离** | listener 线程同步跑 + Semaphore 背压 | TaskExecutor + chunk 线程 | activity worker pool | per-task subprocess / K8s pod |
| **取消/中断** | **❌ 无**（仅 graceful drain 等任务自然结束） | `JobOperator.stop()` 有 interrupt | server-side cancel + heartbeat 检 cancel | task `on_kill` |
| **WAP/分阶段** | 5-stage 编排 + `@Transactional` 仅 COMMIT | Step + chunk + ItemProcessor，listener 切面 | workflow code 即编排 | DAG 节点显式依赖 |
| **观测 / tracing** | Micrometer + structured MDC + Kafka observation enabled | Spring Batch Admin 只读 | Temporal UI + replay debug | UI + structured log |
| **背压** | Semaphore + container.pause/resume | chunk 大小静态 | server 端 task queue 长度感知 | concurrency / pool 配额 |
| **at-least-once** | 业务 SQL `ON CONFLICT` + 无 worker 去重表 | chunk + `ItemReader.skip` | exactly-once（server 端 dedupe） | task instance pk 唯一 |

---

## 4. 细节问题 punch list

### P0（故障级，production 真会出事故）

1. **task 不可中断 / 无 timeout 强终止** ⚠️
   - **位置**：全 worker-core `grep` 无 `Future.cancel`/`InterruptedException` 业务路径
   - **现象**：worker 没有 task-level timeout/cancel 机制；`PrepareStep`/`ComputeStep` 等业务异常只能等其自然完成；plugin 无限循环或长 SQL 卡住 → orchestrator `JobInstanceTimeoutEnforcer` 标 TIMED_OUT，但 worker 线程仍被占着，`Semaphore` permit 永不释放 → **整个 worker 实例容量永久缩水**
   - **业界**：Temporal cancellation token + 心跳检；Spring Batch `JobOperator.stop()` 设 stop flag

2. **PROCESS 5-stage `traceId` 每次不同 → batchKey 不重 → reclaim 后孤儿数据无人清** ⚠️
   - **位置**：`DefaultProcessStageExecutor:120` 用 `process-{taskId}-{traceId}` 生成 batchKey
   - **现象**：`SqlTransformComputePlugin` 的 `prepare/compute/validate/feedback` 都不在事务里（仅 COMMIT 是 `@Transactional(REQUIRES_NEW)`）；如果 COMPUTE 写到一半 worker 进程被 kill，`process_staging` 留下半批数据。**仅靠 `ProcessStagingOrphanCleaner` 异步清理**；期间内若 orch reclaim 同 task 重派到别的 worker，新 worker pre-DELETE（`SqlTransformComputePlugin:121`）才清掉 — 但 traceId 每次不同，新 worker 的 batchKey 不重旧，孤儿数据无人清

3. **DLQ 写入 `future.join()` 阻塞 listener 线程** ⚠️
   - **位置**：`DeadLetterPublisher:39`
   - **现象**：在 listener 线程上同步等 Kafka producer ack；broker 抖动会让 listener 阻塞 → `max.poll.interval.ms` 超时 → consumer rebalance → **大批任务重派**
   - **业界**：DLQ 异步发送 + 失败计数告警，让消息留在 retry topic 而非阻塞主消费线程

### P1（体验级，影响可观测性 / 一致性）

4. **`maxConcurrentTasks=8` 全局硬编码且不与 V87 `max_concurrent` 联动**
   - **位置**：`AbstractTaskConsumer:69` 仅读 `batch.worker.max-concurrent-tasks`，无视租户/job 级 quota
   - **现象**：orch V87 加的租户级 max_concurrent 配额完全在 orch 派发侧执行，worker 自己不感知 → orch 派发限速 8 个，worker 拿到也只能 semaphore 8 并行，多租户竞争时高优先级租户也只占 8
   - **建议**：暴露 worker 容量到注册元数据，让 orch 派发感知；或 worker 端按租户分桶 semaphore

5. **`WorkerLeaseProperties` 形同虚设（死代码）**
   - **位置**：`config/WorkerLeaseProperties.java` + `WorkerCoreConfiguration`
   - **现象**：properties 只有一个 `renewIntervalMillis=10000`，但 `WorkerTaskLeaseRenewer` 直接 `@Scheduled(fixedDelayString="${batch.worker.lease.renew-interval-millis:10000}")` 读 string 配置，**根本没注入这个 properties bean**

6. **CLAIM 无幂等保护，重复 CLAIM 同一 taskId 时重复执行**
   - **位置**：`TaskDispatchExecutor:32`
   - **现象**：直接 CLAIM → execute；如果 Kafka 重投递（report 失败但任务已跑完），第二次 CLAIM orch 侧若任务还在 RUNNING 会拒，但若已被回收为 READY 则会再次成功 → 业务逻辑被执行两次（仅靠 `ON CONFLICT` 回退，不是所有业务都用 SqlTransformCompute）
   - **业界**：activity invocation id + server-side dedupe（Temporal）/ `idempotency-key` table（Stripe pattern）

7. **`AbstractStageExecutor` cycle guard 太宽松**
   - **位置**：`PipelineStepFlowSupport:34` `maxTransitionGuard = max(steps.size()*4, 16)`
   - **现象**：5-stage pipeline guard 是 20，但 `onFailureNextStepCode` 能任意跳转，恶意/错误配置很容易 4 跳一轮无限循环 20 次后才报错，期间 step_run 记录被疯狂写入

8. **renew/heartbeat 无熔断**
   - **位置**：`WorkerTaskLeaseRenewer:96`（注释明确说"不熔断"）
   - **现象**：orch 长时不可达时 worker 仍在跑 task，最终 report 失败丢业务结果（HTTP 重试 max-attempts 后抛异常）→ task 回 PARTIAL_FAILED 由 orch 重派，**白干一遍**
   - **业界**：Temporal activity 检测到 server 不可达就自动 cancel local 计算

9. **`HeartbeatService.beat` 与 `WorkerRuntimeFacade.heartbeat` 重复路径（迁移残留）**
   - **位置**：`AbstractWorkerLoop:94` 调 `workerRuntimeFacade.heartbeat`；`DefaultHeartbeatService:24` 提供 `beat` — 检索发现 `DefaultHeartbeatService` 在 worker 路径未被调用

10. **report 失败但业务已成功 → 进 DLQ → 运维重放 → 重复执行**
    - **位置**：`DefaultTaskExecutionWrapper.execute` finally 块外面被 `AbstractTaskConsumer.doConsume` catch
    - **现象**：业务**已执行成功**但 report 没成功 → 进 DLQ → 运维重放 → 业务**重复执行**
    - **建议**：区分"执行失败"和"execute 成功 + report 失败"两种语义，后者不该进 DLQ 而该靠 orch 端 lease 超时回收 + worker 端 outbox 表本地缓存重 report

### P2（优化级，技术债 / 防御深度）

11. **`awaitDrain` deadline 用 `System.currentTimeMillis()`**（`ActiveTaskLeaseRegistry:108`）— NTP 时钟回拨期间会让 `remaining` 变负 → 立即返回 timeout=false。应该用 `System.nanoTime()` 单调钟

12. **`WorkerRegistration.currentLoad` 永远是 0**（`DefaultHeartbeatService:34`）— 只做 null→0 回退，无人写入实际负载。orch 无法基于 worker 实际并发做 least-loaded 调度，capability_tags 也只能做静态匹配

13. **`WorkerKafkaSubscribeProperties` PATTERN 模式 regex `\.[^.]+` 过于宽松**（`AbstractTaskConsumer:352`）— 任意一段后缀都匹配，**跨租户 topic 泄漏风险**（一个 worker 可能消费别的租户的 topic）。Production 应强制 TENANT_SCOPED 模式 + allowlist

14. **`@Scheduled` 没有 worker 进程级独占** — `WorkerTaskLeaseRenewer.renewActiveTaskLeases` / `fastRetryFailedLeases` 在每个 worker JVM 都跑，无 ShedLock，多 worker 部署时 renew 调用量 = workers × in-flight tasks，10s 周期下高并发租户会**压垮 orch `/internal/tasks/.../renew` 端点**（无 batch renew API）

15. **DLQ envelope 无版本号**（`DeadLetterPublisher:30`）— 简单 map 序列化，未来 envelope schema 演进无法平滑迁移；运维 republish 工具难以做向后兼容

16. **PROCESS `FEEDBACK swallow exception`**（`FeedbackStep:38`）合理但**不该 swallow 所有 RuntimeException**，至少 `OutOfMemoryError`/`InterruptedException` 应该重抛；当前 catch RuntimeException 已经够用，但缺少"超过 N 次连续 swallow 触发降级"的保护

17. **`accepts()` 大小写归一不彻底**（`AbstractTaskConsumer:444` + `AbstractWorkerLoop:119`）— 双向归一只在源头，下游所有比较都得记得加 `IgnoreCase`，未来加新 worker 类型很容易遇到问题

---

## 5. 关键文件路径速查

| 类别 | 文件 |
|---|---|
| Consumer/路由 | `batch-worker-core/.../support/AbstractTaskConsumer.java` |
| Worker 生命周期 | `batch-worker-core/.../support/AbstractWorkerLoop.java` |
| Stage 编排 | `batch-worker-core/.../support/AbstractStageExecutor.java` |
| 派发执行器 | `batch-worker-core/.../application/TaskDispatchExecutor.java` |
| Lease 续约 | `batch-worker-core/.../infrastructure/WorkerTaskLeaseRenewer.java` |
| Drain | `batch-worker-core/.../infrastructure/GracefulKafkaShutdown.java` + `ActiveTaskLeaseRegistry.java` |
| HTTP client | `batch-worker-core/.../infrastructure/HttpTaskExecutionClient.java` |
| Execution wrapper | `batch-worker-core/.../infrastructure/DefaultTaskExecutionWrapper.java` |
| DLQ | `batch-worker-core/.../infrastructure/DeadLetterPublisher.java` |
| Kafka config | `batch-worker-core/.../config/KafkaConsumerConfiguration.java` |
| PROCESS 5-stage | `batch-worker-process/.../stage/DefaultProcessStageExecutor.java` |
| PROCESS plugin | `batch-worker-process/.../sql/SqlTransformComputePlugin.java` |
| Orch CLAIM SQL | `batch-orchestrator/src/main/resources/mapper/JobPartitionMapper.xml` |

---

## 6. 修复优先级建议

**最关键的 3 件事**：P0-1（cancellation/timeout）、P0-3（DLQ 阻塞）、P1-10（report 失败语义混淆）。前两个是 production 真会出事故的，第三个是隐蔽 data corruption 风险。

| 优先级 | 工作量 | 收益 |
|---|---|---|
| P0 全做 | ~1 天 | 闭环 worker 可中断 + DLQ 异步化 + report 本地缓存重投，消除 3 个故障级缺陷 |
| 仅 P0-1 | ~3 小时 | 单点解决"worker 容量永久缩水"问题（最常见 production 事故） |
| P1 优化 | ~1 天 | 治理 6 个一致性 / 浪费类问题，主要靠 V87 联动 + ShedLock 加固 |
| P2 治理 | ~半天 | 技术债清理，单条改动小但多 |
