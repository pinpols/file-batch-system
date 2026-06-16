# SDK Quickstart — 5 分钟跑起一个租户自托管 worker

`batch-worker-sdk` 让租户在自己的进程里跑业务 handler,只通过 HTTP + Kafka 与平台通信,不连平台 DB / 不加载平台代码。本文给「最短可运行路径」,完整背景与边界见 [ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)。

排障见 [troubleshooting.md](./troubleshooting.md);完整接入流程(申请 API key / 配 Kafka SASL / 注册 task type)见 [`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)。

---

## 前置

- **JDK 21+**(SDK 用 Java 21 record / pattern)
- 平台已下发给你:
  - `BASE_URL` — 平台 `/internal/*` 入口
  - `TENANT_ID` — 你的租户 ID
  - `API_KEY` — `worker.execute` scope 的 API key(P2 起强制;申请流程见 onboarding §2)
  - Kafka bootstrap + topic pattern + group + SASL/SCRAM 凭据(prod 必填;本地联调可走 PLAINTEXT)
- 一个 `workerCode`(自取,在你 tenant 内唯一,例如 `acme-worker-01`)

> 推荐用 `BatchPlatformClientConfig.fromEnv()` 直接吃环境变量,默认前缀 **`BATCH_SDK_`**(即 `BATCH_SDK_BASE_URL` / `BATCH_SDK_TENANT_ID` / ...)。完整变量名见 `BatchPlatformClientConfig.fromEnv()` 的 Javadoc。
> sample 工程([`examples/sample-tenant-worker-java/`](../../examples/sample-tenant-worker-java/))走 builder 风格 + `BATCH_*` 短前缀,两种都行,二选一。

> 平台 `<revision>` 当前 `1.1.0-SNAPSHOT`(见根 `pom.xml`)。release 时由 `-Drevision=X.Y.Z` 覆盖,SDK 坐标版本同步。

---

## 5 步走

### 1. 引依赖

只一个坐标(Spring 用户跳到 §「下一步」):

```xml
<dependency>
  <groupId>com.example.batch</groupId>
  <artifactId>batch-worker-sdk</artifactId>
  <version>1.1.0-SNAPSHOT</version>
</dependency>
```

Core SDK 不绑 Spring,jar < 2 MB(只 jackson + http-client + kafka-clients + slf4j)。约束见 ADR-035 §1。

### 2. 实现一个 handler

```java
public final class EchoHandler implements SdkTaskHandler {
  @Override public String taskType() { return "echo"; }

  @Override public SdkTaskResult execute(SdkTaskContext ctx) {
    // ctx.taskId() / ctx.parameters() / ctx.tenantId() 都从派单 payload 解出
    return SdkTaskResult.ok("echoed", Map.copyOf(ctx.parameters()));
  }
}
```

抛 `RuntimeException` 会被 dispatcher 转 `SdkTaskResult.fail(msg)`,然后通过 REPORT 回平台。失败语义见 ADR-035 §11。

### 3. 构造 client config

```java
BatchPlatformClientConfig config = BatchPlatformClientConfig.fromEnv();  // 读 BATCH_SDK_* 环境变量
// 或 builder 风格,见 examples/sample-tenant-worker-java/.../SampleTenantWorker.java
```

`fromEnv()` 默认前缀 `BATCH_SDK_`,必填变量缺一即一次性报全;builder 风格示例(用 `BATCH_*` 短前缀)见 [`examples/sample-tenant-worker-java/README.md`](../../examples/sample-tenant-worker-java/README.md)。

### 4. 注册 handler + start

```java
BatchPlatformClient client = BatchPlatformClient.builder(config)
    .register(new EchoHandler())
    .build();
Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
client.start();   // 失败抛 RuntimeException,见 troubleshooting #worker-起不来
```

### 5. 验证

进程起来后看日志应出现 `register ok` + 周期性 `heartbeat ok`。然后:

- console 「我的 Worker」页应能看到你的 `workerCode`(`is_self_hosted=true` 过滤)
- 你注册的 task type 应能在 `GET /api/console/custom-task-types?tenantId=...` 列出
- 触发一个该 type 的 job → handler 收到 `execute()` 调用

---

## 下一步

- **Spring Boot 用户**:用 `batch-worker-sdk-spring-boot-starter`,configuration 走 `batch.worker-sdk.*` properties,handler 是 bean 即自动注册。示例:[`examples/sample-tenant-worker-java-spring/`](../../examples/sample-tenant-worker-java-spring/)。设计见 ADR-035 §1.1。
- **完整接入清单**(API key 申请 / Kafka ACL 初始化 / k8s manifest):[`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)
- **wire-protocol 字段细节**(Kafka payload / register body / report body): ADR-035 §「实施记录:协议字段细节」(独立 `docs/sdk/wire-protocol.md` TODO,跟踪在 sdk-roadmap)
- **排障**:[`troubleshooting.md`](./troubleshooting.md)
- **架构约束**(为什么平台不跑租户代码 / 不下放 DB / 不下放 outbox):ADR-035 §3 + §4
