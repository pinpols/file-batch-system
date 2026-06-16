# 状态机与关键枚举

## job_instance 生命周期
- `CREATED`:launch 第一阶段已提交(job_instance=CREATED),但分区/任务尚未创建。
- `RUNNING`:launch 第二阶段完成,分区/任务已建,正在执行。
- `SUCCESS` / `FAILED`:终态。
- 注意「CREATED 卡住」:若 launch 进程在 T1(写 CREATED)与 T2(建分区/任务并转 RUNNING)之间崩溃,实例会停在 CREATED、无可执行子项、Kafka lag 为零。由 `StaleCreatedLaunchRecoveryScheduler` 自动补跑 T2 恢复。

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
