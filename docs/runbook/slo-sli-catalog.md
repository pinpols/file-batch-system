# SLO / SLI 目录

本文定义批量调度平台的服务等级指标目录。它不是当前生产承诺,也不替代 staging 压测、灾备演练或客户 SLA；它用于把 Prometheus 规则、Grafana 面板、Runbook 和上线验收连接到同一套业务口径。

## 使用原则

- SLO 必须能回到业务结果,不能只看技术组件是否存活。
- SLI 必须说明数据来源、采样窗口、标签边界和不纳入场景。
- 告警阈值必须能反查到 SLO、容量假设或恢复动作。
- 本地、staging 和生产阈值可以不同；文档只定义口径,目标环境负责给出实际阈值。
- 历史压测和本地 smoke 不能替代当前环境 SLO 证据。

## 核心 SLI

| SLI | 业务含义 | 主要信号 | 典型 Runbook |
|---|---|---|---|
| 调度准点率 | 计划触发在允许延迟内进入 launch / WAITING / RUNNING | trigger fire 延迟、`trigger_request` 状态、Quartz misfire、readiness defer | [`trigger-operations.md`](./trigger-operations.md)、[`dependency-aware-fire.md`](./dependency-aware-fire.md) |
| 批次日完成率 | 指定业务日内关键作业终态达到 SUCCESS 或可解释终态 | `batch_day_instance`、`job_instance` 终态、审批 / skip 记录 | [`batch-day-gate-howto.md`](./batch-day-gate-howto.md)、[`go-live-realism-audit-2026-06-21.md`](./go-live-realism-audit-2026-06-21.md) |
| 运行积压深度 | 运行态排队是否超过容量假设 | WAITING partition、oldest queued age、tenant / queue 并发 | [`heavy-workload-operations.md`](./heavy-workload-operations.md)、[`autoscaling-strategy.md`](./autoscaling-strategy.md) |
| Outbox 投递健康 | 控制面事件是否持续可投递 | `outbox_event` backlog、oldest unpublished age、retry / DLQ | [`outbox-architecture.md`](../architecture/outbox-architecture.md)、[`compensation-cleanup.md`](./compensation-cleanup.md) |
| Kafka 消费滞后 | Worker / 下游消费者是否跟上生产速率 | consumer group lag、topic backlog、KEDA scaler 信号 | [`autoscaling-strategy.md`](./autoscaling-strategy.md)、[`base-services-deployment.md`](./base-services-deployment.md) |
| 文件到达准点率 | 上游文件组是否在 SLA 内完整到达并通过完整性门禁 | file arrival group、manifest / checksum、missing / stale 告警 | [`event-driven-arrival.md`](./event-driven-arrival.md)、[`worker-stage-coverage.md`](./worker-stage-coverage.md) |
| 重试恢复率 | 失败任务是否在重试预算内恢复,未耗尽进入 DLQ | `retry_schedule`、`dead_letter_task`、worker report 失败原因 | [`forensic-replay-howto.md`](./forensic-replay-howto.md)、[`compensation-cleanup.md`](./compensation-cleanup.md) |
| Worker 在线健康 | Worker 是否按能力和租约稳定参与执行 | `worker_registry` 心跳、lease renew、fast retry、offline count | [`rolling-upgrade-workers.md`](./rolling-upgrade-workers.md)、[`worker-stage-coverage.md`](./worker-stage-coverage.md) |
| 下游 readiness 成功率 | 依赖上游结果的触发是否正确等待并在窗口内放行 | readiness defer、timeout、asset partition EFFECTIVE | [`dependency-aware-fire.md`](./dependency-aware-fire.md)、[`../design/asset-partition-readiness.md`](../design/asset-partition-readiness.md) |
| 数据库迁移一致性 | 代码迁移文件与目标库 Flyway history 是否一致 | `flyway_schema_history`、schema governance report | [`db-migration-checklist.md`](./db-migration-checklist.md)、[`../design/database-schema-governance.md`](../design/database-schema-governance.md) |

## 告警映射

| 告警类 | 映射 SLI | 处理原则 |
|---|---|---|
| `Outbox backlog` / oldest unpublished age | Outbox 投递健康 | 先判定数据库写入、publisher、Kafka 或下游 topic 是否异常；不要直接清表 |
| `Kafka lag` / KEDA lag scaler | Kafka 消费滞后、运行积压深度 | 先看消费者在线、rebalance、partition 数和 worker 饱和度，再扩容 |
| `file arrival SLA violation` | 文件到达准点率 | 先定位到达组、上游渠道和 manifest / checksum；缺文件不应手工伪造成功 |
| `ONLINE workers have stale heartbeats` | Worker 在线健康 | 检查 Worker 网络、心跳间隔、租约续期和发布中的滚动升级 |
| `worker lease fast-retry storm` | Worker 在线健康、重试恢复率 | 检查 lease 服务、DB 锁、worker GC / 网络抖动和 fast retry 原因 |
| `readiness timeout` / readiness retry high | 下游 readiness 成功率、调度准点率 | 确认上游真实终态和 asset partition,决定补跑、放弃或延长窗口 |
| `oldest queued partition waited` | 运行积压深度 | 检查准入限制、资源队列、Worker 容量、Kafka lag 和 PG 锁等待 |
| `PG lock / Hikari saturation` | 多个 SLI 的基础依赖 | 先保护写路径和主链路,再分析慢 SQL、连接池和迁移/清理任务 |

Prometheus 规则入口：

- Docker: `deploy/docker/observability/prometheus-batch-rules.yml`
- Helm: `helm/batch-platform/files/prometheus-batch-rules.yml`

修改告警规则时,应同步更新本文或引用的 Runbook,避免告警阈值变成无人解释的技术数字。

## 验收证据

一次上线或容量评估至少保留：

- 环境和版本：commit、镜像 digest、配置 profile、数据规模。
- 时间窗口：采样开始 / 结束时间、是否覆盖业务高峰。
- 业务结果：关键批次日、关键作业、文件到达组和重试 / DLQ 结论。
- 技术信号：Outbox、Kafka lag、Worker 心跳、DB 连接池、PG 锁、readiness timeout。
- 未覆盖项：未跑的 Worker 类型、未演练的故障、未接入的告警通道。

## 不纳入 SLO 的内容

- 单次本地 smoke 的耗时。
- 已跳过、已取消或 pending 的 CI job。
- 无真实数据规模的 `idx_scan=0`、低行数或空库现象。
- 前端 mock 接口通过的 Playwright 用例。
- 没有目标环境接收端的告警配置。

## 相关入口

- 观测栈: [`observability-stack.md`](./observability-stack.md)
- 告警升级: [`alert-escalation.md`](./alert-escalation.md)
- 上线就绪: [`go-live-readiness.md`](./go-live-readiness.md)
- staging 执行: [`go-live-staging-execution.md`](./go-live-staging-execution.md)
- 工程成熟度路线: [`../architecture/engineering-maturity-roadmap.md`](../architecture/engineering-maturity-roadmap.md)
