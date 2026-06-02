# batch-worker-sdk-spring-boot-starter

ADR-035 租户自托管 Worker SDK 的可选 Spring Boot 适配层。

只有 Spring Boot 租户 worker 需要依赖本模块。非 Spring 租户应直接依赖 [`batch-worker-sdk`](../batch-worker-sdk/);
core SDK 继续保持 Spring-free。

## 接入只需 3 步(零 wiring 代码)

1. **依赖 starter**:

   ```xml
   <dependency>
     <groupId>com.example.batch</groupId>
     <artifactId>batch-worker-sdk-spring-boot-starter</artifactId>
     <version>1.1.0</version>
   </dependency>
   ```

2. **声明 handler `@Component`**(或 `@Bean`):

   ```java
   @Component
   public class MyImportHandler implements SdkTaskHandler {
     @Override public String taskType() { return "tenant_xyz_import"; }
     @Override public SdkTaskResult execute(SdkTaskContext ctx) { /* ... */ }
   }
   ```

3. **在 `application.yml` 填 `batch.worker-sdk.*`**(见下方)。

**不需要 `@Enable` 注解**,放进 classpath 即生效。完整可跑示范:[`examples/sample-tenant-worker-spring/`](../examples/sample-tenant-worker-spring/) 和 [`examples/batch-worker-sdk-template/`](../examples/batch-worker-sdk-template/)(生产 fork 起点 + Dockerfile + CI)。

## 使用方式

引入 starter 后,在业务应用里声明一个或多个 `SdkTaskHandler` bean:

```java
@Bean
SdkTaskHandler importHandler() {
  return new MyImportHandler();
}
```

配置平台连接参数:

```yaml
batch:
  worker-sdk:
    enabled: true
    base-url: https://batch.example.com
    api-key: ${BATCH_API_KEY}
    tenant-id: tenant-a
    worker-code: tenant-a-worker-1
    kafka-bootstrap: kafka.example.com:9092
    kafka-topic-pattern: batch.task.dispatch.*.tenant-a
    kafka-group-id: tenant-a-workers
```

starter 会自动创建:

- `BatchPlatformClientConfig`:从 `batch.worker-sdk.*` 绑定生成
- `BatchPlatformClient`:自动注册容器内所有 `SdkTaskHandler` bean
- `SmartLifecycle`:负责调用 `BatchPlatformClient.start()` / `stop()`

## 扩展点

- 设置 `batch.worker-sdk.enabled=false`:保留 config/client wiring,但不注册 lifecycle 自启动 bean。
  适用于 slice test 或自定义启动流程。
- 自己提供 `BatchPlatformClientConfig` bean:覆盖默认 properties → config 转换。
- 自己提供 `BatchPlatformClient` bean:完全接管 client 构造。
- 需要声明式幂等时提供一个 `SdkIdempotencyStore` bean;没有该 bean 时 starter 会跳过注入,
  不会报错。

## 配置项

完整字段表见 [`batch-worker-sdk/README.md#配置项一览`](../batch-worker-sdk/README.md);starter 把 `batch.worker-sdk.*` 经 `@ConfigurationProperties` 自动绑定到 `BatchPlatformClientConfig`,字段名 kebab-case ↔ Java camelCase 转换。常用片段:

```yaml
batch:
  worker-sdk:
    enabled: true                                # false → 不自启 lifecycle,wiring 仍存在(slice test 用)
    base-url: https://batch.example.com
    api-key: ${BATCH_API_KEY}                    # 必走 env / Vault / K8s Secret
    tenant-id: tenant-a
    worker-code: tenant-a-worker-1
    kafka-bootstrap: kafka.example.com:9092
    kafka-topic-pattern: batch.task.dispatch.tenant-a.*
    kafka-group-id: tenant-a-workers
    max-concurrent-tasks: 16
    heartbeat-interval-ms: 15000
    graceful-shutdown-timeout-ms: 30000
```

## 生命周期

- **启动**:`SmartLifecycle.start()`(默认 phase `Integer.MAX_VALUE - 1024`,在 web container 之后)→ 调 `BatchPlatformClient.start()` → 注册到平台 + 起 Kafka consumer + heartbeat scheduler
- **关闭**:`SmartLifecycle.stop()` → `BatchPlatformClient.stop(Duration)`(默认 30s,见 `graceful-shutdown-timeout-ms`),按 Kafka 15% / dispatcher 70% / scheduler 10% / deactivate 5% 预算切分
- **零-handler 启动**:抛 `IllegalStateException` 直接 fail,K8s/systemd 会重启 —— 防止"启动正常但永远不接活"

## 兼容性

v1 只面向仓库当前基线 Spring Boot 4.x。Boot 3.x 兼容性不承诺;如果后续要支持,需要单独补兼容矩阵和测试 lane。

## 文档索引

- [`batch-worker-sdk/README.md`](../batch-worker-sdk/README.md) —— core SDK 完整 API + 配置项 + 安全约束
- [`examples/sample-tenant-worker-spring/`](../examples/sample-tenant-worker-spring/) —— 最小示范
- [`examples/batch-worker-sdk-template/`](../examples/batch-worker-sdk-template/) —— 生产 fork 起点(Dockerfile + run.sh + CI)
- [`docs/sdk/quickstart.md`](../docs/sdk/quickstart.md) —— 5 分钟起跑(starter 路径)
- [`docs/sdk/troubleshooting.md`](../docs/sdk/troubleshooting.md) —— 排障

## License

Apache-2.0,与主仓一致。见 [`LICENSE`](../LICENSE)。
