# batch-worker-sdk-testkit

**ADR-035 租户自托管 Worker SDK** —— Java 端集成测试工具包(SDK-P5-2)。

给租户的 SDK worker 测试用的 fake 平台 + JUnit 5 扩展,让你**不起真 orchestrator** 就能端到端验证 handler 行为(register → claim → report 全链路)。

> 核心 SDK 见 [`core/`](../core/);Spring Boot 自动装配见 [`spring/`](../spring/);Python 对等实现见 [`../../python/`](../../python/)。

## 形态

- 仅测试期依赖,**不进** worker 运行时 jar(`<scope>test</scope>` 引入)
- 依赖:`batch-worker-sdk`(core)+ `spring-kafka-test`(EmbeddedKafka)+ `junit-jupiter`

## 提供什么

| 组件 | 作用 |
|------|------|
| `FakeBatchPlatform` | 进程内 fake 平台:模拟 `/internal/*`(register/claim/report/renew)+ 派单,记录所有调用 |
| `@BatchWorkerTest` / `BatchWorkerExtension` | JUnit 5 扩展:自动拉起 FakeBatchPlatform + EmbeddedKafka,注入到测试 |
| `TaskDispatchMessageBuilder` | 构造 `batch.task.dispatch.<tenant>.*` 派单消息的 fluent builder（`TaskDispatchMessageBuilder.dispatch("<taskType>")`） |
| `RecordedReport` | 断言用 record:捕获 worker 上报的终态(`success()` / `message()` / `errorCode()` / `outputs()`) |

## 用法

```java
@BatchWorkerTest
class MyHandlerTest {
    @Test
    void completesTask(FakeBatchPlatform platform) {
        platform.dispatch(
            TaskDispatchMessageBuilder.dispatch("my-task-type").taskId(101L).build());
        RecordedReport report = platform.awaitReport(101L, Duration.ofSeconds(5));
        assertThat(report.success()).isTrue();
        assertThat(platform.claims()).contains(101L);
    }
}
```

`dispatch` 要求非空任务 ID 和非空白租户 ID，校验失败不会登记半成品。测试平台以同一快照登记租户与任务载荷；已认领调用的载荷不会被后续同 ID 派单覆盖。这些是 testkit 的行为，不代替真实平台协议验收。

HTTP 桩仅支持注册、心跳、下线以及单任务 claim、report、renew 和 non-idempotent-started；其他路径返回 404，已支持路径使用非 POST 方法返回 405。批量接口不由此桩模拟。

任务报告完成后不能重复认领。需要模拟平台重试时，先调用 `requeue(taskId)` 将已完成任务重置为可认领状态，再派单；此操作保留最近一次派单快照，清除该任务当前报告和非幂等开始记录，不自动发送消息。重新认领会生成新的调用标识，旧调用不能续租、开始或报告。

完整自检见 `FakeBatchPlatformSelfTest`(成功/失败/取消三条链路)。

## 相关

- [`core/README.md`](../core/README.md) —— core SDK 完整 API + 配置项
- 跨语言契约 fixtures:`docs/api/sdk-contract-fixtures/`
