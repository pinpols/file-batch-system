# batch-worker-sdk

**ADR-035 Phase 1 落地** — 租户自托管 worker SDK。

## 形态

- 单一 jar(目标 < 2 MB),依赖只 4 个:`jackson` / `kafka-clients` / `slf4j-api` / `lombok(provided)`
- **不依赖** Spring / batch-common / 任何 framework
- 通过 HTTP `/api/internal/*` + Kafka `batch.task.dispatch.<tenant>.*` 跟平台通信

## 业务方典型用法

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

class MyImportHandler implements SdkTaskHandler {
    @Override public String taskType() { return "tenant_xyz_import"; }
    @Override public SdkTaskResult execute(SdkTaskContext ctx) {
        int rows = doImport(ctx.parameters());
        return SdkTaskResult.ok("imported " + rows + " rows", Map.of("rows", rows));
    }
}

BatchPlatformClient client = BatchPlatformClient.builder(config)
    .register(new MyImportHandler())
    .build();
client.start();
Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
```

## Phase 1 本 PR 范围

- ✅ 模块骨架 + pom(无 Spring,最小依赖)
- ✅ SDK API:`BatchPlatformClient` / `Config` / `Builder`
- ✅ 任务执行契约:`SdkTaskHandler` / `SdkTaskContext` / `SdkTaskResult`
- ✅ HTTP 协议层:`PlatformHttpClient`(register / heartbeat / claim / report / renew-lease)
- ✅ 4 个 test 类 + JDK HttpServer stub 真 HTTP 测试
- ⏳ Kafka 派单消费(下一个 PR P1.2)
- ⏳ Heartbeat scheduler(下一个 PR)
- ⏳ Task dispatcher 线程池 + 失败兜底 catch(下一个 PR)
- ⏳ Sample tenant worker(examples/sample-tenant-worker/)

## 后续 PR 路线

| PR | 内容 |
|---|---|
| P1.1(本 PR) | Module skeleton + HTTP client + 协议 DTO + register 跑通 |
| P1.2 | Kafka consumer wrapper + 派单消息解析 + task dispatcher 线程池 |
| P1.3 | Heartbeat scheduler + lease renewal + graceful shutdown |
| P1.4 | examples/sample-tenant-worker/ + E2E 跑通(需 P2 平台端 API key 实装)|

## 安全约束

| 约束 | 实现 |
|---|---|
| 所有调用必须带 `X-Batch-Tenant-Id` | `PlatformHttpClient` 自动注入 |
| Write 操作必须带 `Idempotency-Key` | claim / report 接 string 参数,业务方传 `BatchPlatformClient.newIdempotencyKey()` |
| API Key 不能进日志 | `PlatformHttpClient` 不 log header |
| 业务 handler 异常不能拖垮整 worker | 框架在 dispatcher 处兜底 catch + report failure(P1.2) |

## 测试

```bash
mvn -pl batch-worker-sdk test
```

无 Spring / 无 Testcontainers,本地 5s 内跑完。
