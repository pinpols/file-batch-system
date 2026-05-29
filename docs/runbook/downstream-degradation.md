# Downstream 降级策略清单

> P1-B 落地阶段一(2026-05-30)— 集中管理 BE 各 `*ProxyService` 调下游服务的降级 / fail-fast 决策。
>
> 实现工具:[DownstreamFallback](../../batch-common/src/main/java/com/example/batch/common/resilience/DownstreamFallback.java) — 当前是手写 try/catch + Micrometer metrics 的轻量集中模板。
> 后续 Resilience4j 引入(SB4 兼容性确认后)只需替换本类内部实现,调用方不变。

---

## 策略表

| Service:Endpoint | 类型 | 策略 | Fallback 返回 | Owner | 实现状态 |
|---|---|---|---|---|---|
| trigger:GET /list | 只读 | 降级 | empty list + WARN | ops | ✅ PR #97 |
| trigger:GET /scheduler-status | 只读 | 降级 | `{status: UNKNOWN}` | ops | ✅ PR #99 |
| trigger:POST /pause-all | 写 | fail-fast | — | ops | ✅ PR #99 |
| trigger:POST /resume-all | 写 | fail-fast | — | ops | ✅ PR #99 |
| trigger:POST /{action} | 写 | fail-fast | — | ops | ✅ PR #99 |
| trigger:POST /pause-tenant | 写 | fail-fast | — | ops | ✅ PR #99 |
| trigger:POST /resume-tenant | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /instances/{id}/{action} | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /instances/partitions/{id}/{action} | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /workflow-runs/{id}/{action} | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /workflow-runs/{id}/skip-node | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:GET /scheduler/snapshot | 只读 | fail-fast<sup>1</sup> | — | ops | ✅ PR #99 |
| orchestrator:GET /scheduler/snapshot/history | 只读 | 降级 | empty list | ops | ✅ PR #99 |
| orchestrator:POST /outbox/cleanup | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /outbox/republish | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /batch-days/operate | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:POST /forensic/export | 写 | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:GET /forensic/export/{id}/download | 写<sup>2</sup> | fail-fast | — | ops | ✅ PR #99 |
| orchestrator:GET /cluster-diagnostic | 只读 | 降级 + 缓存 stale | 上次成功值 | ops | ⏳ TODO(独立 PR,需补 cache 层) |
| dashboard:GET /sla-report | 只读 | 降级 + 缓存 stale | last-known cache | ops | ⏳ TODO(独立 PR,需补 cache 层) |
| push:POST /send-vapid | 异步 | retry 3 + dead-letter | — | notif | ⏳ TODO(独立 epic,走 spring-retry / outbox) |

<sup>1</sup> 强类型响应,FE 不接受空对象 → fail-fast 让前端显示真实错误而不是渲染空字段。
<sup>2</sup> 文件下载,降级无意义(下载失败必须告诉用户),按 fail-fast 处理。

**Legend**:
- ✅ 已用 `DownstreamFallback` 接入
- ⏳ TODO 待迁(目前可能裸调或散写 try/catch)
- 🚫 不接入(走业务自己的特殊语义)

## 原则

### 何时用 `callOrFallback`(降级)

满足全部:

1. 读路径(GET / 查询)
2. fallback 值业务可接受(空列表 / "未知" 状态 / stale cache)
3. UI 可降级展示(显示 banner 而不是整页崩)

### 何时用 `callOrThrow`(fail-fast + 统一 metrics)

任一:

1. 写路径(POST / PUT / DELETE)— 业务必须知道失败
2. 上游失败要触发上游业务异常(如 `BizException`)
3. 鉴权 / 幂等 / 关键性 endpoint

### 何时不用本工具

1. **异步任务** — 走 retry queue + dead-letter,不是同步调用(用 spring-retry 或 outbox)
2. **批量 N+1 调用** — 性能敏感,本工具开销不大但语义上应该批量
3. **WebFlux 异步流** — 当前项目都是 sync RestClient,未来引入 reactive 时再加 reactive 变体

## metrics

所有走本工具的调用都自动上报:

```
downstream.call.total{service=<svc>, op=<op>, outcome=success|fallback|failure}
downstream.call.total{service=<svc>, op=<op>, outcome=fallback|failure, exception=<class>}
```

Grafana 大盘指标(待建):

- 每个 `service` 的 fallback rate
- 每个 `service` 的 P99 latency(需另加 Timer)
- Alertmanager 规则:`fallback rate ≥ 50% for 5m` → page

## 守护(待加)

ArchUnit 规则(后续 PR):
- 所有 `*ProxyService` 的 public 方法体内不允许直接 `try { ... } catch (RestClientException ...)`,必须走 `DownstreamFallback`
- 所有 `*ProxyService` 必须构造器注入 `DownstreamFallback`

## 未来升级:Resilience4j

当前实现是手写 try/catch + 集中模板,**缺**:

- ❌ Circuit breaker(failureRate ≥ 阈值 → 自动断开,定时半开探活)
- ❌ TimeLimiter(超时不阻塞调用方)
- ❌ Bulkhead(隔离 thread pool)
- ❌ Rate limiter

升级路径:`DownstreamFallback` 内部实现替换为 `CircuitBreakerRegistry.circuitBreaker(service).executeSupplier(primary)`,API 不变。前提:Resilience4j 出 SB4 兼容版本(预计 2026 Q3)。

待办 issue:`P1-B Phase 2: 引 Resilience4j(SB4 兼容验证)`

## 迁移指南

把散在 ProxyService 里的手写 try/catch 迁过来:

```java
// Before
public List<Foo> listFoos() {
  try {
    return client.get().retrieve().body(List.class);
  } catch (RestClientException ex) {
    log.warn("foo downstream down: {}", ex.getMessage());
    return List.of();
  }
}

// After
public List<Foo> listFoos() {
  return downstreamFallback.callOrFallback(
      "foo", "list",
      () -> client.get().retrieve().body(List.class),
      ex -> List.of());
}
```

迁完一个写一个,在本文档的策略表把 ⏳ TODO 改 ✅ + 备注 commit 号。
