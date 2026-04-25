# 观测三件套：Metrics + Logs + Traces

## 背景

P3 收尾：在已有 Prometheus + Grafana + Jaeger + Loki 的基础上**接入 Tempo**，让 Grafana
变成"指标 → 日志 → 链路"一站式排障入口。Jaeger 保留作为 UI 兼容（旧链路调查习惯），
新查询都走 Tempo（与 Grafana 原生集成更深，附带 service graph + span metrics）。

```
应用进程 (micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp)
   │
   │  OTLP HTTP 4318 (traces + logs)
   ▼
otel-collector
   │
   ├── traces ──→ tempo:4317  ──┐
   │                            │       ┌────────────┐
   │      └────→ jaeger:4317  ──┼──────▶│  Grafana   │
   │                            │       │            │
   ├── logs ───→ loki:3100/otlp ┴──────▶│  ↳ datasrc:│
   │                                    │   Prom +   │
prometheus ─ scrape /actuator/prometheus│   Loki +   │
                          │             │   Tempo +  │
                          └────────────▶│   Jaeger   │
                                        └────────────┘
```

## 启动观测栈

```bash
# 起底层（postgres / redis / kafka / minio / quartz / replica）
docker compose --env-file .env.local up -d

# 叠加观测栈：prometheus + grafana + loki + tempo + jaeger + otel-collector + exporters
docker compose -f docker-compose.yml -f docker-compose.observability.yml --env-file .env.local up -d

# 起应用（traces + logs 自动经 otel-collector:4318 上报）
docker compose -f docker-compose.yml -f docker-compose.app.yml --env-file .env.local --profile apps up -d
```

入口端口（宿主机）：

| UI / API | 端口 | 用途 |
|---|---|---|
| Grafana | http://localhost:13000 | **主入口**：dashboard / Explore（Metrics + Logs + Traces） |
| Prometheus | http://localhost:19090 | 直查 metrics（生产一般不暴露） |
| Loki | http://localhost:13100 | 直查 logs（一般通过 Grafana） |
| Tempo HTTP | http://localhost:13200 | 直查 traces（一般通过 Grafana） |
| Jaeger UI | http://localhost:16686 | 旧 UI，新查询去 Grafana Explore Tempo |
| Alertmanager | http://localhost:19093 | 告警路由查看 |

## 三件套联动

### 1. Metrics → Traces（exemplar）

Prometheus 数据源开启了 `exemplarTraceIdDestinations` → Tempo。当 metric 携带
exemplar（应用通过 micrometer 自动写入）时，Grafana 时序图上的小钻石标记可点开
跳转到对应 trace。

**前提**：应用要打开 micrometer exemplar，本项目通过 `micrometer-tracing-bridge-otel`
默认开启，无需额外配置。

### 2. Logs → Traces（Loki derivedFields）

Loki 数据源声明了两条 derivedField，从日志行中提取 `traceId` 字段：

```yaml
matcherRegex: '"traceId"\s*:\s*"([a-f0-9]{16,32})"'
datasourceUid: tempo  # 优先 Tempo
url: $${__value.raw}
```

**前提**：应用日志必须包含 `traceId` 字段。本项目 `batch-defaults.yml` 的 logging
pattern 已带 `%X{traceId:-}`，并且生产/容器模式建议设
`BATCH_LOG_FORMAT=ecs` 让 Spring Boot 3.4+ 输出 ECS JSON 结构化日志，traceId
自动成为顶级 JSON 字段（被 derivedFields 正则匹配）。

### 3. Traces → Logs（Tempo tracesToLogsV2）

Tempo 数据源的 `tracesToLogsV2`：在 trace 视图选中 span，右上点 "Logs for this span"
跳到 Loki 按 `service.name` + `traceID` 过滤的查询，时间窗按 span 起止 ±5 min 自动展开。

### 4. Traces → Metrics（Tempo serviceMap）

Tempo 自带 `metrics_generator` 输出 service-graph 与 span-metrics 到本地 WAL，
Grafana 用 Prometheus 数据源（`tracesToMetrics`）展示服务依赖图与 P99/error rate。

## 排障路径示例

**场景**：千万级压测时观察到 `batch_outbox_publish_latency_seconds` p99 飙到 5s。

1. **Grafana → Dashboards → Batch Coverage**：定位 outlier 时间窗。
2. 在 metric 图上点 exemplar 钻石 → 自动跳到 Tempo 中那一条慢 trace。
3. Tempo trace view：找到耗时最长的 span（比如 `OutboxPublisher.publish`）。
4. 右上 "Logs for this span" → Loki 显示该 traceId 的所有日志，看 WARN/ERROR。
5. 顺带 service graph 看依赖：是不是下游 Kafka 写延迟导致的连锁。

整个路径不需要在多个 UI 间手工抄 traceId。

## 验证清单（部署后跑一遍）

```bash
# 1. otel-collector 健康
curl -s http://localhost:4318/v1/traces -X POST -H "Content-Type: application/json" \
  -d '{"resourceSpans":[]}' && echo "OTLP HTTP 接收 OK"

# 2. Tempo 接收 trace
curl -s http://localhost:13200/ready

# 3. Loki 接收 OTLP 日志
curl -s http://localhost:13100/ready

# 4. Grafana 数据源全部 healthy（首次访问）
open http://localhost:13000  # Explore → 切换 datasource 测试

# 5. 触发一次业务请求生成真实数据
curl -X POST http://localhost:18081/api/triggers/launch -H "Content-Type: application/json" \
  -d '{"tenantId":"default-tenant","jobCode":"disp_local_probe","bizDate":"2026-04-25"}'

# 30 秒内：
#   - Grafana → Explore → Tempo: 搜 service.name="batch-trigger" 应能看到 trace
#   - Grafana → Explore → Loki: {service_name="batch-trigger"} 应能看到 INFO 日志
#   - 点击日志行 TraceID 链接应跳转到 Tempo 对应 trace
```

## 故障

### Tempo 不收 trace
- 看 otel-collector 容器日志：`docker logs batch-otel-collector | grep -i tempo`
- Tempo OTLP 端口被占？`docker port batch-tempo`，应该有 4317

### Loki 不收 OTLP 日志
- Loki 3.x 默认配置的 OTLP 接收需要 `limits_config.allow_structured_metadata: true`
  + schema v13；本仓库 `docker/observability/loki-config.yml` 已开启
- 如果改 image 版本要重新确认这两项

### Grafana 找不到 Tempo
- 检查 `grafana-provisioning/datasources/datasources.yml` 是否挂载到容器
  （`docker exec batch-grafana ls /etc/grafana/provisioning/datasources`）
- 容器启动后 datasources 是 immutable 的，改完要 `docker compose restart grafana`

### Logs 里没 traceId 字段
- 应用配置：`logging.pattern.console` 必须含 `%X{traceId:-}`，**或**
  `BATCH_LOG_FORMAT=ecs` 启用结构化输出（Spring Boot 3.4+ 自动注入 OTel traceId）
- 验证：`docker logs batch-orchestrator | grep -E '"traceId":"[a-f0-9]'`

## 生产部署补充

本地 Tempo 走 `monolithic` + 本地磁盘（`storage.trace.backend: local`），适合开发/压测，
**生产**应：
1. **后端换对象存储**：S3/GCS/Azure Blob —— `storage.trace.backend: s3` + `s3.bucket/endpoint`
2. **拆角色**：distributor / ingester / querier / compactor 独立 deploy（参考
   [grafana/tempo-distributed helm chart](https://github.com/grafana/helm-charts/tree/main/charts/tempo-distributed)）
3. **多副本 + replication_factor=3**：避免单实例宕机丢链路
4. **保留期**：本地默认 7d；生产建议 14-30d，配套对象存储成本核算

Loki 同理：本地 filesystem，生产换对象存储 + 多副本。

## 相关文件

- `docker-compose.observability.yml` — 观测栈 compose
- `docker/observability/otel-collector.yml` — Collector 流水线
- `docker/observability/tempo.yml` — Tempo 配置
- `docker/observability/loki-config.yml` — Loki 配置（OTLP + structured metadata）
- `docker/observability/grafana-provisioning/datasources/datasources.yml` — 数据源 + 联动
- `batch-common/src/main/resources/batch-defaults.yml` `spring.tracing` / `spring.otlp` — 应用侧 OTLP 出口
