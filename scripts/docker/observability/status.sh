#!/usr/bin/env bash
# =========================================================
# status.sh - 查看本地观测栈容器状态
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
  -f docker-compose.observability.yml \
  --profile observability \
  ps "$@"
