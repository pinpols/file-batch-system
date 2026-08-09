# Third-Party Software Licenses

**Product**: `batch-platform`  
**Version**: `${revision}` (默认 `1.3.0-SNAPSHOT`,CI-friendly)  
**Generated**: `2026-08-09`  
**Source**: curated from the current `pom.xml` / module POM files + `sdk/python/pyproject.toml`;Maven 部分以 `mvn -P compliance` 输出为底(391 transitive 依赖见 `sbom.json`)。

This document is a human-readable snapshot of the third-party components referenced by the repository at the time of generation.
Internal modules under `io.github.pinpols.batch:*` are excluded.

**变更摘要(2026-08-09 vs 2026-06-03)**:
- 对象存储客户端由 MinIO Java SDK 8.6.0 替换为 **AWS SDK for Java v2 (S3) 2.31.78**(#401,`S3ObjectStore` 改走 `software.amazon.awssdk:s3`)
- 新增 **Resilience4j 2.3.0**(熔断)与 **Bucket4j 8.14.0**(分布式限流)
- OkHttp 4.12.0 → **5.0.0-alpha.16**(JVM 类在 `okhttp-jvm`);测试侧 MockWebServer 同步改为 `mockwebserver3`
- JSqlParser 4.5 → **5.3**;Logback → **1.5.34**;JSch 0.2.23 → **0.2.26**;Flyway → **12.4.0**;PostgreSQL JDBC → **42.7.12**;Jackson → **3.1.4**(Boot 4.1 管理)
- 测试依赖:Testcontainers 1.21.4 → **2.0.5**;WireMock 依赖已移除(集成测试改用内部 fixture)
- Python SDK `aiohttp` 下限 3.10 → **3.14.3**
- 其余直接依赖版本不变

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
| Spring Boot | 4.1.0 | Apache-2.0 | all | Parent BOM |
| Spring Framework | managed by Spring Boot 4.1.0 | Apache-2.0 | all | Transitive |
| Spring Kafka | managed by Spring Boot 4.1.0 | Apache-2.0 | orchestrator, worker-core, workers | Runtime messaging |
| Spring Data Redis (Lettuce) | managed by Spring Boot 4.1.0 | Apache-2.0 | orchestrator, console-api | 分布式缓存、SSE 广播 |
| Spring Security | 7.1.0 | Apache-2.0 | console-api | Console 鉴权 |
| Spring Security OAuth2 JOSE | managed by Spring Boot 4.1.0 | Apache-2.0 | console-api | JWT Token 签发/验签 |
| Spring AI Starter Model OpenAI / Anthropic | 2.0.0-M3 | Apache-2.0 | console-api | Console AI feature |
| MyBatis Spring Boot Starter | 4.0.1 | Apache-2.0 | orchestrator, workers, trigger, console-api | Runtime persistence layer |
| Flyway Core | 12.4.0 | Apache-2.0 | all | Platform migrations |
| Flyway PostgreSQL support | 12.4.0 | Apache-2.0 | all | PostgreSQL dialect |
| Hibernate Validator | 9.1.0.Final | Apache-2.0 | orchestrator | Bean Validation 实现 |
| AWS SDK for Java v2 (S3) | 2.31.78 | Apache-2.0 | common, orchestrator, workers | Object storage access(S3 协议,兼容 MinIO) |
| Jackson Databind | 3.1.4 | Apache-2.0 | common | JSON serialization |
| Jackson Datatype JSR310 | managed by Spring Boot 4.1.0 | Apache-2.0 | common | Java time module |
| Micrometer Core | managed by Spring Boot 4.1.0 | Apache-2.0 | worker-core | 应用指标基础 |
| Micrometer Registry Prometheus | managed by Spring Boot 4.1.0 | Apache-2.0 | orchestrator, workers, trigger, console-api | Metrics export |
| Micrometer Tracing Bridge OTel | managed by Spring Boot 4.1.0 | Apache-2.0 | common | Observation → OpenTelemetry 桥接 |
| OpenTelemetry Exporter OTLP | managed by Spring Boot 4.1.0 | Apache-2.0 | common | Trace/Span 推送到 OTel Collector |
| OpenTelemetry Exporter JDK Sender | managed by Spring Boot 4.1.0 | Apache-2.0 | common | 使用 JDK HttpClient 替代 OkHttp 5.x |
| OkHttp (okhttp-jvm) | 5.0.0-alpha.16 | Apache-2.0 | export, dispatch | HTTP client(JVM 类在 `okhttp-jvm`) |
| Apache POI | 5.4.0 | Apache-2.0 | import, export, console-api | Spreadsheet handling |
| Quartz Scheduler | managed by Spring Boot 4.1.0 | Apache-2.0 | trigger | Cron / FixedRate 调度 |
| SLF4J API | managed by Spring Boot 4.1.0 | MIT | all (transitive) | Logging facade |
| PostgreSQL JDBC Driver | 42.7.12 | BSD-2-Clause | all | Database driver |
| JSch (mwiede fork) | 0.2.26 | ISC / BSD-3-Clause | dispatch | SFTP support |
| Angus Mail | managed by Spring Boot 4.1.0 | EPL-2.0 / GPL-2.0 with Classpath Exception | dispatch | SMTP 邮件分发 |
| Jakarta EE APIs | managed by Spring Boot 4.1.0 | EPL-2.0 | all | API surface |
| Logback Classic | 1.5.34 | EPL-2.0 + LGPL-2.1 | all (transitive) | Logging backend |
| Netty DNS Resolver macOS | managed by Spring Boot 4.1.0 | Apache-2.0 | orchestrator, console-api | macOS profile 条件激活 |
| Project Lombok | 1.18.46 | MIT | all (provided) | Annotation processor |
| JSqlParser | 5.3 | Apache-2.0(LGPL-2.1 OR Apache-2.0,走 Apache) | export | SQL parsing / schema whitelist |
| ShedLock | 6.3.0 | Apache-2.0 | common | Distributed lock |
| Resilience4j | 2.3.0 | Apache-2.0 | orchestrator, console-api | Circuit breaker |
| Bucket4j | 8.14.0 | Apache-2.0 | orchestrator, console-api | Distributed rate limiting |
| Spring Boot Configuration Processor | managed by Spring Boot 4.1.0 | Apache-2.0 | all (annotation processor) | 编译期生成 `spring-configuration-metadata.json`，IDE 提示 / dict 自动化 |

## Test and Tooling Dependencies

These packages are used in test or build tooling and are not shipped as production runtime artifacts.

| Component | Version | License | Scope | Used By |
|---|---|---|---|---|
| Testcontainers BOM / modules | 2.0.5 | Apache-2.0 | test | all |
| testcontainers-redis | 2.2.2–2.2.4 | Apache-2.0 | test | dispatch, trigger, console-api |
| Spring Boot starter test | managed by Spring Boot 4.1.0 | Apache-2.0 | test | all |
| Spring Kafka Test | managed by Spring Boot 4.1.0 | Apache-2.0 | test | orchestrator, worker-core |
| MyBatis starter test | managed by Spring Boot 4.1.0 | Apache-2.0 | test | orchestrator, workers, console-api |
| mockwebserver3 | 5.0.0-alpha.16 | Apache-2.0 | test | worker-core, trigger, atomic |
| GreenMail | 2.1.8 | Apache-2.0 | test | dispatch（SMTP 测试） |
| Okio / Okio JVM | 3.17.0 / 3.12.0 | Apache-2.0 | test/runtime helper | transitive via OkHttp |
| AssertJ | managed by Spring Boot 4.1.0 | Apache-2.0 | test | all |
| Mockito | managed by Spring Boot 4.1.0 | MIT | test | all |
| Kotlin Standard Library | 2.3.21 (transitive via OkHttp 5.x) | Apache-2.0 | transitive | — |

## SDK 模块覆盖(对外发布物)

`batch-worker-sdk` / `batch-worker-sdk-spring-boot-starter` / `batch-worker-sdk-testkit` 是租户自托管 worker 的对外发布 jar。core SDK **必须 framework-free**,starter / testkit 才能引 Spring。

| 模块 | 直接依赖 | 备注 |
|---|---|---|
| `batch-worker-sdk`(core) | jackson-databind / jackson-datatype-jsr310 / kafka-clients / slf4j-api / lombok(provided) | 不引 Spring;target jar < 2 MB |
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

1. Some versions are managed by the Spring Boot 4.1.0 BOM and are intentionally shown as "managed by Spring Boot 4.1.0".
2. Test-scoped dependencies are listed for completeness, but they do not ship in production images or jars.
3. If you need the exact resolved dependency tree, run:

```bash
mvn dependency:tree -Dverbose
```

4. If you need the machine-generated third-party report, regenerate it with the `compliance` Maven profile and use the output under `target/generated-sources/license/`.
