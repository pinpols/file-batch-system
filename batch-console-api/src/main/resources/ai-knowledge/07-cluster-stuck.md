# 集群 stuck 处置:症状 → 诊断字段 → 处理

当用户报「任务卡住 / 不推进 / 定时任务不跑 / worker 失联 / 事件积压」这类集群面问题时,
先调用只读工具 `getClusterDiagnostics` 拿到当前租户的集群健康快照(固定阈值),再据此判断卡点在哪一层。
诊断分四块:`shedLock`(定时任务租约)、`workers`(worker 注册一致性)、`outbox`(事件投递)、`terminalChildren`(终态遗留子项)。
它是**只读快照**,只用于判断和给建议,处置动作走各自受控入口,AI 不代执行、不写库。

## ShedLock 定时任务租约(shedLock)
- 字段:`totalLocks`、`activeLocks`(lockUntil 非空计为活跃)。
- 症状:某类定时任务(如自愈调度器、对账)整体不跑。
- 排查:活跃锁长期被同一节点持有、或锁未释放,可能是持锁节点崩溃后锁未过期。看对应 `lockedBy` / `lockUntil`。
- 建议:等锁到期自动释放;确认持锁节点是否存活;不要手动删锁除非明确知道持锁进程已死。

## Worker 注册一致性(workers)
- 字段:`onlineWorkers` / `drainingWorkers` / `offlineWorkers`、`staleOnlineWorkers`(心跳超 120s 仍标 ONLINE)、
  `drainingPastDeadlineWorkers`、`decommissionedWorkersWithActiveTasks`、`invalidCapabilityTags`、`runningInstances`、`healthy`。
- 症状:任务卡在 WAITING/READY 无人认领,或分配给了已失联 worker。
- 排查:
  - `onlineWorkers=0` 且 `runningInstances>0` → 没有在线 worker 可执行,任务自然不推进。
  - `staleOnlineWorkers>0` → 有 worker 心跳失联但仍标 ONLINE,任务可能被分给"僵尸"worker。
  - `decommissionedWorkersWithActiveTasks>0` → 已下线 worker 仍挂活跃任务,租约超时后由 orchestrator 回收/改派。
  - `invalidCapabilityTags>0` → 能力标签配置有误,可能导致选不到合适 worker。
- 建议:启动/恢复对应 workerGroup 的 worker;等 lease 超时回收后重试;必要时核对 job_definition.worker_group 与能力标签。

## Outbox 事件投递(outbox)
- 字段:`pendingEvents`、`activeEvents`、`stalePublishingEvents`(PUBLISHING 超 120s)、`deliveryStats`、`healthy`(pending<1000 且无 stalePublishing)。
- 症状:状态变了但下游/通知没收到,事件积压。
- 排查:`pendingEvents` 大量堆积或 `stalePublishingEvents>0` → Kafka 不可用或投递卡住;看 deliveryStats 分布。
- 建议:经 `ConsoleOrchestratorProxyService` → orchestrator `/internal/outbox/*` 做受控 republish;
  **console-api 不能直接 UPDATE/DELETE outbox_event**;运维脚本 `scripts/ops/heal-stuck-outbox.sh`。

## 终态遗留活跃子项(terminalChildren)
- 字段:`terminalInstancesWithActiveChildren`、`healthy`。
- 症状:实例已终态(SUCCESS/FAILED 等)但仍有活跃分区/任务,状态机不一致。
- 排查:`>0` 表示存在终态实例挂着活跃子项,优先看 orchestrator 终态推进日志。
- 建议:必要时通过受控恢复接口处理子节点,不直接改库。

## 单实例级卡点(更细)
- 针对某个具体实例卡住,`ConsoleClusterDiagnosticService.instanceDiagnosis` 会给到分区/任务/outbox 状态计数 + findings
  (如 INSTANCE_HAS_NO_CHILDREN / TERMINAL_INSTANCE_HAS_ACTIVE_CHILDREN / NO_ONLINE_WORKER_FOR_GROUP / OUTBOX_EVENTS_NOT_TERMINAL)。
- 助手侧先用 `getJobInstance` + `getJobExecutionLogs` 定位,再结合集群诊断给建议。

## 边界
- 集群诊断是**判断依据**,不是自动修复;所有处置(republish / 恢复 / RERUN / 起 worker)都走各自受控入口。
- AI 只解读诊断 + 给受控建议,绝不代执行、不写库、不碰 orchestrator/worker 主链。
