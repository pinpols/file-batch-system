# 分布式 Tracing 运维手册

> **目的**：本系统集成 Micrometer Observation + OpenTelemetry，提供 trigger → orchestrator → worker → ack 全链路 timeline。本文档面向运维 / 排障。
>
> **设计**：见 [ADR-013 distributed-tracing](../architecture/adr/ADR-013-distributed-tracing.md)。

---

## 1. 启用方法

### 1.1 本地开发

```bash
# 启动 collector + Tempo + Jaeger + Grafana（叠加 observability stack）
docker compose -f docker-compose.yml -f docker/compose/observability.yml up -d

# 启动业务模块（默认配置已指向 http://otel-collector:4318）
./scripts/local/start-all.sh
```

默认采样 100%（`OTEL_SAMPLING_PROBABILITY=1.0`），所有请求都生成 trace。

### 1.2 生产环境

环境变量：

| 变量 | 默认 | 生产建议 |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318` | 指向 K8s 集群内 collector service |
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | `0.1`（10% 采样降低开销） |

→ collector 容量按"应用 QPS × 采样率 × ~1KB/span"评估。

---

## 2. 查 trace

### 2.1 Jaeger UI（端口 16686）

```
http://localhost:16686
```

- Service 选 `batch-orchestrator` / `batch-trigger` / `batch-worker-import` 等
- 按 **`trace_id`（业务字段）搜索**：已与 OTel traceId 对齐（见下文 §4.3）；也可用 Jaeger UI 自带的 traceId / span tag 搜索

### 2.2 Tempo + Grafana（端口 3000）

```
http://localhost:3000   →  Explore  →  Tempo data source
```

支持 LogQL-style 查询 + 关联 Loki 日志。

### 2.3 已有的种子 span（按服务）

| 服务 | Span 名 | 触发点 |
|---|---|---|
| orchestrator | `orch.launch` | `DefaultLaunchService.launch` 整次 launch（API + 内部） |
| orchestrator | `orch.partition.dispatch` | `DefaultPartitionDispatchService.dispatch` 每次分区派发 |
| orchestrator | `orch.workflow.param-resolve` | `WorkflowParamResolver.resolve` 节点参数 DSL 解析 |
| 自动（HTTP）| `http.server.request` | spring-web，所有 REST 入口 |
| 自动（Kafka producer）| `<topic> send` | 4 个模块的 KafkaTemplate（已 enable observation） |
| 自动（Kafka consumer）| `<topic> receive` | 同上 |
| 自动（JDBC） | `jdbc.query` | spring-data |

---

## 3. 给业务路径加 span

### 3.1 用 `@Observed` 注解（推荐）

```java
import io.micrometer.observation.annotation.Observed;

@Service
public class MyService {

  @Observed(name = "my.module.action", contextualName = "my.module.action")
  public Result doSomething(Request req) {
    // ... 业务逻辑
  }
}
```

要求：
- 方法所在 bean 通过 Spring AOP 代理才能拦截（非 final、public）
- **不能自调用**（`this.method()` 走原 reference，跳过 proxy） — 改成走 `selfProvider`/`@Lazy self` 注入
- 注解被 `BatchObservabilityAutoConfiguration#observedAspect` bean 拦截

### 3.2 手工 ObservationRegistry（细粒度）

```java
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@RequiredArgsConstructor
@Service
public class MyService {

  private final ObservationRegistry observationRegistry;

  public Result doSomething(Request req) {
    return Observation.createNotStarted("my.module.action", observationRegistry)
        .lowCardinalityKeyValue("tenant", req.getTenantId())
        .observe(() -> {
          // ... 业务逻辑
          return result;
        });
  }
}
```

适合需要带 tag（key-value）/ 自定义 timing 的场景。

### 3.3 命名约定

- 格式 `<module>.<domain>.<action>` 全小写 + dot 分隔
- module: `orch` / `trigger` / `worker.import` / `worker.export` / `worker.process` / `worker.dispatch` / `console`
- 例：`orch.launch` / `worker.import.parse` / `console.config.publish`

---

## 4. 排障

### 4.1 没看到任何 trace

| 现象 | 排查 |
|---|---|
| Jaeger UI 完全空 | 1) collector 是否启动：`docker logs batch-otel-collector` <br> 2) 应用是否能连 collector：grep app log `Failed to export spans` <br> 3) 采样率：`OTEL_SAMPLING_PROBABILITY` 是否 `0` |
| 看到自动 span 但没业务 span | `@Observed` 注解是否在 final/private 方法上（被 AOP 跳过）；或方法是 self-call |
| trace 链断（trigger 看到，orch 看不到对应消费 span）| Kafka observation 是否启用：grep `setObservationEnabled` 看模块覆盖度 |

### 4.2 trace 太多 collector 撑不住

降低采样：

```bash
export OTEL_SAMPLING_PROBABILITY=0.1   # 10%
# 重启业务模块
```

或在 collector 侧加 tail-based sampling（修改 `docker/observability/otel-collector.yml`）。

### 4.3 业务 trace_id 与 OTel traceId 关系

**2026-05-03 已自动桥接**：`IdGenerator.newTraceId()` 优先取当前 OTel active span 的 traceId（32 hex chars），无 OTel context 时 fallback UUID。HTTP/Kafka 入口处自动 instrument 已建立 OTel current span → **业务持久化字段 `trace_id` 自然与 OTel traceId 一致**。

排障时:
- 用业务字段 `trace_id`（在 SQL 表 / MDC / 日志里）粘到 Jaeger/Tempo 搜索框 → 直接定位 timeline
- 反向: 在 Jaeger/Tempo 看到的 traceId 也能直接 SQL `WHERE trace_id = '...'` 查业务表

边界 case:
- 在没有 OTel context 的内部触发（如某些 unit test / 后台脚本入口），`IdGenerator.newTraceId()` 仍生成 UUID 格式（也是 32 hex chars）
- 入参带 `trace_id` 的请求（如 trigger 重发）会保留入参值，不会被 OTel 覆盖（`resolveTraceId` 优先级）

---

## 5. 采样 + 性能开销参考

| 采样率 | 1000 QPS 场景 | collector 写入 |
|---|---|---|
| 1.0 (100%) | 1000 trace/s × ~5 span/trace × ~1KB = ~5MB/s | 高 |
| 0.1 (10%) | 100 trace/s × ~5 span = ~500KB/s | 中 |
| 0.01 (1%) | 10 trace/s × ~5 span = ~50KB/s | 低，但极端故障 trace 容易丢 |

→ 生产 0.1 起步，配合 collector tail-based sampling 保留所有 error trace + 10% normal trace。

---

## 6. 相关

- [ADR-013 distributed-tracing](../architecture/adr/ADR-013-distributed-tracing.md) — 决策档
- [docker/compose/observability.yml](../../docker/compose/observability.yml) — 本地 collector + Tempo/Jaeger 编排
- [docker/observability/otel-collector.yml](../../docker/observability/otel-collector.yml) — collector 流水线配置
- [batch-defaults.yml `management.tracing`](../../batch-common/src/main/resources/batch-defaults.yml) — 默认值
