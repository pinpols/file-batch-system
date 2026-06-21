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

## 事后

- 更新本 runbook 中遗漏的依赖或端口。
- 若根因是配置，同步到配置发布流程（DRAFT→PUBLISHED）。
