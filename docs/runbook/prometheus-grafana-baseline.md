# Prometheus / Grafana 基线（第 20 轮）

## 指标端点

各 Spring Boot 服务在 `management.endpoints.web.exposure.include` 中已包含 `prometheus`，并依赖 `micrometer-registry-prometheus` 后，`GET /actuator/prometheus` 暴露 Micrometer 指标。

当前控制台、调度器和全部 worker 模块都已补齐 `micrometer-registry-prometheus`，包括 `batch-console-api`、`batch-orchestrator`、`batch-trigger`、`batch-worker-import`、`batch-worker-export`、`batch-worker-dispatch`。

基础设施监控已补齐 `Prometheus + Redis exporter + Kafka exporter + PostgreSQL exporter + MinIO metrics + node_exporter + cAdvisor`，对应配置见 `docker-compose.observability.yml` 和 `docs/observability/prometheus.yml`。

Prometheus 本地 UI 默认可通过 `http://localhost:${PROMETHEUS_PORT:-19090}` 查看抓取目标和规则状态。

系统级 CPU / 内存 / 磁盘 / 网络 / 负载指标由 `node_exporter` 和 `cAdvisor` 提供，Grafana 里对应为 `Host CPU / load`、`Host memory / disk`、`Host network`、`Container CPU / memory`、`Container network / fs` 面板。

建议抓取目标（示例）：

| 服务 | 默认端口 | 路径 |
|------|----------|------|
| batch-console-api | 18080 | `/actuator/prometheus` |
| batch-trigger | 18081 | `/actuator/prometheus` |
| batch-orchestrator | 18082 | `/actuator/prometheus` |
| batch-worker-import | 18083 | `/actuator/prometheus` |
| batch-worker-export | 18084 | `/actuator/prometheus` |
| batch-worker-dispatch | 18085 | `/actuator/prometheus` |
| node-exporter | 9100 | `/metrics` |
| cadvisor | 8080 | `/metrics` |
| redis-exporter | 9121 | `/metrics` |
| postgres-exporter | 9187 | `/metrics` |
| kafka-exporter | 9308 | `/metrics` |
| minio | 9000 | `/minio/v2/metrics/cluster` |

上表为 **本仓库 Docker 网络内** Java 监听端口（`server.port` 默认 18080–18085）。MinIO 在网内为 `9000`；宿主机映射见 `.env.local`（如 `MINIO_API_PORT=19000`）。

## Prometheus 抓取配置片段

```yaml
scrape_configs:
  - job_name: batch-console-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['console-api:18080']
  - job_name: batch-orchestrator
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['orchestrator:18082']
  - job_name: batch-trigger
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['trigger:18081']
  - job_name: batch-worker-import
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['worker-import:18083']
  - job_name: batch-worker-export
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['worker-export:18084']
  - job_name: batch-worker-dispatch
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['worker-dispatch:18085']
  - job_name: redis-exporter
    static_configs:
      - targets: ['redis-exporter:9121']
  - job_name: postgres-exporter
    static_configs:
      - targets: ['postgres-exporter:9187']
  - job_name: kafka-exporter
    static_configs:
      - targets: ['kafka-exporter:9308']
  - job_name: minio
    metrics_path: /minio/v2/metrics/cluster
    static_configs:
      - targets: ['minio:9000']
  - job_name: node-exporter
    static_configs:
      - targets: ['node-exporter:9100']
  - job_name: cadvisor
    static_configs:
      - targets: ['cadvisor:8080']
```

## Grafana

- 导入 `docs/observability/grafana-dashboard-batch.json`（JVM + 平台计数器 + Redis/Kafka/PostgreSQL/MinIO）。
- 关键指标：`batch_alert_events_total`、`batch_job_sla_violation_count`、`batch_dispatch_circuits_open`、`batch_dispatch_deliveries_total`、`export_file_rows_total`（导出文件行数，按 tenant 分标签）、`dispatch_receipt_total`（分发回执计数，按 tenant 分标签）、`batch_console_realtime_subscriptions_active`、`batch_console_realtime_replay_events_total`、`batch_console_realtime_replay_cursor_miss_total`、`batch_console_realtime_replay_decode_failures_total`、`batch_console_realtime_pubsub_decode_failures_total`、`batch_console_realtime_pubsub_handle_failures_total`、`hikaricp_connections_*`、`redis_connected_clients`、`redis_memory_used_bytes`、`kafka_consumergroup_lag`、`pg_up`、`pg_stat_database_numbackends`、MinIO cluster metrics、JVM 内存/线程。
- 告警与路由模板见 `docs/observability/prometheus-batch-rules.yml` 和 `docs/observability/alertmanager-batch-template.yml`。
- Redis / realtime 已补的 Prometheus 规则包括：
  - `BatchRedisMemoryUsageHigh`
  - `BatchRedisConnectedClientsHigh`
  - `BatchConsoleRealtimeReplayCursorMissGrowing`
  - `BatchConsoleRealtimeReplayDecodeFailures`
  - `BatchConsoleRealtimePubSubDecodeFailures`
  - `BatchConsoleRealtimePubSubHandleFailures`

### Redis / Realtime 补充关注项

控制台 realtime 新增的 Redis 相关业务指标主要来自 `batch-console-api`：

| 指标 | 含义 |
|------|------|
| `batch_console_realtime_subscriptions_active` | 当前活跃 SSE 订阅数 |
| `batch_console_realtime_replay_events_total{stream}` | replay buffer 实际补发事件数 |
| `batch_console_realtime_replay_cursor_miss_total{stream}` | 前端传入 cursor 不在缓冲区中的次数 |
| `batch_console_realtime_replay_decode_failures_total{stream}` | replay buffer 解码失败次数 |
| `batch_console_realtime_pubsub_decode_failures_total` | Pub/Sub 载荷解码失败次数 |
| `batch_console_realtime_pubsub_handle_failures_total{stream,eventType}` | Pub/Sub 事件处理失败次数 |

Redis exporter 侧继续关注：

- `redis_connected_clients`
- `redis_memory_used_bytes`
- `redis_commands_processed_total`
- `redis_keyspace_hits_total`
- `redis_keyspace_misses_total`
- `redis_expired_keys_total`

## 结构化日志

MDC 字段：`service`、`tenantId`、`traceId`、`requestId`、`jobInstanceId`、`fileId`（见 `StructuredLogField`）。日志行模式在各模块 `application.yml` 的 `logging.pattern.console` 中统一，示例管道见 `docs/observability/structured-logging-pipeline.md`。

日志采集侧（Loki/ELK）可按字段解析并关联 trace。
