#!/usr/bin/env bash
# =========================================================
# up.sh - 一键启动本地观测栈
# 说明：
# 1) 默认使用 .env.local。
# 2) 合并基础设施定义与观测叠加文件，只启动观测服务。
# 3) 业务容器请先通过 scripts/docker/up-apps.sh 或 scripts/local/start-all.sh 启动。
# 4) 可指定要启动的观测服务，例如：
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
OBSERVABILITY_SERVICES=(
  prometheus alertmanager jaeger tempo loki otel-collector grafana
  redis-exporter postgres-exporter kafka-exporter node-exporter cadvisor
)

if ! docker network inspect "$OBS_NETWORK_NAME" >/dev/null 2>&1; then
  docker network create "$OBS_NETWORK_NAME" >/dev/null
fi

if [[ $# -gt 0 ]]; then
  OBSERVABILITY_SERVICES=("$@")
fi

COMPOSE=(
  docker compose
  --project-name "$COMPOSE_PROJECT_NAME"
  --env-file "$COMPOSE_ENV_FILE"
  -f docker-compose.yml
  -f deploy/docker/compose/observability.yml
)

# --no-deps 保证本脚本不会顺带接管 PostgreSQL、Kafka、Valkey 等基础设施生命周期。
# Collector 的命名卷权限仍需先同步初始化，避免非 root 进程启动后无法写队列目录。
for service in "${OBSERVABILITY_SERVICES[@]}"; do
  if [[ "$service" == "otel-collector" ]]; then
    "${COMPOSE[@]}" run --rm --no-deps otel-collector-init
    break
  fi
done

"${COMPOSE[@]}" up -d --no-deps "${OBSERVABILITY_SERVICES[@]}"
