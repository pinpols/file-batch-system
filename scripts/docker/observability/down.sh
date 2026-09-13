#!/usr/bin/env bash
# =========================================================
# down.sh - 停止本地观测栈，但不删除容器/网络/卷
# 说明：
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
REQUESTED_COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
if [[ -f "$COMPOSE_ENV_FILE" ]]; then
  COMPOSE_PROJECT_NAME="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME[[:space:]]*=[[:space:]]*//p' "$COMPOSE_ENV_FILE" | head -n 1 | sed 's/[[:space:]]*#.*$//' | sed 's/[[:space:]]*$//')"
fi
if [[ -n "$REQUESTED_COMPOSE_PROJECT_NAME" ]]; then
  COMPOSE_PROJECT_NAME="$REQUESTED_COMPOSE_PROJECT_NAME"
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
export COMPOSE_PROJECT_NAME

docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --env-file "$COMPOSE_ENV_FILE" \
  -f deploy/docker/compose/observability.yml \
  --profile observability \
  stop
