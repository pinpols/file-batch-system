# Third-Party Software Licenses

**Product**: `batch-platform`  
**Version**: `1.0.0-SNAPSHOT`  
**Generated**: `2026-04-09`  
**Source**: curated from the current `pom.xml` / module POM files

This document is a human-readable snapshot of the third-party components referenced by the repository at the time of generation.
Internal modules under `com.example.batch:*` are excluded.

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
| Apache License 2.0 | Spring Boot, Spring Framework, Spring Kafka, Spring Data JDBC, Spring Data Redis, Spring Security, Spring AI, Flyway, MyBatis starter, Hibernate Validator, MinIO Java SDK, OkHttp, Apache POI, JSqlParser, ShedLock, Micrometer, OpenTelemetry, Testcontainers, WireMock, GreenMail |
| MIT License | Project Lombok, SLF4J, Mockito |
| BSD 2-Clause | PostgreSQL JDBC Driver |
| BSD 3-Clause | JSch (mwiede fork) |
| Eclipse Public License 2.0 | JUnit Jupiter, JUnit Platform, Jakarta EE APIs, Angus Mail |
| Eclipse Public License 1.0 + LGPL 2.1 | Logback |
| GPL-2.0 with Classpath Exception | Angus Mail transitive license notice |

## Runtime Dependencies

These are the main runtime-facing third-party components currently used by the platform modules.

| Component | Version | License | Used By | Notes |
|---|---|---|---|---|
| Spring Boot | 4.0.3 | Apache-2.0 | all | Parent BOM |
| Spring Framework | managed by Spring Boot 4.0.3 | Apache-2.0 | all | Transitive |
| Spring Kafka | managed by Spring Boot 4.0.3 | Apache-2.0 | orchestrator, worker-core, workers | Runtime messaging |
| Spring Data JDBC | managed by Spring Boot 4.0.3 | Apache-2.0 | orchestrator, console-api | Configuration-layer persistence |
| Spring Data Redis (Lettuce) | managed by Spring Boot 4.0.3 | Apache-2.0 | orchestrator, console-api | 分布式缓存、SSE 广播 |
| Spring Security | managed by Spring Boot 4.0.3 | Apache-2.0 | console-api | Console 鉴权 |
| Spring Security OAuth2 JOSE | managed by Spring Boot 4.0.3 | Apache-2.0 | console-api | JWT Token 签发/验签 |
| Spring AI Starter Model OpenAI | 2.0.0-M3 | Apache-2.0 | console-api | Console AI feature |
| MyBatis Spring Boot Starter | 4.0.0 | Apache-2.0 | orchestrator, workers, trigger, console-api | Runtime persistence layer |
| Flyway Core | managed by Spring Boot 4.0.3 | Apache-2.0 | all | Platform migrations |
| Flyway PostgreSQL support | managed by Spring Boot 4.0.3 | Apache-2.0 | all | PostgreSQL dialect |
| Hibernate Validator | managed by Spring Boot 4.0.3 | Apache-2.0 | orchestrator | Bean Validation 实现 |
| MinIO Java SDK | 8.6.0 | Apache-2.0 | common, orchestrator, workers | Object storage access |
| Jackson Databind | managed by Spring Boot 4.0.3 | Apache-2.0 | common | JSON serialization |
| Jackson Datatype JSR310 | managed by Spring Boot 4.0.3 | Apache-2.0 | common | Java time module |
| Micrometer Core | managed by Spring Boot 4.0.3 | Apache-2.0 | worker-core | 应用指标基础 |
| Micrometer Registry Prometheus | managed by Spring Boot 4.0.3 | Apache-2.0 | orchestrator, workers, trigger, console-api | Metrics export |
| Micrometer Tracing Bridge OTel | managed by Spring Boot 4.0.3 | Apache-2.0 | common | Observation → OpenTelemetry 桥接 |
| OpenTelemetry Exporter OTLP | managed by Spring Boot 4.0.3 | Apache-2.0 | common | Trace/Span 推送到 OTel Collector |
| OpenTelemetry Exporter JDK Sender | managed by Spring Boot 4.0.3 | Apache-2.0 | common | 使用 JDK HttpClient 替代 OkHttp 5.x |
| OkHttp | 4.12.0 | Apache-2.0 | export, dispatch | HTTP client |
| Apache POI | 5.4.0 | Apache-2.0 | import, export, console-api | Spreadsheet handling |
| Quartz Scheduler | managed by Spring Boot 4.0.3 | Apache-2.0 | trigger | Cron / FixedRate 调度 |
| SLF4J API | managed by Spring Boot 4.0.3 | MIT | all (transitive) | Logging facade |
| PostgreSQL JDBC Driver | managed by Spring Boot 4.0.3 | BSD-2-Clause | all | Database driver |
| JSch (mwiede fork) | 0.2.23 | BSD-3-Clause | dispatch | SFTP support |
| Angus Mail | managed by Spring Boot 4.0.3 | EPL-2.0 / GPL-2.0 with Classpath Exception | dispatch | SMTP 邮件分发 |
| Jakarta EE APIs | managed by Spring Boot 4.0.3 | EPL-2.0 | all | API surface |
| Logback Classic | managed by Spring Boot 4.0.3 | EPL-1.0 + LGPL-2.1 | all (transitive) | Logging backend |
| Netty DNS Resolver macOS | managed by Spring Boot 4.0.3 | Apache-2.0 | orchestrator, console-api | macOS profile 条件激活 |
| Project Lombok | 1.18.42 | MIT | all (provided) | Annotation processor |
| JSqlParser | 4.5 | Apache-2.0 | export | SQL parsing / schema whitelist |
| ShedLock | 6.3.0 | Apache-2.0 | common | Distributed lock |

## Test and Tooling Dependencies

These packages are used in test or build tooling and are not shipped as production runtime artifacts.

| Component | Version | License | Scope | Used By |
|---|---|---|---|---|
| Testcontainers BOM / modules | 1.21.4 | Apache-2.0 | test | all |
| testcontainers-redis | 2.2.2–2.2.4 | Apache-2.0 | test | dispatch, trigger, console-api |
| Spring Boot starter test | managed by Spring Boot 4.0.3 | Apache-2.0 | test | all |
| Spring Kafka Test | managed by Spring Boot 4.0.3 | Apache-2.0 | test | orchestrator, worker-core |
| MyBatis starter test | managed by Spring Boot 4.0.3 | Apache-2.0 | test | orchestrator, workers, console-api |
| WireMock | 3.9.1 | Apache-2.0 | test | e2e-tests |
| MockWebServer | 4.12.0 | Apache-2.0 | test | worker-core, trigger |
| GreenMail | 2.1.8 | Apache-2.0 | test | dispatch（SMTP 测试） |
| Okio / Okio JVM | 3.16.1 | Apache-2.0 | test/runtime helper | transitive via OkHttp |
| AssertJ | managed by Spring Boot 4.0.3 | Apache-2.0 | test | all |
| Mockito | managed by Spring Boot 4.0.3 | MIT | test | all |
| Kotlin Standard Library | transitive via OkHttp 4.x | Apache-2.0 | transitive | — |

## Notes

1. Some versions are managed by the Spring Boot 4.0.3 BOM and are intentionally shown as "managed by Spring Boot 4.0.3".
2. Test-scoped dependencies are listed for completeness, but they do not ship in production images or jars.
3. If you need the exact resolved dependency tree, run:

```bash
mvn dependency:tree -Dverbose
```

4. If you need the machine-generated third-party report, regenerate it with the `compliance` Maven profile and use the output under `target/generated-sources/license/`.

