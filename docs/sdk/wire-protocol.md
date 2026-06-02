# SDK ↔ Orchestrator 通讯协议 / 故障感知

> **协议契约权威源(双轨)**:本文与 [`docs/api/orchestrator-internal.openapi.yaml`](../api/orchestrator-internal.openapi.yaml) 是 wire 层协议的两份权威源 ——
> 本文给**读者视图**(故障感知矩阵 + 时序约束 + 错误码语义 + 重试退避规则),yaml 给**机器视图**(端点 / schema / response code)。
> Java SDK 和未来 BYO(Bring-Your-Own)SDK(Go / Python / Node / .NET)必须基于这两份生成 / 校验实现。BYO 接入指南见
> [`docs/sdk/byo-sdk-guide.md`](byo-sdk-guide.md);language-agnostic 契约 fixtures 见 [`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/)。

> 配套 [`docs/sdk/quickstart.md`](quickstart.md) 与 [`docs/sdk/troubleshooting.md`](troubleshooting.md)。本文是「运行期视图」:把 ADR-035 的协议节、配置节、生命周期节合成一张全景图,让租户接入或调参时不需翻多处。

## 1. 双通道分工

```
┌──────────────────────────────────────────────────────────────────────────┐
│            HTTP /internal/*  ── 控制面,同步,JDK HttpClient             │
│   register / heartbeat / deactivate / claim / report / renew(lease)      │
└──────────────────────────────────────────────────────────────────────────┘
                              ▲                            │
                              │                            ▼
                  ┌─────────────────┐         ┌────────────────────────┐
   self-hosted    │  SDK worker     │ ◄──────── Orchestrator           │
   worker (租户)  │  BatchPlatform- │           WorkerController +     │
                  │  Client          │           TaskController         │
                  └─────────────────┘         └────────────────────────┘
                              ▲                            │
                              │                            ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  Kafka topic `batch.task.dispatch.<tenant>.*` ── 数据面,异步,单向投递   │
│  consumer group 独立 per tenant,wildcard 订阅 + capacity-aware pause     │
└──────────────────────────────────────────────────────────────────────────┘
```

| 通道 | 方向 | 谁主动 | 频次 | SDK 实现 | Orch 实现 |
|---|---|---|---|---|---|
| `POST /internal/workers/register` | SDK → orch | SDK start 一次 | 1 | `PlatformHttpClient.register()` | `WorkerController:35` |
| `POST /internal/workers/{code}/heartbeat` | SDK → orch | `HeartbeatScheduler` | 30s 默认 | `HeartbeatScheduler.tick()` | `WorkerController:42-45` |
| `POST /internal/workers/{code}/deactivate` | SDK → orch | SDK `stop()` | 1 | `BatchPlatformClient.stop()` 收尾 | `WorkerController.deactivate()` |
| `POST /internal/tasks/{id}/claim` | SDK → orch | 收 Kafka 消息后 | 每 task 1 | `TaskDispatcher.claim()` | `TaskController.claim()` |
| `POST /internal/tasks/{id}/report` | SDK → orch | task 终态时 | 每 task 1 | `TaskDispatcher.report()` | `TaskController.report()` |
| `POST /internal/tasks/{id}/renew` | SDK → orch | `LeaseRenewalScheduler` | 60s 默认 | `LeaseRenewalScheduler.tick()` | `TaskController.renew()` |
| Kafka `batch.task.dispatch.<tenant>.*` | orch → SDK | orch 投递 → SDK poll | 持续 | `KafkaTaskConsumer.poll()` | producer in `*OrchestratorService` |

**为什么 HTTP + Kafka 不合并?** ADR-035 §3:
- HTTP 同步天然适合 register / claim / report 这类「需要返回值」的控制流(orch 给 SDK lease ttl、effective config、cancel flag)
- Kafka 异步天然适合 dispatch:orch 不关心 SDK 何时拿到,Kafka 保留 + at-least-once + tenant 隔离 + 横向扩展
- 反向 push 故意没做:避免每个租户 SDK 暴露入向端口,降低边界面积

## 2. 控制反向回流(orch → SDK)

Orch 不主动 push 任何指令。所有"平台→worker"信号搭便车在两个响应体里:

### 2.1 心跳响应 `WorkerHeartbeatResponse`

```java
{
  "platformStatus": "NORMAL" | "DEGRADED" | "PAUSED" | "DRAINING",
  "shouldDrain": true,                     // 平台要求 worker 进 DRAINING
  "desiredMaxConcurrent": 4,               // 平台动态压并发(可比 SDK 配的小)
  "pausedTaskTypes": ["http", "shell"],    // 临时屏蔽某些 type
  "nextHeartbeatHint": "PT15S"             // 平台请求改心跳频率(SDK 暂未消费,见 §6)
}
```

SDK 收到后 `TaskDispatcher.applyPlatformDirective()`:
- `PAUSED` / `DRAINING` → `KafkaTaskConsumer` pause assignment,新 task 不再投
- `pausedTaskTypes` 命中 → `onMessage()` 直接 drop(返 Kafka offset 不 commit,后续平台撤销后还能 redeliver)
- `desiredMaxConcurrent` 当场调整(原子)

### 2.2 lease 续约响应

```java
{
  "leaseUntil": "2026-06-02T10:30:00Z",
  "cancelRequested": true     // 平台要求取消该 task
}
```

`cancelRequested=true` → SDK 调 `dispatcher.markCancelRequested(taskId)` → handler 通过 `CancellationSignal.isCancellationRequested()` 检测。

> **关键延迟**:cancel 信号最大延迟 = `leaseRenewInterval`(默认 60s)。**没有更快的反向 push channel**,这是 ADR-035 §11 的设计权衡。

## 3. 故障感知矩阵(谁感知 / 多久感知 / 反应)

| 故障类型 | 谁感知 | 时延上界 | 反应 |
|---|---|---|---|
| **HTTP 401/403**(凭据失效) | SDK | 立刻 | `TaskDispatcher` 置 `fatal`,后续 `onMessage` 拒收,等 K8s liveness 重启 |
| **HTTP 5xx / 网络抖动** | SDK | 立刻 | 指数退避(200ms 基,默认 3 次)→ 仍失败 log + drop;下次 tick 再来 |
| **HTTP 409**(idempotent 冲突) | SDK | 立刻 | log INFO 视为成功,不重试 |
| **持续 4xx 活锁** | SDK | `clientErrorFailFastThreshold` 次后 | 累计 5 次(默认)4xx 后 fail-fast |
| **worker 心跳停更**(JVM 卡 / 网络隔离 / 容器迁移) | orch | `timeoutSeconds(90) + graceSeconds(30) = 120s` | `WorkerHeartbeatTimeoutScheduler` 每 30s 扫,`ONLINE → OFFLINE`,`DefaultWorkerSelector` 不再选中 |
| **僵尸 worker 抓任务**(被选中后 OFFLINE) | orch | partition rebalance 触发 | partition 自动释放 → 其他 ONLINE worker 接 |
| **单 task lease 失效**(orch 已回收) | SDK | `leaseRenewInterval`(60s) | `renew` 收 404 → log warn,handler 继续跑(报告时被 orch 拒) — 浪费算力,见 §6 短板 |
| **Kafka in-flight 满**(SDK 处理跟不上) | SDK | 下一次 poll | `KafkaTaskConsumer.applyBackpressure()` pause assignment,Kafka 不再投,直到 in-flight 降回 |
| **Kafka SASL 凭据错** | SDK | 第一次 poll | 当前无 fail-fast,会 retry — 见 §6 待补 |
| **平台主动取消任务** | SDK | `leaseRenewInterval`(60s) | `cancelRequested` 经 renew 响应回来,handler 通过 `CancellationSignal` 检测 |
| **SDK 优雅停止** | orch | `deactivate` 调用立刻 + 心跳停更兜底 | DB `status → DRAINING → OFFLINE` |
| **SDK 进程崩(SIGKILL)** | orch | 心跳停更 120s | `OFFLINE` + Kafka partition rebalance |

## 4. 典型调用顺序(完整生命周期)

```
启动:
  SDK ──register──► orch  落 worker_registry, status=ONLINE
  SDK ──Kafka.subscribe──► broker  (wildcard `batch.task.dispatch.<tenant>.*`)
  SDK 启 HeartbeatScheduler  (30s 周期, fixedDelay)
  SDK 启 LeaseRenewalScheduler  (60s 周期, fixedRate)

每个任务:
  orch ──Kafka dispatch──► SDK
  SDK 收到 → claim ──HTTP POST /internal/tasks/{id}/claim──► orch
                              ◄── { effectiveConfig, leaseUntil }
  SDK handler.execute(ctx)    (ctx 持有 CancellationSignal + ProgressReporter)
  SDK ──report──► orch        SUCCESS / FAILED / TERMINATED 终态

心跳(每 30s):
  SDK ──POST /heartbeat──► orch
       ◄── WorkerHeartbeatResponse(directive 5 字段)
       SDK 应用 directive: 改 maxConcurrent / 进 PAUSED / drain 等

续约(每 60s,遍历 in-flight):
  for each in-flight taskId:
    SDK ──POST /tasks/{id}/renew──► orch
         ◄── { leaseUntil, cancelRequested }
         true → markCancelRequested(taskId) → handler 自检

停止(SDK 端 stop(timeout) 触发):
  → draining=true        立刻拒新消息
  → kafkaConsumer.wakeup()
  → 等 in-flight drain (timeout 40%)
  → executor.awaitTermination (timeout 60%)
  → ──POST /deactivate──► orch   DB status=OFFLINE
```

## 5. 配置与时序对齐(运维必读)

| 配置项 | 默认 | 必须满足 |
|---|---|---|
| `heartbeatInterval` | 30s | < orch `timeoutSeconds`(90s) / 3 |
| `leaseRenewInterval` | 60s | < orch lease ttl / 2(避免 lease 提前回收) |
| `httpTimeout` | 10s | < `heartbeatInterval` / 3(避免心跳堆积) |
| `kafkaPollInterval` | 200ms | 看场景,IO 密集可加大 |
| orch `timeoutSeconds` | 90s | — |
| orch `graceSeconds` | 30s | + `timeoutSeconds` 留 5-12 次漏跳容忍 |

**最常见配错**:`leaseRenewInterval >= orch lease ttl`,导致任务被无故回收,handler 跑完报告却被拒。SDK 当前**无 cross-field 校验**,见 §6。

## 6. 当前最大短板(对应深度审查 [`docs/analysis/2026-06-02-sdk-atomic-fe-deep-review.md`](../analysis/2026-06-02-sdk-atomic-fe-deep-review.md))

| 短板 | 现状 | 改进进度 |
|---|---|---|
| Kafka SASL 凭据错时无 fail-fast,会 retry 风暴 | 待补 | TOP #1 部分(Lane A 完成 stop 超时,Kafka pause 已有,SASL fail-fast 仍缺) |
| cancel 信号无主动 push,延迟 60s | 设计权衡(避免反向 channel)| 不动 |
| `nextHeartbeatHint` orch 下发但 SDK 未消费 | 待补 — SDK 接到但调度器不动态调速 | follow-up |
| heartbeat / lease 超时阈值无 cross-field 校验 | 运维配错只能事后排查 | `troubleshooting.md` 写了排查路径,代码层无校验 |
| worker fingerprint console 看板 | BE 端点已补(PR #240 Lane D);FE 看板未做 | 等 FE follow-up |
| 凭据走 parameters / descriptor 泄露 | `SensitiveDataValidator`(PR #242 Lane C)拦截 register 路径 | atomic executor 入口注入待 Lane C/B PR 合后回头补 |

## A. 协议版本与 schemaVersion(BYO SDK 兼容矩阵)

派单消息 `TaskDispatchMessage.schemaVersion`(Phase 0 起携带)是协议演进的主版本锚。SDK 侧 [TaskDispatchMessage.java](../../batch-worker-sdk/src/main/java/com/example/batch/sdk/dispatcher/TaskDispatchMessage.java):

```java
public static final Set<String> SUPPORTED_MAJOR_VERSIONS = Set.of("v1", "v2");
// 缺字段时 fallback 主版本(老 orchestrator 没填 schemaVersion 时按 v1 解析)
```

**BYO SDK 必须等价实现**:

| schemaVersion 取值 | BYO SDK 行为 |
|---|---|
| 缺字段 / null | 当 `v1` 解析(老 orchestrator 兼容) |
| `v1`、`v2`、`v2-rc`(任何已知 major) | 正常处理 |
| `v3+` / 未知 major | **直接 reject 消息**(log error, 不 commit Kafka offset),避免按错版本反序列化字段错乱 |

**Jackson 反序列化策略**:所有 DTO 必须 `@JsonIgnoreProperties(ignoreUnknown = true)` 等价行为 ——
平台 forward 加新字段时旧 BYO SDK 不能崩。Phase 0 引入新字段只能是 nullable optional,绝不删除老字段。
重命名 / 删除走 [`docs/runbook/sdk-dual-rollout.md`](../runbook/sdk-dual-rollout.md) 双轨纪律(平台先双写,SDK 全量升级后再删旧字段)。

## B. 错误码语义(BYO SDK 必须等价分类)

### HTTP `/internal/*` 端点状态码(对照 [PlatformHttpException.java](../../batch-worker-sdk/src/main/java/com/example/batch/sdk/internal/PlatformHttpException.java))

| HTTP code | 语义 | BYO SDK 必须实现的行为 |
|---|---|---|
| `200` | 成功 | 正常解包 |
| `401` / `403` | 凭据失效 / tenant 越权 | **fail-fast**:`TaskDispatcher` 置 fatal,后续 onMessage 拒收,等容器重启;**不重试** |
| `404` | workerCode / taskId 不存在 | log warn,放弃当前请求;后续心跳如继续 404 视为被运维清掉,fail-fast |
| `409` | 任务已被他 worker 认领 / lease 已回收 | **当幂等成功处理**:log INFO,**不报告失败**,不影响后续认领 |
| 其他 4xx | 客户端构造错(参数缺失 / schema 错) | log error,放弃;**累计 5 次(默认 `clientErrorFailFastThreshold=5`)后 fail-fast** —— 防活锁 |
| `5xx` | 平台侧问题 | **指数退避重试**(详见 §C) |
| 传输层错(socket / DNS / timeout) | 网络问题 | 走与 5xx 等价的指数退避 |

### task 执行结果错误码(report body `errorCode` 字段)

Java SDK 端 `SdkTaskResult.fail(throwable)` 默认填 `throwable.getClass().getSimpleName()`(如 `IllegalStateException`),但 atomic executors / 业务 handler 可填业务码。**BYO SDK 必须等价支持**以下规范分类(对应 atomic Lane K + ADR-012 FailureClass):

| 推荐 errorCode | 语义 | failureClass(可选,ADR-012) |
|---|---|---|
| `SUCCESS` | 成功(对应 `success=true`,errorCode 通常空) | — |
| `TIMEOUT` | 执行超 task 配置 timeout | TRANSIENT |
| `KILLED` / `CANCELLED` | cancelRequested 触发的主动停 | TERMINAL_USER |
| `SECURITY_REJECTED` | SensitiveDataValidator 拦截 / 凭据放在 parameters | TERMINAL_CONFIG |
| `EXECUTION_FAILED` | 业务逻辑抛(默认兜底分类) | BUSINESS |
| `CONFIG_INVALID` | EffectiveTaskConfig 校验失败 / 缺必填 | TERMINAL_CONFIG |
| `RESOURCE_EXHAUSTED` | 内存 / 磁盘 / 连接池耗尽 | TRANSIENT |

> **字段名红线**:report body 必须用 `errorCode`(不是 `errorClass`)、`outputs`(不是 `output`)、`resultSummary`(不是 `errorMessage`)。
> 平台端 `TaskExecutionReportDto` 只读这三个名字,错名 = 字段被静默丢弃。

## C. 重试与退避(BYO SDK 必须等价实现)

Java SDK 端默认值(`BatchPlatformClientConfig`):

| 配置项 | 默认值 | 适用错误 |
|---|---|---|
| `retryBaseDelayMs` | 200ms | 5xx / 传输层错 |
| `retryMaxAttempts` | 3 | 5xx / 传输层错的最大尝试次数(含首发) |
| `retryBackoffStrategy` | 指数(2^n × base) | attempt 1 = 200ms,attempt 2 = 400ms,attempt 3 = 800ms |
| `clientErrorFailFastThreshold` | 5 | 累计 4xx(401/403 除外,那俩立即 fail-fast)达 5 次后置 fatal |
| `authFailFastImmediate` | true | 401/403 一次就 fail-fast,**不走指数退避** |

**BYO SDK 必须实现的等价规则**:

```
on http-call(endpoint, body):
  for attempt in 1..retryMaxAttempts:
    status = invoke(endpoint, body)
    match status:
      200..299      → return success
      401, 403      → MUST fail-fast, mark dispatcher fatal, stop polling
      404           → return not-found (caller decides)
      409           → return idempotent-success (log INFO)
      other 4xx     → increment clientErrorCounter; if >= 5: fail-fast; else: return failure
      5xx, transport-err →
        if attempt < retryMaxAttempts:
          sleep( base * 2^(attempt-1) )  # 200, 400, 800 ms
          continue
        else:
          return failure (caller log + drop; next tick retries)
```

**心跳 / lease renew 特殊豁免**:这俩是周期性 tick,单次失败可以等下一 tick 自然重试,**不必内部指数退避**(防 tick 之间累积阻塞)。
但 **register / claim / report** 是单次性、丢了就丢任务的关键调用,**必须**走完整 retryMaxAttempts。

**Kafka SASL/SCRAM 凭据错**:当前 Java SDK 仍 retry 风暴(见 §6 短板),Lane A 待补 fail-fast;**BYO SDK 推荐直接 fail-fast**(认证失败不可能靠重试恢复)。

## 7. 引用

- 协议规范源头:**ADR-035** [`docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md`](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) §3(协议)/ §4(配置)/ §11(运行期)
- SDK HTTP 封装:`batch-worker-sdk/src/main/java/com/example/batch/sdk/internal/PlatformHttpClient.java`
- SDK Kafka:`batch-worker-sdk/src/main/java/com/example/batch/sdk/dispatcher/KafkaTaskConsumer.java`
- SDK 调度:`batch-worker-sdk/src/main/java/com/example/batch/sdk/scheduler/{HeartbeatScheduler,LeaseRenewalScheduler}.java`
- Orch 接收:`batch-orchestrator/src/main/java/com/example/batch/orchestrator/controller/{WorkerController,TaskController}.java`
- Orch 故障感知:`batch-orchestrator/.../scheduler/WorkerHeartbeatTimeoutScheduler.java`
- 排障:[`docs/sdk/troubleshooting.md`](troubleshooting.md)
- 上线流程:[`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)
