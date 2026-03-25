# Prometheus / Grafana 基线（第 20 轮）

## 指标端点

各 Spring Boot 服务在 `management.endpoints.web.exposure.include` 中已包含 `prometheus`，并依赖 `micrometer-registry-prometheus` 后，`GET /actuator/prometheus` 暴露 Micrometer 指标。

建议抓取目标（示例）：

| 服务 | 默认端口 | 路径 |
|------|----------|------|
| batch-console-api | 8080 | `/actuator/prometheus` |
| batch-trigger | 8081 | `/actuator/prometheus` |
| batch-orchestrator | 8082 | `/actuator/prometheus` |
| batch-worker-import | （无 HTTP 时多为进程内 actuator） | 按实际 `management.server.port` |

## Prometheus 抓取配置片段

```yaml
scrape_configs:
  - job_name: batch-orchestrator
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8082']
  - job_name: batch-console-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8080']
```

## Grafana

- 导入 `docs/observability/grafana-dashboard-batch.json`（JVM + 平台计数器）。
- 关键指标：`batch_alert_events_total`、`batch_job_sla_violation_count`、`batch_dispatch_circuits_open`、`batch_dispatch_deliveries_total`、`export_file_rows_total`（导出文件行数，按 tenant 分标签）、`dispatch_receipt_total`（分发回执计数，按 tenant 分标签）、JVM 内存/线程。
- 告警与路由模板见 `docs/observability/prometheus-batch-rules.yml` 和 `docs/observability/alertmanager-batch-template.yml`。

## 结构化日志

MDC 字段：`service`、`tenantId`、`traceId`、`requestId`、`jobInstanceId`、`fileId`（见 `StructuredLogField`）。日志行模式在各模块 `application.yml` 的 `logging.pattern.console` 中统一，示例管道见 `docs/observability/structured-logging-pipeline.md`。

日志采集侧（Loki/ELK）可按字段解析并关联 trace。
