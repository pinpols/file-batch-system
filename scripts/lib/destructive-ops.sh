#!/usr/bin/env bash
# 本地维护/仿真脚本的破坏性操作边界。生产运维命令不应调用此 helper 绕过自身审批流程。

# shellcheck source=runtime-defaults.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/runtime-defaults.sh"

batch_require_local_docker_context() {
  local context endpoint
  command -v docker >/dev/null 2>&1 || {
    echo "破坏性操作拒绝执行:找不到 Docker CLI。" >&2
    return 2
  }
  docker info >/dev/null 2>&1 || {
    echo "破坏性操作拒绝执行:Docker daemon 不可用。" >&2
    return 2
  }

  context="$(docker context show 2>/dev/null)" || context=""
  endpoint="$(docker context inspect "$context" --format '{{(index .Endpoints "docker").Host}}' 2>/dev/null)" || endpoint=""
  case "$endpoint" in
    unix://*|npipe://*) ;;
    *)
      echo "破坏性操作拒绝执行:仅允许本机 Docker socket，当前 context=${context:-unknown} endpoint=${endpoint:-unknown}。" >&2
      return 2
      ;;
  esac
  case "${BATCH_ENV:-${SPRING_PROFILES_ACTIVE:-local}}" in
    *prod*|*staging*|*uat*)
      echo "破坏性操作拒绝执行:当前环境标识为 ${BATCH_ENV:-$SPRING_PROFILES_ACTIVE}。" >&2
      return 2
      ;;
  esac
}

batch_require_compose_container() {
  local container="$1"
  local expected_project="${2:-${COMPOSE_PROJECT_NAME:-batch-platform}}"
  local actual_project
  case "$expected_project" in
    prod|production|staging|uat|preprod)
      echo "破坏性操作拒绝执行:Compose project 属于受保护环境 ${expected_project}。" >&2
      return 2
      ;;
  esac
  batch_require_local_docker_context || return
  actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$container" 2>/dev/null)" || actual_project=""
  if [[ -z "$actual_project" || "$actual_project" != "$expected_project" ]]; then
    echo "破坏性操作拒绝执行:容器 ${container} 不属于 Compose project ${expected_project}(实际=${actual_project:-无标签})。" >&2
    return 2
  fi
}

batch_require_local_host() {
  local host="$1"
  local normalized_host
  normalized_host="$(printf '%s' "$host" | tr '[:upper:]' '[:lower:]')"
  case "$normalized_host" in
    localhost|127.0.0.1|::1|\[::1\]|"$BATCH_DEFAULT_REDIS_HOST"|"$BATCH_DEFAULT_MINIO_SERVICE_HOST"|"$BATCH_DEFAULT_KAFKA_SERVICE_HOST"|"$BATCH_DEFAULT_POSTGRES_SERVICE_HOST"|"$BATCH_DEFAULT_POSTGRES_CONTAINER"|"$BATCH_DEFAULT_KAFKA_CONTAINER"|"$BATCH_DEFAULT_MINIO_CONTAINER") ;;
    *)
      echo "破坏性操作拒绝执行:目标不是本机/本地 Compose 服务，host=${host:-empty}。" >&2
      return 2
      ;;
  esac
}
