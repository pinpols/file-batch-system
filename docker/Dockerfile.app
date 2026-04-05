FROM maven:3.9.11-eclipse-temurin-25 AS builder

ARG MODULE

WORKDIR /workspace

COPY . .

RUN set -eux; \
    mvn -q -pl batch-common -am -DskipTests install; \
    mvn -q -pl "${MODULE}" -am -Dmaven.test.skip=true package; \
    jar="$(ls "${MODULE}/target/${MODULE}-"*.jar | grep -Ev 'sources|javadoc|original' | head -n 1)"; \
    cp "$jar" /tmp/app.jar

FROM eclipse-temurin:25-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /tmp/app.jar /app/app.jar

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
