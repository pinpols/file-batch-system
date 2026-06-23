# Java + Python SDK 联合深度代码审查

**日期**:2026-06-02
**方法**:3 个并行 Explore agent 钻 Java SDK 家族 / Python SDK / 跨 SDK 一致性,合成 + 交叉验证
**范围**:
- Java:`batch-worker-sdk/`(core)+ `batch-worker-sdk-spring-boot-starter/` + `batch-worker-sdk-testkit/`
- Python:`batch-worker-sdk-python/`(Phase 0-5 + 包结构 refactor 后,8 子包)
- 跨 SDK:`docs/api/sdk-contract-fixtures/` + `docs/api/sdk-shared-constants.yaml` + `.github/workflows/sdk-contract-parity.yml`

---

## 0. 总评

| SDK | 评分 | 一句话定性 |
|---|---|---|
| **Java SDK** | **3.5/5** | Phase 1-5 完整;3 个 P0 hardening 缝隙(Kafka SASL retry 风暴 / consumer 线程未 join / lease 用 fixedRate 易积压) |
| **Python SDK** | **3.5/5** | 包结构干净、retry/config 优秀;3 个 P0 实装裂缝(`mark_cancel_requested` 死路径 / SASL retry hang / in-flight dict 多线程不安全) |
| **跨 SDK 等价度** | **40%** | 公共 API 形对齐,行为有 3 处协议级 bug(`descriptor.schema` alias 错 / `schemaVersion` 只支持 v2 / Python parity test 全 xfail) |

**最关键判断**:Python SDK 当前**不可生产用** — 3 个 protocol-breaking 缺陷意味着它没法跟真实 orchestrator 通讯;Java SDK 可生产但有 3 处 production hardening 缺口,K8s 重启场景下可能丢任务。

---

## 1. 跨 SDK 双中 — 两边都缺(改一边没用)

| 缺陷 | Java 位置 | Python 位置 | 影响 |
|---|---|---|---|
| **Kafka SASL 凭据错无 fail-fast** | `KafkaTaskConsumer.java:96-107`(catch generic) | `internal/_kafka.py:145-171`(无 timeout 包) | 凭据错时 retry 风暴,Pod 2 分钟 hang 后被 K8s 强杀 |
| **`nextHeartbeatHint` clamp 缺失** | `HeartbeatScheduler.java`(无 1s 下限 / 10×上限) | `scheduler/_heartbeat.py:90-119` | 平台错配 `100ms` → SDK 心跳风暴打死 orch |
| **Lease scheduler 失败语义不全** | `LeaseRenewalScheduler.java:86-91`(不检 leaseUntil) | `scheduler/_lease.py:82-91`(同) | handler 跑完白工,report 被 orch 拒(lease 已 timeout) |
| **stop(timeout) 超时阶段数字不一致** | Java `kafka 20% / dispatcher 75% / sched 5%` | Python `kafka 20% / drain 60% / sched 20%` | 同协议下数字不一致,运维无法写统一 SLA |

→ **改造时必须两边同步**。建议设计文档(`docs/sdk/wire-protocol.md` §5 §6)落定数字后,Java + Python 同 PR 改。

---

## 2. Python 端独有的协议级 P0(等价性破裂)

| Bug | 位置 | 现象 | 修复 |
|---|---|---|---|
| **🔴 `SdkTaskTypeDescriptor.input_schema` alias 错** | `batch-worker-sdk-python/src/batch_worker_sdk/task/descriptor.py:32` `Field(alias="schema")` | wire JSON 实际是 `inputSchema`(camelCase),Python 收 / 发都对不上 | alias 改 `"inputSchema"` |
| **🔴 `schemaVersion` 只支持 v2** | `batch-worker-sdk-python/src/batch_worker_sdk/dispatcher/dispatcher.py:50-53` `_SUPPORTED_SCHEMA_PREFIXES = ("v2",)` | 老平台 v1 消息被静默 drop;Java 端是 `Set.of("v1", "v2")` | 改 `("v1", "v2")` |
| **🔴 `mark_cancel_requested` 根本不存在** | `batch-worker-sdk-python/src/batch_worker_sdk/scheduler/_lease.py:118` 调它;`dispatcher/dispatcher.py` 无此方法 | `getattr` fallback 写 WARN log,**取消信号永远不翻**,handler 跑到 lease timeout | `TaskDispatcher` 加 `mark_cancel_requested(taskId, reason)` 方法,内部翻 `cancel_signal._event.set()` |

**这三个一日不修,Python SDK 一日不能上生产**。

---

## 3. Java 端独有 P0

| 缺陷 | 位置 | 影响 |
|---|---|---|
| **🔴 `KafkaTaskConsumer.close()` 不 join() poll 线程** | `batch-worker-sdk/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java:259-265` | `wakeup()` 后立即返回,但 poll 线程可能还在跑;`BatchPlatformClient.stop()` 已进 deactivate,Kafka offset 未 commit;K8s SIGKILL 时**任务可能重放或丢** |
| **🟠 `LeaseRenewalScheduler` 用 `scheduleAtFixedRate`** | `LeaseRenewalScheduler.java:46` vs `HeartbeatScheduler.java:76`(fixedDelay) | 续约 tick 卡(5xx retry)后,下轮立即又来,内存爆;Heartbeat 没这问题,两边不一致 |
| **🟠 `claimWithRetry` 退避无 jitter** | `TaskDispatcher.java:414` `delayMs = baseDelayMs << attempt` | N 个 worker 同步雪崩 retry(wire-protocol §C 提到但未实装) |

---

## 4. Python 端独有 P0

| 缺陷 | 位置 | 影响 |
|---|---|---|
| **🟠 `in_flight` dict 多线程不安全** | `batch-worker-sdk-python/src/batch_worker_sdk/dispatcher/dispatcher.py:79,193,196` 普通 dict | 单 event loop 时 GIL 保护勉强 OK;**未来 executor offload 立崩** |
| **🟠 `@batch_task` global registry 无测试隔离** | `batch-worker-sdk-python/src/batch_worker_sdk/handler/_decorator.py:36` module-level `_REGISTERED_HANDLERS` | 同模块两次 import 触发 "duplicate task_type" 异常;参数化测试踩 |
| **🔴 parity test 全 xfail** | `batch-worker-sdk-python/tests/test_shared_constants_parity.py:65-72` | Java parity 有牙齿,Python 端纸老虎 — drift guard **整个不对称**,Lane P 在 Python 侧失效 |

---

## 5. drift guard(Lane P #257)的实际效力

**结论**:Java 侧有阻塞力,Python 侧 xfail 无阻塞,gate **不对等**。

| 守护 | Java 侧 | Python 侧 |
|---|---|---|
| `JsonFixtureContractTest` | ✅ 12 fixture 全 PASS | ❌ `tests/contract/test_contract_runner.py` 全 xfail(P1 HTTP 类 fixture 标 xfail tolerated)|
| `SharedConstantsParityTest` | ✅ 4 个常量集反射对账 | ❌ `tests/test_shared_constants_parity.py` 4 个 case 全 `xfail strict` |
| `sdk-contract-parity.yml` CI | Java fail = block PR | Python xfail 不 block;`parity-report` 永远只 log |
| `atomic_error_codes` | Lane K 加 enum 后会校验 | yaml 占位空 list,Python 等价物根本没建 |

→ **Python SDK 实质上无 drift 守护**。当前 main 上 Java/Python 看起来"通过 CI",纯粹因为 Python 测试都 xfail。

---

## 6. 测试盲区(Java + Python 都缺)

| # | 盲区 | 影响 |
|---|---|---|
| 1 | Kafka rebalance 期间 pause 状态丢失 → 重放 | 偶现性 / 难复现的任务重复 |
| 2 | `nextHeartbeatHint` 并发到达的 reschedule race | hint 被吞,频率不生效 |
| 3 | lease cancel + handler 自然完成的 race | 双写 / 双报告 |
| 4 | `stop(timeout)` 边界:in-flight task 跑 timeout-1ms vs +1ms | 线程泄露 |
| 5 | SASL 凭据错的 fail-fast 路径 | retry 风暴未拦 |
| 6 | 跨 SDK 同一 fixture 的 pass / fail 差异化(Java 过 / Python xfail)是 drift 不是 OK | 行为漂移 |

---

## 7. 设计亮点(值得继承)

### Java
- ✅ Phase 1-5 hardening 链路:`stop(Duration)` 预算分摊 + in-flight drain timeout WARN + hysteresis pause/resume
- ✅ 并发模式一致:`AtomicBoolean` / `AtomicInteger` / `volatile` + `ConcurrentHashMap`
- ✅ 声明式幂等 + 重试装饰器 auto-wrap(`TaskDispatcher` 构造期织入 `@Idempotent` / `@RetryOn`)

### Python
- ✅ retry 状态机(`retry/_retry.py:87-193`)— 完美镜像 Java §C,4xx 累计计数 + 409/404 分流
- ✅ config 校验(`client/config.py:109-151`)— Lane I 4 条规则一字不差移植,错误消息可 grep
- ✅ `ProgressReporter` 并发(`task/progress.py:56-107`)— Lock + shallow copy 防御正确
- ✅ 包结构 refactor 后清晰:`client/` / `dispatcher/` / `handler/` / `internal/` / `retry/` / `scheduler/` / `task/` / `testkit/`

---

## 8. TOP 10 改进项(P0/P1/P2 排序)

| # | 项 | Java | Python | 跨 SDK? | 工作量 |
|---|---|---|---|---|---|
| **1** 🔴 P0 | Python `descriptor.input_schema` alias 改 `"inputSchema"` | — | 必须 | — | 1h |
| **2** 🔴 P0 | Python `_SUPPORTED_SCHEMA_PREFIXES = ("v1", "v2")` | — | 必须 | — | 0.5h |
| **3** 🔴 P0 | Python `TaskDispatcher.mark_cancel_requested(...)` 实装 | — | 必须 | — | 4h |
| **4** 🔴 P0 | 两端 Kafka SASL fail-fast(`asyncio.wait_for` Python / AuthException 分流 Java) | 必须 | 必须 | ✅ | 1d |
| **5** 🟠 P1 | Java `KafkaTaskConsumer.close()` 加 thread join + stop 预算分摊 | 必须 | — | — | 4h |
| **6** 🟠 P1 | Java `LeaseRenewalScheduler` 改 fixedDelay | 必须 | — | — | 1h |
| **7** 🟠 P1 | 两端 `nextHeartbeatHint` clamp(min 1s / max 10×interval) | 必须 | 必须 | ✅ | 0.5d |
| **8** 🟠 P1 | Python parity test 改 strict + 实装常量导出 | — | 必须 | — | 4h |
| **9** 🟡 P2 | Python `@batch_task` 加 test isolation fixture | — | 必须 | — | 1h |
| **10** 🟡 P2 | Java claimWithRetry 加 jitter | 必须 | — | — | 1h |

**汇总**:
- P0(1-4)≈ 1.5d
- P1(5-8)≈ 1.5d
- P2(9-10)≈ 0.25d
- **完成 P0+P1 ≈ 3 天,SDK 可进生产灰度**

执行细节见 [`2026-06-02-sdk-fix-execution-plan.md`](2026-06-02-sdk-fix-execution-plan.md)。

---

## 9. 后续治理

| 治理项 | 现状 | 目标 |
|---|---|---|
| **Python parity test xfail 转 strict** | xfail strict | 实装常量导出后改 enforce |
| **CI gate 双 SDK 等权** | Python 不 block PR | `sdk-contract-parity.yml` Python job 也成必过门禁 |
| **OpenAPI yaml 变更触发 SDK 同步检查** | 仅 path filter | 新增 `OpenApiCompletionTest` 反射检查 PlatformHttpClient 覆盖度 |
| **`atomic_error_codes` 跨 SDK 同步** | yaml 占位空 list | Lane K 合并后 Java 反射 + Python 导出 + 双向 parity |
| **testkit FakeBatchPlatform 加 fault injection** | 仅 happy path | 支持 401/403/5xx/409 注入,真测 retry / fail-fast 路径 |

---

## 附:本报告调研方法

并行三 Explore agent:
1. **Java SDK 家族审查** — 钻 `batch-worker-sdk/` + starter + testkit 全 60+ Java 类
2. **Python SDK 审查** — 钻 `batch-worker-sdk-python/` 8 子包 + 50+ 测试文件
3. **跨 SDK 一致性** — 比对公共 API surface / 协议字段 / 关键行为 / drift guard 有效性

每 agent 提交 2500-3500 字详细 file:line 引用报告;主进程合成 + 去重 + 跨报告交叉验证,得出 TOP 10 + 治理项。

**调研所触文件主要范围**:
- Java:`batch-worker-sdk/src/{main,test}/java/io/github/pinpols/batch/sdk/{client,dispatcher,scheduler,internal,wire,...}/`
- Python:`batch-worker-sdk-python/src/batch_worker_sdk/{client,dispatcher,handler,internal,retry,scheduler,task,testkit}/` + `batch-worker-sdk-python/tests/`
- 跨域:`docs/api/sdk-contract-fixtures/*.json` + `docs/api/sdk-shared-constants.yaml` + `docs/sdk/wire-protocol.md` + `.github/workflows/sdk-contract-parity.yml`
