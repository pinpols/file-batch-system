# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-25 AS builder

ARG MODULE

WORKDIR /workspace

COPY pom.xml ./
COPY batch-common/pom.xml batch-common/pom.xml
COPY batch-console-api/pom.xml batch-console-api/pom.xml
COPY batch-orchestrator/pom.xml batch-orchestrator/pom.xml
COPY batch-trigger/pom.xml batch-trigger/pom.xml
COPY batch-worker-core/pom.xml batch-worker-core/pom.xml
COPY batch-worker-import/pom.xml batch-worker-import/pom.xml
COPY batch-worker-export/pom.xml batch-worker-export/pom.xml
COPY batch-worker-dispatch/pom.xml batch-worker-dispatch/pom.xml
COPY batch-e2e-tests/pom.xml batch-e2e-tests/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    set -eux; \
    mvn -q -pl "${MODULE}" -am -DskipTests dependency:go-offline

COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    set -eux; \
    mvn -q -pl batch-common -am -DskipTests install; \
    mvn -q -pl "${MODULE}" -am -DskipTests package; \
    jar="$(ls "${MODULE}/target/${MODULE}-"*.jar | grep -Ev 'sources|javadoc|original' | head -n 1)"; \
    cp "$jar" /tmp/app.jar

FROM eclipse-temurin:25-jre-jammy AS runtime

# 全栈 UTF-8：容器 locale 强制 C.UTF-8，避免 JVM file.encoding / 日志 / 进程 IO
# 退化到 ANSI_X3.4-1968 导致非 ASCII 字符乱码。
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /tmp/app.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENV JAVA_OPTS=""

ENTRYPOINT ["/app/entrypoint.sh"]
