# 日常巡检 SOP（可执行）

## 频率

- 生产：每日开工前；变更后额外一次。
- 非生产：每周至少一次。

## 一键全量巡检（推荐）

```bash
# 设置环境变量后运行主入口脚本（涵盖以下所有检查项）
PGHOST=<db-host> PGPORT=5432 PGDATABASE=batch_db PGUSER=batch PGPASSWORD=<pwd> \
BATCH_OBSERVABILITY_BASE_URLS=http://<console>:8080,http://<orchestrator>:8082 \
BATCH_OBSERVABILITY_KAFKA_BOOTSTRAP_SERVERS=<kafka>:9092 \
  bash scripts/local/inspect-all.sh
```

退出码 0 = 全部通过，1 = 存在 FAIL 项。

## 单项巡检脚本

| 脚本 | 检查内容 | 跳过环境变量 |
|---|---|---|
| `scripts/local/inspect-observability.sh` | 服务 health、Prometheus 指标、Kafka consumer lag | `BATCH_INSPECT_SKIP_OBSERVABILITY=true` |
| `scripts/local/inspect-db.sh` | Flyway 历史、告警事件、卡死作业、Outbox 积压、死信积压、重试积压 | `BATCH_INSPECT_SKIP_DB=true` |
| `scripts/local/inspect-workers.sh` | DRAINING 超时、心跳失联、孤儿任务 | `BATCH_INSPECT_SKIP_WORKERS=true` |

## 自愈脚本（发现异常后执行）

| 脚本 | 适用场景 | 安全说明 |
|---|---|---|
| `scripts/local/heal-drain-timeout.sh` | inspect-workers 报告 DRAINING 超时 Worker | 默认 dry-run；`BATCH_HEAL_DRY_RUN=false` 才实际执行 |
| `scripts/local/heal-dead-letters.sh` | 死信积压 > 阈值，需批量重放 | 默认 dry-run；可按 tenant/source_type 过滤 |
| `scripts/local/heal-stuck-outbox.sh` | Outbox 积压长期不消费（Kafka 恢复后 Orchestrator 未重启） | 默认 dry-run；重置 retry_count 触发重发 |

## 检查项说明

1. **健康检查**
   `inspect-all.sh` 已覆盖。对 `batch-console-api`、`batch-trigger`、`batch-orchestrator` 调用 `GET /actuator/health`，期望 `status` 为 `UP`。

2. **Prometheus 指标**
   确认进程存活；Grafana 查看 `batch_job_sla_violation_count` / `batch_alert_events_total` / `BatchKafkaConsumerLagHigh` 告警。

3. **告警表（控制台）**
   `GET /api/console/query/alerts?tenantId=<tenant>&limit=50`，查看 `occurrence_count` 是否正常收敛。

4. **数据库**
   `inspect-db.sh` 已覆盖：Flyway 失败迁移、卡死作业（RUNNING 超 60 分钟）、Outbox 积压（>120s）、死信积压（>50 条）、重试积压（overdue）。

5. **Worker 排空**
   `inspect-workers.sh` 已覆盖：DRAINING 超时 → 运行 `heal-drain-timeout.sh`。

## 记录

- 异常项记下时间、租户、traceId（若日志/告警中有）、处理人。
- 需跟进的告警在 `batch.alert_event` 中 `status` 后续可扩展为 ACK（见 incident runbook）。
