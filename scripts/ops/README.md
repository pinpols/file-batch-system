# 运维巡检与自愈脚本

这里放本地、staging、生产跳板机或临时运维 Job 可复用的巡检、自愈和补偿入口。脚本默认
按环境变量连接目标系统，不要求目标系统运行在本仓库的 Docker Compose 中。

## 常用入口

- `inspect-all.sh`：总巡检入口。
- `inspect-db.sh`：数据库健康、积压和 Flyway 状态巡检。
- `inspect-workers.sh`：worker 心跳、排空和任务占用巡检。
- `inspect-observability.sh`：观测栈连通性巡检。
- `render-alertmanager-config.sh`：Alertmanager 容器启动时校验共享 Bearer Token 并渲染本地配置。
- `inspect-dependencies.sh`：PostgreSQL / Kafka / Valkey / MinIO 基础依赖只读巡检，支持宿主机 CLI 和 Docker fallback。
- `inspect-production-capacity.sh`：生产容量治理只读巡检，覆盖 PostgreSQL 热表/保留、Kafka topic retention 和对象存储 bucket/lifecycle。
- `plan-production-retention.sh`：生产保留治理只读计划，输出 PostgreSQL 归档策略缺口、Kafka topic 保留策略、对象存储 lifecycle 和 Redis TTL 候选项；不执行清理。
- `apply-infra-governance.sh`：按内置 `config/ops-governance/{local,test,benchmark,prod}.env` 或外部 `--profile-file` 预览治理配置；Kafka topic 与 MinIO lifecycle 可显式 apply，PostgreSQL / Valkey 配置需要通过对应部署系统重启或滚动发布生效。
- `inspect-runtime-governance.sh`：只读检查内置 profile 或外部 `--profile-file` 是否覆盖 LOCAL / NAS / SFTP / OSS / API / API_PUSH / EMAIL 通道、Worker Report Outbox、Quota / ShedLock、读副本、业务分片、Quartz、观测和外部端点治理边界。
- `run-toolbox.sh`：进入独立运维工具箱，提供 `psql`、Kafka CLI、`mc`、`redis-cli` 和 Python 治理运行时；应用镜像不内置这些工具，容器以非 root 用户运行，生产执行必须使用目标组件的最小权限账号。
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

基础依赖的故障边界和生产处置见
[`docs/runbook/dependency-operations.md`](../../docs/runbook/dependency-operations.md)。
生产容量和存储增长治理见
[`docs/runbook/production-capacity-governance.md`](../../docs/runbook/production-capacity-governance.md)。

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
