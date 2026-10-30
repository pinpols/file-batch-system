# 只读运维的证据与执行边界

## OPS-1 按真实行为选入口

先读`ops/README`、入口及所有子调用，区分只读查询、本地证据文件和目标环境变更。`inspect-all`的子项可能SKIP/NOT_FOUND；整体退出0不自动证明全部巡检完成，逐项记录范围和缺失项。名字叫heal的脚本可能调用受控写API，不能在只读任务里运行。

`apply-infra-governance`的preview、显式apply及profile加载有不同边界；`compose-release verify`也生成本地覆盖文件。采集已有日志/状态与获准生成本地报告可以安排，但不能据此改配置、启动工具容器、发业务请求或执行修复。

## OPS-2 故障证据关联

按同一时间窗和构建/环境串联业务事实、Outbox、lease、Kafka、数据库等待与通知回执。日志没有错误、lag为0、无metric或rollout已完成都不能单独证明业务恢复。先缩小首个错误与影响范围，再给可验证根因和待验证假设。

保留未知外部结果，重放/force-offline/取消走当前审批和幂等API；即使脚本默认dry-run，也要核实预览是否真的无目标变更。下一步需要写入时给出精确目标与预期证据，不在本技能内部执行。

## 当前来源

- [README.md](../../../../scripts/ops/README.md)
- [inspect-all.sh](../../../../scripts/ops/inspect-all.sh)
- [apply-infra-governance.sh](../../../../scripts/ops/apply-infra-governance.sh)
- [dependency-operations.md](../../../../docs/runbook/dependency-operations.md)
- [high-risk-operations.md](../../../../docs/runbook/high-risk-operations.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
