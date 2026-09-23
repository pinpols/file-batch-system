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
REQUESTED_COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
if [[ -f "$COMPOSE_ENV_FILE" ]]; then
  COMPOSE_PROJECT_NAME="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME[[:space:]]*=[[:space:]]*//p' "$COMPOSE_ENV_FILE" | head -n 1 | sed 's/[[:space:]]*#.*$//' | sed 's/[[:space:]]*$//')"
fi
if [[ -n "$REQUESTED_COMPOSE_PROJECT_NAME" ]]; then
  COMPOSE_PROJECT_NAME="$REQUESTED_COMPOSE_PROJECT_NAME"
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
export COMPOSE_PROJECT_NAME
OBSERVABILITY_SERVICES=(
  prometheus alertmanager jaeger tempo loki otel-collector otel-collector-init grafana
  redis-exporter postgres-exporter kafka-exporter node-exporter cadvisor
)

if [[ $# -gt 0 ]]; then
  OBSERVABILITY_SERVICES=("$@")
fi

docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker-compose.yml \
  -f deploy/docker/compose/observability.yml \
  ps "${OBSERVABILITY_SERVICES[@]}"
