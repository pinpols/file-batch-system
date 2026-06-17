# BYO(Bring Your Own)SDK 接入指南

> **目标**:让其他语言(Go / Python / Node / .NET / Rust …)团队照协议规范自研 worker SDK,平台不维护多语言代码,只稳协议。
> **协议权威源(双轨)**:[`docs/sdk/wire-protocol.md`](wire-protocol.md)(读者视图)+ [`docs/api/orchestrator-internal.openapi.yaml`](../api/orchestrator-internal.openapi.yaml)(机器视图)。
> **行为对账**:[`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/) — language-agnostic JSON 契约用例,任何实现都可写 runner 跑通验证。
> **配套**:[ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) §3/§4/§9/§11、[`docs/sdk/quickstart.md`](quickstart.md)(Java SDK 五分钟接入)、[`docs/sdk/troubleshooting.md`](troubleshooting.md)。
> **五语言对齐矩阵(亲核、带 `文件:行号` 证据)**:[`docs/sdk/sdk-parity-matrix.md`](sdk-parity-matrix.md) —— 回答"哪些对齐 / 哪些没对齐",并记录了自动化审查在此题上系统性误报的根因与复核守则。

---

## 0. 适用人群

写本 SDK 之前先确认:

- **已有 worker 进程是 Go / Python / Node / .NET / Rust**,不想在生产引 JVM(JVM 内存预算 / 启动时间 / 镜像体积是硬约束)。
- **想深度集成自家观测体系**(用 OpenTelemetry SDK 的 Go 版 / Python 版,衔接公司 trace / metric / log pipeline),不愿吃 Java SDK 默认行为。
- **是高级用户**:能读协议规范、能跑契约测试、能跟上协议演进(平台改 wire schema 时同步升级)。

**不适合**:刚试水的租户。先用 Java SDK 跑通生产,再考虑切自研 —— 自研 SDK 的隐性成本(协议演进、边缘 case、运维工具链)往往超预期。

---

## 1. 实现清单(必做 —— 不实现 = 不接入)

### 1.1 八个 HTTP `/internal/*` endpoint

按 `orchestrator-internal.openapi.yaml` 实现以下 8 个稳定接口。**带 `x-protocol-stability: stable` 的必须实现;`internal-only` 的可跳过(平台运维专用,SDK 不主动调)。**

| Method | Path | x-protocol-stability | 谁主动 | 频率 | 必做语义 |
|---|---|---|---|---|---|
| `POST` | `/internal/workers/register` | **stable** | SDK start | 1 次 | 启动注册,带 `WorkerHeartbeatDto`(含 capabilityTags、buildId、sdkVersion 等指纹) |
| `POST` | `/internal/workers/{code}/heartbeat` | **stable** | 心跳调度 | 30s | 解析回包 `WorkerHeartbeatResponse`,应用 directive(详 §1.3) |
| `POST` | `/internal/workers/{code}/deactivate` | **stable** | SDK stop | 1 次 | 优雅停时主动告别 |
| `POST` | `/internal/tasks/{id}/claim` | **stable** | 收 Kafka 后 | 每 task 1 次 | body 解析 `EffectiveTaskConfig`(任务业务参数实时快照) |
| `POST` | `/internal/tasks/{id}/report` | **stable** | task 终态 | 每 task 1 次 | `errorCode` / `outputs` / `resultSummary` 字段名严格不可错(§B) |
| `POST` | `/internal/tasks/{id}/renew` | **stable** | lease 续约调度 | 60s × 每 in-flight task | 解析回包 `cancelRequested`,触发 handler 中止 |
| `POST` | `/internal/tasks/leases/renew-batch` | **stable** | lease 续约调度(批量) | 60s | ADR-016 批量优化;BYO SDK 可先用单 renew,后期换批量 |
| `POST` | `/internal/tasks/{id}/cancel` | internal-only | console / scheduler | — | **BYO SDK 不主动调**;通过 renew 响应感知 cancel |

**HTTP 客户端最低要求**:
- 支持自定义 header(`Idempotency-Key` 给 claim / report 强烈推荐)
- 连接池 + keep-alive(每次重新握手会让心跳堆积)
- 配置 timeout < heartbeat / 3(默认 10s,见 wire-protocol §5)

### 1.2 一个 Kafka consumer

- **Topic 订阅**:wildcard `batch.task.dispatch.<tenant>.*`(per-tenant 隔离,租户只看自己 topic)
- **认证**:SASL/SCRAM-SHA-512(平台凭据走 env / secret,**严禁** payload 内传)
- **Consumer group**:`g-sdk-<tenantId>-<workerCode>`(per worker,平台不感知 group 名)
- **反序列化**:JSON UTF-8;DTO 字段集对照 [TaskDispatchMessage schema](../api/orchestrator-internal.openapi.yaml#L_search),未知字段忽略(`ignoreUnknown` 等价)
- **schemaVersion 兼容矩阵**:见 wire-protocol §A —— 未知 major(`v3+`)直接 reject,不 commit offset
- **at-least-once 投递**:平台不去重,SDK 收到后调 claim 时由 orch `409` 兜底幂等

### 1.3 心跳调度(默认 30s,消费 nextHeartbeatHint 动态调速)

```
loop:
  sleep( current_interval )       # 初始 30s
  resp = POST /internal/workers/{code}/heartbeat
  if resp.nextHeartbeatHint:
    current_interval = parse(resp.nextHeartbeatHint)   # 平台动态调,例 PT15S
  if resp.shouldDrain: enter DRAINING state            # 见 §1.5
  if resp.desiredMaxConcurrent: adjust concurrency
  if resp.pausedTaskTypes: pause those types in Kafka
```

> Java SDK 目前 **接** `nextHeartbeatHint` 但 **没动态调速**(见 wire-protocol §6 短板);BYO SDK 推荐一次性把动态调速做出来。

### 1.4 lease 续约调度(默认 60s,遍历 in-flight)

```
loop:
  sleep( 60s )
  for each task in in_flight_tasks:
    resp = POST /internal/tasks/{taskId}/renew with TaskHeartbeatRequest
    if resp.cancelRequested:
      signal_cancellation(task)   # 见 §1.6 FSM 与 cancel
    if status == 404 / 409:
      drop task locally           # lease 已被回收,handler 跑完也报不了
```

**关键约束**:`leaseRenewInterval < orch lease ttl / 2`(防 lease 提前回收;详 wire-protocol §5)。

### 1.5 4 态 FSM + partition pause/resume

worker 必须维护以下状态(对照 `WorkerHeartbeatResponse.platformStatus`):

```
NORMAL    → Kafka assignment active,正常认领
DEGRADED  → Kafka assignment active,但日志降噪 / 降并发(平台还在观望)
PAUSED    → Kafka assignment.pause(),不收新消息;in-flight 继续跑
DRAINING  → Kafka assignment.pause();在手任务跑完后调 deactivate 退出
```

**partition 级 pause/resume**(Kafka client 一般有 `assignment.pause(partitions)` API):capacity-aware backpressure 用 —— in-flight 满了暂停 partition,处理完再 resume。Java SDK `KafkaTaskConsumer.applyBackpressure()` 是参考实现。

### 1.6 优雅停止(stop with timeout)

```
stop(timeout):
  draining = true                       # 立刻拒新 Kafka 消息
  kafkaConsumer.wakeup()                # 中断 poll
  wait_for_in_flight_drain(timeout * 0.4)
  executor.shutdown_and_wait(timeout * 0.6)
  POST /internal/workers/{code}/deactivate
```

**SIGTERM 处理**:推荐捕获 SIGTERM 触发 `stop(30s)`;K8s 默认 terminationGracePeriodSeconds 30s,SDK 必须在窗口内退完。

### 1.7 错误分类与重试策略

**完全按 [wire-protocol §B + §C](wire-protocol.md#b-错误码语义byo-sdk-必须等价分类) 实现**。简记:

- `401 / 403` → fail-fast,不重试
- `404` → log warn,放弃
- `409` → 当幂等成功
- 其他 4xx → 累计 5 次 fail-fast
- `5xx` / 传输错 → 指数退避(200ms 基,2^n,默认 3 次)
- Kafka SASL 凭据错 → 推荐直接 fail-fast(Java SDK 还没做,BYO 起点就做更省事)

### 1.8 凭据走 env,严禁入 payload

**SensitiveDataValidator(Java SDK Lane C)** 等价行为:扫描 register body / dispatch parameters 的 key,命中 `password`、`secret`、`token`、`apiKey`、`accessKey` 等关键词的字段值**必须**为空(凭据走环境变量),否则直接 reject(register 期 fail-fast,parameters 期把任务直接 fail 报告 `errorCode=SECURITY_REJECTED`)。

BYO SDK **必须**实现等价校验,代码层留 hook 供租户扩 deny-list。

### 1.9 租户自检(消费时验 tenant id,防 ACL 漂移)

每条 Kafka 消息反序列化后,**必须**验 `msg.tenantId == config.tenantId`。如果 broker ACL 因运维错配漏配让别租户消息漂进来,SDK 必须直接 drop + log error(不 commit offset)—— 这是最后一道防线。

---

## 2. 推荐实现清单(锦上添花)

| 项 | 收益 | Java SDK 参考 |
|---|---|---|
| **capacity-aware partition pause/resume** | in-flight 满时不阻塞别 worker,Kafka 端原生 backpressure | `KafkaTaskConsumer.applyBackpressure()` |
| **ThrottledLogger 同款防噪** | 大量重复 WARN(如 lease 失效)聚合输出,日志成本可控 | `batch-worker-sdk/.../util/ThrottledLogger.java` |
| **OpenTelemetry trace 衔接** | dispatch message `runtimeAttributes.traceId` 透传到 task ctx,trace 全链路打通 | `SdkTaskContext.traceId()` |
| **graceful drain on SIGTERM** | K8s rolling deploy 0 task 丢失 | §1.6 |
| **buildId / sdkVersion 上报** | 平台 fingerprint 看板(`/ops/worker-fingerprints`)能反查租户 SDK 版本分布 | `WorkerHeartbeatDto.buildId / sdkVersion`(register 期上报一次) |
| **dispatch 消息 hash 去重** | 平台保证 at-least-once,但同一 idempotencyKey 可能投多次;SDK 内部 LRU 去重省 claim 调用 | Java SDK 暂无,可加分项 |
| **nextHeartbeatHint 动态调速** | 平台负载高时全局降频,负载低时升频 | Java SDK 未实现(短板 #3) |

---

## 3. 测试自检(契约 fixtures)

[`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/) 提供 language-agnostic JSON 契约用例。

**用法**:写一个 contract runner(任意语言),按 fixture JSON 的 `given` 起 SDK、`when` 触发 HTTP / Kafka 调用、断言 `then.sdkExpectedAction`。10+ 用例覆盖:

- register 成功 / 同 workerCode 重复(平台 idempotent)
- heartbeat 各 directive(NORMAL / DRAINING / PAUSED / desiredMaxConcurrent / nextHeartbeatHint)
- claim 401 fail-fast / 409 当成功 / 200 解 EffectiveTaskConfig
- report success / failure(errorCode 字段名正确性)
- renew cancelRequested=true
- 5xx 指数退避
- Kafka partition pause / resume
- stop with timeout

任何 BYO SDK PR 必须先把这 10+ fixtures 跑绿(本 lane 不强制 CI,但租户上线评审用)。

---

## 4. 已知的语言坑(简短建议)

### Go

- **Kafka client**:推荐 `confluent-kafka-go`(C 库 wrapper,功能全;CGO 依赖)或 `segmentio/kafka-go`(纯 Go,SASL/SCRAM 实现得跑通)。
- **HTTP**:`net/http` + `http.Client{Transport: ...}` 即可,无需 framework。
- **并发**:goroutine + `context.Context` 透传 cancel;心跳 / lease renew 用 `time.Ticker`。
- **JSON**:`encoding/json` 默认 ignore unknown,符合协议要求。
- **坑**:`net/http.Client` 默认无超时,**必须** `Timeout: 10*time.Second` 否则心跳挂死整个 worker。

### Python

- **Kafka**:`confluent-kafka-python`(C 库 wrapper,生产推荐)或 `aiokafka`(asyncio 模式)。
- **HTTP**:`httpx`(sync + async 双模)或 `requests`(sync only)。
- **心跳调度**:同步模式用 `threading.Timer` / `apscheduler`;async 模式用 `asyncio.create_task` + `asyncio.sleep`。
- **坑**:GIL 让真并行受限;CPU 密集型 handler 用 multiprocessing 或交给 atomic executor 那边跑。日志库 `logging.basicConfig` 必须配 JSON formatter(平台日志聚合统一 JSON)。

### Node.js

- **Kafka**:`kafkajs`(纯 JS,API 干净)或 `node-rdkafka`(C 库 wrapper,性能更好)。
- **HTTP**:`undici`(Node 内置 fetch 后端,性能最好)或 `axios`(API 友好)。
- **心跳调度**:`setInterval` 即可;**注意** Node 单线程,长循环 handler 会阻塞调度器 —— 必须 `await` / worker_threads。
- **坑**:`Promise` 异常未 catch 会变成 unhandledRejection,**必须**全局兜底;否则 SIGTERM 时进程 hang。

### .NET / C#

- **Kafka**:`Confluent.Kafka` 官方库。
- **HTTP**:`HttpClient`(注意复用,`new HttpClient()` per call 会耗 socket)。
- **心跳调度**:`System.Threading.Timer` 或 `IHostedService`。
- **坑**:`HttpClient.Timeout` 默认 100s,**必须**显式调小到 10s。

---

## 5. 参考实现

- **Java SDK(参考实现 + 协议主版本守护)**:[`batch-worker-sdk/`](../../batch-worker-sdk/) — 包含 `BatchPlatformClient` / `PlatformHttpClient` / `KafkaTaskConsumer` / `HeartbeatScheduler` / `LeaseRenewalScheduler` / `TaskDispatcher` 完整链路。
- **Java SDK 示例租户 worker**:[`examples/sample-tenant-worker-java/`](../../examples/sample-tenant-worker-java/) —— plain Java(无 Spring)、200 行起一个 worker 进程。
- **Java SDK 契约测试**:[`batch-worker-sdk/src/test/java/com/example/batch/sdk/dispatcher/SdkPlatformContractTest.java`](../../batch-worker-sdk/src/test/java/com/example/batch/sdk/dispatcher/SdkPlatformContractTest.java) —— 字段集守护;本 lane 抽出的 JSON fixtures 即源自此。
- **多语言示例租户 worker**(命名统一 `sample-tenant-worker-<lang>`):
  - [`examples/sample-tenant-worker-go/`](../../examples/sample-tenant-worker-go/) —— `sdk/go` 运行时 + segmentio/kafka-go(可 `go build`)。
  - [`examples/sample-tenant-worker-typescript/`](../../examples/sample-tenant-worker-typescript/) —— `sdk/typescript` 运行时 + kafkajs(Node ≥25)。
  - [`examples/sample-tenant-worker-rust/`](../../examples/sample-tenant-worker-rust/) —— `sdk/rust` + rdkafka,**示意**(真 HTTP 待 reqwest 适配器,CI 编译)。
  - [`examples/sample-tenant-worker-python/`](../../examples/sample-tenant-worker-python/) —— Python 3.12 asyncio。

---

## 6. 协议演进与升级纪律

- **平台改 wire schema 必须先双写**(走 [`docs/runbook/sdk-dual-rollout.md`](../runbook/sdk-dual-rollout.md)):新增字段必为 nullable optional;改名 / 删除走两阶段(N 发布兼容老名,N+1 删老名)。
- **BYO SDK 团队订阅**:平台改 schema 时 PR 标签 `sdk-wire-protocol`,BYO SDK 维护者 review;若 schema 升 major(`v3`),所有 BYO SDK 需在窗口期内升级,否则未知 major 会被自家 SDK reject。
- **协议契约 PR-gate**:平台端 `WorkerController` / `TaskController` 改字段 → 必须同 PR 改 `orchestrator-internal.openapi.yaml` + `wire-protocol.md` Changelog + `sdk-contract-fixtures/` 对应用例。CI 拦截漂移(待补脚本)。

---

## 7. 引用

- 协议读者视图:[`docs/sdk/wire-protocol.md`](wire-protocol.md)
- 协议机器视图:[`docs/api/orchestrator-internal.openapi.yaml`](../api/orchestrator-internal.openapi.yaml)
- 契约 fixtures:[`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/)
- ADR-035 §9 两套绑定:[`docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md`](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)
- 双轨滚动:[`docs/runbook/sdk-dual-rollout.md`](../runbook/sdk-dual-rollout.md)
- 上线流程:[`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)
- 端到端 journey:[`docs/sdk/onboarding-journey.md`](onboarding-journey.md)(Lane L,PR #249)
