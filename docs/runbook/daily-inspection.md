# 日常巡检 SOP（可执行）

## 频率

- 生产：每日开工前；变更后额外一次。
- 非生产：每周至少一次。

## 检查项（顺序执行）

1. **健康检查**  
   对 `batch-console-api`、`batch-trigger`、`batch-orchestrator` 调用 `GET /actuator/health`，期望 `status` 为 `UP`。

2. **指标**  
   打开各服务 `GET /actuator/prometheus`，确认进程存活；在 Grafana 查看 `batch_job_sla_violation_count` 是否异常抬升、`batch_alert_events_total` 是否有持续 ERROR/CRITICAL。
   本地可直接运行 `scripts/local/inspect-observability.sh`，它会同时检查 health、Prometheus 指标和可选 Kafka consumer lag。

3. **告警表（控制台）**  
   `GET /api/console/query/alerts?tenantId=<tenant>&limit=50`（需控制台权限），查看最近 `last_seen_at` 是否集中重复同一 `dedup_fingerprint`（收敛正常应表现为 `occurrence_count` 增加而非无限新行）。

4. **数据库**  
   连接 PostgreSQL，确认 `batch` schema 下 Flyway 历史无失败；`batch.alert_event` 表可查询。

5. **Kafka / 外部依赖**（若使用）  
   确认 broker 可达；MinIO 端点可达（若 worker 使用）。

## 记录

- 异常项记下时间、租户、traceId（若日志/告警中有）、处理人。
- 需跟进的告警在 `batch.alert_event` 中 `status` 后续可扩展为 ACK（见 incident runbook）。
