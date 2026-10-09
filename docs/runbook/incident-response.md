# 故障响应 Runbook（可执行）

## 演练剧本目录

具体场景的逐步操作("怎么发现 → 怎么定位 → 怎么恢复")见 [`playbooks/`](playbooks/README.md):

| 剧本 | 场景 | 优先级 |
|---|---|---|
| [pg-primary-failover](playbooks/pg-primary-failover.md) | PG 主库挂,从库切主 | P0 |
| [redis-shedlock-down](playbooks/redis-shedlock-down.md) | Redis 全断,ShedLock 切 jdbc fallback | P0 |
| [kafka-rebalance-stuck](playbooks/kafka-rebalance-stuck.md) | Consumer group lag 飙高,rebalance 卡 | P1 |
| [outbox-stuck-publishing](playbooks/outbox-stuck-publishing.md) | `outbox_event` PUBLISHING 长期停滞自愈 | P1 |
| [batch-day-not-settling](playbooks/batch-day-not-settling.md) | `batch_day_instance` 卡 SETTLING | P2 |

本文件是总框架(原则 / 分级 / 常用命令);具体剧本在 `playbooks/`。

## 原则

1. 先恢复业务（限流、降级、跳过非关键批次），再根因。
2. 所有人工操作走控制台或已有治理 API，保留 `traceId` / `approvalId` 审计。

## 分级（建议）

| 级别 | 现象 | 首动作 |
|------|------|--------|
| P1 | 调度/编排完全不可用 | 检查 orchestrator `health`、DB、Kafka；回滚最近发布 |
| P2 | 单租户大量失败或 SLA 告警 | 查 `batch.alert_event` / 控制台 alerts；隔离租户 |
| P3 | 单任务失败可重试 | 补偿入口 `CompensationCommand`；重试分区/步骤 |

## 常用命令

- **健康**：`curl -sSf http://localhost:18082/actuator/health`（orchestrator）
- **Prometheus**：`curl -sSf http://localhost:18082/actuator/prometheus | head`
- **控制台告警列表**：`GET /api/console/query/alerts?tenantId=default-tenant&limit=100`

## 数据库

- `batch.alert_event`：按 `tenant_id`、`last_seen_at` 排序；`occurrence_count` 高表示重复告警已收敛。
- `batch.job_execution_log`：`log_type='ALARM'` 与 SLA 相关。

## 作业时限告警

| 告警类型 | 排查重点 | 处置边界 |
|---|---|---|
| `JOB_RUNNING_TOO_LONG` | 对比告警阈值、实例 `startedAt`、Worker task 状态和执行日志；区分执行变慢与下游阻塞 | 软阈值只告警，不会终止实例；需要停止时走现有取消/超时治理流程 |
| `JOB_NOT_STARTED_BY_DEADLINE` | 核对 `scheduledAt`、实例状态、队列等待、Kafka lag、可用 Worker 与路由能力 | 告警不会拒绝或重放实例；先恢复派发链路，再按批量日规则判断补跑 |
| `JOB_NOT_COMPLETED_BY_DEADLINE` | 比较告警中的 deadline、当前/完成时间和作业配置；确认定时实例是否仍在运行，或扫描是否晚于实际完成 | 只通知，不自动重跑或更改业务结果；按业务窗口和对账要求决定是否补数 |
| `JOB_FINAL_PARTITION_FAILURE` | 查实例最终 `failed_partition_count`，再下钻具体 FAILED 分区、重试次数、错误码和对应 Worker 日志 | 仅近期非 dry-run 的 FAILED/PARTIAL_FAILED 实例且失败分区数大于零触发；不代表重试中的分区失败 |

## 依赖与通知可用性告警

| 告警 | 排查重点 | 恢复边界 |
|---|---|---|
| `BatchDependencyExporterDown` | 检查 Prometheus target、依赖容器/服务、Exporter 进程、网络和认证；区分服务故障与只读监控端点故障 | 恢复 target 后确认 `up=1`；Helm 未部署对应 exporter 时，不应把该 target 当作必选项 |
| `BatchAlertmanagerNotifySkipped` | 按 `receiver` 检查对应租户 `notification_channel` 是否存在、启用、类型正确；检查 Console `am_notify_skipped` 日志与 Alertmanager 路由 | 建立/修复渠道后重新发送测试告警，并确认 `notification_delivery_log` 为 SUCCESS；告警事件入库不代表外部渠道已送达 |
| `BatchAlertmanagerDeliveryFailed` | 按 `receiver` 查询 `notification_delivery_log` 的 FAILED 记录，检查渠道凭据、外部服务可达性和发送器错误 | 修复渠道后验证新告警送达；同时确认有独立升级渠道可接收该平台告警，避免故障渠道告警自己 |
| `BatchCoreServiceTargetMissing` | 检查 Prometheus Targets、Kubernetes ServiceMonitor selector/标签或 Docker file-SD target 清单 | 恢复 target 发现后确认对应 `up` 序列出现且为 1；不要只通过重启 Prometheus 清除告警 |
| `BatchAlertmanagerDown` / `BatchAlertmanagerNotificationFailures` / `BatchAlertmanagerConfigReloadFailed` | 查看 Alertmanager `/metrics`、启动日志和配置重载状态；检查外部 receiver 错误 | 告警路由依赖 Alertmanager 正常工作；Alertmanager 或 Prometheus 自身不可用时，由集群级独立监控发出通知 |

扫描器自身失败检查 Prometheus `BatchJobMonitoringScanFailures`、`BatchJobMonitoringEventFailures` 及 orchestrator 日志。修复扫描/落库故障后由下一轮扫描重试；告警 claim 与事件写入在独立事务中，失败不会阻断作业主链路。配置和完整时限语义见 [`sla-and-quality.md`](../design/sla-and-quality.md)。

## 事后

- 更新本 runbook 中遗漏的依赖或端口。
- 若根因是配置，同步到配置发布流程（DRAFT→PUBLISHED）。
