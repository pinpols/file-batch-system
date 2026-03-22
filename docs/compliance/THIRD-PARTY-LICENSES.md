# Third-Party Software Licenses

**Product**: batch-platform
**Version**: 1.0.0-SNAPSHOT
**Generated**: 2026-03-22 (manually compiled from pom.xml dependency declarations)
**Authoritative source**: run `mvn -P compliance license:aggregate-add-third-party` to regenerate

This document lists all third-party software components included in batch-platform and their applicable licenses.
Internal modules (`com.example.batch:*`) are excluded.

---

## License Summary

| License | Component Count |
|---|---|
| Apache License 2.0 | 22 |
| MIT License | 5 |
| BSD 2-Clause / PostgreSQL License | 2 |
| Eclipse Public License 2.0 | 3 |
| Eclipse Public License 1.0 / LGPL 2.1 | 1 |
| EPL-2.0 / GPL-2.0 with Classpath Exception | 2 |

---

## Apache License 2.0

Full text: https://www.apache.org/licenses/LICENSE-2.0

| Component | Version | URL |
|---|---|---|
| Spring Boot | 4.0.3 | https://spring.io/projects/spring-boot |
| Spring Framework | (managed by Spring Boot 4.0.3) | https://spring.io/projects/spring-framework |
| Spring Kafka | (managed by Spring Boot 4.0.3) | https://spring.io/projects/spring-kafka |
| Spring AI | 2.0.0-M3 | https://spring.io/projects/spring-ai |
| MyBatis Spring Boot Starter | 4.0.0 | https://mybatis.org/spring-boot-starter/ |
| Flyway Core | (managed by Spring Boot 4.0.3) | https://flywaydb.org |
| Flyway Database PostgreSQL | (managed by Spring Boot 4.0.3) | https://flywaydb.org |
| MinIO Java SDK | 8.6.0 | https://github.com/minio/minio-java |
| Jackson Databind | (managed by Spring Boot 4.0.3) | https://github.com/FasterXML/jackson-databind |
| Jackson Datatype JSR310 | (managed by Spring Boot 4.0.3) | https://github.com/FasterXML/jackson-modules-java8 |
| Micrometer Registry Prometheus | (managed by Spring Boot 4.0.3) | https://micrometer.io |
| OkHttp | 4.12.0 | https://square.github.io/okhttp |
| WireMock | 3.9.1 | https://wiremock.org |
| Apache POI | 5.4.0 | https://poi.apache.org |
| Apache Kafka Client | (managed by Spring Boot 4.0.3) | https://kafka.apache.org |
| Quartz Scheduler | (managed by Spring Boot 4.0.3) | http://www.quartz-scheduler.org |
| SLF4J API | (managed by Spring Boot 4.0.3) | https://www.slf4j.org |
| AssertJ Core | (managed by Spring Boot 4.0.3) | https://assertj.github.io/doc |
| Byte Buddy | (transitive via Mockito) | https://bytebuddy.net |
| Apache Commons Compress | (transitive via Apache POI 5.4.0) | https://commons.apache.org/proper/commons-compress |
| Apache Commons Collections | (transitive via Apache POI 5.4.0) | https://commons.apache.org/proper/commons-collections |
| Apache Commons Math | (transitive via Apache POI 5.4.0) | https://commons.apache.org/proper/commons-math |

---

## MIT License

Full text: https://opensource.org/licenses/MIT

| Component | Version | URL |
|---|---|---|
| Project Lombok | 1.18.42 | https://projectlombok.org |
| Testcontainers | 1.20.4 | https://testcontainers.com |
| Mockito Core | (managed by Spring Boot 4.0.3) | https://site.mockito.org |
| SLF4J Simple | (transitive) | https://www.slf4j.org |
| Kotlin Standard Library | (transitive via OkHttp 4.x) | https://kotlinlang.org |

---

## BSD 2-Clause / PostgreSQL License

| Component | Version | License | URL |
|---|---|---|---|
| PostgreSQL JDBC Driver | (managed by Spring Boot 4.0.3) | BSD 2-Clause | https://jdbc.postgresql.org |
| JSch (mwiede fork) | 0.2.23 | BSD 3-Clause | https://github.com/mwiede/jsch |

---

## Eclipse Public License 2.0

Full text: https://www.eclipse.org/legal/epl-2.0/

| Component | Version | URL |
|---|---|---|
| JUnit Jupiter (JUnit 5) | (managed by Spring Boot 4.0.3) | https://junit.org/junit5 |
| JUnit Platform | (managed by Spring Boot 4.0.3) | https://junit.org/junit5 |
| Jakarta EE APIs | (managed by Spring Boot 4.0.3) | https://jakarta.ee |

---

## Eclipse Public License 1.0 / GNU LGPL 2.1

| Component | Version | License | URL |
|---|---|---|---|
| Logback | (managed by Spring Boot 4.0.3) | EPL-1.0 + LGPL-2.1 | https://logback.qos.ch |

---

## EPL-2.0 / GPL-2.0 with Classpath Exception (Jakarta EE Compatibility)

| Component | Version | URL |
|---|---|---|
| Eclipse Angus Mail | (managed by Spring Boot 4.0.3) | https://eclipse-ee4j.github.io/angus-mail |
| Jakarta Mail API | (managed by Spring Boot 4.0.3) | https://jakarta.ee/specifications/mail |

---

## Notes

1. **Test-scoped dependencies** (Testcontainers, WireMock, Mockito, JUnit, AssertJ, spring-boot-starter-test) are **not** included in production build artifacts. Their licenses apply only to the development/CI environment.

2. **Transitive dependencies** managed by Spring Boot 4.0.3 BOM inherit the versions pinned in that BOM. Run `mvn dependency:tree -Dverbose` for the full resolved tree.

3. **Lombok** is a compile-time annotation processor; the Lombok runtime JAR is not shipped in production artifacts.

4. **"managed by Spring Boot 4.0.3"** means the version is pinned by the Spring Boot parent BOM and varies with that BOM. To retrieve exact resolved versions, run:
   ```
   mvn dependency:tree -Dincludes=<groupId>:<artifactId> -pl <module>
   ```

5. To regenerate this file automatically, activate the `compliance` Maven profile:
   ```
   mvn -P compliance license:aggregate-add-third-party
   ```
   The plugin writes to `target/generated-sources/license/THIRD-PARTY.txt` per module.

6. To generate the CycloneDX SBOM:
   ```
   mvn -P compliance cyclonedx:makeAggregateBom
   ```
   Output: `target/bom.json` and `target/bom.xml` in the root module.
