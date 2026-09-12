#!/usr/bin/env bash
# 本地开发磁盘清理。默认只预览；数据卷、运行日志和构建产物均需显式启用。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APPLY=false
RETENTION_DAYS=7
INCLUDE_ANONYMOUS_VOLUMES=false
INCLUDE_RUN_LOGS=false
INCLUDE_BUILD_ARTIFACTS=false
INCLUDE_APP_LOGS=false
INCLUDE_OBSERVABILITY_VOLUMES=false
ALL_BUILD_CACHE=false
PRUNE_OLD_IMAGE_TAGS=false

usage() {
  cat <<'EOF'
用法: bash scripts/local/cleanup-disk.sh [选项]

默认行为是输出磁盘使用情况和待清理项，不执行删除。

选项:
  --apply                       执行清理
  --retention-days <天数>       仅处理早于该天数的资源，默认 7
  --include-anonymous-volumes   同时处理无引用的 Docker 匿名卷
  --include-run-logs            同时处理 logs/runs 下的历史运行目录
  --include-build-artifacts     同时处理仓库内 Maven target 目录
  --include-app-logs            同时清理 logs/current/app、logs/app、logs/archive/app 下的历史应用日志
  --include-observability-volumes 同时清理本地观测栈命名卷
  --all-build-cache             清理全部未使用的 BuildKit 缓存，忽略保留周期
  --prune-old-image-tags        每个镜像仓库只保留最新版本和容器引用版本
  -h, --help                    显示帮助

示例:
  bash scripts/local/cleanup-disk.sh
  bash scripts/local/cleanup-disk.sh --apply
  bash scripts/local/cleanup-disk.sh --apply --all-build-cache
  bash scripts/local/cleanup-disk.sh --apply --prune-old-image-tags
  bash scripts/local/cleanup-disk.sh --apply --retention-days 14 --include-anonymous-volumes
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
      ;;
    --prune-old-image-tags)
      PRUNE_OLD_IMAGE_TAGS=true
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

prune_old_image_tags() {
  local active_ids candidates count refs keep ref id repo tag
  active_ids="$(docker ps --all --quiet \
    | xargs -r docker inspect --format '{{.Image}}' \
    | sed 's/^sha256://' \
    | sort -u)"

  candidates="$(docker image ls --format '{{.Repository}}\t{{.Tag}}' \
    | awk -F '\t' '$1 != "<none>" && $2 != "<none>" {print}' \
    | while IFS=$'\t' read -r repo tag; do
        refs="$(docker image ls "$repo" --format '{{.Repository}}:{{.Tag}}' | grep -v ':<none>$')"
        keep="$(printf '%s\n' "$refs" \
          | awk -v repository="$repo" '$0 == repository ":latest" {print; found=1; exit} END {if (!found) exit 1}' \
          || printf '%s\n' "$refs" | head -n 1)"
        ref="${repo}:${tag}"
        [ "$ref" = "$keep" ] && continue
        id="$(docker image inspect "$ref" --format '{{.Id}}' | sed 's/^sha256://')"
        printf '%s\n' "$active_ids" | grep -qx "$id" && continue
        printf '%s\n' "$ref"
      done \
    | sort -u)"

  count="$(printf '%s\n' "$candidates" | grep -c . || true)"
  echo "符合条件的历史镜像标签: ${count}"
  [ -n "$candidates" ] || return 0
  if [ "$APPLY" = true ]; then
    while IFS= read -r ref; do
      docker image rm "$ref" >/dev/null
    done <<< "$candidates"
    echo "已删除 ${count} 个历史镜像标签"
  else
    printf '%s\n' "$candidates"
  fi
}

echo "清理模式: $([ "$APPLY" = true ] && echo 执行 || echo 预览)"
echo "保留周期: ${RETENTION_DAYS} 天"
df -h "$ROOT" | tail -n 1

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo
  echo 'Docker 清理前占用:'
  docker system df

  # BuildKit 缓存和 dangling 镜像均可重新生成，但保留最近构建以免影响开发效率。
  if [ "$ALL_BUILD_CACHE" = true ]; then
    run_or_preview docker builder prune --all --force
  else
    run_or_preview docker builder prune --force --filter "until=${hours}h"
  fi
  run_or_preview docker image prune --force --filter "until=${hours}h"

  if [ "$PRUNE_OLD_IMAGE_TAGS" = true ]; then
    prune_old_image_tags
  else
    echo '历史镜像标签: 未启用清理（使用 --prune-old-image-tags 显式启用）'
  fi

  if [ "$INCLUDE_ANONYMOUS_VOLUMES" = true ]; then
    cutoff_epoch="$(($(date +%s) - RETENTION_DAYS * 86400))"
    candidates="$({
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

    count="$(printf '%s\n' "$candidates" | grep -c . || true)"
    echo "符合条件的无引用 Docker 匿名卷: ${count}"
    if [ -n "$candidates" ]; then
      if [ "$APPLY" = true ]; then
        printf '%s\n' "$candidates" | xargs docker volume rm >/dev/null
        echo "已删除 ${count} 个无引用 Docker 匿名卷"
      else
        printf '%s\n' "$candidates" | head -n 20
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
      printf '%s\n' "$log_candidates" | head -n 20
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
  echo "历史应用日志（超过 ${RETENTION_DAYS} 天）:"
  app_candidates=""
  for dir in "$ROOT/logs/current/app" "$ROOT/logs/app" "$ROOT/logs/archive/app"; do
    if [ -d "$dir" ]; then
      found="$(find "$dir" -maxdepth 1 -type f -name '*.log' -mtime +${RETENTION_DAYS} -print || true)"
      if [ -n "$found" ]; then
        if [ -z "$app_candidates" ]; then
          app_candidates="$found"
        else
          app_candidates="$app_candidates\n$found"
        fi
      fi
    fi
  done

  app_count="$(printf '%s\n' "$app_candidates" | grep -c . || true)"
  echo "符合条件的应用日志文件: ${app_count}"
  app_disk_size="$(printf '%s\n' "$app_candidates" | xargs -r du -ch 2>/dev/null | awk 'END {print $1}')"
  [ -n "$app_disk_size" ] && echo "应用日志待清理空间: ${app_disk_size}"
  if [ -n "$app_candidates" ]; then
    if [ "$APPLY" = true ]; then
      printf '%s\n' "$app_candidates" | xargs -r rm -f
      echo "已删除 ${app_count} 个应用日志文件（清理空间: ${app_disk_size:-未知}）"
    else
      printf '%s\n' "$app_candidates" | head -n 20
      if [ "$app_count" -gt 20 ]; then
        echo "... 其余 $((app_count - 20)) 个已省略"
      fi
    fi
  fi
else
  echo '历史应用日志: 未启用清理（使用 --include-app-logs 显式启用）'
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
echo '受保护项: 运行中/已停止容器、数据库文件与 Maven 仓库。若未启用对应参数，Docker 卷/日志/历史运行目录不清理。'
