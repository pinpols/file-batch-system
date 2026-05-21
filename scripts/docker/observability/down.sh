#!/usr/bin/env bash
# =========================================================
# down.sh - 停止本地观测栈，但不删除容器/网络/卷
# Notes:
# 1) 默认使用 .env.local。
# 2) 只执行 docker compose stop，不执行 down。
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

docker compose \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker/compose/observability.yml \
  --profile observability \
  stop
