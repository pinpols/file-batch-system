# ADR-046 Phase 2 · 切片 2.3 施工方案 —— worker-core 消费端攒批

> 状态:**待施工**(代码留给有全栈压测环境时做;2.0/2.1/2.2/2.4 已落 main)。
> 包名以重命名后为准:`io.github.pinpols.batch.*`。
> 前置:orchestrator 侧 `POST /internal/tasks/claim-batch` / `report-batch` 已在 main(#683/#684)。

## 目标与护栏
- 把 worker 消费从「1 Kafka record = 1 task = 1 claim + 1 report」改成「攒 K 条 → **一次** claim-batch → 逐 partition 独立执行 → **一次** report-batch」,控制面往返 O(N)→O(N/K)。
- **双路径 + 默认关**:worker 侧 flag 关 → 现有 per-record 路径**一字不动**;开 → 批路径。
- partition 执行语义/幂等/lease/DLQ/背压**逐项保持**;**无束状态机、无 worker 束循环**。
- **生产启用前必须全栈压测**(本地无法验证):上万 fan-out 对照基线证明 claim/report 争用实降且零正确性回归,再开 flag。

## 改动清单(逐文件)

### 1. worker 侧 flag(新增)
`batch-worker/core/.../config/WorkerBatchClaimProperties.java`(仿现有 `batch.worker.*` props):
- `@ConfigurationProperties(prefix = "batch.worker.batch-claim")`
- `boolean enabled = false`(总开关,默认关)
- `int maxBatchSize = 32`(攒批上限 K;须 ≤ orchestrator 侧 `batch.task.batch-claim.maxBatchSize`)
- `Duration window = 50ms`(攒批等待窗口,Kafka `max.poll.records` 也要 ≥ K)
- per-job 覆盖可选(对齐 orchestrator 侧 `jobOverrides`)

### 2. HTTP client 批方法(`HttpTaskExecutionClient` + 接口 `TaskExecutionClient`)
**照 `renewLeasesBatch` 模板**(已有:chunk + 退避 + 响应长度校验 + 降级单条),新增:
- `List<ClaimBatchResult> claimBatch(List<ClaimBatchItem> items)` → `POST /internal/tasks/claim-batch`
  - 内嵌 worker-local record:`ClaimBatchHttpRequest(items)` / `ClaimBatchHttpItem(tenantId,taskId,workerId,partitionInvocationId)` / `ClaimBatchHttpResponse(results)` / `ClaimBatchHttpResult(taskId,claimed,config)`
  - chunk 按 `maxBatchSize`;404/400 → 降级逐条 `claim`;5xx/网络 → 退避重试后降级;响应项数不匹配 → 降级
- `List<ReportBatchResult> reportBatch(List<TaskExecutionReport> reports)` → `POST /internal/tasks/report-batch`
  - 同款 chunk/降级;**注意** report 现有 outbox 协调器(`WorkerReportOutboxCoordinator`)路径:批模式下要么逐项仍走 outbox 协调、要么批量直发——**优先批量直发 HTTP,失败降级单条 `report`(单条已含 outbox 兜底)**,避免双写语义漂移。
- 复用现有 `RetryState` / `currentTraceId()` / tenant+trace header。

### 3. 执行器批路径(`TaskDispatchExecutor`)
新增 `List<WorkerExecutionResult> executeBatch(List<TaskDispatchMessage> messages, String workerId)`:
1. `claimBatch` 领 K(返回逐项 claimed + config);
2. 仅对 claimed=true 的:**逐个**走现有 `workerRuntimeFacade.execute(task)`(per-partition try/catch、checkpoint、RLS、MDC 全不变);
3. 收集每项 report → `reportBatch` 一次上报;
4. 返回逐项结果(claimed=false / 执行失败 / report 失败 各自标记)。
- **claimed=false 的项**:不执行(被抢/不可领),对应 Kafka record 视为已处理(commit),与单路径「claim 4xx → 跳过」一致。

### 4. 消费端批 listener(`AbstractTaskConsumer` + 子类 + `KafkaConsumerConfiguration`)
- `KafkaConsumerConfiguration`:新增一个 **batch 模式** container factory(`factory.setBatchListener(true)`),仅当 flag 开时子类用它;flag 关用现有 record factory。
- `AbstractTaskConsumer`:加 `protected boolean doConsumeBatch(List<String> payloads)`:
  - **背压**:`semaphore.tryAcquire(n)` 一次取 n=batch.size() 个 permit;取不满则只处理取到的、其余留给重投(或 pause)。
  - 逐条 `JsonUtils.fromJson` + `accepts` 过滤 → 组成 messages;
  - `RlsTenantContextHolder`:批内可能跨 tenant → **按 tenant 分组**分别 `runWithTenant` 调 `executeBatch`(RLS holder 是单 tenant 语义,不能整批混绑);
  - 调 `executeBatch`;
  - **offset 语义**:全部项成功/已处理 → 返回 true(commit 整批);**任一 transient(5xx/网络)** → 返回 false(不 commit,整批重投,靠幂等去重已成功项);**fatal/4xx 项** → 该项进 DLQ,其余正常,整批 commit。
  - permit `finally` 释放 n 个(对齐单路径的泄漏修复教训)。
- 子类(Atomic/Dispatch/Export/Import/Process `*TaskConsumer`):各加一个 batch `@KafkaListener` 方法(`List<String> payloads` → `doConsumeBatch`),用 batch factory + 同 listenerId 规则;flag 关时不激活(或同方法内 size==1 走单路径)。

### 5. 观测(复用 2.4)
worker 侧补:`batch.worker.batch_consume.size` 直方图 + 攒批命中率;orchestrator 侧 2.4 指标已就绪。

## 至少一次 / 正确性要点(评审必看)
- **offset 仅在 report-batch 确认后提交**;批内任一 transient → 整批不 commit 重投,已成功项靠 partition 幂等(claim CAS + report 幂等)安全去重。
- **批内部分失败**:claimed=false / fatal 项独立处理(跳过 / DLQ),不拖累其余项,不整批回滚。
- **跨 tenant 批**:RLS 按 tenant 分组绑定,严禁整批单 tenant 混绑(否则 biz DS RLS 串租户)。
- `<2` 条自动回退单路径(零额外开销)。
- maxBatchSize 必须 ≤ orchestrator 侧上限(否则 claim-batch/report-batch 被 4xx 拒)。

## 测试 + 验收门
- **单测**:client `claimBatch`/`reportBatch`(mock HTTP:正常/404 降级/5xx 重试降级/响应不匹配降级);`executeBatch`(claimed 混合 + 执行失败 + report 失败逐项);`doConsumeBatch`(背压取不满 / 跨 tenant 分组 / transient 不 commit / fatal 进 DLQ)。
- **集成**:`batch-e2e-tests` 新增一个 BUNDLE 多 partition 作业,flag 开,断言一次 poll 领/报多 partition + 终态正确。
- **压测验收门(硬性)**:上万 fan-out,对照 2.0 基线(`load-tests/run-control-plane-worker-benchmark.sh`)证明 claim/report 往返与锁争用实降、零正确性回归;**达标才允许在生产开 flag**。

## 分步建议(逐 PR,可回退)
1. **2.3a**:client `claimBatch`+`reportBatch`(+接口+DTO+单测)——纯加法,不接 listener。
2. **2.3b**:`executeBatch`(+单测)。
3. **2.3c**:batch factory + `doConsumeBatch` + 子类 batch listener + worker flag(默认关)。
4. **2.3d**:e2e + 压测验收 → 达标后才开 flag。
