# sample-tenant-worker-spring

ADR-035 租户自托管 worker 的 **Spring Boot 接入示范**,经
[`batch-worker-sdk-spring-boot-starter`](../../batch-worker-sdk-spring-boot-starter/) 自动装配。

> **同一自托管能力的 4 种接入,按租户技术栈选**:
> - [`../sample-tenant-worker`](../sample-tenant-worker/) — 纯 Java + 手写 `main` wiring
> - **`sample-tenant-worker-spring`(本目录)** — Java + Spring Boot starter(自动装配)
> - [`../sample-tenant-worker-python`](../sample-tenant-worker-python/) — Python 3.12+ + asyncio
> - [`../batch-worker-sdk-template`](../batch-worker-sdk-template/) — Java 生产 fork 起点(Dockerfile + CI)
>
> core SDK 本身始终 framework-free(Java SDK Spring-free / Python SDK 无 web framework 依赖);本示范多依赖一个可选 starter。
> 下方"两种接入对比"指 Java 域内的 plain vs Spring,Python 版本另见上链。

## 跑起来

```bash
# 方式一:dev 直接跑
mvn spring-boot:run -f examples/sample-tenant-worker-spring/pom.xml

# 方式二:打包后跑
mvn package -f examples/sample-tenant-worker-spring/pom.xml
java -jar examples/sample-tenant-worker-spring/target/sample-tenant-worker-spring-1.0.0-SNAPSHOT.jar
```

平台连接参数走环境变量(见 [`application.yml`](src/main/resources/application.yml)):
`BATCH_BASE_URL` / `BATCH_TENANT_ID` / `BATCH_WORKER_CODE` / `BATCH_KAFKA` / `BATCH_API_KEY` …

## 接入只需 3 步(零 wiring 代码)

1. 依赖 `batch-worker-sdk-spring-boot-starter`(见 [`pom.xml`](pom.xml))。
2. 把每个 `SdkTaskHandler` 实现声明成 `@Component`(见 [`EchoHandler`](src/main/java/com/example/batch/ext/sample/spring/EchoHandler.java))。
3. 在 `application.yml` 填 `batch.worker-sdk.*`。

starter 自动完成:properties → `BatchPlatformClientConfig` 绑定、收集所有 `SdkTaskHandler` bean 注册、
`SmartLifecycle` 托管 `start()` / `stop()`。**不需要 `@Enable` 注解**,放进 classpath 即生效。

---

## 两种接入对比

| 维度 | 纯 Java(`sample-tenant-worker`) | Spring Boot(本示例) |
|---|---|---|
| 依赖 | `batch-worker-sdk`(+ 一个 slf4j 绑定) | `batch-worker-sdk-spring-boot-starter` + `spring-boot-starter` |
| 配置来源 | 手写 `System.getenv` → builder | `application.yml` 的 `batch.worker-sdk.*` 自动绑定(或 `BatchPlatformClientConfig.fromEnv()`) |
| handler 注册 | 手写 `.register(new XxxHandler())` | handler 声明 `@Component`,starter 自动收集注册 |
| client 生命周期 | 自己 `client.start()` + 注册 shutdown hook | `SmartLifecycle` 随 Spring 上下文起停 |
| 入口代码量 | ~40 行 `main` wiring | `@SpringBootApplication` + `SpringApplication.run`,无 wiring |
| 适合 | 不想引 Spring 的轻量进程 / 跨语言对照 | 已有 Spring Boot 技术栈的租户 |
| 零-handler / 平台不可达 | `start()` 抛异常,自行 `System.exit(1)` | `start()` 抛异常 → 应用启动失败(K8s/systemd 重启) |

**两种都不是平台侧依赖**:都跑在租户自己的基础设施上,平台只当调度面;选哪种纯看租户技术栈。

> 可选开关:`batch.worker-sdk.enabled=false` 可让进程起来但**不真连平台**(本地调试 / 灰度)。
> 高级覆盖:容器里自带一个 `BatchPlatformClientConfig` 或 `BatchPlatformClient` bean,starter 会自动退让。
