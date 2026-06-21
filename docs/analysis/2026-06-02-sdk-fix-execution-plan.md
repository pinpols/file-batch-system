# SDK TOP 10 改进 — 6 Lane 并发执行 Plan

**日期**:2026-06-02
**配套**:[`2026-06-02-sdk-code-deep-review.md`](2026-06-02-sdk-code-deep-review.md) §8 TOP 10
**目标**:并发 6 agent 完成全部 10 项,~6h 内 PR 全合 main

---

## 0. 冲突分析(必读)

| 文件 | 改它的 TOP # | 冲突? |
|---|---|---|
| `batch-worker-sdk-python/src/batch_worker_sdk/dispatcher/dispatcher.py` | #2 + #3 | ⚠️ 同文件 → 必须打包 |
| `batch-worker-sdk/src/main/java/.../KafkaTaskConsumer.java` | #4-Java + #5 | ⚠️ 同文件 → 必须打包 |
| 其余 | 各自独立 | ✅ 无冲突 |

→ 6 个 lane,2 个 lane 是合并打包,4 个 lane 是单独改动。

---

## 1. 6 Lane 切分

| Lane | 含 TOP # | 内容 | 改的文件 | 估时 |
|---|---|---|---|---|
| **🐍 A** Python 协议级 P0 三连 | #1 + #2 + #3 | descriptor alias 修 + schemaVersion 支持 v1 + `mark_cancel_requested` 实装 + cancel signal 真翻 | `batch-worker-sdk-python/src/batch_worker_sdk/task/descriptor.py` + `dispatcher/dispatcher.py` + `scheduler/_lease.py` + `task/cancellation.py`(若需)| 4h |
| **🐍 B** Python 治理 | #8 + #9 | parity test 改 strict + 新建 `constants.py` 导出 + `@batch_task` test isolation fixture | `constants.py`(新)+ `tests/test_shared_constants_parity.py` + `handler/_decorator.py` + `conftest.py` | 3h |
| **🐍 C** Python Kafka SASL fail-fast | #4-Py | `consumer.start()` 包 `asyncio.wait_for(timeout=10)` + raise `PlatformError` | `batch-worker-sdk-python/src/batch_worker_sdk/internal/_kafka.py` + 单测 | 2h |
| **🐍 D** Python heartbeat clamp | #7-Py | `nextHeartbeatHint` 加 1s ≤ x ≤ 10×interval clamp | `batch-worker-sdk-python/src/batch_worker_sdk/scheduler/_heartbeat.py` + 单测 | 1h |
| **☕ E** Java Kafka hardening | #4-Java + #5 | `AuthException` 分流 fail-fast + `close()` 加 thread join + `stop()` 预算重分(预留 kafka join 时间)| `batch-worker-sdk/src/main/java/.../{KafkaTaskConsumer,BatchPlatformClient}.java` + IT | 1d |
| **☕ F** Java scheduler 小事三件 | #6 + #7-Java + #10 | `LeaseRenewal` 改 `fixedDelay` + `Heartbeat` clamp + `claimWithRetry` 加 jitter | `batch-worker-sdk/src/main/java/.../{LeaseRenewalScheduler,HeartbeatScheduler,TaskDispatcher}.java` + 各自单测 | 0.5d |

**总并发耗时**:max ≈ 1d(Lane E),~95% 时间在 6 个 agent 并行,串行只是 PR 队列 rebase + CI。

---

## 2. 文件域矩阵(确认 Lane 间零冲突)

| 文件 | A | B | C | D | E | F |
|---|---|---|---|---|---|---|
| `batch-worker-sdk-python/.../task/descriptor.py` | ✅ | | | | | |
| `batch-worker-sdk-python/.../task/cancellation.py` | ✅ | | | | | |
| `batch-worker-sdk-python/.../dispatcher/dispatcher.py` | ✅ | | | | | |
| `batch-worker-sdk-python/.../scheduler/_lease.py` | ✅ | | | | | |
| `batch-worker-sdk-python/.../handler/_decorator.py` | | ✅ | | | | |
| `batch-worker-sdk-python/.../constants.py`(新) | | ✅ | | | | |
| `batch-worker-sdk-python/.../internal/_kafka.py` | | | ✅ | | | |
| `batch-worker-sdk-python/.../scheduler/_heartbeat.py` | | | | ✅ | | |
| `batch-worker-sdk-python/tests/test_shared_constants_parity.py` | | ✅ | | | | |
| `batch-worker-sdk-python/tests/conftest.py` | | ✅ | | | | |
| `batch-worker-sdk/.../KafkaTaskConsumer.java` | | | | | ✅ | |
| `batch-worker-sdk/.../BatchPlatformClient.java` | | | | | ✅ | |
| `batch-worker-sdk/.../LeaseRenewalScheduler.java` | | | | | | ✅ |
| `batch-worker-sdk/.../HeartbeatScheduler.java` | | | | | | ✅ |
| `batch-worker-sdk/.../TaskDispatcher.java` | | | | | | ✅ |

**潜在冲突点**:`batch-worker-sdk-python/src/batch_worker_sdk/__init__.py` — A 可能加 cancel 内部方法导出,B 可能加 `constants` 模块导出。**brief 强制**:都用 append 模式,不删别人的行,GitHub auto-rebase 大概率干净合。

---

## 3. 每 Lane 详细 brief 要点

### Lane A(Python 协议级 P0 三连)

**任务**:
1. **#1**:`task/descriptor.py:32` `Field(alias="schema")` → `Field(alias="inputSchema")`。检 wire-protocol §A schema 字段名权威源。
2. **#2**:`dispatcher/dispatcher.py:50` `_SUPPORTED_SCHEMA_PREFIXES = ("v2",)` → `("v1", "v2")`。对照 Java `TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS`。
3. **#3**:`TaskDispatcher` 加 `mark_cancel_requested(task_id: int, reason: str) -> None`,内部维护 `_pending_cancellations: dict[int, CancellationSignal]`,在 `on_message` 注入 ctx 时存引用,该方法翻 `signal.mark_cancelled()`。`scheduler/_lease.py:118` 的 `getattr` 回退删掉。

**测试**:
- `tests/test_descriptor_wire_alias.py`:验 `model_dump_json()` 出 `inputSchema`
- `tests/test_dispatcher_schema_versions.py`:v1 / v2 msg 都接,v3 drop
- `tests/test_dispatcher_cancellation_path.py`:lease renew 收 `cancelRequested=True` → 30 行内 handler 自检 `ctx.cancel_signal.is_cancellation_requested == True`(用 FakeBatchPlatform)

### Lane B(Python 治理)

**任务**:
1. **#8**:新建 `batch-worker-sdk-python/src/batch_worker_sdk/constants.py` 暴露:
   ```python
   SCHEMA_VERSIONS_SUPPORTED: Final[tuple[str, ...]] = ("v1", "v2")
   SENSITIVE_KEYWORDS: Final[frozenset[str]] = frozenset({...})  # 从 internal/_sensitive_keys.py 抽
   TASK_STATUSES: Final[tuple[str, ...]] = ("CREATED", "READY", ...)
   ```
   `__init__.py` append 导出(不删既有行)。
   `tests/test_shared_constants_parity.py` 删 `@pytest.mark.xfail` marker,改 strict assertion。
2. **#9**:`handler/_decorator.py` 加 `_clear_registered_handlers()` 给测试用(已存在则确认导出)。`tests/conftest.py` 加 autouse fixture `clear_handlers_each_test`。

**测试**:
- `tests/test_constants_parity_strict.py`(新名,避免冲撞)
- `tests/handler/test_decorator_isolation.py`(新):两个 test 各自 `@batch_task("same")`,不互相污染

### Lane C(Python Kafka SASL fail-fast)

**任务**:
- `internal/_kafka.py:145` 附近 `await consumer.start()` 包 `asyncio.wait_for(consumer.start(), timeout=10.0)`
- `TimeoutError` 转 `PlatformError(code="kafka_sasl_timeout", message="Kafka broker auth/connect timeout — check SASL credentials and broker address")`
- 不重试,直接传播到 `BatchPlatformClient.start()` 让上层 fail-fast

**测试**:`tests/test_kafka_sasl_fail_fast.py`:mock aiokafka start hang → 10s 内 raise

### Lane D(Python heartbeat clamp)

**任务**:
- `scheduler/_heartbeat.py` `_apply_hint(hint: timedelta) -> timedelta`:
  ```python
  MIN_HINT_S = 1.0
  MAX_HINT_MULTIPLIER = 10
  baseline = self._config.heartbeat_interval.total_seconds()
  clamped = max(MIN_HINT_S, min(hint.total_seconds(), baseline * MAX_HINT_MULTIPLIER))
  ```
- 改 `_next_interval_s` + log INFO "applied heartbeat hint X (clamped from Y)"

**测试**:`tests/scheduler/test_heartbeat_hint_clamp.py`:hint=0.1s → 1s;hint=600s(baseline=30s, max=300s)→ 300s;hint=15s → 15s 不变

### Lane E(Java Kafka hardening,两 P0 同改一文件)

**任务**:
1. **#4-Java**:`KafkaTaskConsumer.java` poll loop catch `AuthenticationException` → 标 `fatalAuth=true` + 不重试,fail-fast 退出 loop;`BatchPlatformClient` 检测到 `fatalAuth` 后 stop(skip deactivate,直接 close)
2. **#5**:`KafkaTaskConsumer.close()` 加 `kafkaThread.join(joinTimeout)`,join 超时则 `log.warn("Kafka poll thread did not exit within X")`;`BatchPlatformClient.stop()` 预算重分:kafka join 占 15% / kafka close 占 5% / dispatcher 70% / scheduler 10%

**测试**:
- `KafkaConsumerAuthFailureIT.java`:模拟 broker reject auth → SDK 10s 内 fail-fast,不 retry
- `BatchPlatformClientStopJoinTest.java`:slow poll thread → close() 卡 join timeout,WARN log,不阻塞 dispatcher drain

### Lane F(Java scheduler 三件)

**任务**:
1. **#6**:`LeaseRenewalScheduler.java:46` `scheduleAtFixedRate` → `scheduleWithFixedDelay`
2. **#7-Java**:`HeartbeatScheduler.applyHeartbeatHint()` 加 clamp(同 Lane D 逻辑)
3. **#10**:`TaskDispatcher.claimWithRetry` 退避加 ±10% jitter:
   ```java
   long delayMs = baseDelayMs << attempt;
   delayMs += ThreadLocalRandom.current().nextLong(0, delayMs / 10);
   ```

**测试**:
- `LeaseRenewalSchedulerFixedDelayTest`:模拟 tick 卡 10s,验证下次 tick 不立即追赶
- `HeartbeatSchedulerClampTest`:同 Lane D 逻辑 mirror
- `TaskDispatcherClaimJitterTest`:重试 100 次,delayMs 分布检查有 jitter spread

---

## 4. 调度与依赖

```
T+0     T+1h   T+2h   T+3h   T+4h   T+5h   T+6h
 ┃       ┃      ┃      ┃      ┃      ┃      ┃
 ├─ A ──────────┘                            (4h, P0)
 ├─ B ────────┘                              (3h, P1)
 ├─ C ────┘                                  (2h, P1)
 ├─ D ──┘                                    (1h, P1)
 ├─ E ────────────────────────────────┘      (1d, P0/P1)
 └─ F ────────────────┘                      (0.5d, P1/P2)
                                       ↓
                            队列 rebase + CI 串行 ~2h
                                       ↓
                            全 6 PR 落 main
```

依赖关系:
- A 是 P0 protocol 破裂,**强烈建议先合**(其它 lane 都不动它的文件,但 A 合后 Python SDK 才能真用)
- B/C/D 独立,任意顺序合
- E/F 互不相干,任意顺序合
- 全 lane 互不引用对方的输出,**无强依赖**

---

## 5. 风险与对冲

| 风险 | 概率 | 影响 | 对冲 |
|---|---|---|---|
| Lane A 三个改动叠加,agent cherry-pick 错 | 中 | A 的 PR 不能合 | brief 里把 3 改动分 3 个 subsection,顺序固定;单测分文件 |
| Lane E 改 `stop(timeout)` 预算分摊,可能影响 Python `_lifecycle.py` 兼容 | 中 | 跨 SDK 行为变 | brief 强调只动 Java 侧,Python 不动;wire-protocol §5 文档同步更新 |
| Lane B 新建 `constants.py`,可能与 Lane A 撞 `__init__.py` 导出 | 中 | rebase 冲突 | 都用 append 模式,GitHub auto-rebase 干净合;若不干净,我手解 |
| 6 个 agent 并发 worktree,GitHub auto-rebase 排队 | 中 | 慢 1-2 cycle | 接受;P0 优先合(A 排第一) |
| 别的 session 同时还在跑 r3-* PR | 高 | 队列堵 | 我的 lane 全在 batch-worker-sdk-python/* + batch-worker-sdk/* 范围,跟 r3-* 不重叠 |
| 多 agent worktree 互相踩 | 高 | 写错文件 | 历史教训:每 agent brief 明确 worktree 隔离 + 提醒 cwd |

---

## 6. 完成验收

每 lane 必须满足:
- ✅ `pytest -q`(Python)或 `mvn -pl batch-worker-sdk -am test`(Java)全部通过
- ✅ ruff + mypy strict(Python)/ spotless(Java)全部通过
- ✅ 新增至少 1 个测试覆盖本 lane 改动
- ✅ commit + push + `gh pr create --base main` + `gh pr merge --squash --auto`
- ✅ 回报 ≤ 300 字,含 PR# / 改文件 `file:line` / 关键测试名 / 任何 blocker

主进程在 6 个 PR 全合后:
1. 跑 sdk-contract-parity CI workflow 看 fixture 全 PASS
2. 拉 main 验:
   ```bash
   cd sdk-python && .venv/bin/pytest -q  # 期望 0 xfail(parity 已 strict)
   mvn -pl batch-worker-sdk test          # 全部通过
   ```
3. 追加 §10 到 `2026-06-02-sdk-code-deep-review.md` 报告交付状态 + 评分回望

---

## 7. 启动

**A 档**(6 个并发,~6h 总时长,接受小风险)— 推荐
**B 档**(A 先做,其它后发,~10h 零冲突)— 保守

说 A 或 B,我直接发出。
