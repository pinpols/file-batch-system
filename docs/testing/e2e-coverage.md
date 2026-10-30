# E2E 场景与代码定位

本页是持续维护的场景索引。列出的源码存在只说明覆盖入口；本轮是否执行、是否通过以及目标环境边界，应查看实际测试报告和[Workflow验收矩阵](../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)。不从旧文件数量或早期“全通过”判断当前覆盖。

| 场景 | 代码入口 | 验证重点 |
|---|---|---|
| 导入 | [ImportPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ImportPipelineE2eIT.java) | 文件校验、解析、落库与行级对账 |
| 导出 | [ExportPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ExportPipelineE2eIT.java) | 数据查询、文件生成、存储及产物内容 |
| 加工 | [ProcessPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ProcessPipelineE2eIT.java) | 暂存、校验、发布与业务结果 |
| 分发 | [DispatchPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DispatchPipelineE2eIT.java) | 渠道发送、回执和终态 |
| 跨租户 | [MultiTenantConcurrentE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/MultiTenantConcurrentE2eIT.java) | 并发租户的身份、数据与状态隔离 |
| Outbox重投 | [OutboxForwarderRetryE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/OutboxForwarderRetryE2eIT.java) | 发布失败、重试与幂等 |
| 死信审批恢复 | [DeadLetterApprovalReplayE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DeadLetterApprovalReplayE2eIT.java) | 权限、审批、恢复与结果 |
| Worker排空 | [WorkerDrainE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerDrainE2eIT.java) | 停止接收、在途完成/回收及状态 |
| 进程崩溃恢复 | [LaunchProcessCrashRecoveryE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/LaunchProcessCrashRecoveryE2eIT.java) | 触发/实例创建窗口及重启收敛 |
| 外部效果未知 | [WorkerReportLostAfterBusinessCommitE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java) | 业务提交后响应丢失、未知结果与对账 |
| 补跑审批 | [WorkflowOccurrenceCatchUpApprovalE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java) | Workflow目标、occurrence身份和审批 |
| Workflow通知 | [WorkflowTerminalNotificationE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowTerminalNotificationE2eIT.java) | 终态通知与独立运行身份 |

Workflow直接 START→END、WAIT跳过、网关、批次日义务、分区/任务CAS与RLS等细粒度情况由对应单元/真实数据库集成测试补齐，按[全项目测试计划](full-project-test-plan.md)选择层级。图形/HTTP成功不能替代业务数据、外部副作用与持久化身份断言。

共享fixture、执行命令和发布证据分别见[测试说明](README.md)、[发布门禁](release-gate.md)、[SIM可靠性矩阵](backend-sim-reliability.md)及[验证记录](../verifications/README.md)。新用例先复用已有链路和fixture，再补当前风险，不维护另一套历史覆盖百分比。
