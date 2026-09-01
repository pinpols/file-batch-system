#!/bin/sh
# =========================================================================
# entrypoint.sh — 通用应用容器启动入口
#
# 在 exec java 之前做两件事：
#   1. StatefulSet 场景：从 POD_NAME 末尾提取 ordinal，自动注入
#      BATCH_OUTBOX_SHARD_INDEX（仅当该变量未显式设置时）。
#      例：batch-orchestrator-0 → SHARD_INDEX=0
#          batch-orchestrator-1 → SHARD_INDEX=1
#   2. Deployment 场景（POD_NAME 末尾不是数字）：保留原有行为，不做注入。
#
# 允许显式覆盖：在 Helm values 里直接设 BATCH_OUTBOX_SHARD_INDEX 即跳过
# 自动推导，兼容旧 Deployment 清单。
# =========================================================================
set -eu

if [ -n "${POD_NAME:-}" ] && [ -z "${BATCH_OUTBOX_SHARD_INDEX:-}" ]; then
  ordinal="${POD_NAME##*-}"
  case "$ordinal" in
    ''|*[!0-9]*)
      # POD_NAME 末尾不是纯数字（Deployment hash 后缀场景），跳过
      :
      ;;
    *)
      export BATCH_OUTBOX_SHARD_INDEX="$ordinal"
      echo "[entrypoint] StatefulSet ordinal detected: POD_NAME=${POD_NAME}, BATCH_OUTBOX_SHARD_INDEX=${BATCH_OUTBOX_SHARD_INDEX}"
      ;;
  esac
fi

# JAVA_OPTS:全模块共享基础(配 Helm configmap)
# JAVA_OPTS_EXTRA:per-service override(配 Helm 各 service 的 javaOptsExtra,通过
#   deployment env 注入,优先级高于 envFrom-configmap 同名)
# Spring Boot 4 layered extraction keeps the launcher, application archive and
# dependency directories separate so Docker can reuse unchanged dependency layers.
# Keep the jar fallback for locally assembled images and older deployment artifacts.
# shellcheck disable=SC2086
if [ -d /app/spring-boot-loader ] && [ -d /app/application ]; then
  exec java ${JAVA_OPTS} ${JAVA_OPTS_EXTRA:-} \
    -cp "/app/spring-boot-loader/*:/app/application/*:/app/application/lib/*:/app/dependencies/lib/*:/app/snapshot-dependencies/lib/*" \
    org.springframework.boot.loader.launch.JarLauncher
fi

exec java ${JAVA_OPTS} ${JAVA_OPTS_EXTRA:-} -jar /app/app.jar
