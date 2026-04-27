# 观测栈（Observability Stack）— 一站式部署 + 排障 SOP

> 整合自原 4 个 runbook：prometheus-grafana-baseline / observability-loki-tempo / otel-integration / structured-logging-pipeline。
> 三件套一体化呈现：metrics（Prom + Grafana baseline）+ traces（Tempo + Jaeger 兼容）+ logs（Loki + 结构化 pipeline）+ OTel Collector 多路复用。

## 1. Loki + Tempo 三件套主部署


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

---

## 2. Prometheus + Grafana 基线


## 指标端点

各 Spring Boot 服务在 `management.endpoints.web.exposure.include` 中已包含 `prometheus`，并依赖 `micrometer-registry-prometheus` 后，`GET /actuator/prometheus` 暴露 Micrometer 指标。

当前控制台、调度器和全部 worker 模块都已补齐 `micrometer-registry-prometheus`，包括 `batch-console-api`、`batch-orchestrator`、`batch-trigger`、`batch-worker-import`、`batch-worker-export`、`batch-worker-process`、`batch-worker-dispatch`。

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
| batch-worker-process | 18086 | `/actuator/prometheus` |
| node-exporter | 9100 | `/metrics` |
| cadvisor | 8080 | `/metrics` |
| redis-exporter | 9121 | `/metrics` |
| postgres-exporter | 9187 | `/metrics` |
| kafka-exporter | 9308 | `/metrics` |
| minio | 9000 | `/minio/v2/metrics/cluster` |

上表为 **本仓库 Docker 网络内** Java 监听端口（`server.port` 默认 18080–18086）。MinIO 在网内为 `9000`；宿主机映射见 `.env.local`（如 `MINIO_API_PORT=19000`）。

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
  - job_name: batch-worker-process
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['worker-process:18086']
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

MDC 字段：`service`、`tenantId`、`traceId`、`requestId`、`jobInstanceId`、`fileId`（见 `StructuredLogField`）。日志行模式在各模块 `application.yml` 的 `logging.pattern.console` 中统一，示例管道见 `docs/observability/observability-stack.md`。

日志采集侧（Loki/ELK）可按字段解析并关联 trace。

---

## 3. OTel Collector 集成


## 架构

```
batch-platform 各服务
  │  (OTLP HTTP :4318)
  ▼
OTel Collector  ──► Jaeger   :16686（UI；宿主机映射默认同端口）
                ──► Loki     :3100（容器内 API；宿主机映射默认 :13100）
                        │
                        ▼
                    Grafana  :3000（容器内；宿主机映射默认 :13000）
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
# Grafana   : http://localhost:13000  (admin/admin)
# Loki API  : http://localhost:13100
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

---

## 4. 结构化日志 pipeline


平台当前的统一 MDC 口径是 `service`、`tenantId`、`traceId`、`requestId`、`jobInstanceId`、`fileId`。控制台、调度、触发和 HTTP 型 worker 已统一到同一类 console pattern，便于 Loki / ELK / OpenSearch 做字段解析和跨服务关联。

## 当前日志格式

仓库中的 console pattern 已包含统一字段：

```text
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%X{service:-} %X{tenantId:-} %X{traceId:-} %X{requestId:-} %X{jobInstanceId:-} %X{fileId:-}] %logger{40} - %msg%n
```

## Loki / Promtail 示例

下面是一个和当前 pattern 对齐的 Promtail 示例，只做字段抽取，不改业务日志内容：

```yaml
scrape_configs:
  - job_name: batch-apps
    static_configs:
      - targets: [localhost]
        labels:
          job: batch-apps
          __path__: /var/log/batch/*.log
    pipeline_stages:
      - regex:
          expression: '^(?P<ts>[^ ]+) (?P<level>[^ ]+) \\[(?P<thread>[^]]+)\\] \\[(?P<service>[^ ]*) (?P<tenantId>[^ ]*) (?P<traceId>[^ ]*) (?P<requestId>[^ ]*) (?P<jobInstanceId>[^ ]*) (?P<fileId>[^ ]*)\\] (?P<logger>[^ ]+) - (?P<msg>.*)$'
      - labels:
          service:
          tenantId:
          traceId:
          requestId:
          jobInstanceId:
          fileId:
          level:
      - output:
          source: msg
```

## 约定

- `service` 用于区分控制面和各 worker。
- `tenantId` 用于租户隔离与排障聚合。
- `traceId` 和 `requestId` 用于链路追踪。
- `jobInstanceId` 和 `fileId` 用于把调度、补偿、文件治理日志关联到同一实体。

---

## 日志租户隔离机制

> 日期：2026-04-09

### 整体方案

所有模块共用 SLF4J MDC + 统一 log pattern，**逻辑隔离**（按字段过滤），不做物理隔离（不按租户分文件/分 topic）。

### MDC 注入点

| 模块 | 注入点 | 注入的 MDC 字段 |
|------|--------|----------------|
| console-api | `ConsoleRequestContextFilter` — 每个 HTTP 请求入口 | `service` `tenantId` `traceId` `requestId` |
| orchestrator / trigger | `HttpRequestMdcFilter` — 通用 Servlet Filter | `service` `tenantId` `traceId` `requestId` |
| orchestrator 内部调度 | `JobSlaScheduler` `PartitionLeaseReclaimScheduler` `WaitingPartitionDispatchScheduler` `DefaultScheduleForwarder` `DefaultRetryGovernanceService` — 每轮循环手动 put | `tenantId` `traceId` `jobInstanceId` |
| worker | `AbstractTaskConsumer` — 每条 Kafka 消息消费前 | `tenantId` `traceId` `taskId` `jobInstanceId` `workerId` `workerType` `runMode` |
| worker pipeline step | `AbstractPipelineStepExecutionAdapter` — 每个 step 执行前 | `tenantId` `traceId` `jobInstanceId` `workerId` `runMode` |

### 隔离粒度

| 维度 | MDC 字段 | 查询示例（Loki LogQL） |
|------|---------|----------------------|
| 租户 | `tenantId` | `{tenantId="tenant-a"}` |
| 请求链路 | `traceId` + `requestId` | `{traceId="1a2b3c"}` |
| 作业实例 | `jobInstanceId` | `{jobInstanceId="12345"}` |
| 文件 | `fileId` | `{fileId="67890"}` |
| 服务 | `service` | `{service="batch-orchestrator"}` |

### 生命周期管理

- **HTTP 请求**：Filter 入口 put → finally `BatchMdc.clear()` 清除全部字段。
- **Kafka 消费**：`AbstractTaskConsumer` 消费前 put → finally 逐个 remove（`tenantId` `traceId` `taskId` `jobInstanceId` `workerId` `workerType` `runMode`）。
- **内部调度循环**：`BatchMdc.withTenantAndTrace()` 自动 save/restore 上下文，循环体处理单个 job 时额外 put `jobInstanceId`，finally remove。

### 日志格式切换

配置项：`BATCH_LOG_FORMAT` 环境变量（`batch-defaults.yml`）。

| 环境 | 值 | 输出格式 |
|------|---|---------|
| 本地开发 | 留空（默认） | 纯文本，console pattern |
| 生产 / K8s | `ecs` | JSON（ECS 格式），Loki / ELK 直接按字段索引 |

### 已知局限

| 局限 | 说明 | 风险等级 |
|------|------|---------|
| 无物理隔离 | 所有租户写同一个日志文件/流，靠字段过滤而非分文件 | 低 — 生产日志系统（Loki/ELK）都支持标签过滤 |
| Kafka 消费线程池共享 | 多租户消息在同一个 consumer group，靠代码保证 MDC 不串 | 低 — 当前每条消息处理前 put、finally remove，链路完整 |
| 异步线程池 MDC 不传播 | `@Async` 或自定义线程池不会自动携带 MDC | 中 — 当前关键路径均为同步或手动注入，但若后续引入异步需加 `TaskDecorator` |
| 无独立的 MDC 传播装饰器 | 缺少通用的 `MdcTaskDecorator` 包装 | 低 — 目前无需，但异步扩展时应优先补充 |
