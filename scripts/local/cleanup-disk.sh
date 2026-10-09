#!/usr/bin/env bash
# 本地开发磁盘清理。默认只预览；数据卷、运行日志和构建产物均需显式启用。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
# shellcheck source=../lib/destructive-ops.sh
source "$ROOT/scripts/lib/destructive-ops.sh"
APPLY=false
CONFIRM_ROOT=""
RETENTION_DAYS=7
INCLUDE_ANONYMOUS_VOLUMES=false
INCLUDE_RUN_LOGS=false
INCLUDE_BUILD_ARTIFACTS=false
INCLUDE_APP_LOGS=false
INCLUDE_OBSERVABILITY_VOLUMES=false
ALL_BUILD_CACHE=false
INCLUDE_BUILD_CACHE=false
INCLUDE_TEST_CONTAINERS=false
INCLUDE_LOCAL_REUSE_CONTAINERS=false
INCLUDE_COMPOSE_INIT_CONTAINERS=false
INCLUDE_BIZ_SHARDS=false

usage() {
  cat <<'EOF'
用法: bash scripts/local/cleanup-disk.sh [选项]

默认行为是输出磁盘使用情况和待清理项，不执行删除。

选项:
  --apply                       执行清理
  --confirm-root <目录名>       与 --apply 一起使用，必须输入仓库根目录名
  --retention-days <天数>       仅处理早于该天数的资源，默认 7
  --include-anonymous-volumes   同时处理无引用的 Docker 匿名卷
  --include-run-logs            同时处理 logs/runs 下的历史运行目录
  --include-build-artifacts     同时处理仓库内 Maven target 目录
  --include-app-logs            同时清理 logs/archive/app 下的历史应用归档日志
  --include-observability-volumes 同时清理本地观测栈命名卷
  --include-build-cache         清理超过保留期的 BuildKit 缓存
  --all-build-cache             清理全部未使用的 BuildKit 缓存，忽略保留周期
  --include-test-containers     清理已退出且带 BFS 所有权标签的测试容器
  --include-local-reuse-containers 清理带 BFS 本地复用标签的测试容器（可包含运行中容器）
  --include-compose-init-containers 清理本项目已退出的 Kafka/MinIO 初始化容器
  --include-biz-shards          清理带 BFS 所有权标签的 routing-sim 分片容器
  --batch <name>                选择批次：test-residue、build-cache、safe
  -h, --help                    显示帮助

示例:
  bash scripts/local/cleanup-disk.sh
  bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system
  bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --batch test-residue
  bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --batch build-cache
  bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --all-build-cache
  bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --retention-days 14 --include-anonymous-volumes
EOF
}

require_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    echo "缺少参数值: $1" >&2
    exit 2
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apply)
      APPLY=true
      ;;
    --confirm-root)
      require_value "$@"
      CONFIRM_ROOT="$2"
      shift
      ;;
    --retention-days)
      require_value "$@"
      RETENTION_DAYS="$2"
      shift
      ;;
    --include-anonymous-volumes)
      INCLUDE_ANONYMOUS_VOLUMES=true
      ;;
    --include-run-logs)
      INCLUDE_RUN_LOGS=true
      ;;
    --include-build-artifacts)
      INCLUDE_BUILD_ARTIFACTS=true
      ;;
    --include-app-logs)
      INCLUDE_APP_LOGS=true
      ;;
    --include-observability-volumes)
      INCLUDE_OBSERVABILITY_VOLUMES=true
      ;;
    --all-build-cache)
      ALL_BUILD_CACHE=true
      INCLUDE_BUILD_CACHE=true
      ;;
    --include-build-cache)
      INCLUDE_BUILD_CACHE=true
      ;;
    --include-test-containers)
      INCLUDE_TEST_CONTAINERS=true
      ;;
    --include-local-reuse-containers)
      INCLUDE_LOCAL_REUSE_CONTAINERS=true
      ;;
    --include-compose-init-containers)
      INCLUDE_COMPOSE_INIT_CONTAINERS=true
      ;;
    --include-biz-shards)
      INCLUDE_BIZ_SHARDS=true
      ;;
    --batch)
      require_value "$@"
      case "$2" in
        test-residue)
          INCLUDE_TEST_CONTAINERS=true
          INCLUDE_COMPOSE_INIT_CONTAINERS=true
          INCLUDE_BIZ_SHARDS=true
          ;;
        build-cache)
          INCLUDE_BUILD_CACHE=true
          ;;
        safe)
          INCLUDE_TEST_CONTAINERS=true
          INCLUDE_COMPOSE_INIT_CONTAINERS=true
          INCLUDE_BIZ_SHARDS=true
          INCLUDE_BUILD_CACHE=true
          ;;
        *)
          echo "不支持的清理批次: $2" >&2
          exit 2
          ;;
      esac
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

case "$RETENTION_DAYS" in
  ''|*[!0-9]*)
    echo "--retention-days 必须是非负整数" >&2
    exit 2
    ;;
esac

hours=$((RETENTION_DAYS * 24))

if [ "$APPLY" = true ]; then
  expected_root="$(basename "$ROOT")"
  if [ "$CONFIRM_ROOT" != "$expected_root" ]; then
    echo "拒绝删除:必须同时传 --confirm-root '$expected_root'。" >&2
    exit 2
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    batch_require_local_docker_context || exit $?
  fi
fi

run_or_preview() {
  if [ "$APPLY" = true ]; then
    "$@"
  else
    printf '[预览]'
    printf ' %q' "$@"
    printf '\n'
  fi
}

cleanup_observability_volumes() {
  local compose_project
  local -a suffixes=(
    prometheus-data
    loki-data
    tempo-data
    otel-collector-data
    grafana-data
  )
  local -A candidates=()
  local suffix candidate
  compose_project="${COMPOSE_PROJECT_NAME:-batch-platform}"

  for suffix in "${suffixes[@]}"; do
    while IFS= read -r candidate; do
      [ -n "$candidate" ] && candidates["$candidate"]=1
    done < <(docker volume ls -q --filter "name=${compose_project}_${suffix}" || true)

    while IFS= read -r candidate; do
      [ -n "$candidate" ] && candidates["$candidate"]=1
    done < <(docker volume ls -q --filter "name=_${suffix}$" || true)
  done

  if [ "${#candidates[@]}" -eq 0 ]; then
    echo '符合条件的观测栈命名卷: 0'
    return
  fi

  echo "符合条件的观测栈命名卷: ${#candidates[@]}"
  printf '%s\n' "${!candidates[@]}"

  if [ "$APPLY" = true ]; then
    printf '%s\n' "${!candidates[@]}" | xargs -r docker volume rm -f >/dev/null
    echo "已删除 ${#candidates[@]} 个观测栈命名卷（注意将触发其内容清空）"
  fi
}

show_and_remove_containers() {
  local title="$1" container_ids="$2" count cid
  count="$(printf '%s\n' "$container_ids" | grep -c . || true)"
  echo "${title}: ${count}"
  [ -n "$container_ids" ] || return 0
  if [ "$APPLY" = true ]; then
    while IFS= read -r cid; do
      [ -n "$cid" ] || continue
      docker rm -f "$cid" >/dev/null
    done <<< "$container_ids"
    echo "已清理 ${count} 个容器（不删除镜像或卷）"
  else
    printf '%s\n' "$container_ids"
  fi
}

cleanup_test_containers() {
  local owner_label="io.github.pinpols.batch.testcontainers.owner=file-batch-system"
  local reuse_label="io.github.pinpols.batch.testcontainers.reuse=local-opt-in"
  local test_container_ids="" reuse_container_ids="" cid running

  if [ "$INCLUDE_TEST_CONTAINERS" = true ]; then
    while IFS= read -r cid; do
      [ -n "$cid" ] || continue
      running="$(docker inspect --format '{{.State.Running}}' "$cid" 2>/dev/null || echo false)"
      [ "$running" = false ] && test_container_ids+="${cid}"$'\n'
    done < <(docker ps -aq --filter "label=${owner_label}")
    show_and_remove_containers '符合条件的已退出 BFS Testcontainers' "${test_container_ids%$'\n'}"
  fi

  if [ "$INCLUDE_LOCAL_REUSE_CONTAINERS" = true ]; then
    reuse_container_ids="$(docker ps -aq --filter "label=${reuse_label}")"
    show_and_remove_containers '符合条件的 BFS 本地复用容器' "$reuse_container_ids"
  fi
}

cleanup_compose_init_containers() {
  local init_container_ids="" cid service project
  while IFS= read -r cid; do
    [ -n "$cid" ] || continue
    project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid" 2>/dev/null || true)"
    service="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "$cid" 2>/dev/null || true)"
    [ "$project" = "${COMPOSE_PROJECT_NAME:-batch-platform}" ] || continue
    case "$service" in
      kafka-init|minio-init|minio-volume-init)
        init_container_ids+="${cid}"$'\n'
        ;;
    esac
  done < <(docker ps -aq --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-batch-platform}" --filter status=exited)
  show_and_remove_containers '符合条件的已退出 Compose 初始化容器' "${init_container_ids%$'\n'}"
}

cleanup_biz_shards() {
  local owner_label="io.github.pinpols.batch.testcontainers.owner=file-batch-system"
  local shard_container_ids
  shard_container_ids="$(docker ps -aq \
    --filter 'name=^batch-postgres-biz-shard-' \
    --filter "label=${owner_label}")"
  show_and_remove_containers '符合条件的本地业务分片容器' "$shard_container_ids"
}

echo "清理模式: $([ "$APPLY" = true ] && echo 执行 || echo 预览)"
echo "保留周期: ${RETENTION_DAYS} 天"
df -h "$ROOT" | tail -n 1

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo
  echo 'Docker 清理前占用:'
  docker system df

  if [ "$INCLUDE_BUILD_CACHE" = true ]; then
    if [ "$ALL_BUILD_CACHE" = true ]; then
      run_or_preview docker builder prune --all --force
    else
      run_or_preview docker builder prune --force --filter "until=${hours}h"
    fi
  else
    echo 'BuildKit 缓存: 未启用清理（使用 --include-build-cache 或 --batch build-cache）'
  fi

  if [ "$INCLUDE_TEST_CONTAINERS" = true ] || [ "$INCLUDE_LOCAL_REUSE_CONTAINERS" = true ]; then
    cleanup_test_containers
  else
    echo 'BFS Testcontainers: 未启用清理（使用 --include-test-containers 或 --include-local-reuse-containers）'
  fi
  if [ "$INCLUDE_COMPOSE_INIT_CONTAINERS" = true ]; then
    cleanup_compose_init_containers
  else
    echo '已退出 Compose 初始化容器: 未启用清理（使用 --include-compose-init-containers）'
  fi
  if [ "$INCLUDE_BIZ_SHARDS" = true ]; then
    cleanup_biz_shards
  else
    echo '业务分片测试容器: 未启用清理（使用 --include-biz-shards）'
  fi

  if [ "$INCLUDE_ANONYMOUS_VOLUMES" = true ]; then
    cutoff_epoch="$(($(date +%s) - RETENTION_DAYS * 86400))"
    anon_volume_candidates="$({
      docker volume ls --quiet --filter dangling=true \
        | grep -E '^[0-9a-f]{64}$' || true
    } | while IFS= read -r volume; do
      [ -n "$volume" ] || continue
      created="$(docker volume inspect --format '{{.CreatedAt}}' "$volume" 2>/dev/null || true)"
      labels="$(docker volume inspect --format '{{json .Labels}}' "$volume" 2>/dev/null || true)"
      case "$labels" in
        'null'|'{}'|'{"com.docker.volume.anonymous":""}') ;;
        *) continue ;;
      esac
      created_epoch="$(python3 - "$created" <<'PY'
import datetime
import sys

value = sys.argv[1].strip()
try:
    normalized = value.replace("Z", "+00:00")
    if "." in normalized:
        head, tail = normalized.split(".", 1)
        fraction, offset = tail, ""
        for marker in ("+", "-"):
            position = fraction.find(marker)
            if position >= 0:
                fraction, offset = fraction[:position], fraction[position:]
                break
        normalized = f"{head}.{fraction[:6]}{offset}"
    print(int(datetime.datetime.fromisoformat(normalized).timestamp()))
except (TypeError, ValueError):
    print(2**63 - 1)
PY
)"
      if [ "$created_epoch" -lt "$cutoff_epoch" ]; then
        printf '%s\n' "$volume"
      fi
    done)"

    count="$(printf '%s\n' "$anon_volume_candidates" | grep -c . || true)"
    echo "符合条件的无引用 Docker 匿名卷: ${count}"
    if [ -n "$anon_volume_candidates" ]; then
      if [ "$APPLY" = true ]; then
        printf '%s\n' "$anon_volume_candidates" | xargs docker volume rm >/dev/null
        echo "已删除 ${count} 个无引用 Docker 匿名卷"
      else
        awk 'NR <= 20 { print }' <<< "$anon_volume_candidates"
        if [ "$count" -gt 20 ]; then
          echo "... 其余 $((count - 20)) 个已省略"
        fi
      fi
    fi
  else
    echo '匿名卷: 未启用清理（使用 --include-anonymous-volumes 显式启用）'
  fi
else
  echo 'Docker 不可用，跳过 Docker 清理'
fi

if [ "$INCLUDE_RUN_LOGS" = true ]; then
  echo
  echo "历史运行日志（超过 ${RETENTION_DAYS} 天）:"
  if [ -d "$ROOT/logs/runs" ]; then
    log_candidates="$(find "$ROOT/logs/runs" -mindepth 2 -maxdepth 2 -type d -mtime "+${RETENTION_DAYS}" -print)"
    log_count="$(printf '%s\n' "$log_candidates" | grep -c . || true)"
    echo "符合条件的历史运行目录: ${log_count}"
    if [ -n "$log_candidates" ] && [ "$APPLY" = true ]; then
      printf '%s\n' "$log_candidates" | while IFS= read -r directory; do
        rm -rf "$directory"
      done
      echo "已删除 ${log_count} 个历史运行目录"
    elif [ -n "$log_candidates" ]; then
      awk 'NR <= 20 { print }' <<< "$log_candidates"
      if [ "$log_count" -gt 20 ]; then
        echo "... 其余 $((log_count - 20)) 个已省略"
      fi
    fi
  fi
else
  echo '历史运行日志: 未启用清理（使用 --include-run-logs 显式启用）'
fi

if [ "$INCLUDE_BUILD_ARTIFACTS" = true ]; then
  echo
  echo 'Maven 构建目录:'
  find "$ROOT" -type d -name target -prune -print
  if [ "$APPLY" = true ]; then
    find "$ROOT" -type d -name target -prune -exec rm -rf {} +
  fi
else
  echo 'Maven 构建目录: 未启用清理（使用 --include-build-artifacts 显式启用）'
fi

if [ "$INCLUDE_APP_LOGS" = true ]; then
  echo
  echo "历史应用归档日志（超过 ${RETENTION_DAYS} 天）:"
  app_candidates=()
  while IFS= read -r -d '' file; do
    app_candidates+=("$file")
  done < <(log_find_archived_log_files "$ROOT" app "$RETENTION_DAYS")

  app_count="${#app_candidates[@]}"
  echo "符合条件的应用日志文件: ${app_count}"
  app_disk_size=""
  if [ "$app_count" -gt 0 ]; then
    app_disk_size="$(du -ch -- "${app_candidates[@]}" 2>/dev/null | awk 'END {print $1}')"
  fi
  [ -n "$app_disk_size" ] && echo "应用日志待清理空间: ${app_disk_size}"
  if [ "$app_count" -gt 0 ]; then
    if [ "$APPLY" = true ]; then
      rm -f -- "${app_candidates[@]}"
      echo "已删除 ${app_count} 个应用日志文件（清理空间: ${app_disk_size:-未知}）"
    else
      printf '%s\n' "${app_candidates[@]:0:20}"
      if [ "$app_count" -gt 20 ]; then
        echo "... 其余 $((app_count - 20)) 个已省略"
      fi
    fi
  fi
else
  echo '历史应用归档日志: 未启用清理（使用 --include-app-logs 显式启用）'
fi

if [ "$INCLUDE_OBSERVABILITY_VOLUMES" = true ]; then
  echo
  cleanup_observability_volumes
else
  echo '观测栈命名卷: 未启用清理（使用 --include-observability-volumes 显式启用）'
fi

if [ "$APPLY" = true ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo
  echo 'Docker 清理后占用:'
  docker system df
fi

echo
echo '受保护项: 所有 Docker 镜像、Compose 应用/基础环境容器、命名卷、数据库文件与 Maven 仓库；本脚本不执行 image prune/rmi、volume prune 或 system prune。'
