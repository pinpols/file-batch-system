# syntax=docker/dockerfile:1.7

FROM maven:3.9.15-eclipse-temurin-26 AS builder

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
COPY batch-worker-process/pom.xml batch-worker-process/pom.xml
COPY batch-worker-dispatch/pom.xml batch-worker-dispatch/pom.xml
COPY batch-e2e-tests/pom.xml batch-e2e-tests/pom.xml

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eux; \
    mvn -q -pl "${MODULE}" -am -DskipTests dependency:go-offline

COPY . .

SHELL ["/bin/bash", "-o", "pipefail", "-c"]
# Maven target/ 下 jar 名固定无非 ASCII / 空格,glob + grep 挑去 sources/javadoc 最直观
# hadolint ignore=SC2010
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eux; \
    mvn -q -pl batch-common -am -DskipTests install; \
    mvn -q -pl "${MODULE}" -am -DskipTests package; \
    jar="$(ls "${MODULE}/target/${MODULE}-"*.jar | grep -Ev 'sources|javadoc|original' | head -n 1)"; \
    cp "$jar" /tmp/app.jar

FROM eclipse-temurin:25-jre-jammy AS runtime

# 全栈 UTF-8：容器 locale 强制 C.UTF-8，避免 JVM file.encoding / 日志 / 进程 IO
# 退化到 ANSI_X3.4-1968 导致非 ASCII 字符乱码。
ENV BATCH_TIMEZONE_DEFAULT_ZONE="Asia/Shanghai" \
    TZ="Asia/Shanghai" \
    BATCH_LOCALE="C.UTF-8" \
    LANG="C.UTF-8" \
    LC_ALL="C.UTF-8"

# curl 不 pin 版本:容器基底是 eclipse-temurin:25-jre-jammy(自身已 pin),curl 跟随 jammy 安全更新,
# 无需上锁版本号(否则每月 Ubuntu 安全 patch 后重建会失败)
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# R7-A4-P0：runtime stage 改为非 root 运行（uid/gid 1000=batch），与 Helm
# podSecurityContext.runAsNonRoot:true / runAsUser:1000 对齐；
# 同时 chown /app 让 readOnlyRootFilesystem 场景仍能写 entrypoint 自身。
RUN groupadd --system --gid 1000 batch \
    && useradd --system --uid 1000 --gid batch --home-dir /app --shell /sbin/nologin batch

COPY --from=builder /tmp/app.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh && chown -R batch:batch /app

USER 1000

ENV JAVA_OPTS=""

ENTRYPOINT ["/app/entrypoint.sh"]
