# Third-Party Software Licenses

**Product**: `batch-platform`  
**Version**: `1.0.0` (与根 POM 默认 GA 版本一致)
**Generated**: `2026-09-25`
**Source**: curated from the current `pom.xml` / module POM files + `sdk/python/pyproject.toml`;Maven 部分以 `mvn -P compliance` 输出为底（406 components，见 `sbom.json`）。

This document is a human-readable snapshot of the third-party components referenced by the repository at the time of generation.
Internal modules under `io.github.pinpols.batch:*` are excluded.

**变更摘要(2026-09-25)**:
- Spring Boot 4.1.0 → **4.1.1**;Spring AI M3 → **2.0.1**;MyBatis starter 4.0.1 → **4.1.0**
- AWS SDK 2.31.78 → **2.55.5**;OkHttp 5.0.0-alpha.16 → **5.5.0 GA**;Okio → **3.18.2**
- POI 5.4.0 → **5.5.1**;Commons Compress 1.27.1 → **1.28.0**;JSqlParser 5.3 → **5.4**
- PostgreSQL JDBC 42.7.12 → **42.7.13**;Resilience4j 2.3.0 → **2.4.0**（改用 Boot 4 专用模块）;Bucket4j 8.14.0 → **8.20.0**
- 更新 ArchUnit、Lombok、Bouncy Castle、Spring Retry 与构建插件版本；跨主版本升级留待兼容性评估，不由版本扫描自动升级

For a machine-generated report, run:

```bash
mvn -P compliance license:aggregate-add-third-party
```

For an SBOM, run:

```bash
mvn -P compliance cyclonedx:makeAggregateBom
```

## License Summary

| License family | Representative components |
|---|---|
| Apache License 2.0 | Spring Boot, Spring Framework, Spring Kafka, Spring Data Redis, Spring Security, Spring AI, Flyway, MyBatis starter, Hibernate Validator, AWS SDK for Java v2 (S3), OkHttp, Apache POI, JSqlParser, ShedLock, Resilience4j, Bucket4j, Micrometer, OpenTelemetry, Testcontainers, GreenMail |
| MIT License | Project Lombok, SLF4J, Mockito |
| BSD 2-Clause | PostgreSQL JDBC Driver |
| ISC / BSD 3-Clause (Revised BSD) | JSch (mwiede fork) |
| Eclipse Public License 2.0 | JUnit Jupiter, JUnit Platform, Jakarta EE APIs, Angus Mail |
| Eclipse Public License 2.0 + LGPL 2.1 | Logback |
| GPL-2.0 with Classpath Exception | Angus Mail transitive license notice |

## Runtime Dependencies

These are the main runtime-facing third-party components currently used by the platform modules.

| Component | Version | License | Used By | Notes |
|---|---|---|---|---|
| Spring Boot | 4.1.1 | Apache-2.0 | all | Parent BOM |
| Spring Framework | managed by Spring Boot 4.1.1 | Apache-2.0 | all | Transitive |
| Spring Kafka | managed by Spring Boot 4.1.1 | Apache-2.0 | orchestrator, worker-core, workers | Runtime messaging |
| Spring Data Redis (Lettuce) | managed by Spring Boot 4.1.1 | Apache-2.0 | orchestrator, console-api | 分布式缓存、SSE 广播 |
| Spring Security | 7.1.0 | Apache-2.0 | console-api | Console 鉴权 |
| Spring Security OAuth2 JOSE | managed by Spring Boot 4.1.1 | Apache-2.0 | console-api | JWT Token 签发/验签 |
| Spring AI Starter Model OpenAI / Anthropic | 2.0.1 | Apache-2.0 | console-api | Console AI feature |
| MyBatis Spring Boot Starter | 4.1.0 | Apache-2.0 | orchestrator, workers, trigger, console-api | Runtime persistence layer |
| Flyway Core | 12.4.0 | Apache-2.0 | all | Platform migrations |
| Flyway PostgreSQL support | 12.4.0 | Apache-2.0 | all | PostgreSQL dialect |
| Hibernate Validator | 9.1.0.Final | Apache-2.0 | orchestrator | Bean Validation 实现 |
| AWS SDK for Java v2 (S3) | 2.55.5 | Apache-2.0 | common, orchestrator, workers | Object storage access(S3 协议,兼容 MinIO) |
| Jackson Databind | 3.1.4 | Apache-2.0 | common | JSON serialization |
| Jackson Datatype JSR310 | managed by Spring Boot 4.1.1 | Apache-2.0 | common | Java time module |
| Micrometer Core | managed by Spring Boot 4.1.1 | Apache-2.0 | worker-core | 应用指标基础 |
| Micrometer Registry Prometheus | managed by Spring Boot 4.1.1 | Apache-2.0 | orchestrator, workers, trigger, console-api | Metrics export |
| Micrometer Tracing Bridge OTel | managed by Spring Boot 4.1.1 | Apache-2.0 | common | Observation → OpenTelemetry 桥接 |
| OpenTelemetry Exporter OTLP | managed by Spring Boot 4.1.1 | Apache-2.0 | common | Trace/Span 推送到 OTel Collector |
| OpenTelemetry Exporter JDK Sender | managed by Spring Boot 4.1.1 | Apache-2.0 | common | 使用 JDK HttpClient 替代 OkHttp 5.x |
| OkHttp (okhttp-jvm) | 5.5.0 | Apache-2.0 | export, dispatch, Java worker SDK | HTTP client(JVM 类在 `okhttp-jvm`) |
| Apache POI | 5.5.1 | Apache-2.0 | import, export, console-api | Spreadsheet handling |
| Apache Commons Compress | 1.28.0 | Apache-2.0 | import | tar archive handling |
| Quartz Scheduler | managed by Spring Boot 4.1.1 | Apache-2.0 | trigger | Cron / FixedRate 调度 |
| SLF4J API | managed by Spring Boot 4.1.1 | MIT | all (transitive) | Logging facade |
| PostgreSQL JDBC Driver | 42.7.13 | BSD-2-Clause | all | Database driver |
| JSch (mwiede fork) | 0.2.26 | ISC / BSD-3-Clause | dispatch | SFTP support |
| Angus Mail | managed by Spring Boot 4.1.1 | EPL-2.0 / GPL-2.0 with Classpath Exception | dispatch | SMTP 邮件分发 |
| Jakarta EE APIs | managed by Spring Boot 4.1.1 | EPL-2.0 | all | API surface |
| Logback Classic | 1.5.34 | EPL-2.0 + LGPL-2.1 | all (transitive) | Logging backend |
| Netty DNS Resolver macOS | managed by Spring Boot 4.1.1 | Apache-2.0 | orchestrator, console-api | macOS profile 条件激活 |
| Project Lombok | 1.18.48 | MIT | all (provided) | Annotation processor |
| JSqlParser | 5.4 | Apache-2.0(LGPL-2.1 OR Apache-2.0,走 Apache) | export | SQL parsing / schema whitelist |
| ShedLock | 6.3.0 | Apache-2.0 | common | Distributed lock |
| Resilience4j | 2.4.0 | Apache-2.0 | common, orchestrator, dispatch, console-api | Spring Boot 4 integration |
| Bucket4j | 8.20.0 | Apache-2.0 | orchestrator, console-api | Distributed rate limiting |
| Spring Boot Configuration Processor | managed by Spring Boot 4.1.1 | Apache-2.0 | all (annotation processor) | 编译期生成 `spring-configuration-metadata.json`，IDE 提示 / dict 自动化 |

## Test and Tooling Dependencies

These packages are used in test or build tooling and are not shipped as production runtime artifacts.

| Component | Version | License | Scope | Used By |
|---|---|---|---|---|
| Testcontainers BOM / modules | 2.0.5 | Apache-2.0 | test | all |
| testcontainers-redis | 2.2.2–2.2.4 | Apache-2.0 | test | dispatch, trigger, console-api |
| Spring Boot starter test | managed by Spring Boot 4.1.1 | Apache-2.0 | test | all |
| Spring Kafka Test | managed by Spring Boot 4.1.1 | Apache-2.0 | test | orchestrator, worker-core |
| MyBatis starter test | managed by Spring Boot 4.1.1 | Apache-2.0 | test | orchestrator, workers, console-api |
| mockwebserver3 | 5.5.0 | Apache-2.0 | test | worker-core, trigger, atomic |
| GreenMail | 2.1.8 | Apache-2.0 | test | dispatch（SMTP 测试） |
| Okio / Okio JVM | 3.18.2 / 3.18.1 | Apache-2.0 | test/runtime helper | transitive via OkHttp |
| AssertJ | managed by Spring Boot 4.1.1 | Apache-2.0 | test | all |
| Mockito | managed by Spring Boot 4.1.1 | MIT | test | all |
| Kotlin Standard Library | 2.3.21 (transitive via OkHttp 5.x) | Apache-2.0 | transitive | — |

## SDK 模块覆盖(对外发布物)

`batch-worker-sdk` / `batch-worker-sdk-spring-boot-starter` / `batch-worker-sdk-testkit` 是租户自托管 worker 的对外发布 jar。core SDK **必须 framework-free**,starter / testkit 才能引 Spring。

| 模块 | 直接依赖 | 备注 |
|---|---|---|
| `batch-worker-sdk`(core) | jackson-databind / jackson-datatype-jsr310 / kafka-clients / slf4j-api / okhttp-jvm / lombok(provided) | 不引 Spring;target jar < 2 MB;OkHttp 的 Okio/Kotlin 依赖进入租户运行时 classpath |
| `batch-worker-sdk-spring-boot-starter` | core SDK + spring-boot-autoconfigure + spring-boot-starter | 仅 Spring Boot 4.x;`@ConfigurationProperties` 自动绑定 |
| `batch-worker-sdk-testkit` | core SDK + aiohttp 等价 Java fake server | 测试 scope,不进生产 image |

## Python SDK Runtime Dependencies

`sdk/python/pyproject.toml`(独立工具链,不进 Maven reactor;PyPI 名 `batch-worker-sdk`,import 名 `batch_worker_sdk`):

| Component | Version | License | Scope | Used By | Notes |
|---|---|---|---|---|---|
| httpx | >= 0.27 | BSD-3-Clause | runtime | `internal/_http.py` | async HTTP client(register / claim / report / heartbeat / renew-lease) |
| pydantic | >= 2.7 | MIT | runtime | `task/`, `client/`, `dispatcher/` | 不可变值对象 + config validation |
| aiokafka | >= 0.11 | Apache-2.0 | runtime | `internal/_kafka.py` | async Kafka consumer(派单消费 + capacity-aware pause) |
| aiohttp | >= 3.14.3 | Apache-2.0 | optional(testkit) | `testkit.FakeBatchPlatform` | in-process 平台 fake;生产 worker **不要** 装 `[testkit]` extra |
| asyncpg | >= 0.29 | Apache-2.0 | optional(sql) | `handler/atomic/_sql.py` / `_stored_proc.py` | 只有用 SQL / stored-proc atomic handler 才装 `[sql]` extra |
| pytest | >= 8 | MIT | dev | tests | 测试 runner |
| pytest-asyncio | >= 0.23 | Apache-2.0 | dev | tests | async fixtures |
| pytest-httpx | >= 0.30 | MIT | dev | tests | httpx 请求拦截 mock |
| ruff | >= 0.6 | MIT | dev | lint / format | 替代 black + flake8 + isort |
| mypy | >= 1.10 | MIT | dev | type check | strict mode |

Python SDK 自身按 **Apache-2.0** 发布(与主仓一致);上述传递依赖中除 httpx (BSD-3) 外全部为 Apache-2.0 / MIT。GPL / LGPL / copyleft 依赖**零**(license-risk-assessment.md §Python 已审核)。

## Notes

1. Some versions are managed by the Spring Boot 4.1.1 BOM and are intentionally shown as "managed by Spring Boot 4.1.1".
2. Test-scoped dependencies are listed for completeness, but they do not ship in production images or jars.
3. If you need the exact resolved dependency tree, run:

```bash
mvn dependency:tree -Dverbose
```

4. If you need the machine-generated third-party report, regenerate it with the `compliance` Maven profile and use the output under `target/generated-sources/license/`.
