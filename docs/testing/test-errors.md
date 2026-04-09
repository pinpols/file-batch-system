# 测试失败清单

生成时间：2026-04-06

---

## 一、单元测试失败（batch-orchestrator）

### 1. `OutboxPollScheduler.lockConfig` — NullPointerException

**影响测试：**
- `OutboxPollSchedulerTest.shouldSkipAdvanceWhenCircuitBreakerDeniesPolling`
- `OutboxPollSchedulerTest.shouldAdvanceAndUpdateCircuitBreakerWhenAllowed`

**错误信息：**
```
java.lang.NullPointerException: Cannot invoke "OutboxProperties.getShardTotal()"
  because "BatchOrchestratorGovernanceProperties.outbox()" is null
  at OutboxPollScheduler.lockConfig(OutboxPollScheduler.java:64)
  at OutboxPollScheduler.poll(OutboxPollScheduler.java:37)
```

**根因：** 测试中 `BatchOrchestratorGovernanceProperties` mock 没有为 `outbox()` 配置返回值，导致 `lockConfig()` 拿到 null 后 NPE。

**修复方向：** 在测试 `@BeforeEach` 或 stub 阶段，为 `governanceProperties.outbox()` mock 一个合法的 `OutboxProperties` 实例。

---

### 2. `OutboxPollSchedulerTest.shouldSkipAdvanceWhenCircuitBreakerDeniesPolling` — UnnecessaryStubbingException

**错误信息：**
```
org.mockito.exceptions.misusing.UnnecessaryStubbingException:
Unnecessary stubbings detected.
  1. -> at OutboxPollSchedulerTest.shouldSkipAdvanceWhenCircuitBreakerDeniesPolling(OutboxPollSchedulerTest.java:59)
```

**根因：** 第 59 行存在一个 stub 声明，但测试执行路径中该 stub 从未被调用（因为 NPE 更早抛出，或逻辑路径不经过该调用）。

**修复方向：** 删除 `OutboxPollSchedulerTest.java:59` 处多余的 `when(...).thenReturn(...)` 语句，或在 NPE 修复后重新评估其必要性。

---

### 3. `OutboxPollSchedulerTest.shouldAdvanceAndUpdateCircuitBreakerWhenAllowed` — Mockito 未被调用

**错误信息：**
```
Wanted but not invoked:
  scheduleForwarder.advance(<Capturing argument: SchedulePlan>)
  -> at DefaultScheduleForwarder.advance(DefaultScheduleForwarder.java:33)
Actually, there were zero interactions with this mock.
  at OutboxPollSchedulerTest.shouldAdvanceAndUpdateCircuitBreakerWhenAllowed(OutboxPollSchedulerTest.java:52)
```

**根因：** 测试期望 `scheduleForwarder.advance()` 被调用，但实际生产代码（或 mock 配置）导致该方法路径未执行。  
可能与上面 NPE 同根——`outbox()` 返回 null 导致 `poll()` 提前异常退出，`advance()` 根本没机会被调用。

**修复方向：** 先修复 NPE（第1条），再验证此测试是否自动通过。若仍失败，检查 `OutboxPollScheduler.poll()` 的条件分支是否与测试场景匹配。

---

### 4. `KafkaOutboxPublisherTest.shouldRecordFailedDeliveryWhenDispatchTopicSendFails` — CompletableFuture 未完成异常

**位置：** `KafkaOutboxPublisherTest.java:55`

**错误信息：**
```
java.lang.AssertionError:
Expecting
  <CompletableFuture[Completed: false]>
to be completed exceptionally.
```

**根因：** 测试期望 Kafka send 失败后 `CompletableFuture` 以异常完成，但实际 Future 仍处于 `Completed: false`（未完成状态）。  
说明 mock 的 Kafka `send()` 失败回调没有触发，或者生产代码中 Future 的异常传播链路有问题。

**修复方向：** 检查 `KafkaOutboxPublisher` 中 dispatch topic send 失败时的异常处理逻辑，确保 `CompletableFuture` 在失败路径上调用了 `completeExceptionally()`。

---

### 5. `KafkaOutboxPublisherTest.shouldRecordFailedDeliveryWhenFallbackTopicSendFails` — CompletableFuture 未完成异常

**位置：** `KafkaOutboxPublisherTest.java:80`

**错误信息：** 同第 4 条，Fallback topic 路径相同问题。

**修复方向：** 同第 4 条，检查 fallback topic send 失败时的 `completeExceptionally()` 调用。

---

## 二、集成测试崩溃（batch-trigger）

**模式：** `--it`（`-Dtest='*IntegrationTest,*IT'`）

**错误信息：**
```
[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
[ERROR] Process Exit Code: 1
[ERROR] org.apache.maven.surefire.booter.SurefireBooterForkException
```

**根因：** JVM fork 在测试启动阶段即崩溃（仅 2 秒），未能生成任何测试报告。  
结合 `batch-orchestrator` dump 文件中看到的 `Testcontainers` 注解扫描挂起迹象，最可能原因是：
- Docker daemon 未运行 / Docker socket 路径不匹配
- Testcontainers 初始化超时触发 Maven shutdown hook

**修复方向：**
1. 运行前确认 Docker 已启动：`docker ps`
2. 检查环境变量 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`（默认 `~/.docker/run/docker.sock`），确认路径有效
3. 必要时增大 Surefire fork 超时或查看 `batch-trigger/target/surefire/` 下的详细 JVM 启动日志

---

## 三、E2E 测试崩溃（batch-common）

**模式：** `--e2e`

**错误信息：** 同上，`batch-common` 模块同样触发 `The forked VM terminated`。

**根因 & 修复方向：** 同第二条，Docker 环境问题。

---

## 汇总

| # | 模块 | 测试类 | 错误类型 | 优先级 |
|---|------|--------|----------|--------|
| 1 | batch-orchestrator | OutboxPollSchedulerTest | NPE: outbox() 返回 null | 高（阻塞其他测试） |
| 2 | batch-orchestrator | OutboxPollSchedulerTest | UnnecessaryStubbing（L59） | 低（NPE 修复后复查） |
| 3 | batch-orchestrator | OutboxPollSchedulerTest | advance() 未被调用 | 中（NPE 修复后复查） |
| 4 | batch-orchestrator | KafkaOutboxPublisherTest | Future 未 completeExceptionally（L55） | 高 |
| 5 | batch-orchestrator | KafkaOutboxPublisherTest | Future 未 completeExceptionally（L80） | 高 |
| 6 | batch-trigger | *IntegrationTest | JVM crash（Docker 未就绪） | 环境问题 |
| 7 | batch-common | E2E | JVM crash（Docker 未就绪） | 环境问题 |
