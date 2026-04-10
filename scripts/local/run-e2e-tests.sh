#!/usr/bin/env bash
# =========================================================
# run-e2e-tests.sh - 运行 batch-e2e-tests
# Notes:
# 1) 使用 Testcontainers 启动 Postgres、Kafka 和 MinIO。
# 2) macOS / Docker Desktop 下如遇 socket 问题，可按脚本提示设置 TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE。
# =========================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$HOME/.docker/run/docker.sock}"
# Docker Engine 29+ requires API ≥ 1.44; docker-java default 1.32 causes Testcontainers discovery to fail with HTTP 400.
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

LOG_DIR="$ROOT_DIR/logs/test"
LOG_FILE="$LOG_DIR/run-e2e-tests.log"

mkdir -p "$LOG_DIR"

mvn -pl batch-e2e-tests -am test \
  --no-transfer-progress \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "$@" 2>&1 | tee "$LOG_FILE"

exit ${PIPESTATUS[0]}
