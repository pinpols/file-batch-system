# ADR-013 · 分布式 Tracing — Micrometer Observation + OpenTelemetry

- **Status**: Accepted · **Implemented**（2026-05-03）
- **Date**: 2026-05-03
- **Supersedes**: —
- **Related**: ADR-002（outbox）/ ADR-010（trigger 异步链路）/ docs/analysis/orchestrator-vs-industry-2026-05-03.md §2.6

---

## 背景

排查 "trigger → orchestrator → worker → ack" 全链路问题需要 grep N 个服务日志按 `trace_id` 关联，效率几倍折损。业界主流调度系统（Temporal / Airflow / Argo）都已集成 OpenTelemetry，提供 timeline 视图 + span 树。

落地前已有：业务字段 `trace_id` 透传（V77/V78）；Kafka `setObservationEnabled(true)`；Spring Boot 4.x `ObservationRegistry`；`docker/compose/observability.yml`（Collector / Tempo / Jaeger / Loki / Grafana）。

本 ADR **已实现闭环**（2026-05-03）：


| 项               | 说明                                                                                                                                                                                                     |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| OTel 桥接 + 导出    | `batch-common`：`micrometer-tracing-bridge-otel`、`opentelemetry-exporter-otlp`（JDK sender），BOM 由 Spring Boot 4.1.0 管理                                                                                   |
| `@Observed` AOP | `BatchObservabilityAutoConfiguration` → `ObservedAspect` bean                                                                                                                                          |
| 默认配置            | `batch-defaults.yml`：`management.tracing.sampling.probability=${OTEL_SAMPLING_PROBABILITY:1.0}`；`management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4318}/v1/traces` |
| 种子 manual span  | `orch.launch`、`orch.partition.dispatch`、`orch.workflow.param-resolve`（后续按需追加）                                                                                                                          |
| 业务 trace ↔ OTel | `OtelTraceContext.currentTraceIdOrNull()`；`IdGenerator.newTraceId()` 优先当前 OTel span traceId（详见 §后果）                                                                                                    |


## 决策（摘要）

**采用 Micrometer Observation + OpenTelemetry 桥接**：依赖与导出走 Boot 管理的 OTLP；观测语义用 `@Observed` + 自动 instrument（HTTP/Kafka/JDBC 等）；业务持久化 `trace_id` 与运行时 TraceContext 的对齐见 §后果「已实现」小节。

## 自动 instrument 覆盖（零业务改动获得）

Spring Boot 4.x + 已加依赖后自动获得：


| 类别             | Span 名                | 来源                                       |
| -------------- | --------------------- | ---------------------------------------- |
| HTTP server    | `http.server.request` | spring-web                               |
| HTTP client    | `http.client.request` | RestClient / RestTemplate                |
| JDBC           | `jdbc.query`          | spring-data                              |
| Kafka producer | `<topic> send`        | KafkaTemplate（已 enable observation）      |
| Kafka consumer | `<topic> receive`     | listener container（已 enable observation） |
| Scheduled      | `task.scheduled`      | spring-context（部分支持）                     |


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

**已实现（记入档案 · 2026-05-03）**：

- 业务持久化 `trace_id` 与 OTel traceId **入口对齐**：`OtelTraceContext.currentTraceIdOrNull()`；`IdGenerator.newTraceId()` 优先取当前 OTel span 的 traceId（32 hex）；无 OTel context 时 fallback UUID。HTTP/Kafka 自动 instrument 建立 current span 后，新建业务的 `trace_id` 与 Jaeger/Tempo 查询一致。**反向**（仅用外部传入的业务 trace_id 覆盖 OTel TraceContext）按 OTel SDK 惯例不做 — 外部传入值仍可走 baggage / MDC / 日志并行关联。

**后续演进（可选立项）**：

- **运维 dashboard**：Grafana 已配但未导入 batch 专属 trace dashboard。
- **采样策略动态调整**：当前固定 `OTEL_SAMPLING_PROBABILITY`，生产可考虑 head-based / tail-based 自适应采样。

## 测试覆盖

- `BatchObservabilityAutoConfiguration`：编译 + bean 装配
- `OtelTraceContextTest`：`currentTraceIdOrNull()` 在有/无 active span 下的行为
- 业务路径 `@Observed`：测试上下文未启用 AOP 时为 noop，不破坏既有单测
- **推荐手工验收**：`docker compose -f docker-compose.yml -f docker/compose/observability.yml up -d`，触发一次 launch，在 Jaeger/Tempo 查 `orch.launch` 是否串联 Kafka send/receive

## 演化时间线


| 日期         | 演化                                                                                                                      |
| ---------- | ----------------------------------------------------------------------------------------------------------------------- |
| ~2026-04   | docker/compose/observability.yml 落地（Tempo/Jaeger/Loki/Grafana/OTel Collector）                                           |
| ~2026-04   | batch-common pom 加 micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp                                         |
| ~2026-04   | batch-defaults.yml 加 management.tracing 配置                                                                              |
| 2026-05-03 | **本 ADR**：补 ObservedAspect bean + 3 个种子 manual span，闭环可用                                                                |
| 2026-05-03 | **业务 trace_id 桥接**：`OtelTraceContext` + `IdGenerator.newTraceId()` 优先用 OTel current span traceId，业务字段与 OTel timeline 一致 |
