# 运维巡检与自愈脚本

这里放本地和 staging 可复用的巡检、自愈和补偿入口。

## 常用入口

- `inspect-all.sh`：总巡检入口。
- `inspect-db.sh`：数据库健康、积压和 Flyway 状态巡检。
- `inspect-workers.sh`：worker 心跳、排空和任务占用巡检。
- `inspect-observability.sh`：观测栈连通性巡检。
- `trigger-compensation.sh`：触发补偿任务。
- `manage-trigger.sh`：通过 Trigger 管理 API 执行注册、暂停、恢复、排空和状态查询。

## 自愈脚本

- `heal-dead-letters.sh`
- `heal-drain-timeout.sh`
- `heal-retry-partitions.sh`
- `heal-retry-tasks.sh`
- `heal-stuck-outbox.sh`
- `heal-zombie-pipelines.sh`

`sql/` 保存巡检和自愈脚本调用的 SQL 片段，`testdata/` 保存 Alertmanager 配置生成器的样例。

## Trigger 运维边界

`manage-trigger.sh` 默认是 dry-run。真实执行必须显式设置
`BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false`，并提供非默认的 `BATCH_INTERNAL_SECRET`。
脚本只调用 `/api/triggers/management/**`，不直接更新 Quartz 表、`job_definition` 或
`trigger_misfire_pending`；业务状态变更必须经过 Trigger 服务的幂等 API。

常用操作：

```bash
bash scripts/ops/manage-trigger.sh status
bash scripts/ops/manage-trigger.sh drain-status
BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false BATCH_INTERNAL_SECRET="$SECRET" \
  bash scripts/ops/manage-trigger.sh pause-tenant "$TENANT_ID"
```

misfire 验证分为两类：`scripts/sim/24-trigger-stage6d.sh` 的 fixture fallback 只验证
pending、审批补跑和 outbox 业务闭环；真实 Quartz `triggerMisfired` 自动生成 pending 必须按
[`trigger-operations.md`](../../docs/runbook/trigger-operations.md) 的专项清单单独记录，不能用
fallback 结果代替。
