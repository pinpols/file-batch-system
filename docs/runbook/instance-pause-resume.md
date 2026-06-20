# 实例暂停 / 恢复(Instance Pause / Resume)

> ADR-044 Phase A。给 `job_instance` 加可逆 `PAUSED` 态:运维可暂停一个 RUNNING 实例的**新分区派发**(在途分区自然终结,不破坏性 kill),排查后再恢复。区别于破坏性的 `cancel` / `terminate`。

## 机制

| 维度 | 说明 |
|---|---|
| 状态 | `JobInstanceStatus.PAUSED`(可逆非终态;对外生命周期投影归 RUNNING) |
| 暂停 | `RUNNING → PAUSED`:`POST /internal/instances/{id}/pause` |
| 恢复 | `PAUSED → RUNNING`:`POST /internal/instances/{id}/resume` |
| 派发跳过 | `WaitingPartitionDispatchScheduler` 的 `selectWaitingPartitionsGlobal` 加 `NOT EXISTS(父 instance PAUSED)`——PAUSED 实例的 WAITING 分区不再被派发 |
| 在途 | 已 dispatch 的分区/task **照常跑完并 REPORT**,只是不再派下一波;暂停≠冻结在途 |
| 恢复后 | 重新纳入派发;已 SUCCESS 的分区不重跑(靠既有 partition 幂等) |

## 守护

- 仅 `RUNNING` 可暂停、仅 `PAUSED` 可恢复(服务层 `allowedFrom` 集合校验,非法转换抛 `STATE_CONFLICT`)。
- 状态推进走 **version CAS**(`updateLifecycleStatus`)+ DB 层 `NotFromTerminal` 守护(终态实例不可暂停/恢复),并发改动返回 0 行 → `STATE_CONFLICT`,要求重试。
- 不动 `finished_at`(PAUSED/RUNNING 非终态);不走终态 child-reconcile。

## 边界 / 后续(Phase B)

- 本期 **仅 job_instance**。`workflow_run` 的 PAUSED(含 workflow 节点派发跳过)、console-api 的 pause/resume 控制层 + OpenAPI、`batchDaySerial` 串行闸(注:批次日串行的底座 `BatchDayGateService` + `countNonTerminalByJobCodeAndBizDate` 已存在)留 Phase B。
- 暂停**不挑机器 / 不扩缩容**(ADR-027 红线),纯实例调度状态;不与 ADR-042 的 `WAITING`(等容量、自动续)混淆——`PAUSED` 必须显式 resume。
- `archive.job_instance_archive` 不更新 CHECK:PAUSED 非终态永不进归档,且 `ArchiveSchemaDriftCheck` 只比对 column。
