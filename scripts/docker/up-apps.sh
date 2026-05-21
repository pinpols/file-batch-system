#!/usr/bin/env bash
# =========================================================
# up-apps.sh - 一键启动本地基础依赖 + 应用容器
# Notes:
# 1) 默认使用 .env.local。
# 2) 默认启动 docker-compose.yml + docker/compose/app.yml 的 apps + replica profile
#    （read-replica 默认 enabled=true，必须把 postgres-replica 一起拉起来才不会 unhealthy）。
# 3) 可透传额外 docker compose 参数，例如：
#    ./scripts/docker/up-apps.sh console-api
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

_DOCKER_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../local/docker-path.sh
source "${_DOCKER_SCRIPT_DIR}/../local/docker-path.sh"
ensure_docker_on_path
unset _DOCKER_SCRIPT_DIR

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-local}"
APP_NETWORK_NAME="${COMPOSE_PROJECT_NAME}_batch-network"

export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}"
export COMPOSE_DOCKER_CLI_BUILD="${COMPOSE_DOCKER_CLI_BUILD:-1}"

if ! docker network inspect "$APP_NETWORK_NAME" >/dev/null 2>&1; then
  docker network create "$APP_NETWORK_NAME" >/dev/null
fi

mkdir -p "$ROOT/logs/docker"

docker compose \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker-compose.yml \
  -f docker/compose/app.yml \
  --profile apps \
  --profile replica \
  up -d --force-recreate --remove-orphans "$@"
