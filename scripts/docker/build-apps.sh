#!/usr/bin/env bash
# =========================================================
# build-apps.sh - 一键构建本地应用镜像（默认启用 BuildKit）
# Notes:
# 1) 默认使用 .env.local。
# 2) 默认构建 docker-compose.yml + docker/compose/app.yml 的 apps + replica profile
#    （console-api depends_on postgres-replica，缺 replica profile 会报 undefined service）。
# 3) 默认启用 DOCKER_BUILDKIT=1 / COMPOSE_DOCKER_CLI_BUILD=1。
# 4) 可透传额外 docker compose build 参数，例如：
#    ./scripts/docker/build-apps.sh console-api
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
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-plaform}"

export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}"
export COMPOSE_DOCKER_CLI_BUILD="${COMPOSE_DOCKER_CLI_BUILD:-1}"

docker compose \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker-compose.yml \
  -f docker/compose/app.yml \
  --profile apps \
  --profile replica \
  build "$@"
