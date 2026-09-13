#!/usr/bin/env bash
# =========================================================
# down-apps.sh - 停止本地基础依赖 + 应用容器，但不删除容器/网络/卷
# 说明：
# 1) 默认使用 .env.local。
# 2) 只执行 docker compose stop，不执行 down。
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
REQUESTED_COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
# 与启动脚本一致：优先采用 env 文件中的项目名，同时允许显式环境变量覆盖。
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
if [[ -n "$REQUESTED_COMPOSE_PROJECT_NAME" ]]; then
  COMPOSE_PROJECT_NAME="$REQUESTED_COMPOSE_PROJECT_NAME"
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
export COMPOSE_PROJECT_NAME
unset REQUESTED_COMPOSE_PROJECT_NAME

docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker-compose.yml \
  -f deploy/docker/compose/app.yml \
  --profile apps \
  --profile replica \
  stop
