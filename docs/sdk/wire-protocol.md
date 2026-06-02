# SDK ↔ Orchestrator 通讯协议 / 故障感知

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

## 7. 引用

- 协议规范源头:**ADR-035** [`docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md`](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) §3(协议)/ §4(配置)/ §11(运行期)
- SDK HTTP 封装:`batch-worker-sdk/src/main/java/com/example/batch/sdk/internal/PlatformHttpClient.java`
- SDK Kafka:`batch-worker-sdk/src/main/java/com/example/batch/sdk/dispatcher/KafkaTaskConsumer.java`
- SDK 调度:`batch-worker-sdk/src/main/java/com/example/batch/sdk/scheduler/{HeartbeatScheduler,LeaseRenewalScheduler}.java`
- Orch 接收:`batch-orchestrator/src/main/java/com/example/batch/orchestrator/controller/{WorkerController,TaskController}.java`
- Orch 故障感知:`batch-orchestrator/.../scheduler/WorkerHeartbeatTimeoutScheduler.java`
- 排障:[`docs/sdk/troubleshooting.md`](troubleshooting.md)
- 上线流程:[`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)
