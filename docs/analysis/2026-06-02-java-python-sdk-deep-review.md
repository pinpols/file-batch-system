---
title: Java + Python SDK 联合深度审查
date: 2026-06-02
---

# Java + Python SDK 联合深度审查(2026-06-02)

## 0. 总评

| SDK | 评分 | 一句话定性 |
|---|---|---|
| **Java SDK** | **3.5/5** | Phase 1-5 完整;**3 个 P0 hardening 缝隙**(Kafka SASL retry 风暴 / consumer 线程未 join / lease 用 fixedRate 易积压) |
| **Python SDK** | **3.5/5** | 包结构干净、retry/config 优秀;**3 个 P0 实装裂缝**(`mark_cancel_requested` 死路径 / SASL retry hang / in-flight dict 多线程不安全) |
| **跨 SDK 等价度** | **40%** | 公共 API 形对齐,行为有 **3 处协议级 bug**:`descriptor.schema` alias 错、`schemaVersion` 只支持 v2、Python parity test 全 xfail |

---

## 1. 跨报告交叉验证 — **两边都中**的真问题

这些是两个 SDK 都有同一类缺陷,改一边没用、**必须同步修**:

| 缺陷 | Java 位置 | Python 位置 | 影响 | 优先级 |
|---|---|---|---|---|
| **Kafka SASL 凭据错无 fail-fast** | `KafkaTaskConsumer.java:96-107`(catch generic) | `internal/_kafka.py:145-171`(无 timeout 包) | 凭据错时 retry 风暴,Pod 2 分钟 hang 后被 K8s 强杀,任务状态混乱 | 🔴 P0 |
| **`nextHeartbeatHint` clamp 缺失** | `HeartbeatScheduler.java:56-75`(无 1s 下限/10×上限) | `scheduler/_heartbeat.py:90-119`(同) | 平台错配 `100ms` → SDK 心跳风暴打死 orch;反向错配 `1h` → 心跳三分钟一次,重任务无法感知取消 | 🟠 P1 |
| **Lease scheduler 失败语义不全** | `LeaseRenewalScheduler.java:86-91`(不检 leaseUntil < now) | `scheduler/_lease.py:82-91`(同) | handler 跑完白工,report 被 orch 拒(lease 已 timeout);重任务(>1h)自动泄露 | 🟠 P1 |

---

## 2. 协议级 bug — **Python 端独有**(等价性破裂)

**这三个是生产阻断**,Python SDK 现在无法生产:

| Bug | 位置 | 现象 | 危害 |
|---|---|---|---|
| **🔴 `SdkTaskTypeDescriptor.input_schema` alias 错** | `task/descriptor.py:32` `Field(alias="schema")` | wire JSON 实际是 `inputSchema`(camelCase Java 映射),Python 端 `Field(alias="schema")` 收 / 发都对不上 | SDK 从平台拉 descriptor(包含 inputSchema)时反序列化全 fail,`unrecognized field` |
| **🔴 `schemaVersion` 只支持 v2** | `dispatcher/dispatcher.py:50-53` `_SUPPORTED_SCHEMA_PREFIXES = ("v2",)` | 老平台 v1 消息(format `v1:topic:taskId`)被静默 drop;Java 端 `Set.of("v1", "v2")` | Kafka 消息全被跳过,任务永不派发 |
| **🔴 `mark_cancel_requested` 根本不存在** | `scheduler/_lease.py:118` 调它;`TaskDispatcher` class 无此方法定义 | `getattr(dispatcher, "mark_cancel_requested", None)` fallback None,WARN log 一句后继续;**cancel 信号永不翻** | handler `CancellationSignal.is_cancelled()` 永真,无法响应平台取消指令;task 跑满 lease timeout 被无故回收 |

---

## 3. Java 端独有的 P0

| 缺陷 | 位置 | 影响 | 后果 |
|---|---|---|---|
| **KafkaTaskConsumer.close() 不 join() poll 线程** | `KafkaTaskConsumer.java:259-265` | `consumer.wakeup()` 后立即返回,但 poll 线程可能还在跑;BatchPlatformClient.stop() 已进 deactivate 阶段,Kafka **offset 尚未 commit** | K8s SIGKILL 时任务可能重放 / 损坏的状态(in-flight 标记已清,但 offset 未 reset) |
| **LeaseRenewalScheduler 用 scheduleAtFixedRate 而非 fixedDelay** | `LeaseRenewalScheduler.java:46` vs `HeartbeatScheduler.java:76`(fixedDelay) | 若续约 tick 卡(5xx retry),下轮立即又来(fixedRate 的宿命);堆积导致内存爆,线程池拒绝 | heap dump 显示 `RejectedExecutionException` / out-of-memory; HeartbeatScheduler 没这问题,两边差异是技术债 |
| **claimWithRetry 退避无 jitter** | `TaskDispatcher.java:414-420` `delayMs = baseDelayMs << attempt` | N 个 worker 同步雪崩 retry (all backoff exponentially in sync) | orch DB 收到 5xx → requeue claim → N 个 worker 再齐声等 200ms*2^n,形成脉冲(thundering herd) |

---

## 4. Python 端独有的 P0

| 缺陷 | 位置 | 影响 |
|---|---|---|
| **`in_flight` dict 多线程不安全** | `dispatcher.py:79,193,196` 普通 dict(无 lock) | 单 event loop 时 GIL 保护勉强 OK;**未来 executor offload to thread pool** 立崩(dict iteration error / missing key race) |
| **`@batch_task` global registry 无测试隔离** | `handler/_decorator.py:36` module-level 单 `_REGISTERED_HANDLERS` dict | pytest 同模块两次 import(conftest 里 import + test 里 import)触发 duplicate task_type;xfail 掩盖了这个 |
| **parity test 全 xfail** | `tests/test_shared_constants_parity.py:65-72` 所有 case 都标 `@pytest.mark.xfail(...)` | Java parity 有牙齿(fail = block PR),Python 端纸老虎 — **drift guard 对称破裂**;Java 后来加常量 Python 无人知 |

---

## 5. drift guard(Lane P #257)的实际效力

**结论**:Java 侧有阻塞力,Python 侧 xfail 无阻塞 — gate **不对等**。

| 守护机制 | Java 侧 | Python 侧 | 能卡 PR 吗 |
|---|---|---|---|
| `JsonFixtureContractTest(12 fixtures)` | ✅ 12 fixture 全 PASS | ❌ test_contract_runner.py 全 xfail(P1 HTTP class fixture标 `xfail tolerated`) | Java fail = block;Py xfail = ignored |
| `SharedConstantsParityTest(4 constant sets)` | ✅ 反射扫 enum + 对账 yaml | ❌ test_shared_constants_parity.py 4 case 全 strict xfail | Java fail = block;Py xfail = pass |
| `sdk-contract-parity.yml` CI gate | Java 侧跑 ↑ 两个测试 | Python 侧跑 ↑ 两个测试(都 xfail) | Java fail CI block;Py pass CI(虚假) |
| `atomic_error_codes` 常量 | Lane K 加 enum 后 test 自动校验 | yaml 占位空 list `[]`,Python 等价物根本没建 | — |

→ **Python SDK 实质上无 drift 守护**。当前 main 上两端"都通过 CI",纯粹因为 Python 测试都 xfail。Java 加字段 → Python 无人知晓 → 下一个 lane agent 重复无谓工作。

---

## 6. 测试盲区(Java + Python 都缺)

这些场景都没有单测:

1. Kafka rebalance 期间 partition pause 状态丢失 → 冲突重放
2. `nextHeartbeatHint` 并发到达的 reschedule race(scheduler tick 与 heartbeat.onReceive 竞争)
3. lease revoked + handler 自然完成的 race(report 路径不知道已被 revoke)
4. `stop(timeout)` 边界:in-flight task 跑 timeout-1ms 完成 vs +1ms 被强杀
5. SASL 凭据错 → 非预期异常捕获 → fail-fast 不 trigger

---

## 7. 评分回望

| 维度 | Java | Python | 跨 SDK | 理由 |
|---|---|---|---|---|
| 设计架构 | 4/5 | 4/5 | 3/5 | 两边模块边界清晰;跨端形对齐但行为协议缝隙大 |
| 错误处理 | 3/5 | 3/5 | 2/5 | SASL 无 fail-fast、descriptor alias 错、cancel 死路径 |
| 测试覆盖 | 4/5 | 3.5/5 | 2/5 | Java 契约测 12/12 pass;Py 全 xfail;parity 对称破裂 |
| 文档可读性 | 3.5/5 | 4/5 | 3/5 | Java ADR 详尽但坑多;Py 代码结构更清;跨文档割裂 |
| **整体可用性** | **3.5/5** | **3.0/5**(P0 protocol bug 阻) | **2.5/5**(drift unguarded) | Java 可试飞;Py 待 #1/#2/#3;两端不等价 |

---

## 8. TOP 10 改进项

按**优先级 + 工作量**排序,前 6 项是本周可完成的:

| # | 项 | Java | Py | 跨 SDK | 工作量 | 优先级 |
|---|---|---|---|---|---|---|
| **1** | Python `descriptor.input_schema` alias 改 `"inputSchema"` | — | 必修 | — | 1h | 🔴 P0-block |
| **2** | Python `_SUPPORTED_SCHEMA_PREFIXES` += `"v1"` | — | 必修 | — | 0.5h | 🔴 P0-block |
| **3** | Python `TaskDispatcher.mark_cancel_requested(...)` 实装 | — | 必修 | — | 4h(含 ut) | 🔴 P0-block |
| **4** | Kafka SASL fail-fast(两端 AuthException / timeout 分流) | 必修 | 必修 | ✅ 同 PR 可协议 | 1d | 🔴 P0 |
| **5** | Java `KafkaTaskConsumer.close()` 加 poll thread join | 必修 | — | — | 4h | 🔴 P0 |
| **6** | Java `LeaseRenewalScheduler` 改 `fixedDelay` | 必修 | — | — | 1h | 🟠 P1 |
| **7** | `nextHeartbeatHint` clamp min/max(两端) | 必修 | 必修 | ✅ | 0.5d | 🟠 P1 |
| **8** | Python parity test 改 strict + 实装常量导出 | — | 必修 | — | 4h | 🟠 P1 |
| **9** | Python `@batch_task` 加 pytest isolation fixture | — | 必修 | — | 1h | 🟡 P2 |
| **10** | Java claimWithRetry 加 jitter | 必修 | — | — | 1h | 🟡 P2 |

**生产灰度前必修**:完成 #1-#4 + #5-#6 = ~3d。

---

## 9. 结论 & 后续

**现状**:
- Java SDK 功能完整但有 3 个 P0 硬化缝隙,可试飞。
- Python SDK 公共 API 对齐但 **3 个协议级 bug 是生产阻断**,descriptor alias / schemaVersion / cancel signal 都破。
- drift guard 对称破裂,无法防两端漂移。

**后续**(见执行 plan 文档):
- 6 个并行 lane(分 A-F)在本周内完成 TOP 10
- Lane A(Python P0 三连)最紧,必须优先
- 完成后 SDK 可生产灰度(99% 等价度)

---

## 附:代码示例

### Java `KafkaTaskConsumer` close 缺 join

```java
// 当前(线程泄漏)
public void close() throws InterruptedException {
  consumer.wakeup();  // ← 退出 poll loop
  // consumer 线程还在跑,可能未 commit offset
  executorService.shutdown();
}

// 应该改为
public void close(Duration timeout) throws InterruptedException {
  consumer.wakeup();
  boolean terminated = executorService.awaitTermination(
    timeout.get(SECONDS), SECONDS);  // ← join
  if (!terminated) {
    log.warn("Kafka consumer thread did not terminate in {}", timeout);
  }
}
```

### Python `descriptor` alias 错

```python
# 当前(错)
class SdkTaskTypeDescriptor(BaseModel):
    input_schema: dict = Field(..., alias="schema")  # wire JSON 是 inputSchema!
    # pydantic.ValidationError: unrecognized field 'inputSchema'

# 应该改为
    input_schema: dict = Field(..., alias="inputSchema")
```

### Python `cancel_requested` 死路径

```python
# 当前(无用)
async def renew_one(self, taskId):
    resp = await self._http.renew(taskId)
    if resp.cancelRequested:
        self.dispatcher.mark_cancel_requested(taskId)  # ← 方法不存在!
    # getattr 返回 None,代码继续,cancel signal 永不翻

# 应该改为
class TaskDispatcher:
    async def mark_cancel_requested(self, task_id: str):
        if task_id in self._in_flight:
            self._in_flight[task_id].cancel_signal._event.set()
```
