# 双数据库、WAL和业务恢复验证

## DR-1 恢复窗口不止单库行数

为platform/business数据库、对象文件、Kafka位点、Outbox/ledger/receipt明确一致性时点和拥有权。独立dump不自动是跨库原子快照；检查恢复后的任务、业务副作用、去重记录和外部交付，防止控制面回到较旧时间后重放已执行业务。

## DR-2 有WAL配置不等于可PITR

核对真实base、连续WAL、独立故障域归档、权限/版本、恢复目标和应用可服务时点；archive_timeout只是切换条件，不保证归档成功或RPO。分别量逻辑恢复、物理PITR、failover和Worker接管，不能用旁路库耗时作生产承诺。

## DR-3 失败保留与清理边界

从脚本检查旁路/原位模式、实际停止组件、恢复命令注入及退出清理；占位RESTORE_CMD不是真演练。验证前固定测试拥有的数据/容器和终止条件，不在失败循环中重复破坏性恢复；完成后检查服务状态、残留、备份与证据保留。

## 当前来源

- [backup-and-pitr.md](../../../../docs/runbook/backup-and-pitr.md)
- [ha-readiness.md](../../../../docs/runbook/ha-readiness.md)
- [dr-drill.sh](../../../../scripts/db/backup/dr-drill.sh)
- [dr-drill-pitr.sh](../../../../scripts/sim/dr-drill-pitr.sh)
- [dr-drill-fleet-crash.sh](../../../../scripts/sim/dr-drill-fleet-crash.sh)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
