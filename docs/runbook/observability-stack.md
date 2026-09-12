# 可观测性栈运行手册

> 适用范围：batch-platform 的指标、日志、链路、JVM 诊断和告警。业务审计仍以 PostgreSQL
> 审计表为准，不用 Loki 替代合规存证。

## 1. 标准拓扑

```text
Spring Boot 应用
  ├─ /actuator/prometheus ───────────────→ Prometheus ─→ Grafana / Alertmanager
  ├─ structured stdout ─────────────────→ 容器运行时轮转（故障兜底）
  ├─ Logback → OTel appender ─┐
  └─ Micrometer/OTel traces ──┴─ OTLP → Collector
                                      ├─ logs   → Loki
                                      └─ traces → Tempo + Jaeger
```

应用侧由 `spring-boot-starter-opentelemetry` 创建 SDK、Trace/Log provider 和 exporter；
`opentelemetry-logback-appender-1.0` 及 `OpenTelemetryLogbackBridge` 将 SLF4J 事件接入 Log
provider。只引入 Starter 不等于已经采集 Logback 日志。

生产使用外部高可用 Collector，Chart 内置 Collector 只用于开发、验收或小型部署。

## 2. 数据契约

### 2.1 日志

- 生产 `BATCH_LOG_FORMAT=ecs`，本地留空使用可读 pattern。
- stdout 与 OTLP 同时保留：stdout 是节点级应急证据，OTLP 是集中查询主通道。
- Docker 全部服务使用 `local` logging driver，默认单文件 50 MiB、保留 3 个。
- 不允许应用同时写挂载目录日志，避免 stdout、文件、OTLP 三份重复。
- Collector 使用内存保护、批处理、磁盘队列和有限重试；禁止 debug exporter。
- 敏感属性在业务代码和 Collector redaction 两层拦截。

可信 MDC 字段以 `StructuredLogField` 为准。HTTP 请求头中的租户先写
`requestedTenantId`；只有 JWT/API-Key 校验后的租户才能写 `tenantId`。`tenantId`、
`traceId`、`requestId`、`jobInstanceId`、`taskId`、`fileId` 等高基数字段进入 Loki
structured metadata，不建立索引标签。

### 2.2 链路

- 应用 head sampling 固定为 `1.0`。
- Collector tail sampling 全量保留 ERROR 和超过 5 秒的慢链路，普通成功链路保留 10%。
- Tempo 是 Grafana 主查询后端，Jaeger 保留兼容查询。
- 业务 `trace_id` 与当前 OTel traceId 对齐，但不允许外部请求值覆盖 OTel TraceContext。

### 2.3 指标

- Prometheus 拉取应用 Actuator、Collector、Loki、Tempo、Kafka、PostgreSQL、Valkey、节点和容器指标。
- 指标标签只允许稳定低基数字段；租户、实例号、任务号不得作为 Prometheus label。
- Collector/Loki/Tempo 自身的拒收、队列、发送失败和存活状态必须纳入告警。

### 2.4 JVM 诊断

所有 Helm 工作负载挂载 `/var/log/app` 诊断卷，默认上限 4 GiB，保存 GC log、JFR 和
heap dump。它不是集中日志通道；Pod 重建后允许丢失，生产需要由节点诊断采集方案及时转储。

## 3. 本地启动

```bash
cp .env.example .env.local
# 按 .env.example 设置必填数据库密码和内部密钥
docker compose -f docker-compose.yml -f deploy/docker/compose/app.yml \
  -f deploy/docker/compose/observability.yml --env-file .env.local \
  --profile apps --profile replica up -d
```

要让应用上报到 Collector：

```bash
MANAGEMENT_OPENTELEMETRY_ENABLED=true \
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
docker compose -f docker-compose.yml -f deploy/docker/compose/app.yml \
  -f deploy/docker/compose/observability.yml --env-file .env.local \
  --profile apps --profile replica up -d
```

入口：Grafana `http://localhost:13000`、Prometheus `http://localhost:19090`、Jaeger
`http://localhost:16686`、Tempo API `http://localhost:13200`、Loki API
`http://localhost:13100`。

## 4. 生产配置

`helm/values-prod.yaml` 已强制：

```yaml
otel:
  enabled: true
  endpoint: http://otel-collector.monitoring.svc.cluster.local:4318
  samplingProbability: "1.0"
  logFormat: ecs
serviceMonitor:
  enabled: true
prometheusRule:
  enabled: true
```

外部 Collector 地址必须替换为目标集群真实 Service。生产不得把 `otelCollector.enabled`
与外部 Collector 同时作为两套独立采集主链路；迁移期需要双写时应明确容量、去重和回退窗口。

## 5. 验收

### 5.1 静态与配置验证

```bash
python3 scripts/ci/check-observability-contract.py
python3 scripts/ci/check-production-overlay-safety.py
bash scripts/ci/check-helm-prometheusrule-sync.sh
helm lint helm/batch-platform -f helm/values-prod.yaml
```

Collector 配置应使用对应版本官方镜像执行 `validate`；Prometheus 规则应使用
`promtool check rules`。PR gate 已执行仓库级可观测性契约检查。

### 5.2 运行时验收

1. 发起一条可控业务请求，记录响应 `traceId`。
2. 在 Tempo 按 `service.name` 和 `traceId` 找到完整链路。
3. 在 Loki 用同一 `traceId` 找到应用日志，确认 `tenantId` 来自认证身份。
4. 从 Loki 日志跳转 Tempo，确认链接到同一 trace。
5. 停止 Loki/Tempo 2 分钟后恢复，确认 Collector 队列下降且无持续发送失败。
6. 制造一条错误和一条超过 5 秒的测试链路，确认 tail sampling 均保留。
7. 检查 Collector、Loki、Tempo 告警在后端停止或拒收时触发。

常用查询：

```logql
{service_name="batch-orchestrator"} |= "ERROR"
{service_name="batch-console-api"} | tenantId="default-tenant"
{service_name=~"batch-.+"} | traceId="<trace-id>"
{service_name="batch-console-api"} | frontendEventType="error"
```

## 6. 故障处理

### 应用有 stdout、Loki 无日志

1. 确认 `MANAGEMENT_OPENTELEMETRY_ENABLED=true`。
2. 确认依赖中同时存在 Starter 和 OTel Logback appender。
3. 检查 Collector `otelcol_exporter_send_failed_log_records`、队列容量和 Loki 拒收指标。
4. 检查 Loki schema v13 与 `allow_structured_metadata=true`。

### 观测栈容量回收

1. 完成压测后优先执行
   ```bash
   bash scripts/local/cleanup-disk.sh --apply --include-observability-volumes
   ```
2. 清理前先确认业务日志与告警指标已归档（如需要保留审计排障时长）。
3. 清理后重新检查 `docker system df` 与 Prometheus/Loki 就绪状况。

### Tempo 无链路

1. 检查应用 head sampling 是否为 `1.0`。
2. 检查 Collector receiver refused、exporter send failed 和 queue capacity 指标。
3. 检查 Tempo `/ready` 及 discarded spans 指标。

### Collector 重启后积压丢失

- Docker 检查 `otel-collector-data` named volume。
- Helm 内置 Collector 使用 `emptyDir`，仅保证容器重启，不保证 Pod 重建；需要持久保证时切外部
  Collector 并使用持久卷或受管遥测网关。

### 磁盘增长

- `docker inspect <container>` 确认 logging driver 为 `local` 且存在轮转上限。
- 检查 Loki/Tempo/Prometheus 7 天保留策略及命名卷。
- 检查 `grafana-data`、`prometheus-data`、`loki-data`、`tempo-data`、`otel-collector-data` 已按策略回收；若容量逼近上限，先做一次 `--include-observability-volumes` 再重压测。
- 检查 `/var/log/app` 中 heap dump、JFR 和 GC log；诊断卷满不应改成无限容量。
- 本地 Compose 的 Prometheus 已增加 `retention.size=15GB` 与 `retention.time=7d` 双重边界，默认避免 tsdb 无限膨胀。

## 7. 告警与容量

至少关注：Collector 不可用、receiver refused、exporter send failed、队列超过 80%、Loki/
Tempo 不可用或丢弃数据、Outbox backlog、Kafka lag、Hikari 饱和、PostgreSQL 锁等待、Worker
lease circuit、SSE 订阅和回放失败。

全量 head sampling 会增加应用到 Collector 的网络和 CPU 开销，这是保留错误/慢链路的必要条件。
容量不足时优先水平扩展外部 Collector 和后端，再调整普通成功链路的 tail sampling；不要在应用
端降低 head sampling，否则错误和慢链路可能在到达 Collector 前已经丢失。

## 8. 权威文件

- `batch-common/src/main/resources/batch-defaults.yml`
- `deploy/docker/compose/observability.yml`
- `deploy/docker/observability/otel-collector.yml`
- `deploy/docker/observability/loki-config.yml`
- `deploy/docker/observability/tempo.yml`
- `deploy/docker/observability/prometheus-batch-rules.yml`
- `helm/batch-platform/templates/otel-collector.yaml`
- `helm/values-prod.yaml`
- `docs/design/logging-architecture.md`
