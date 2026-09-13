# syntax=docker/dockerfile:1.7

# 双路径 builder + per-image runtime 设计:
#   - BUILD_MODE=all:8 个应用共享一次全 reactor 构建,适合整套镜像构建。
#   - BUILD_MODE=module:只构建 MODULE 及其 reactor 依赖闭包,适合单服务迭代。
#   - runtime 只提取当前 MODULE 的 jar,不在 builder 中二次复制全部可执行 jar。
#
# 性能:
#   - 首跑(cold m2 cache):builder ~8 min(download deps + parallel build),8 runtime 各几秒
#   - 增量(源码改 / 加新代码):builder ~2 min,runtime 秒级,总 ~3 min
#   - 单服务迭代由 BUILD_MODE=module 避免无关模块的 compile/testCompile/repackage。
#
# 自包含,不需要 host 端 mvn(适配 Portainer 这类直接跑 `docker compose build` 的 GitOps 工具)。

ARG BUILD_MODE=all
ARG MAVEN_BUILD_FLAGS="-B -ntp -Dmaven.test.skip=true -DskipITs=true -Dspotless.check.skip=true -Dpmd.skip=true -Dcyclonedx.skip=true -Dlicense.skip=true -Dmaven.javadoc.skip=true -Dflatten.skip=true -Djacoco.skip=true"

# ───── Stage 1: Maven 依赖基层─────
# Keep the tag for readability, pin the manifest digest for reproducible builds.
FROM maven:3.9.16-eclipse-temurin-21@sha256:a972570be789ee5c9fa23446a8914ac7327560b5c022f662cfa9452aef829f18 AS maven-base

WORKDIR /workspace

# aliyun mirror 避开 Maven Central 在 18081 代理下的不稳定 HTTPS
COPY deploy/docker/settings.xml /usr/share/maven/conf/settings.xml

# 先 COPY .mvn 与所有 pom.xml 单独一层 → Maven JVM 参数 / POM 改动才 invalidate deps cache
COPY pom.xml ./
COPY .mvn/ .mvn/
COPY batch-common/pom.xml batch-common/pom.xml
COPY batch-test-support/pom.xml batch-test-support/pom.xml
COPY batch-console-api/pom.xml batch-console-api/pom.xml
COPY batch-orchestrator/pom.xml batch-orchestrator/pom.xml
COPY batch-trigger/pom.xml batch-trigger/pom.xml
COPY batch-worker/pom.xml batch-worker/pom.xml
COPY batch-worker/core/pom.xml batch-worker/core/pom.xml
COPY batch-worker/import/pom.xml batch-worker/import/pom.xml
COPY batch-worker/export/pom.xml batch-worker/export/pom.xml
COPY batch-worker/process/pom.xml batch-worker/process/pom.xml
COPY batch-worker/dispatch/pom.xml batch-worker/dispatch/pom.xml
COPY batch-worker/atomic/pom.xml batch-worker/atomic/pom.xml
COPY sdk/java/core/pom.xml sdk/java/core/pom.xml
COPY sdk/java/spring/pom.xml sdk/java/spring/pom.xml
COPY sdk/java/testkit/pom.xml sdk/java/testkit/pom.xml
COPY batch-e2e-tests/pom.xml batch-e2e-tests/pom.xml

# ───── Stage 1A: 全 reactor 依赖预取─────
FROM maven-base AS deps-all

ARG MAVEN_BUILD_FLAGS

# m2 cache mount(id 命名以便跨 compose build 复用同一份;Portainer/手动 build 都行)
RUN --mount=type=cache,target=/root/.m2,id=batch-mvn-cache,sharing=locked \
    set -eux; \
    mvn ${MAVEN_BUILD_FLAGS} -pl '!batch-e2e-tests' -am dependency:go-offline

# ───── Stage 1B: 单模块依赖闭包预取─────
FROM maven-base AS deps-module

ARG MODULE
ARG MAVEN_BUILD_FLAGS

RUN --mount=type=cache,target=/root/.m2,id=batch-mvn-cache,sharing=locked \
    set -eux; \
    test -n "${MODULE}"; \
    mvn ${MAVEN_BUILD_FLAGS} -pl ":${MODULE}" -am dependency:go-offline

# ───── Stage 2A: 整套镜像共享的全 reactor builder─────
FROM deps-all AS builder-all

ARG MAVEN_BUILD_FLAGS

COPY . .

RUN --mount=type=cache,target=/root/.m2,id=batch-mvn-cache,sharing=locked \
    set -eux; \
    mvn ${MAVEN_BUILD_FLAGS} -T 1C \
      -pl '!batch-e2e-tests' package

# ───── Stage 2B: 单服务及其依赖闭包 builder─────
FROM deps-module AS builder-module

ARG MODULE
ARG MAVEN_BUILD_FLAGS

COPY . .

RUN --mount=type=cache,target=/root/.m2,id=batch-mvn-cache,sharing=locked \
    set -eux; \
    test -n "${MODULE}"; \
    mvn ${MAVEN_BUILD_FLAGS} -T 1C \
      -pl ":${MODULE}" -am package

# ───── Stage 3: 从所选 builder 中只提取当前服务 jar─────
# hadolint ignore=DL3006
FROM builder-${BUILD_MODE} AS selected-artifact

ARG MODULE

RUN set -eux; \
    mkdir -p /selected; \
    jar="$(find /workspace -type f -path '*/target/*-exec.jar' \
      -name "${MODULE}-*-exec.jar" \
      ! -name '*-sources-*' ! -name '*-javadoc-*' ! -name '*-original-*' \
      -print -quit)"; \
    test -n "$jar"; \
    cp "$jar" "/selected/${MODULE}-exec.jar"

# ───── Stage 4: 解压当前服务的 Spring Boot layers─────
FROM selected-artifact AS selected-layers

ARG MODULE

RUN set -eux; \
    java -Djarmode=tools -jar "/selected/${MODULE}-exec.jar" extract --layers --destination /layers; \
    rm -rf /selected

# ───── Stage 5: per-image runtime─────
# Keep the tag for readability, pin the manifest digest for reproducible builds.
FROM eclipse-temurin:21-jre-jammy@sha256:bce52ea7da1f72e6bf5bec505e63b6eb55ba79ad1226903579f77eab1a80139a

ARG MODULE
ARG BUILD_REVISION=unknown

# 容量基线必须能证明运行镜像对应哪一版源码。不要沿用基础镜像的 version 标签
# 代替应用版本；revision 由标准构建入口或 CI 注入。
LABEL org.opencontainers.image.revision="${BUILD_REVISION}"

ENV BATCH_TIMEZONE_DEFAULT_ZONE="Asia/Shanghai" \
    TZ="Asia/Shanghai" \
    BATCH_LOCALE="C.UTF-8" \
    LANG="C.UTF-8" \
    LC_ALL="C.UTF-8"

# curl 不 pin 版本:容器基底 eclipse-temurin:21-jre-jammy 已 pin,curl 跟随 jammy 安全补丁
# hadolint ignore=DL3008
RUN sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g; s|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list \
    && apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# R7-A4-P0:非 root(uid/gid 10001=batch),使用高 UID 避免与宿主机用户冲突。
RUN groupadd --system --gid 10001 batch \
    && useradd --system --uid 10001 --gid batch --home-dir /app --shell /sbin/nologin batch

# 分层复制：依赖层在业务源码变更时保持 Docker cache；Spring Boot loader 与应用层独立。
COPY --from=selected-layers /layers/dependencies/ /app/dependencies/
COPY --from=selected-layers /layers/spring-boot-loader/ /app/spring-boot-loader/
COPY --from=selected-layers /layers/snapshot-dependencies/ /app/snapshot-dependencies/
COPY --from=selected-layers /layers/application/ /app/application/

COPY deploy/docker/entrypoint.sh /app/entrypoint.sh
RUN app_jar="$(find /app/application -maxdepth 1 -type f -name '*-exec.jar' -print -quit)" \
    && test -n "$app_jar" \
    && ln -s "$app_jar" /app/app.jar \
    && for dependency in /app/dependencies/lib/*.jar /app/snapshot-dependencies/lib/*.jar; do \
         [ -e "$dependency" ] || continue; \
         ln -sfn "$dependency" "/app/application/lib/$(basename "$dependency")"; \
       done \
    && mkdir -p /var/log/app /var/cache/app /logs /app/logs \
    && chmod +x /app/entrypoint.sh \
    && chown -R batch:batch /app /var/log/app /var/cache/app /logs /app/logs

USER 10001

ENV JAVA_OPTS=""
ENV JAVA_OPTS_EXTRA=""

ENTRYPOINT ["/app/entrypoint.sh"]
