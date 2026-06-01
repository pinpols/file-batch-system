# batch-worker-sdk-spring-boot-starter

ADR-035 租户自托管 Worker SDK 的可选 Spring Boot 适配层。

只有 Spring Boot 租户 worker 需要依赖本模块。非 Spring 租户应直接依赖 `batch-worker-sdk`;
core SDK 继续保持 Spring-free。

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

## 兼容性

v1 只面向仓库当前基线 Spring Boot 4.x。Boot 3.x 兼容性不承诺;如果后续要支持,需要单独补兼容矩阵和测试 lane。
