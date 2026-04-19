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

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /tmp/app.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENV JAVA_OPTS=""

ENTRYPOINT ["/app/entrypoint.sh"]
