#!/usr/bin/env bash
# =========================================================
# build-apps.sh - 本地联调应用模块单独构建入口
# Notes:
# 1) 仅打包六个 Java 应用模块，不启动 Docker、不启动本地进程。
# 2) 默认执行 Maven package -DskipTests，供 start-all.sh / 手工联调复用。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "==> Maven 打包应用模块（-Dmaven.test.skip=true）..."
mvn -q -Dmaven.test.skip=true \
  -pl batch-trigger,batch-orchestrator,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
  -am package -T 1C

echo "==> 构建完成"
