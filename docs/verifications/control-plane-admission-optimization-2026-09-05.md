# 控制面准入与排空优化复核 - 2026-09-05

## 背景与结论

10 万请求压力过程中，`default-tenant` 的 `fair_share_group=core`、共享上限 6 和
`exceeded_strategy=REJECT` 主导了失败结果；这验证的是业务配额，而不是控制面容量。此前
WAITING 队列每 10 秒扫描 100 条，在容量释放后还会产生最长一个轮询周期的空窗。

本轮只实施不改变状态机和跨实例幂等语义的优化。公平组最终准入仍由 PostgreSQL
事务级 advisory lock 守护，未替换为本地缓存或非原子计数。

## 六项处置

| 项 | 处置 | 状态 |
|---|---|---|
| 1. 容量与业务配额分离 | `run-p2-capacity-profile.sh` 默认准备并清理 `p2capacity` 临时租户，策略无 tenant/group 并发上限；原始 `default-tenant` 配额不再参与容量结论 | 已实施 |
| 2. 公平组准入临界区 | WAITING 候选排序只读共享组活跃数；真正 `WAITING -> READY/RUNNING` 的 `REQUIRES_NEW` 事务继续持 advisory lock 并复核 | 已实施 |
| 3. 容量释放后排空 | task 非重试终态在提交后发布事件，`WaitingPartitionDispatchKick` 用 250ms 合并调度一次；10 秒周期扫描仍是跨实例和遗漏事件兜底 | 已实施 |
| 4. Trigger 自适应准入 | 增加可选 AIMD 本地闸门，默认关闭：慢于阈值时减半，正常完成时每请求恢复一个许可 | 已实施，待灰度开启 |
| 5. 执行面扩容 | Helm 生产 overlay 已为五类 worker 配置 HPA；Kafka lag KEDA 模板和示例已存在。生产启用 KEDA 前仍需确认 operator、topic 分区数和每 worker 资源画像 | 已核查，不改副本策略 |
| 6. Quartz 长事务 | Trigger 的 Quartz JobStore 已独立使用 10 分钟 idle-in-transaction 上限；现有 fire/执行耗时/misfire/活跃 trigger 指标可定位。没有证据表明应缩短超时或改变 Quartz 锁策略 | 已核查，补充运行准则 |

## 配置与观测

- `BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_KICK_ENABLED=true`
- `BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_KICK_DELAY_MILLIS=250`
- `BATCH_TRIGGER_API_LAUNCH_ADAPTIVE_ENABLED=false`
- `BATCH_TRIGGER_API_LAUNCH_MIN_CONCURRENCY=16`
- `BATCH_TRIGGER_API_LAUNCH_SLOW_REQUEST_THRESHOLD_MILLIS=1000`

新增指标：

- `batch.scheduler.waiting_dispatch.kick.requested`
- `batch.scheduler.waiting_dispatch.kick.coalesced`
- `batch.scheduler.waiting_dispatch.kick.executed`
- `batch.trigger.api_launch.admission.active`
- `batch.trigger.api_launch.admission.limit`
- `batch.trigger.api_launch.admission.rejected`

Quartz 排查时先关联 `batch.trigger.quartz.execution.duration`、
`batch.trigger.quartz.misfire.total` 与 PostgreSQL `pg_stat_activity`。持续超过 60 秒的
`idle in transaction` 应保留 application name、query、wait event 和 xact age，再决定是否处理
具体 Quartz 锁竞争；不能仅因出现一次连接而全局收紧 Trigger 的 10 分钟保护。

## 验证

- 定向单测：公平组锁、等待队列合并唤醒、任务 outcome、Trigger 自适应闸门均通过。
- `bash -n load-tests/scripts/run-p2-capacity-profile.sh` 通过。
- 仍需在 Docker/预发运行新的 `RUN_10W_STORM=1 RUN_FAIRNESS=0`，确认 `p2capacity` 真实路由到
  专用压测 worker 或隔离的本地 fallback，且报告中没有 `FAIR_SHARE_GROUP_JOB_LIMIT`。
