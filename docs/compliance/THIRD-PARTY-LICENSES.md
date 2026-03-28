# Third-Party Software Licenses

**Product**: `batch-platform`  
**Version**: `1.0.0-SNAPSHOT`  
**Generated**: `2026-03-28`  
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
| Apache License 2.0 | Spring Boot, Spring Framework, Spring Kafka, Spring AI, Flyway, MyBatis starter, MinIO Java SDK, OkHttp, Apache POI, JSqlParser, ShedLock, Testcontainers, WireMock |
| MIT License | Project Lombok |
| BSD 2-Clause | PostgreSQL JDBC Driver |
| BSD 3-Clause | JSch (mwiede fork) |
| Eclipse Public License 2.0 | JUnit Jupiter, JUnit Platform, Jakarta EE APIs, Angus Mail |
| Eclipse Public License 1.0 + LGPL 2.1 | Logback |
| GPL-2.0 with Classpath Exception | Angus Mail transitive license notice |

## Runtime Dependencies

These are the main runtime-facing third-party components currently used by the platform modules.

| Component | Version | License | Notes |
|---|---|---|---|
| Spring Boot | 4.0.3 | Apache-2.0 | Parent BOM |
| Spring Framework | managed by Spring Boot 4.0.3 | Apache-2.0 | Transitive |
| Spring Kafka | managed by Spring Boot 4.0.3 | Apache-2.0 | Runtime messaging |
| Spring AI Starter Model OpenAI | 2.0.0-M3 | Apache-2.0 | Console AI feature |
| MyBatis Spring Boot Starter | 4.0.0 | Apache-2.0 | Persistence layer |
| Flyway Core | managed by Spring Boot 4.0.3 | Apache-2.0 | Platform migrations |
| Flyway PostgreSQL support | managed by Spring Boot 4.0.3 | Apache-2.0 | PostgreSQL driver integration |
| MinIO Java SDK | 8.6.0 | Apache-2.0 | Object storage access |
| Jackson Databind | managed by Spring Boot 4.0.3 | Apache-2.0 | JSON serialization |
| Jackson Datatype JSR310 | managed by Spring Boot 4.0.3 | Apache-2.0 | Java time module |
| Micrometer Registry Prometheus | managed by Spring Boot 4.0.3 | Apache-2.0 | Metrics export |
| OkHttp | 4.12.0 | Apache-2.0 | HTTP client and transport helper |
| Apache POI | 5.4.0 | Apache-2.0 | Spreadsheet handling |
| Quartz Scheduler | managed by Spring Boot 4.0.3 | Apache-2.0 | Trigger metadata / scheduling |
| SLF4J API | managed by Spring Boot 4.0.3 | MIT | Logging facade |
| PostgreSQL JDBC Driver | managed by Spring Boot 4.0.3 | BSD-2-Clause | Database driver |
| JSch (mwiede fork) | 0.2.23 | BSD-3-Clause | SFTP support |
| JUnit Jupiter | managed by Spring Boot 4.0.3 | EPL-2.0 | Test scope |
| JUnit Platform | managed by Spring Boot 4.0.3 | EPL-2.0 | Test scope |
| Jakarta EE APIs | managed by Spring Boot 4.0.3 | EPL-2.0 | API surface |
| Angus Mail | managed by Spring Boot 4.0.3 | EPL-2.0 / GPL-2.0 with Classpath Exception | Mail support |
| Logback Classic | managed by Spring Boot 4.0.3 | EPL-1.0 + LGPL-2.1 | Logging backend |
| Project Lombok | 1.18.42 | MIT | Annotation processor |
| JSqlParser | 4.5 | Apache-2.0 | SQL parsing support |
| ShedLock | 6.3.0 | Apache-2.0 | Distributed lock |

## Test and Tooling Dependencies

These packages are used in test or build tooling and are not shipped as production runtime artifacts.

| Component | Version | License | Scope |
|---|---|---|---|
| Testcontainers BOM / modules | 1.21.4 | Apache-2.0 | test |
| Spring Boot starter test | managed by Spring Boot 4.0.3 | Apache-2.0 | test |
| MyBatis starter test | managed by Spring Boot 4.0.3 | Apache-2.0 | test |
| WireMock | 3.9.1 | Apache-2.0 | test |
| MockWebServer | 4.12.0 | Apache-2.0 | test |
| Okio / Okio JVM | 3.16.1 | Apache-2.0 | test/runtime helper |
| AssertJ | managed by Spring Boot 4.0.3 | Apache-2.0 | test |
| Mockito | managed by Spring Boot 4.0.3 | MIT | test |
| Kotlin Standard Library | transitive via OkHttp 4.x | Apache-2.0 | transitive |

## Notes

1. Some versions are managed by the Spring Boot 4.0.3 BOM and are intentionally shown as "managed by Spring Boot 4.0.3".
2. Test-scoped dependencies are listed for completeness, but they do not ship in production images or jars.
3. If you need the exact resolved dependency tree, run:

```bash
mvn dependency:tree -Dverbose
```

4. If you need the machine-generated third-party report, regenerate it with the `compliance` Maven profile and use the output under `target/generated-sources/license/`.

