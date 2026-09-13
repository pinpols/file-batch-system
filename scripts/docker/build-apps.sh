#!/usr/bin/env bash
# =========================================================
# build-apps.sh - 一键构建本地应用镜像（默认启用 BuildKit）
# 说明：
# 1) 默认使用 .env.local。
# 2) 默认构建 docker-compose.yml + deploy/docker/compose/app.yml 的 apps + replica profile
#    （console-api depends_on postgres-replica，缺 replica profile 会报 undefined service）。
# 3) 默认启用 DOCKER_BUILDKIT=1 / COMPOSE_DOCKER_CLI_BUILD=1。
# 4) 只指定一个应用服务时自动切换为 Maven 模块闭包构建；不指定或指定多个时共享全 reactor。
# 5) 可用 BUILD_MODE=all|module 显式覆盖自动选择。
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
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"

export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}"
export COMPOSE_DOCKER_CLI_BUILD="${COMPOSE_DOCKER_CLI_BUILD:-1}"

app_services=(
  console-api trigger orchestrator worker-import worker-export worker-process worker-dispatch worker-atomic
)
selected_services=()
for argument in "$@"; do
  for service in "${app_services[@]}"; do
    if [[ "$argument" == "$service" ]]; then
      selected_services+=("$service")
      break
    fi
  done
done

if [[ -n "${BUILD_MODE:-}" ]]; then
  if [[ "$BUILD_MODE" != "all" && "$BUILD_MODE" != "module" ]]; then
    echo "ERROR: BUILD_MODE 只能是 all 或 module，当前值: $BUILD_MODE" >&2
    exit 2
  fi
  build_mode="$BUILD_MODE"
elif [[ "${#selected_services[@]}" -eq 1 ]]; then
  build_mode="module"
else
  build_mode="all"
fi

if [[ "$build_mode" == "module" && "${#selected_services[@]}" -ne 1 ]]; then
  echo "ERROR: BUILD_MODE=module 必须且只能指定一个应用服务" >&2
  exit 2
fi

echo "==> Docker 应用镜像构建模式: ${build_mode}"

build_revision="${BATCH_BUILD_REVISION:-$(git rev-parse HEAD)}"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  build_revision="${build_revision}-dirty"
fi
echo "==> Docker 应用镜像源码版本: ${build_revision}"

docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --env-file "$COMPOSE_ENV_FILE" \
  -f docker-compose.yml \
  -f deploy/docker/compose/app.yml \
  --profile apps \
  --profile replica \
  build \
  --build-arg "BUILD_MODE=${build_mode}" \
  --build-arg "BUILD_REVISION=${build_revision}" \
  "$@"
