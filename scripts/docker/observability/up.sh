#!/usr/bin/env bash
# =========================================================
# up.sh - 一键启动本地观测栈
# Notes:
# 1) 默认使用 .env.local。
# 2) 只启动 docker/compose/observability.yml 的 observability profile。
# 3) 业务容器请先通过 scripts/docker/up-apps.sh 或 scripts/local/start-all.sh 启动。
# 4) 可透传额外 docker compose 参数，例如：
#    ./scripts/docker/observability/up.sh prometheus grafana
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

_DOCKER_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../local/docker-path.sh
source "${_DOCKER_SCRIPT_DIR}/../../local/docker-path.sh"
ensure_docker_on_path
unset _DOCKER_SCRIPT_DIR

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
OBS_NETWORK_NAME="${COMPOSE_PROJECT_NAME}_batch-network"

if ! docker network inspect "$OBS_NETWORK_NAME" >/dev/null 2>&1; then
  docker network create "$OBS_NETWORK_NAME" >/dev/null
fi

docker compose \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker/compose/observability.yml \
  --profile observability \
  up -d "$@"
