#!/usr/bin/env bash
# =========================================================
# logs.sh - 跟踪本地观测栈日志
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
TAIL_LINES="${TAIL_LINES:-100}"

docker compose \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker/compose/observability.yml \
  --profile observability \
  logs -f --tail="$TAIL_LINES" "$@"
