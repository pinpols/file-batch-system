# syntax=docker/dockerfile:1.7

# 单 stage(runtime-only);jar 由 host 端 `mvn -T 1C -DskipTests package` 预 build,
# Dockerfile 只 COPY。配套 deploy.yml 的「Maven package 全量」步骤。
#
# 改动原因(性能):原 builder stage 内嵌 mvn,9 个 image 各跑一次完整 builder
# (BuildKit layer cache 不命中因 MODULE ARG 不同),`--mount=type=cache sharing=locked`
# 又串行化共享 m2 → 全程 15-20 min。改成 host 端一次 mvn -T 1C(2-3 min)+ 9 个
# 镜像 COPY 几秒 = 总时长 3-5 min。
#
# Standalone `docker build` 前提:host 已跑过 `mvn -T 1C -DskipTests package`,各模块
# target/${MODULE}-${revision}.jar 已生成。否则 build 会因 COPY 找不到文件失败。

FROM eclipse-temurin:25-jre-jammy

ARG MODULE

# 全栈 UTF-8 + 上海时区,与 application-*.yml / compose 对齐
ENV BATCH_TIMEZONE_DEFAULT_ZONE="Asia/Shanghai" \
    TZ="Asia/Shanghai" \
    BATCH_LOCALE="C.UTF-8" \
    LANG="C.UTF-8" \
    LC_ALL="C.UTF-8"

# curl 不 pin 版本:容器基底 eclipse-temurin:25-jre-jammy 已 pin,curl 跟随 jammy 安全补丁
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# R7-A4-P0:非 root 运行(uid/gid 1000=batch),与 Helm podSecurityContext.runAsUser:1000 对齐
RUN groupadd --system --gid 1000 batch \
    && useradd --system --uid 1000 --gid batch --home-dir /app --shell /sbin/nologin batch

# 复制整个 target/ 再过滤出主 jar(排除 sources/javadoc/original)。
# 不直接 `COPY ${MODULE}/target/${MODULE}-*.jar /app/app.jar`:glob 命中多个时 docker COPY 失败。
COPY ${MODULE}/target/ /tmp/build/
RUN set -eux; \
    jar="$(ls /tmp/build/${MODULE}-*.jar 2>/dev/null | grep -Ev 'sources|javadoc|original' | head -n 1)"; \
    [ -n "$jar" ] || { echo "ERROR: no main jar found for ${MODULE}; run \`mvn -T 1C -DskipTests package\` on host first"; exit 1; }; \
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
