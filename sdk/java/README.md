# batch-worker-sdk —— Java SDK 家族

ADR-035 租户自托管 Worker SDK 的 Java 实现,拆成 3 个独立模块。总入口见 [`sdk/README.md`](../README.md)。

| 模块 | 坐标 | 是什么 | 何时引 |
|---|---|---|---|
| [`core/`](core/) | `io.github.pinpols.batch:batch-worker-sdk` | 核心:`SdkTaskHandler` / `BatchPlatformClient` / `TaskDispatcher` / Kafka 消费 + 心跳/租约调度 + 5 类 worker batteries handler。**零 Spring 依赖**,jar < 2 MB；控制面 HTTP 由 OkHttp 5 提供 Happy Eyeballs | 所有 Java worker(必引) |
| [`spring/`](spring/) | `io.github.pinpols.batch:batch-worker-sdk-spring-boot-starter` | 可选 Spring Boot 4.x 自动装配:`@Component` handler 即自动注册,`SmartLifecycle` 接管 start/stop | 用 Spring Boot 时引 |
| [`testkit/`](testkit/) | `io.github.pinpols.batch:batch-worker-sdk-testkit` | 测试套件:`FakeBatchPlatform` in-process 平台 fake + `@BatchWorkerTest` JUnit 扩展 | 仅 test scope,**生产不引** |

## 最短接入

```xml
<dependency>
  <groupId>io.github.pinpols.batch</groupId>
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

`ctx.dependencyResultSnapshot()` 是 Job 依赖闸门的观察证据，可包含 `SAME_DAY` 或 `PREVIOUS_BIZ_DAY` 对齐；它不包含冻结业务数据，也不会强制业务查询按该版本读取。Workflow 跨日依赖随本次任务的 `ctx.parameters().get("crossDay")` 一起传入；每个条目带有 `resultVersionId`、业务键/版本号和冻结的 `output` 对象。handler 应使用 `ctx.crossDayResults("alias")`（范围依赖返回多项），从选定版本读取业务数据，不能重新查询 effective/latest。可选依赖没有结果时返回空列表；已提供但身份缺失、结构错误或存储类型不支持时会失败关闭。结果对象、嵌套 Map 和 List 均为只读快照。当前只接受 `INLINE_JSON`；`EXTERNAL_REF` 和 `FILE_RECORD` 会在 Orchestrator 解析阶段失败关闭，直到读取、租户授权与保留契约完成验收。

Kafka 消费组优先沿用已提交位点；首次订阅或位点已过期时，从 broker 仍保留的最早消息读取，避免 SDK 晚于派单启动或新路由 topic 上线时漏掉待执行任务。历史消息仍经过租户校验与服务端 CLAIM，终态任务不会重新执行业务动作。此策略不恢复已被 Kafka 保留策略删除的消息；上线应保留稳定的消费组身份并观察积压。

执行线程抛出 `Error` 后，SDK 会原样传播错误并清理本次续租、取消和进度上下文，同时将 dispatcher 保持为 fatal；后续新派发暂停，`client.isHealthy()` 返回 `false`。宿主应依据健康状态重启 SDK 或进程；安全调用仍可由新 SDK 实例通过平台租约回收恢复，已经标记开始的非幂等调用继续按未知结果治理。

## 环境 / 构建

- JDK：随平台根 pom(`<java.version>`,当前 JDK 21);运行兼容性见 `core/README.md`。
- 5 类 worker handler batteries 单测:`bash scripts/local/sdk-handler-tests.sh java`。
- 真链路本地验证:`bash scripts/local/sdk-e2e-local.sh java`。

## 样例

可运行样例:[`examples/self-hosted-sdk/sample-tenant-worker-java/`](../../examples/self-hosted-sdk/sample-tenant-worker-java/)(纯 core)与 [`sample-tenant-worker-java-spring/`](../../examples/self-hosted-sdk/sample-tenant-worker-java-spring/)(Spring starter)。
