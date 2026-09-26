---
name: disaster-recovery-validation
description: 用户要求评估或演练备份、PITR、数据库/Worker 故障恢复、RPO/RTO 或恢复后业务收敛时使用。区分本地逻辑恢复、故障注入和真实 staging 灾备证据。
---

# 灾备与恢复验证

## 先定恢复目标与风险边界

- 阅读 `docs/runbook/backup-and-pitr.md`、`docs/runbook/go-live-readiness.md` 和对应脚本；明确目标环境、恢复点、RPO/RTO、两套数据库和 Kafka/outbox 的恢复边界。
- 默认只在隔离环境或 staging 演练。任何 `--in-place`、DROP/重建、恢复命令或会影响现有服务的故障注入，必须先确认目标环境和用户授权；严禁把本地脚本未经审查直接用于生产。
- 复制副本不等于备份；逻辑恢复、物理 base + WAL PITR、HA failover 和 Worker 故障重投是不同场景，不能相互代替。

## 演练与恢复后验证

- 演练前记录数据/实例基线、恢复目标时间、镜像与 PostgreSQL 版本、备份来源和当前运行 revision。确认恢复路径及秘密注入方式，不在命令行或日志泄露凭据。
- 本地逻辑恢复按 Runbook 使用 `bash scripts/db/backup/dr-drill.sh`；实际 PITR 使用 `scripts/sim/dr-drill-pitr.sh` 并配置明确的 `RESTORE_CMD`、目标时间和 RTO 预算。Worker 全队崩溃恢复使用 `scripts/sim/dr-drill-fleet-crash.sh`。先读脚本参数和副作用，再运行。
- 恢复后检查 platform 与 business 数据、Flyway 状态、RLS/权限、关键行数/指纹、重复实例、未终结任务、outbox 与重投收敛；需要应用恢复时验证健康状态和真实读写链路。
- 分别记录实测 RPO、RTO、数据一致性、恢复动作与失败点。逻辑 dump 的本地恢复耗时不能代表生产 PITR 的 RTO；脚本通过也不等于生产备份链路已验证。
- 收尾确认脚本恢复了被停止的服务、删除了哪些旁路数据、保留了哪些证据；若失败，停止破坏性重试并保留现场供诊断。

## 参考入口

- `docs/runbook/backup-and-pitr.md`
- `docs/runbook/go-live-readiness.md`
- `docs/runbook/ha-readiness.md`
- `scripts/db/backup/dr-drill.sh`
- `scripts/sim/dr-drill-pitr.sh`、`scripts/sim/dr-drill-fleet-crash.sh`
- 涉及完整本地验收时同时使用 `acceptance-validation`；涉及数据库 DDL 使用 `database-migration-safety`。
