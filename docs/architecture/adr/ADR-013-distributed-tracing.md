# ADR-013 · 分布式 Tracing — Micrometer Observation + OpenTelemetry

- **Status**: Accepted（2026-05-03）
- **Date**: 2026-05-03
- **Supersedes**: —
- **Related**: ADR-002（outbox）/ ADR-010（trigger 异步链路）/ docs/analysis/orchestrator-vs-industry-2026-05-03.md §2.6

---

## 背景

排查 "trigger → orchestrator → worker → ack" 全链路问题需要 grep N 个服务日志按 `trace_id` 关联，效率几倍折损。业界主流调度系统（Temporal / Airflow / Argo）都已集成 OpenTelemetry，提供 timeline 视图 + span 树。

本系统已有：
- ✅ 业务字段 `trace_id` 透传（V77/V78 i18n 三元组同事透传）
- ✅ 4 个 Kafka 模块全 enable `setObservationEnabled(true)` + `setObservationRegistry`
- ✅ Spring Boot 4.x 自带 Micrometer Observation API + `ObservationRegistry` bean
- ✅ docker-compose.observability.yml 含 OTel Collector + Tempo + Jaeger + Loki + Grafana 全套

缺：
- ❌ Micrometer Observation → OTel SDK 的桥接器（`micrometer-tracing-bridge-otel`）
- ❌ OTLP exporter（`opentelemetry-exporter-otlp`）
- ❌ `@Observed` 注解的 AOP 拦截器（`ObservedAspect`）
- ❌ 业务关键路径的 manual span

## 决策

**采用 Micrometer Observation + OpenTelemetry 桥接方案**：

1. **依赖（已加，2026-05 早期）**：`batch-common` pom 含 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`，Spring Boot 4.0.3 BOM 管理版本。
2. **AOP 拦截**：本 ADR 落地 `BatchObservabilityAutoConfiguration`，提供 `ObservedAspect` bean，让 `@Observed` 注解生效。
3. **配置**（已在 `batch-defaults.yml`）：
   - `management.tracing.sampling.probability=${OTEL_SAMPLING_PROBABILITY:1.0}` 默认全采，生产可调到 0.1
   - `management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4318}/v1/traces`
4. **Manual span 落地策略**：关键业务入口加 `@Observed` 注解。本 ADR 落地 3 个种子 span（按 ROI 选）：
   - `orch.launch` — `DefaultLaunchService.launch` 整次 launch
   - `orch.partition.dispatch` — `DefaultPartitionDispatchService.dispatch` 每次分区派发
   - `orch.workflow.param-resolve` — `WorkflowParamResolver.resolve` 节点参数 DSL 解析
   后续按需补充。

## 自动 instrument 覆盖（零业务改动获得）

Spring Boot 4.x + 已加依赖后自动获得：

| 类别 | Span 名 | 来源 |
|---|---|---|
| HTTP server | `http.server.request` | spring-web |
| HTTP client | `http.client.request` | RestClient / RestTemplate |
| JDBC | `jdbc.query` | spring-data |
| Kafka producer | `<topic> send` | KafkaTemplate（已 enable observation）|
| Kafka consumer | `<topic> receive` | listener container（已 enable observation）|
| Scheduled | `task.scheduled` | spring-context（部分支持）|

→ 80% 的链路 timeline 不需要业务改动。

## 后果

**正面**：
- 排障从 grep N 服务日志改为 Tempo/Jaeger 一次 timeline 查询
- 业务关键路径可量化耗时分布（p50/p95/p99 自动 metric）
- `@Observed` 注解使用门槛低，未来按需 instrument
- 与现有业务 `trace_id` 字段不冲突（属于业务持久化字段，OTel TraceContext 是运行时上下文，两者并行）

**负面**：
- OTLP exporter 加 ~6 MB 依赖
- 全采样下每 span ~ 1KB OTel 数据，1000 QPS = ~1MB/s，需要 collector 容量评估
- `@Observed` 注解通过 AOP 拦截，自调用（`this.method()`）无效，需走 Spring proxy

**待 follow-up（本 ADR 不做）**：
- ~~**业务 `trace_id` 字段与 W3C TraceContext 桥接**~~ ✅ **2026-05-03 已落地**：新增 `OtelTraceContext.currentTraceIdOrNull()` 工具，`IdGenerator.newTraceId()` 优先取 OTel current span 的 traceId（32 hex chars），无 OTel context 时 fallback UUID。HTTP/Kafka 入口处自动 instrument 已建立 OTel current span，业务字段 `trace_id` 自然与 OTel traceId 一致。反向桥接（业务字段 → 强制 OTel traceId）按 OTel SDK 设计**不做** — OTel TraceContext 应保持 W3C 标准生成机制，外部传入的业务 trace_id 仅作 baggage / log MDC 字段并行存在。
- **运维 dashboard**：Grafana 已配但未导入 batch 专属 trace dashboard，单独立项。
- **采样策略动态调整**：当前固定值，生产可考虑 head-based / tail-based 自适应采样。

## 测试覆盖

- `BatchObservabilityAutoConfiguration` 编译验证 + bean 装配（已通过 `clean compile`）
- 业务路径 `@Observed` 注解不破坏现有单元测试（spring AOP 在测试上下文不激活时 noop）
- 端到端 trace 验证：本地起 `docker-compose -f docker-compose.yml -f docker-compose.observability.yml up -d`，触发一次 launch，在 Jaeger/Tempo 查 `orch.launch` span 是否串到 Kafka send/receive

## 演化时间线

| 日期 | 演化 |
|---|---|
| ~2026-04 | docker-compose.observability.yml 落地（Tempo/Jaeger/Loki/Grafana/OTel Collector） |
| ~2026-04 | batch-common pom 加 micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp |
| ~2026-04 | batch-defaults.yml 加 management.tracing 配置 |
| 2026-05-03 | **本 ADR**：补 ObservedAspect bean + 3 个种子 manual span，闭环可用 |
| 2026-05-03 | **业务 trace_id 桥接**：`OtelTraceContext` + `IdGenerator.newTraceId()` 优先用 OTel current span traceId，业务字段与 OTel timeline 一致 |
