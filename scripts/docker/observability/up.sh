#!/usr/bin/env bash
# =========================================================
# up.sh - 一键启动本地观测栈
# 说明：
# 1) 默认使用 .env.local。
# 2) 只启动 deploy/docker/compose/observability.yml 的 observability profile。
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
REQUESTED_COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
# 读取 env 文件中的项目名，同时保留命令行环境变量的显式覆盖能力。
if [[ -f "$COMPOSE_ENV_FILE" ]]; then
  COMPOSE_PROJECT_NAME="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME[[:space:]]*=[[:space:]]*//p' "$COMPOSE_ENV_FILE" | head -n 1 | sed 's/[[:space:]]*#.*$//' | sed 's/[[:space:]]*$//')"
fi
if [[ -n "$REQUESTED_COMPOSE_PROJECT_NAME" ]]; then
  COMPOSE_PROJECT_NAME="$REQUESTED_COMPOSE_PROJECT_NAME"
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
export COMPOSE_PROJECT_NAME
OBS_NETWORK_NAME="${COMPOSE_PROJECT_NAME}_batch-network"

if ! docker network inspect "$OBS_NETWORK_NAME" >/dev/null 2>&1; then
  docker network create "$OBS_NETWORK_NAME" >/dev/null
fi

docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --env-file "$COMPOSE_ENV_FILE" \
  -f deploy/docker/compose/observability.yml \
  --profile observability \
  up -d "$@"
