# 状态机与关键枚举

## job_instance 生命周期
- `CREATED`:launch 第一阶段已提交(job_instance=CREATED),但分区/任务尚未创建。
- `WAITING`:已创建但受资源、依赖、下游健康或调度准入限制,暂不派发。
- `READY`:已具备派发条件,等待调度执行。
- `RUNNING`:launch 第二阶段完成,分区/任务已建,正在执行。
- `PAUSED`:可逆暂停态,停发新分区/节点,在途任务自然终结;resume 后回到运行生命周期。
- `SUCCESS` / `FAILED` / `PARTIAL_FAILED`:完成终态。`PARTIAL_FAILED` 表示部分失败,生命周期投影为失败。
- `CANCELLED`:取消终态。
- `TERMINATED`:强制终止终态。
- `SUCCESS_DRY_RUN` / `FAILED_DRY_RUN`:dry-run 演练终态,业务数据、对象存储和远端渠道应保持零副作用;指标、审计和 result_version EFFECTIVE 链路与正式任务隔离。
- 注意「CREATED 卡住」:若 launch 进程在 T1(写 CREATED)与 T2(建分区/任务并转 RUNNING)之间崩溃,实例会停在 CREATED、无可执行子项、Kafka lag 为零。由 `StaleCreatedLaunchRecoveryScheduler` 自动补跑 T2 恢复。

## partition / task 生命周期
- `job_partition`:`CREATED → WAITING → READY → RUNNING/RETRYING → SUCCESS/FAILED/CANCELLED/TERMINATED`。`RETRYING` 仍属于运行生命周期。
- `job_task`:`CREATED → READY → RUNNING → SUCCESS/FAILED/CANCELLED/TERMINATED`。Task 视角没有 WAITING,worker 能拿到任务时应已进入可领取或执行路径。
- 活跃态不是失败;排查积压时要区分 WAITING(准入未满足)、READY(可派发但未领取)、RUNNING/RETRYING(执行中)。

## workflow 生命周期
- `workflow_run`:`CREATED / RUNNING / PAUSED / SUCCESS / FAILED / TERMINATED / SUCCESS_DRY_RUN / FAILED_DRY_RUN`。
- `workflow_node_run` 没有 job_instance 的 CREATED/WAITING/CANCELLED 语义,但有 `WAITING_DEPENDENCY` 表示跨日依赖未齐,以及 `SKIPPED` 表示条件分支不命中或父失败传播跳过。
- `WAITING_DEPENDENCY` 投影为 READY 类等待态,不是 worker 正在执行。

## task / 租约(lease)
- worker CLAIM 任务后持有租约,需定期续租(renew / heartbeat);续租失败返回 409,要求 worker 重新 CLAIM 或放弃。
- 心跳同时回带 `cancelRequested`,worker 据此主动中断长任务,不必等租约超时。
- 每个分区有 `partitionInvocationId`(ADR-014):独立的 stale-worker 守护,绝不从 OTel 桥接;只有 worker 持有当前分区 invocation id 时,report 才被接受,防止过期 worker 冲正。

## prompt 门禁枚举(Console AI 自身)
- `AiPromptDecision`:`APPROVED` / `REJECTED_DISABLED`(总开关关) / `REJECTED_SAFETY`(命中安全阻断词) / `REJECTED_SCOPE`(超出 batch 平台范围)。
- `AiPromptCategory`:`PLATFORM` / `WORKFLOW` / `FILE_GOVERNANCE` / `OPERATIONS` / `OUT_OF_SCOPE`。

## 补偿与对账
- 补偿命令(compensation_command)在「命令插入」与「终态更新」之间若遇 JVM 崩溃会遗留为 RUNNING,由 `StaleCompensationCommandReconciler` 对账修复。
- trigger_request 滞留 ACCEPTED(已建 job_instance 但未标 LAUNCHED)由 `TriggerRequestLaunchReconciler`(ADR-010)下一轮自愈。

## 死信(dead letter)
- 消费失败累计到上限的消息进入死信表;运维可通过控制台/脚本查看、重投或清理。
- 负向测试用例(故意失败)会刷死信,属预期,不要误判为 Kafka 或环境问题。
