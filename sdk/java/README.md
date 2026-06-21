# batch-worker-sdk

**ADR-035 租户自托管 Worker SDK** —— Java 端核心。

跑在租户自己机房 / Kubernetes / VM 上的 worker 进程,经 HTTP `/internal/*` + Kafka `batch.task.dispatch.<tenant>.*` 跟平台通信。**数据 0 出域**,平台只当调度面。

> Python 对等实现见 [`batch-worker-sdk-python/`](../batch-worker-sdk-python/);Spring Boot 自动装配见 [`batch-worker-sdk-spring-boot-starter/`](../batch-worker-sdk-spring-boot-starter/);测试 fake 见 [`batch-worker-sdk-testkit/`](../batch-worker-sdk-testkit/)。

## 形态

- 单一 jar(目标 < 2 MB),依赖只 4 个:`jackson-databind` / `kafka-clients` / `slf4j-api` / `lombok(provided)`
- **不依赖** Spring / batch-common / 任何 framework
- JDK 25(`<java.version>` 由根 pom 控制)
- 通信协议权威源:[`docs/sdk/wire-protocol.md`](../docs/sdk/wire-protocol.md) + [`docs/api/sdk-contract-fixtures/`](../docs/api/sdk-contract-fixtures/) 12 个 JSON 用例

## 快速接入(5 分钟)

### 1. 依赖

Maven:

```xml
<dependency>
  <groupId>com.example.batch</groupId>
  <artifactId>batch-worker-sdk</artifactId>
  <version>1.1.0</version>
</dependency>
```

未发到中央仓时本地安装:

```bash
mvn -pl batch-worker-sdk -am install -DskipTests
```

### 2. 实现 handler

实现 `SdkTaskHandler` 即可:

```java
public class MyImportHandler implements SdkTaskHandler {
  @Override public String taskType() { return "tenant_xyz_import"; }

  @Override public SdkTaskResult execute(SdkTaskContext ctx) {
    int rows = doImport(ctx.parameters());
    return SdkTaskResult.ok("imported " + rows + " rows", Map.of("rows", rows));
  }
}
```

`SdkTaskContext` 提供:`taskId()` / `tenantId()` / `parameters()` / `progressReporter()` / `cancellationSignal()` / `idempotencyKey()`。

要做长任务,周期调 `ctx.progressReporter().report(percent, message)` —— SDK 会把 progress 透传到 console。

### 3. 启动 client

```java
BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
    .baseUrl("https://batch.example.com")
    .apiKey(System.getenv("BATCH_API_KEY"))
    .tenantId("tenant-xyz")
    .workerCode("xyz-import-worker-1")
    .kafkaBootstrap("kafka.example.com:9092")
    .kafkaTopicPattern("batch.task.dispatch.tenant-xyz.*")
    .kafkaGroupId("tenant-xyz-import-workers")
    .build();

BatchPlatformClient client = BatchPlatformClient.builder(config)
    .register(new MyImportHandler())
    .build();

client.start();
Runtime.getRuntime().addShutdownHook(new Thread(() -> client.stop(Duration.ofSeconds(30))));
```

完整可跑示范:[`examples/sample-tenant-worker/`](../examples/sample-tenant-worker/)。

## 公共 API

### Handler 体系

| 接口 / 基类 | 用途 |
|---|---|
| `SdkTaskHandler` | 最小契约:`taskType()` + `execute(ctx)` |
| `SdkAbstractTaskHandler` | 回退 try/catch + 异常 → `SdkTaskResult.fail` 转换 |
| `SdkAbstractImportHandler` / `Export` / `Process` / `Dispatch` / `Atomic` | 5 类业务模板基类,提供 stage 钩子(对齐 ADR-036) |
| `SdkRetryableHandler` | 用 `@RetryOn` 声明重试策略 |
| `SdkIdempotentHandler` | 用 `@Idempotent` 声明幂等键 + `SdkIdempotencyStore` 接管落地 |

### 客户端 / 调度

| 类 | 用途 |
|---|---|
| `BatchPlatformClient` | 入口,start/stop/register handler |
| `BatchPlatformClientConfig` | 不可变 config,builder 构造 |
| `TaskDispatcher` | 派单线程池 + 4-state directive 应用 + cancel signal |
| `KafkaTaskConsumer` | Kafka 派单消费 + capacity-aware partition pause + SASL fail-fast |
| `HeartbeatScheduler` | 心跳上报 + 服务器钳制(`nextHeartbeatHintMs` ∈ [1s, 10×base]) |
| `LeaseRenewalScheduler` | 任务 lease 自动续约(fixedDelay 不漂移) |
| `WorkerRuntimeState` | 4-state FSM:`NORMAL` / `DEGRADED` / `PAUSED` / `DRAINING` |

### Descriptor 上报(SDK Phase 3 M3.1)

`SdkTaskHandler.descriptor()` 重写后,handler 启动时随 register 上报到平台 `custom_task_type_registry`,console 用 `inputSchema` 渲染表单 / `defaults` 三级合并预填。详见 [`examples/sample-tenant-worker/README.md`](../examples/sample-tenant-worker/README.md) 的 descriptor 端到端章节。

```java
@Override public SdkTaskTypeDescriptor descriptor() {
  return SdkTaskTypeDescriptor.builder()
      .displayName("我家导入(echo)")
      .version("v1")
      .defaults(Map.of("batchSize", 2, "targetTable", "staging_${bizDate}"))
      .inputSchema(Map.of("type", "object", "required", List.of("sourcePath")))
      .templateVariables(List.of("bizDate", "dataIntervalStart", "dataIntervalEnd"))
      .build();
}
```

> **凭据纪律(强制)**:SFTP / S3 / DB 密码等敏感值**禁止**走 `defaults` —— 平台 `SensitiveDataValidator` 会按 13 个关键字(password / secret / token / apikey / credential …)拦截 register。凭据一律走 worker 进程的 env / Vault / K8s Secret。

## 配置项一览

`BatchPlatformClientConfig` 关键字段(完整见类 javadoc):

| 字段 | 默认 | 说明 |
|---|---|---|
| `baseUrl` | 必填 | 平台 HTTP base,如 `https://batch.example.com` |
| `apiKey` | 必填 | 平台签发的 worker key,Bearer 注入,**禁日志** |
| `tenantId` | 必填 | 注入 `X-Batch-Tenant-Id` header,所有 internal 调用必带 |
| `workerCode` | 必填 | 进程唯一标识(注册后落 `worker_instance`) |
| `kafkaBootstrap` | 必填 | Kafka broker(SASL 凭据走单独 properties) |
| `kafkaTopicPattern` | 必填 | 派单 topic 正则,如 `batch.task.dispatch.tenant-xyz.*` |
| `kafkaGroupId` | 必填 | consumer group,跨进程同 group 自动分片 |
| `maxConcurrentTasks` | 16 | 进程内并发 handler 上限(超出 capacity-aware pause) |
| `heartbeatIntervalMs` | 15_000 | 心跳基础间隔(服务器 hint 钳制覆盖) |
| `httpConnectTimeoutMs` / `httpReadTimeoutMs` | 5_000 / 30_000 | HTTP 调用超时 |
| `gracefulShutdownTimeoutMs` | 30_000 | `stop()` 总预算(Kafka 15% / dispatcher 70% / scheduler 10% / deactivate 5%) |

## 安全约束(必须遵守)

| 约束 | 实现 |
|---|---|
| 所有调用必须带 `X-Batch-Tenant-Id` | `PlatformHttpClient` 自动注入,**禁手动覆盖** |
| Write 操作必须带 `Idempotency-Key` | claim / report 接 string 参数,业务方传 `BatchPlatformClient.newIdempotencyKey()` 或自家可重放 ID |
| API Key 不进日志 | `PlatformHttpClient` 不 log header |
| 业务 handler 异常不能拖垮整 worker | `TaskDispatcher` 回退 catch + `SdkTaskResult.fail()` report,worker 继续跑 |
| SASL 鉴权失败 fast-fail | `KafkaTaskConsumer` catch `AuthenticationException` → 不重试,`BatchPlatformClient` skip deactivate(凭据已错,HTTP 也 401)→ 由 K8s liveness 重启 |
| Cancel 信号 | `ctx.cancellationSignal()` 实现 `isCancelRequested()`,业务循环里检查后干净退出 |

## 4-state 治理

平台经心跳响应回 `runtimeState` ∈ `{NORMAL, DEGRADED, PAUSED, DRAINING}`:

- `NORMAL`:正常派单
- `DEGRADED`:降速(`maxConcurrentTasks` 减半,新单仍接)
- `PAUSED`:**不接新任务**,Kafka 自动 `pause(assignment)`,已运行的跑完
- `DRAINING`:同 PAUSED + 不续 lease,跑完即停

SDK 自动应用,**租户业务代码不用感知**。

## 测试

```bash
mvn -pl batch-worker-sdk test
```

无 Spring / 无 Testcontainers,本地 5s 内跑完。当前覆盖:8,859 行测试 / 56 测试类(test/main = 1.41,反映对外发布契约的覆盖强度)。

跨 SDK 契约测见 [`docs/api/sdk-contract-fixtures/`](../docs/api/sdk-contract-fixtures/) 的 12 个 JSON 用例(Lane P drift guard),Java + Python SDK 都跑同一份 fixture。

## 文档索引

- [`docs/sdk/quickstart.md`](../docs/sdk/quickstart.md) —— 5 分钟起跑
- [`docs/sdk/onboarding-journey.md`](../docs/sdk/onboarding-journey.md) —— 从 0 到第一个 task 完整 checklist
- [`docs/sdk/wire-protocol.md`](../docs/sdk/wire-protocol.md) —— 通讯协议 + 故障感知矩阵
- [`docs/sdk/troubleshooting.md`](../docs/sdk/troubleshooting.md) —— 排障速查
- [`docs/sdk/byo-sdk-guide.md`](../docs/sdk/byo-sdk-guide.md) —— 自带 SDK(Bring-Your-Own-SDK)指南
- [`docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md`](../docs/architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) —— 定位 ADR

## License

Apache-2.0,与主仓一致。见 [`LICENSE`](../LICENSE) + [`NOTICE`](../NOTICE)。
