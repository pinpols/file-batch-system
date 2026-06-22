# batch-worker-sdk —— Java SDK 家族

ADR-035 租户自托管 Worker SDK 的 Java 实现,拆成 3 个独立模块。总入口见 [`sdk/README.md`](../README.md)。

| 模块 | 坐标 | 是什么 | 何时引 |
|---|---|---|---|
| [`core/`](core/) | `com.example.batch:batch-worker-sdk` | 核心:`SdkTaskHandler` / `BatchPlatformClient` / `TaskDispatcher` / Kafka 消费 + 心跳/租约调度 + 5 类 worker batteries handler。**零 Spring 依赖**,jar < 2 MB(只 jackson + kafka-clients + slf4j) | 所有 Java worker(必引) |
| [`spring/`](spring/) | `com.example.batch:batch-worker-sdk-spring-boot-starter` | 可选 Spring Boot 4.x 自动装配:`@Component` handler 即自动注册,`SmartLifecycle` 接管 start/stop | 用 Spring Boot 时引 |
| [`testkit/`](testkit/) | `com.example.batch:batch-worker-sdk-testkit` | 测试套件:`FakeBatchPlatform` in-process 平台 fake + `@BatchWorkerTest` JUnit 扩展 | 仅 test scope,**生产不引** |

## 最短接入

```xml
<dependency>
  <groupId>com.example.batch</groupId>
  <artifactId>batch-worker-sdk</artifactId>
  <version>1.1.0-SNAPSHOT</version>
</dependency>
```

未发到中央仓时本地安装:`mvn -pl sdk/java/core -am install -DskipTests`。

```java
public class MyImportHandler implements SdkTaskHandler {
  @Override public String taskType() { return "tenant_xyz_import"; }
  @Override public SdkTaskResult execute(SdkTaskContext ctx) {
    int rows = doImport(ctx.parameters());
    return SdkTaskResult.ok("imported " + rows + " rows", Map.of("rows", rows));
  }
}
```

完整 5 步(配 client / 注册 / start)见 [`core/README.md`](core/README.md) 与 [`docs/sdk/quickstart.md`](../../docs/sdk/quickstart.md)。

## 环境 / 构建

- JDK：随平台根 pom(`<java.version>`,当前 JDK 25);运行兼容性见 `core/README.md`。
- 5 类 worker handler batteries 单测:`bash scripts/local/sdk-handler-tests.sh java`。
- 真链路本地验证:`bash scripts/local/sdk-e2e-local.sh java`。

## 样例

可运行样例:[`examples/self-hosted-sdk/sample-tenant-worker-java/`](../../examples/self-hosted-sdk/sample-tenant-worker-java/)(纯 core)与 [`sample-tenant-worker-java-spring/`](../../examples/self-hosted-sdk/sample-tenant-worker-java-spring/)(Spring starter)。
