# ADR-042 · 超容准入:硬拒默认 → 有界队列 + 背压

- **状态**:Accepted(路线图 Phase 2.3)
- **日期**:2026-06-20
- **关联**:ADR-003(T1/T2 launch 拆分)、ADR-010(trigger 异步解耦)、ADR-027(范围红线:挑 worker 不挑机器)、V89(`tenant_quota_policy.exceeded_strategy`)

## 范围边界(实施 PR 必答)

判定提问:**「这个调度策略是在挑 worker / 排队,还是在挑机器 / 扩容?」** 排队 / 准入 → 属本 ADR;扩容 / 挑节点 → 不属。

**做**:
- ✅ 超租户配额时**默认 defer(有界队列 + 背压)而非硬拒**:job_instance 留 WAITING,由既有 `WaitingPartitionDispatchScheduler` 周期重评,容量释放后 re-dispatch。
- ✅ 复用既有 `QuotaExceededStrategy`(V89)三态(`REJECT`/`QUEUE_DEFER`/`DEGRADE_PRIORITY`)+ 既有 outbox / WAITING 机制,**不新增表、不新增第 4 张同义事件表**。
- ✅ 平台默认可配:`batch.resource-scheduler.default-exceeded-strategy`(默认 `QUEUE_DEFER`)。租户显式配了策略以租户为准。

**❌ 不做(ADR-027 红线)**:
- ❌ 自动扩容 worker / 节点编排 / K8s 调度。队列满**不**触发开新机器。
- ❌ 把准入 defer 混进 `trigger_outbox_event`,或新建 `launch_backpressure_queue` 等第 4 张表。

## 背景

V89 引入 `tenant_quota_policy.exceeded_strategy`,三态齐备且 `QUEUE_DEFER` 的 defer 链路(`ResourceCheck.waitForCapacity` → partition 留 WAITING → `WaitingPartitionDispatchScheduler` re-dispatch)已完整实现。但 `QuotaExceededStrategy.from(null/blank)` 的回退是 `REJECT`——**配了 max-running 限额却未显式选策略的租户,峰值流量下被硬拒**(launch 抛 BizException、instance 置 FAILED),这正是「误拒正常请求」的来源。全局闸门(`global-max-running-jobs`)早已是 defer,只有租户级未配策略时硬拒。

## 决策

把**平台默认**超额处置从硬拒翻为有界队列:

- `ResourceSchedulerProperties.defaultExceededStrategy` 默认 `QUEUE_DEFER`。
- `DefaultConcurrencyLimiter`:租户 `exceeded_strategy` 显式配了以其为准;未配则取平台默认(而非 enum 的 `REJECT` 字典回退)。enum `from()` 的 `REJECT` 回退语义保留(字典健壮性),仅准入路径改默认。
- 想恢复旧硬拒默认:把 `batch.resource-scheduler.default-exceeded-strategy` 设回 `REJECT`。

## 影响

- 配了 max-running 但没配策略的租户:峰值流量下从「FAILED 硬拒」变为「WAITING 排队、容量释放后自动跑」——**更安全、不误杀**,符合准实时批量「宁排队不误拒」诉求。
- 显式配 `REJECT` 的租户:行为不变(仍硬拒)。
- 无新表、无新事件类型、orchestrator 仍是唯一状态主机、WAITING 重评走既有 scheduler。

## 备选(未采纳)

- 令牌桶速率限流:平滑 launch 速率,但不解决总量堆积;留作后续增强。
- 优先级负载脱落:过载保高优、脱低优;需优先级模型,复杂度高于当前诉求,`DEGRADE_PRIORITY` 已提供部分能力。
