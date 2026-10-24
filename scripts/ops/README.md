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

## 自愈与诊断脚本

- `inspect-dead-letters.sh`：只读检查 NEW 死信；人工重放经 Console 审批，UNKNOWN 需核实未执行并一次性恢复。
- `heal-drain-timeout.sh`
- `heal-retry-partitions.sh`
- `heal-retry-tasks.sh`
- `heal-stuck-outbox.sh`：只读诊断 stale Outbox；状态处置经 Orchestrator 调度/审计接口。
- `inspect-stale-pipelines.sh`：只读检查 stale RUNNING pipeline；Orchestrator stale sweep 负责 Pipeline 和步骤终态转换。

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

## 证据采集与升级结果检查

- [运行证据采集](capture-runtime-evidence.sh)：只读访问服务、Collector 与 Prometheus 端点。通过 CONSOLE_URL、ORCHESTRATOR_URL、OTEL_COLLECTOR_METRICS_URL、PROMETHEUS_URL 指定目标，通过 EVIDENCE_DIR 指定本地产物目录；不启停服务。
- [Worker 升级结果采集](verify-worker-rolling-upgrade.sh)：配置 NAMESPACE、逗号分隔的 WORKER_DEPLOYMENTS 和可选 KUBE_CONTEXT，采集 rollout、Pod 与事件证据。脚本只读；任务接管、Kafka lag 与业务对账需按运行手册继续验收，不把 rollout 完成当作业务恢复证明。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [am-migration-smoke.sh](am-migration-smoke.sh) | Alertmanager 迁移全链 smoke(迁移方案 §7.1)。分两层:。 |
| [apply-infra-governance.sh](apply-infra-governance.sh) | 基础设施治理 profile 应用入口。默认只预览；可选择下发 Kafka topic 与 MinIO lifecycle。 |
| [env.sh](env.sh) | ops 脚本公共变量。默认只读本地 env,生产/远端请显式传 PG* / URL / TOKEN。 |
| [gen-alertmanager-config-test.sh](gen-alertmanager-config-test.sh) | 契约防漂移 snapshot 测试(迁移方案 §7.2):给定固定 alert_routing_config 行 → 固定 alertmanager.yml 输出。 |
| [gen-alertmanager-config.py](gen-alertmanager-config.py) | Render alertmanager.yml route tree from batch.alert_routing_config (迁移方案 §6.4).。 |
| [heal-drain-timeout.sh](heal-drain-timeout.sh) | 自愈：对排空超时的 Worker 执行 force-offline。 |
| [heal-retry-partitions.sh](heal-retry-partitions.sh) | 自愈：按指定条件重放 job_partition（分区重放/重试回放）。 |
| [heal-retry-tasks.sh](heal-retry-tasks.sh) | 自愈：按指定条件重放 job_task（任务重放/重试回放）。 |
| [heal-stuck-outbox.sh](heal-stuck-outbox.sh) | 只读诊断卡住的 Outbox 投递。 |
| [inspect-all.sh](inspect-all.sh) | 全量巡检主入口。 |
| [inspect-db.sh](inspect-db.sh) | PostgreSQL 数据库巡检。 |
| [inspect-dead-letters.sh](inspect-dead-letters.sh) | 只读巡检待处理死信及其关联运行。 |
| [inspect-dependencies.sh](inspect-dependencies.sh) | 基础依赖只读巡检：PostgreSQL、Kafka、Valkey、MinIO。 |
| [inspect-observability.sh](inspect-observability.sh) | 服务巡检：健康检查、指标、Kafka lag 和基础设施 exporter 指标。 |
| [inspect-production-capacity.sh](inspect-production-capacity.sh) | 生产容量治理只读巡检：PostgreSQL 热表/保留、Kafka topic 保留、对象存储桶容量。 |
| [inspect-runtime-governance.sh](inspect-runtime-governance.sh) | 运行时治理 profile 只读检查。仅校验配置边界，不连接外部系统、不清理数据。 |
| [inspect-stale-pipelines.sh](inspect-stale-pipelines.sh) | 只读巡检长时间未推进的运行中 Pipeline。 |
| [inspect-workers.sh](inspect-workers.sh) | Worker 注册表巡检。 |
| [manage-trigger.sh](manage-trigger.sh) | Trigger 管理 API 运维入口。 |
| [plan-production-retention.sh](plan-production-retention.sh) | 生产保留治理计划：只读输出候选量和缺失策略，不执行归档、删除或配置修改。 |
| [render-alertmanager-config.sh](render-alertmanager-config.sh) | 根据环境变量渲染 Alertmanager 配置。 |
| [run-toolbox.sh](run-toolbox.sh) | 进入独立运维工具箱；不把 psql、Kafka CLI、mc、Python 塞进应用容器。 |
| [sql/common-connectivity.sql](sql/common-connectivity.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/heal-drain-timeout-workers.sql](sql/heal-drain-timeout-workers.sql) | 供 [heal-drain-timeout.sh](heal-drain-timeout.sh) 使用的配套 SQL。 |
| [sql/heal-retry-partitions-batch.sql](sql/heal-retry-partitions-batch.sql) | 供 [heal-retry-partitions.sh](heal-retry-partitions.sh) 使用的配套 SQL。 |
| [sql/heal-retry-partitions-count.sql](sql/heal-retry-partitions-count.sql) | 供 [heal-retry-partitions.sh](heal-retry-partitions.sh) 使用的配套 SQL。 |
| [sql/heal-retry-tasks-batch.sql](sql/heal-retry-tasks-batch.sql) | 供 [heal-retry-tasks.sh](heal-retry-tasks.sh) 使用的配套 SQL。 |
| [sql/heal-retry-tasks-count.sql](sql/heal-retry-tasks-count.sql) | 供 [heal-retry-tasks.sh](heal-retry-tasks.sh) 使用的配套 SQL。 |
| [sql/heal-stuck-outbox-breakdown.sql](sql/heal-stuck-outbox-breakdown.sql) | 供 [heal-stuck-outbox.sh](heal-stuck-outbox.sh) 使用的配套 SQL。 |
| [sql/heal-stuck-outbox-count.sql](sql/heal-stuck-outbox-count.sql) | 供 [heal-stuck-outbox.sh](heal-stuck-outbox.sh) 使用的配套 SQL。 |
| [sql/inspect-db-alert-critical-count.sql](sql/inspect-db-alert-critical-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-alert-warning-count.sql](sql/inspect-db-alert-warning-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-dead-letter-new-count.sql](sql/inspect-db-dead-letter-new-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-flyway-failed-count.sql](sql/inspect-db-flyway-failed-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-flyway-failed-list.sql](sql/inspect-db-flyway-failed-list.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-flyway-public-failed-count.sql](sql/inspect-db-flyway-public-failed-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-flyway-public-failed-list.sql](sql/inspect-db-flyway-public-failed-list.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-flyway-public-success-count.sql](sql/inspect-db-flyway-public-success-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-flyway-success-count.sql](sql/inspect-db-flyway-success-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-outbox-backlog-count.sql](sql/inspect-db-outbox-backlog-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-outbox-backlog-list.sql](sql/inspect-db-outbox-backlog-list.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-outbox-stale-publishing-count.sql](sql/inspect-db-outbox-stale-publishing-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-retry-overdue-count.sql](sql/inspect-db-retry-overdue-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-retry-waiting-count.sql](sql/inspect-db-retry-waiting-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-stuck-jobs-count.sql](sql/inspect-db-stuck-jobs-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-stuck-jobs-list.sql](sql/inspect-db-stuck-jobs-list.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-terminal-active-children-count.sql](sql/inspect-db-terminal-active-children-count.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-db-terminal-active-children-list.sql](sql/inspect-db-terminal-active-children-list.sql) | 供 [inspect-db.sh](inspect-db.sh) 使用的配套 SQL。 |
| [sql/inspect-dead-letters-count.sql](sql/inspect-dead-letters-count.sql) | 供 [inspect-dead-letters.sh](inspect-dead-letters.sh) 使用的配套 SQL。 |
| [sql/inspect-dead-letters-list.sql](sql/inspect-dead-letters-list.sql) | 供 [inspect-dead-letters.sh](inspect-dead-letters.sh) 使用的配套 SQL。 |
| [sql/inspect-dead-letters-summary.sql](sql/inspect-dead-letters-summary.sql) | 供 [inspect-dead-letters.sh](inspect-dead-letters.sh) 使用的配套 SQL。 |
| [sql/inspect-infra-postgres.sql](sql/inspect-infra-postgres.sql) | 基础依赖巡检只读查询；不要在此文件加入修复或清理语句。 |
| [sql/inspect-production-capacity-postgres.sql](sql/inspect-production-capacity-postgres.sql) | 生产容量巡检只读查询；不要在此文件加入修复、清理或锁表语句。 |
| [sql/inspect-stale-pipelines-count.sql](sql/inspect-stale-pipelines-count.sql) | 供 [inspect-stale-pipelines.sh](inspect-stale-pipelines.sh) 使用的配套 SQL。 |
| [sql/inspect-stale-pipelines-list.sql](sql/inspect-stale-pipelines-list.sql) | 供 [inspect-stale-pipelines.sh](inspect-stale-pipelines.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-decommissioned-assignment-count.sql](sql/inspect-workers-decommissioned-assignment-count.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-decommissioned-jobtask-count.sql](sql/inspect-workers-decommissioned-jobtask-count.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-drain-timeout-count.sql](sql/inspect-workers-drain-timeout-count.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-drain-timeout-list.sql](sql/inspect-workers-drain-timeout-list.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-draining-count.sql](sql/inspect-workers-draining-count.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-stale-heartbeat-count.sql](sql/inspect-workers-stale-heartbeat-count.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-stale-heartbeat-list.sql](sql/inspect-workers-stale-heartbeat-list.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-summary.sql](sql/inspect-workers-summary.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/inspect-workers-task-assignment-table-exists.sql](sql/inspect-workers-task-assignment-table-exists.sql) | 供 [inspect-workers.sh](inspect-workers.sh) 使用的配套 SQL。 |
| [sql/plan-production-retention-postgres.sql](sql/plan-production-retention-postgres.sql) | 生产保留治理计划只读查询；用于输出候选量和策略缺口，不执行归档、清理或维护命令。 |
| [trigger-compensation.sh](trigger-compensation.sh) | 手工触发补偿（Console API）。 |
