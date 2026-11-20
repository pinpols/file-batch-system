# 批次日结算栅栏共享锁改造方案（2026-11-20）

## 一、背景

单租户 launch 吞吐在 11–12 run/s 附近封顶，两轮对照排除了"入口并发不足"与"数据库容量不足"：

| 证据 | 数值 |
|---|---|
| 1w @25 rps（12 分区 / 6 consumer） | 10,000/10,000 收敛，`job_instance` avg 4.09 s / p95 15.33 s，PG 3.2/8 核 |
| 100k @25 rps（槽位 8） | launch 11.4 → 4.0 run/s 退化，PG <6/8 核、Worker 空闲 |
| 24 分区 / 12 consumer（随机 key） | launch 3.3 run/s，advisory 等待 31 |
| 按业务日分区 + 24/12 | launch 5.9 run/s，`job_instance` avg 87.7 s |

`pg_stat_statements` 画像（1,000 次 launch）显示：批次日结算栅栏查询调用 1,000 次、总耗时 417 s、均值 417 ms、最大 15.9 s、标准差 1.34 s。等待来自**同业务日事务串行**，不是查询本身。

## 二、栅栏的现状

`batch.acquire_batch_day_settlement_fence(tenant, calendar, biz_date)`（V268）对业务日取 `pg_advisory_xact_lock`，持有到事务提交。当前使用者分两类：

| 调用方 | 对 `day_status` 的操作 | 需要的语义 |
|---|---|---|
| Trigger 准入：`TriggerRequestPersistence` + `trigger_request` BEFORE INSERT 触发器（V326 为最新定义） | 只读，检查是否已关闭 | 共享 |
| 节点派发：`DefaultWorkflowNodeDispatchService.java:134` | 只读 run 状态，改节点/运行行 | 共享 |
| Worker 回报：`WorkflowRunReportLock.java:43` | 锁 `workflow_run` 行后写状态 | 共享 |
| 预期登记：`ExpectedWorkRegistrationService`、`FileGroupExpectationService`、`ExternalEventExpectationService`、`BundleArrivalLauncher` | 只读 + `insertIfAbsent` | 共享 |
| 开日：`BatchDayOpenService` | 创建 / OPEN / SKIPPED | 独占 |
| 人工操作：`BatchDayOperationService` | 状态迁移 | 独占 |
| 结算：`BatchDaySettleScheduler`（`claimSettling` / `finalizeSettling`） | CUTOFF→IN_FLIGHT→SETTLING→终态 | 独占 |

## 三、正确性论证

要保护的不变量：**结算提交时，所有在结算前通过准入/派发/回报的事务都已提交且看到结算前状态；结算之后进入的事务看到终态并被拒绝或跳过。**

- 共享锁之间不互斥：准入、派发、回报可在同一业务日并发，这正是当前被排他锁串行掉的部分。
- 共享与结算的独占互斥：任何读者都无法跨过结算窗口，不变量成立。
- 读者之间没有互斥需求：准入去重靠唯一索引；派发/回报靠 `workflow_run` 行锁；预期登记靠 `insertIfAbsent` 唯一约束。

必须满足的前置条件：

1. 任何事务不得"先共享后升级独占"（会与另一个共享持有者形成锁升级死锁）。开日/人工操作/结算直接取独占。
2. 分类必须完整：漏掉一个写者会让它在结算窗口内与读者并发，破坏一致性。
3. 触发器函数只在 V269/V273/V275/V286/V326 中定义（同一个函数被反复 `CREATE OR REPLACE`），最新版本是 V326。

## 四、实施方案

### 4.1 数据库（V387）

- 新增 `batch.acquire_batch_day_settlement_fence_shared(tenant, calendar, biz_date)`，内部使用 `pg_advisory_xact_lock_shared`，key 与独占版完全一致。
- `CREATE OR REPLACE` `batch.guard_trigger_request_batch_day_admission()`：函数体与 V326 相同，仅把栅栏调用替换为共享版。
- 保留原独占函数给开日、人工操作与结算使用。

### 4.2 Java 调用点（第一批）

| 位置 | 改动 |
|---|---|
| `BatchDayInstanceMapper` + XML | 新增 `acquireSettlementFenceShared` |
| `WorkflowRunReportLock` | 改用共享 |
| `DefaultWorkflowNodeDispatchService` | 改用共享 |
| `TriggerRequestMapper.acquireBatchDaySettlementFence`（XML 内联 SQL） | 改为调用共享函数 |

### 4.3 第二批（可延后）

预期登记与 Bundle 到达路径改共享；它们频率低，先保持独占不影响主路径收益。

### 4.4 测试

- 新增并发集成测试：
  1. 两个共享持有者不互斥；
  2. 结算的独占请求等待共享持有者提交；
  3. 结算提交后，准入在共享锁上等待并看到终态被拒。
- 更新现有断言：`JobNodeDispatchIntegrationTest` 等测试中等待 `acquire_batch_day_settlement_fence` 的用例需要区分共享/独占。

### 4.5 验收

- 迁移在全新库与既有库均可应用。
- 1w 容量复测：预期 launch 20+ run/s、`job_instance` avg 回到 5 s 内、栅栏 SQL 均值降到毫秒级。
- Full CI + 相关集成测试通过。

## 五、风险与回滚

| 风险 | 缓解 |
|---|---|
| 分类遗漏写者 → 结算一致性破坏 | 上表逐点核对；补三个并发场景测试；Code Review 以"是否修改 day_status/结算统计"为判据 |
| 锁升级死锁 | 禁止共享后取独占；开日/操作/结算入口直接独占 |
| 结算等待变长 | 结算频率低；窗口内读者排队是预期行为，可接受 |
| 测试断言依赖排他语义 | 明确更新，避免用"阻塞"断言误伤 |
| 回滚 | 新增迁移把触发器函数换回独占版；Java 调用点切回 `acquireSettlementFence` |

## 六、边界

- 本方案只改变锁模式，不改事务边界、消息顺序与业务状态机。
- 收益在本地 8 核单机验证；staging/生产结论仍需独立环境复测。
- 与"控制面分片"无关：分片不改善单租户吞吐，本方案才是单租户吞吐的直接杠杆。

## 七、实施结果（2026-11-20）

代码与迁移已落地：`db/migration/V387__batch_day_fence_shared_readers.sql`、`BatchDayInstanceMapper.acquireSettlementFenceShared`、`WorkflowRunReportLock`、`DefaultWorkflowNodeDispatchService`、`TriggerRequestMapper.acquireBatchDaySettlementFence`。开日、人工操作与结算保持独占。

### 12 分区 / 6 consumer（10,000 请求 @ 25 rps，槽位 8）

| 指标 | 改造前（Tier 0.6） | 改造后 |
|---|---|---|
| launch HTTP p95 | 2,408–2,799 ms | **457 ms** |
| `job_instance` avg / p95 | 4.09 / 15.33 s | **4.71 / 11.96 s** |
| 栅栏 SQL 均值 | 417 ms（最大 15.9 s） | **2.5 ms（最大 0.46 s）** |
| 收敛 | 10,000/10,000 | 10,000/10,000，0 KO |

launch 事务各阶段合计约 0.88 s（validation 96、prepare 206、dispatch 321、materialize 130、transition 65、resource 48、plan 16 ms），与改造前持平——栅栏等待被消除，但事务本体成本仍在。

### 24 分区 / 12 consumer（同一批 10,000 请求）

| 指标 | 结果 |
|---|---|
| launch 速率 | 5.0 run/s |
| 同时 RUNNING | 684 |
| `job_instance` avg | 91.5 s |
| `job_task` READY 积压 | 640 |
| 栅栏 SQL 均值 | 7.2 ms（不再是瓶颈） |
| PG CPU / 等待 | 4.3/8 核，无持续锁等待 |

结论：**共享锁解决的是锁等待，不是吞吐上限。** 栅栏放开后，高并发下的瓶颈转移到单 Worker 的 claim 领取能力（READY 640、Worker CPU 仅 50%）。在 Worker 扩容或提高有效 claim 批量之前，benchmark 保持 12 分区 / 每实例 6 consumer（`benchmark.yml` 已回滚并注明原因）。

### 测试结果

新增 `BatchDayFenceLockModeIntegrationTest`（真实 PostgreSQL，3/3 通过）：

1. 两个共享读者在同一业务日并发持锁，第二个不阻塞；
2. 结算的独占请求等待共享读者提交后才继续；
3. 结算窗口内共享读者排队，提交后才能继续。

### 后续

- 下一杠杆：Worker claim 容量（多 Worker 实例或提高 `batch-claim` 有效批量）。
- 2026-11-20 追加对照：把 Worker 的 claim/report 轮询分摊到两个 Orchestrator（`endpointCount=2`）后，24/12 仍只有约 2.0 run/s；两个 Orchestrator 的 CPU 已均衡到 62%/64%，但 `pg_stat_activity` 出现 22 个 `LWLock: LockManager` 等待、Worker 8 分钟内重试 30 次。说明 24/12 的瓶颈不是单一 Orchestrator 的 claim 负载，而是高并发下的热点行/缓冲区争用；继续扩 Worker 前需要先定位热点行（`job_partition` / `trigger_request` 更新）。实验配置已回滚。
- 结算独占语义未变；新增的共享函数不影响开日、人工操作与结算路径。
