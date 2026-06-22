# syntax=docker/dockerfile:1.7

# 共享 builder + per-image runtime 设计:
#   - builder stage 不带 ARG MODULE → docker BuildKit 跨 9 个 image 复用同一份 builder layer
#   - builder 内一次 mvn -B -T 1C 并行 build 全 9 模块(共享 m2 cache mount,跨 build 持久化)
#   - 9 个 runtime stage 各 COPY 自己 module 的 jar(从 builder 层零成本)
#
# 性能:
#   - 首跑(cold m2 cache):builder ~8 min(download deps + parallel build),9 runtime 各几秒
#   - 增量(源码改 / 加新代码):builder ~2 min,runtime 秒级,总 ~3 min
#   - 老设计(builder 带 MODULE):9 个 builder 串行各跑一次 mvn,15-20 min
#
# 自包含,不需要 host 端 mvn(适配 Portainer 这类直接跑 `docker compose build` 的 GitOps 工具)。

# ───── Stage 1: 共享 builder(全模块一次性 build)─────
FROM maven:3-eclipse-temurin-25 AS builder

WORKDIR /workspace

# aliyun mirror 避开 Maven Central 在 18081 代理下的不稳定 HTTPS
COPY docker/settings.xml /usr/share/maven/conf/settings.xml

# 先 COPY 所有 pom.xml 单独一层 → 仅 pom 改时才 invalidate deps cache
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
COPY batch-worker-atomic/pom.xml batch-worker-atomic/pom.xml
COPY sdk/java/pom.xml sdk/java/pom.xml
COPY sdk/java-spring/pom.xml sdk/java-spring/pom.xml
COPY sdk/java-testkit/pom.xml sdk/java-testkit/pom.xml
COPY batch-e2e-tests/pom.xml batch-e2e-tests/pom.xml

# m2 cache mount(id 命名以便跨 compose build 复用同一份;Portainer/手动 build 都行)
RUN --mount=type=cache,target=/root/.m2,id=batch-mvn-cache,sharing=locked \
    set -eux; \
    mvn -B -DskipTests -pl '!batch-e2e-tests' -am dependency:go-offline

# 全源码进 builder,build 全部 deployable 模块(e2e-tests 不部署,排除)
COPY . .

RUN --mount=type=cache,target=/root/.m2,id=batch-mvn-cache,sharing=locked \
    set -eux; \
    mvn -B -T 1C -DskipTests -pl '!batch-e2e-tests' package

# ───── Stage 2: per-image runtime(各服务挑自己 jar)─────
FROM eclipse-temurin:25-jre-jammy

ARG MODULE

ENV BATCH_TIMEZONE_DEFAULT_ZONE="Asia/Shanghai" \
    TZ="Asia/Shanghai" \
    BATCH_LOCALE="C.UTF-8" \
    LANG="C.UTF-8" \
    LC_ALL="C.UTF-8"

# curl 不 pin 版本:容器基底 eclipse-temurin:25-jre-jammy 已 pin,curl 跟随 jammy 安全补丁
# hadolint ignore=DL3008
RUN sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g; s|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list \
    && apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# R7-A4-P0:非 root(uid/gid 1000=batch),与 Helm podSecurityContext.runAsUser:1000 对齐
RUN groupadd --system --gid 1000 batch \
    && useradd --system --uid 1000 --gid batch --home-dir /app --shell /sbin/nologin batch

# 从共享 builder 复制本 module target(过滤 sources/javadoc/original)。
# 不写 `COPY --from=builder /workspace/${MODULE}/target/${MODULE}-*.jar /app/app.jar`:glob 命中多个时 docker COPY 失败
COPY --from=builder /workspace/${MODULE}/target/ /tmp/build/
RUN set -eux; \
    jar=""; \
    for f in /tmp/build/"${MODULE}"-*.jar; do \
        case "$f" in *sources*|*javadoc*|*original*) continue ;; esac; \
        [ -f "$f" ] || continue; \
        jar="$f"; \
        break; \
    done; \
    [ -n "$jar" ] || { echo "ERROR: no main jar found for ${MODULE}"; exit 1; }; \
    mv "$jar" /app/app.jar; \
    rm -rf /tmp/build

COPY docker/entrypoint.sh /app/entrypoint.sh
RUN mkdir -p /var/log/app /var/cache/app /logs /app/logs \
    && chmod +x /app/entrypoint.sh \
    && chown -R batch:batch /app /var/log/app /var/cache/app /logs /app/logs

USER 1000

ENV JAVA_OPTS=""
ENV JAVA_OPTS_EXTRA=""

ENTRYPOINT ["/app/entrypoint.sh"]
