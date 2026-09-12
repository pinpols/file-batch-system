#!/usr/bin/env bash
# =========================================================
# up-apps.sh - 一键启动本地基础依赖 + 应用容器
# 说明：
# 1) 默认使用 .env.local。
# 2) 默认启动 docker-compose.yml + deploy/docker/compose/app.yml 的 apps + replica profile
#    （read-replica 默认 enabled=true，必须把 postgres-replica 一起拉起来才不会 unhealthy）。
# 3) 可透传额外 docker compose 参数，例如：
#    ./scripts/docker/up-apps.sh console-api
# 4) 隔离容量压测：COMPOSE_BENCHMARK=1 ./scripts/docker/up-apps.sh
#    若只重建控制面，须显式包含 trigger orchestrator orchestrator-benchmark-replica。
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
COMPOSE_BENCHMARK="${COMPOSE_BENCHMARK:-0}"

# env-common 的默认值服务于宿主机脚本，因此 S3 默认指向 localhost:19000。
# 容器启动入口只保留调用方或 env 文件显式提供的 endpoint；未显式配置时 unset，
# 让 compose 使用 minio:9000。这样外部 S3 配置仍可覆盖，默认本地拓扑也不会串线。
_batch_s3_endpoint_explicit=0
if [[ -v BATCH_S3_ENDPOINT ]]; then
  _batch_s3_endpoint_explicit=1
else
  for _env_file in "$ROOT/.env" "$COMPOSE_ENV_FILE"; do
    if [[ -f "$_env_file" ]] && grep -Eq '^[[:space:]]*(export[[:space:]]+)?BATCH_S3_ENDPOINT=' "$_env_file"; then
      _batch_s3_endpoint_explicit=1
      break
    fi
  done
fi
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
if [[ -n "$REQUESTED_COMPOSE_PROJECT_NAME" ]]; then
  COMPOSE_PROJECT_NAME="$REQUESTED_COMPOSE_PROJECT_NAME"
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
export COMPOSE_PROJECT_NAME
unset REQUESTED_COMPOSE_PROJECT_NAME
if [[ "$_batch_s3_endpoint_explicit" -eq 0 ]]; then
  unset BATCH_S3_ENDPOINT
fi
unset _batch_s3_endpoint_explicit _env_file
APP_NETWORK_NAME="${COMPOSE_PROJECT_NAME}_batch-network"

export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}"
export COMPOSE_DOCKER_CLI_BUILD="${COMPOSE_DOCKER_CLI_BUILD:-1}"

if ! docker network inspect "$APP_NETWORK_NAME" >/dev/null 2>&1; then
  docker network create "$APP_NETWORK_NAME" >/dev/null
fi

echo "应用容器日志通过 docker compose logs 查询，并由 local driver 限量轮转。"

compose_files=(
  -f docker-compose.yml
  -f deploy/docker/compose/app.yml
)
if [[ "$COMPOSE_BENCHMARK" == "1" ]]; then
  compose_files+=(-f deploy/docker/compose/benchmark.yml)
fi

up_args=(up -d --force-recreate)
if [[ "$#" -gt 0 ]]; then
  # 指定服务时只重建目标，避免为一次 profile / 镜像更新连带滚动其健康依赖。
  # 全量启动（无位置参数）仍由 Compose 根据 depends_on 拉起完整应用栈。
  up_args+=(--no-deps)
fi

docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --env-file "$COMPOSE_ENV_FILE" \
  "${compose_files[@]}" \
  --profile apps \
  --profile replica \
  "${up_args[@]}" "$@"
