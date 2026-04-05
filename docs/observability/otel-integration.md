# OpenTelemetry 接入说明

## 架构

```
batch-platform 各服务
  │  (OTLP HTTP :4318)
  ▼
OTel Collector  ──► Jaeger   :16686  (Trace UI)
                ──► Loki     :3100   (Log 存储)
                        │
                        ▼
                    Grafana  :3000   (统一可视化：Metrics + Traces + Logs)
                        │
                    Prometheus (已有，保持 /actuator/prometheus 抓取不变)
```

## 已完成的接入工作

| 组件 | 变更 | 说明 |
|---|---|---|
| `batch-common/pom.xml` | 新增 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | 全部服务自动获得 Trace 能力 |
| `batch-defaults.yml` | 新增 `management.tracing` + `management.otlp` 配置块 | 统一 OTLP 导出端点，采样率可按环境覆盖 |
| `batch-defaults.yml` | 新增 `logging.structured.format.console` | 生产设 `ecs` 输出 JSON，本地留空 |
| `docs/observability/otel-collector.yml` | Collector pipeline 配置 | Traces→Jaeger，Logs→Loki |
| `docker-compose.observability.yml` | 新增 observability profile | Collector + Jaeger + Loki + Grafana |
| `docs/observability/grafana-provisioning/` | 数据源自动注入 | Prometheus + Loki + Jaeger |
| `helm/batch-platform/templates/otel-collector.yaml` | K8s Deployment + Service + ConfigMap | `otelCollector.enabled=true` 时生效 |
| `helm/batch-platform/templates/configmap.yaml` | 新增 OTEL env vars | `OTEL_EXPORTER_OTLP_ENDPOINT` 等 |

---

## 本地快速启动

```bash
# 1. 启动业务基础栈 + 应用容器
./scripts/docker/up-apps.sh

# 2. 启动可观测性栈
./scripts/docker/observability/up.sh

# 访问
# Jaeger UI : http://localhost:16686
# Grafana   : http://localhost:3000  (admin/admin)
# Loki API  : http://localhost:3100
```

---

## 环境变量参考

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318` | Collector HTTP 端点（不含路径） |
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | 采样率：本地 1.0；生产建议 0.1 |
| `BATCH_LOG_FORMAT` | `""`（空） | `ecs` = JSON 输出；空 = 可读格式 |
| `DEPLOYMENT_ENVIRONMENT` | `local` | 注入到所有遥测数据的 `deployment.environment` 标签 |

---

## 生产 K8s 部署

```bash
helm upgrade --install batch-platform ./helm/batch-platform \
  -n batch --create-namespace \
  -f helm/values-prod.yaml \
  --set otelCollector.enabled=true \
  --set otelCollector.jaegerEndpoint="jaeger.tracing.svc.cluster.local:4317" \
  --set otelCollector.lokiEndpoint="http://loki.logging.svc.cluster.local:3100" \
  --set otel.samplingProbability="0.1"
```

---

## 关键 MDC 字段与 Trace 关联

`micrometer-tracing-bridge-otel` 会自动将当前 Span 的 `traceId`/`spanId` 注入 MDC，
与现有手动设置的 `tenantId`/`requestId`/`jobInstanceId`/`fileId` 共存：

| MDC 字段 | 来源 | 说明 |
|---|---|---|
| `traceId` | OTEL 自动注入 | 32 位十六进制，与 Jaeger 中的 Trace ID 一致 |
| `spanId` | OTEL 自动注入 | 当前 Span |
| `service` | 手动 | 服务名称 |
| `tenantId` | 手动 | 租户隔离 |
| `requestId` | 手动 | 接口请求 ID |
| `jobInstanceId` | 手动 | 作业实例 ID |
| `fileId` | 手动 | 文件 ID |

Grafana Loki 数据源已配置 `derivedFields`，从日志中提取 `traceId` 并生成跳转到
Jaeger 的链接，实现 Log → Trace 一键关联。

---

## 生产注意事项

1. **Jaeger 存储**：all-in-one 使用内存存储，重启后丢失。生产需切换到
   `SPAN_STORAGE_TYPE=elasticsearch` 或使用 Tempo 替代。

2. **Loki 存储**：本地使用 Docker volume。生产需配置 S3/GCS 后端
   （`storage_config.aws.s3` / `storage_config.gcs`）。

3. **采样率**：高流量生产环境设 `OTEL_SAMPLING_PROBABILITY=0.1`（10%）；
   排查问题时临时设为 `1.0`，排查完毕后恢复。

4. **Collector 资源**：默认 `limits.memory=640Mi`；高吞吐场景适当上调，
   或开启 `memory_limiter` 的 `ballast_size_mib`。
