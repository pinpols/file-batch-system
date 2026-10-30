# 调度身份、时间与恢复核对

## SCH-1 固定发生身份与业务日期

区分计划fire时点、实际回调时点、租户业务日、日历调整和当前时间；Occurrence/幂等键及重试恢复不能每次按now重算。用相同业务输入跨JVM时区、DST gap/overlap、午夜和cutoff验证，显式记录日历/版本快照。

## SCH-2 持久化义务与控制节点

按当前Workflow/业务日模型追义务登记、节点准入、JobInstance创建和Worker分发；不能从旧“开日预建实例”描述倒推实现。START→END直接终结、WAIT跳过、readiness延期及暂停恢复分别检查，不把END当旧任务派发。

## SCH-3 故障恢复窗口

分别验证真正Quartz misfire回调和fixture补pending；持久化游标前后崩溃、重复恢复、旧leader/版本及暂停后重启追到请求与实例结果。锁持有/释放、事务提交与重试代理沿真实调用时间线确认，不凭注解排列就批量添加Order或延长锁。

## 当前来源

- [trigger-operations.md](../../../../docs/runbook/trigger-operations.md)
- [core-model.md](../../../../docs/architecture/core-model.md)
- [workflow-dependency-guide.md](../../../../docs/architecture/workflow-dependency-guide.md)
- [time-date-semantics.md](../../../../docs/design/time-date-semantics.md)
- [workflow-daily-integration-acceptance-matrix-2026-10-14.md](../../../../docs/plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
