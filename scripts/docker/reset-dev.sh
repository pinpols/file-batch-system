#!/usr/bin/env bash
# 清空本地 Docker 开发环境。
#
# 默认只预览。执行删除必须显式传 --apply，并在交互确认或传 --yes 后才会继续。
# 清理边界由 Compose project label 确定，不使用 docker system prune，避免误删
# 其他项目、生产容器或宿主机上无关的构建缓存。
#
# 默认清理：当前 Compose project 的容器、命名卷和专用网络。
# 可选清理：这些容器使用的镜像、这些容器挂载的匿名卷。
# BuildKit 缓存是 Docker 全局资源，不在本脚本中清理；需要时使用
# scripts/local/cleanup-disk.sh 的 --all-build-cache。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# shellcheck source=../local/docker-path.sh
source "$ROOT/scripts/local/docker-path.sh"
ensure_docker_on_path

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
APPLY=false
YES=false
REMOVE_IMAGES=false
REMOVE_ANONYMOUS_VOLUMES=false

usage() {
  cat <<'EOF'
用法: bash scripts/docker/reset-dev.sh [选项]

默认只预览，不删除资源。

选项:
  --apply                    执行删除；仍需交互确认或同时传 --yes
  --yes                      跳过交互确认，仅建议用于已审阅的本地命令
  --project-name <名称>      Compose 项目名，默认 batch-platform
  --include-images           同时删除目标项目容器使用的镜像
  --include-anonymous-volumes
                             同时删除目标项目容器挂载的匿名卷
  -h, --help                 显示帮助

示例:
  # 先查看将要清理什么
  bash scripts/docker/reset-dev.sh

  # 清空本地 batch-platform 容器、命名卷和网络
  bash scripts/docker/reset-dev.sh --apply

  # 连同该项目容器使用的镜像一起清理
  bash scripts/docker/reset-dev.sh --apply --include-images

  # 非交互脚本调用：必须显式声明 project name 和 --yes
  COMPOSE_PROJECT_NAME=batch-platform \
    bash scripts/docker/reset-dev.sh --apply --yes --include-images

说明:
  - 只按 com.docker.compose.project=${PROJECT_NAME} 清理容器和命名卷。
  - ${PROJECT_NAME}_batch-network 只有在没有其他容器连接时才会删除。
  - 不清理 BuildKit 全局缓存；使用 scripts/local/cleanup-disk.sh 单独处理。
  - 不要对 .env.prod 或生产 Docker context 执行此脚本。
EOF
}

require_value() {
  if [[ "$#" -lt 2 || -z "$2" ]]; then
    echo "ERROR: $1 缺少参数值。" >&2
    exit 2
  fi
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --apply)
      APPLY=true
      ;;
    --yes)
      YES=true
      ;;
    --project-name)
      require_value "$@"
      PROJECT_NAME="$2"
      shift
      ;;
    --include-images)
      REMOVE_IMAGES=true
      ;;
    --include-anonymous-volumes)
      REMOVE_ANONYMOUS_VOLUMES=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: 未知参数: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ -z "$PROJECT_NAME" || "$PROJECT_NAME" =~ [[:space:]] ]]; then
  echo "ERROR: Compose 项目名不能为空且不能包含空白字符。" >&2
  exit 2
fi

case "$PROJECT_NAME" in
  prod|production|staging|uat|preprod|default)
    echo "ERROR: 拒绝对受保护的 Compose 项目名执行开发环境清理: $PROJECT_NAME" >&2
    exit 2
    ;;
esac

if [[ "$YES" == true && "$APPLY" != true ]]; then
  echo "ERROR: --yes 只能与 --apply 一起使用。" >&2
  exit 2
fi

docker info >/dev/null 2>&1 || {
  echo "ERROR: Docker daemon 不可用，请先启动 Docker Desktop 或 Docker Engine。" >&2
  exit 1
}

project_filter="label=com.docker.compose.project=${PROJECT_NAME}"
containers="$(docker ps -aq --filter "$project_filter")"
named_volumes="$(docker volume ls -q --filter "$project_filter")"
network_name="${PROJECT_NAME}_batch-network"

# 在删除容器前记录其镜像和挂载卷，避免清理后失去精确边界。
image_ids=""
mounted_volumes=""
if [[ -n "$containers" ]]; then
  image_ids="$(printf '%s\n' "$containers" \
    | xargs docker inspect --format '{{.Image}}' \
    | sort -u)"
  mounted_volumes="$(printf '%s\n' "$containers" \
    | xargs docker inspect --format '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}}{{"\n"}}{{end}}{{end}}' \
    | sed '/^$/d' \
    | sort -u)"
fi

anonymous_volumes=""
if [[ "$REMOVE_ANONYMOUS_VOLUMES" == true && -n "$mounted_volumes" ]]; then
  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    labels="$(docker volume inspect "$volume" --format '{{json .Labels}}' 2>/dev/null || true)"
    if [[ "$labels" == *'com.docker.volume.anonymous'* ]]; then
      anonymous_volumes="${anonymous_volumes}${volume}\n"
    fi
  done <<< "$mounted_volumes"
  anonymous_volumes="$(printf '%b' "$anonymous_volumes" | sed '/^$/d' | sort -u)"
fi

print_list() {
  local label="$1" values="$2"
  local count=0
  if [[ -n "$values" ]]; then
    count="$(printf '%s\n' "$values" | sed '/^$/d' | wc -l | tr -d ' ')"
  fi
  echo "${label}: ${count}"
  if [[ -n "$values" ]]; then
    printf '%s\n' "$values" | sed '/^$/d' | sed 's/^/  - /'
  fi
}

echo "Docker 开发环境清理范围"
echo "  Compose project: $PROJECT_NAME"
echo "  Docker context:  $(docker context show 2>/dev/null || echo unknown)"
print_list "容器" "$containers"
print_list "命名卷" "$named_volumes"
print_list "匿名卷" "$anonymous_volumes"
if [[ "$REMOVE_IMAGES" == true ]]; then
  print_list "待删除镜像 ID" "$image_ids"
else
  echo "镜像: 0（默认保留；需要时传 --include-images）"
fi

network_exists=false
if docker network inspect "$network_name" >/dev/null 2>&1; then
  network_exists=true
  connected="$(docker network inspect "$network_name" --format '{{range $id, $container := .Containers}}{{$container.Name}} {{end}}' 2>/dev/null \
    | tr ' ' '\n' | sed '/^$/d' || true)"
  print_list "网络中当前连接的容器" "$connected"
fi

if [[ "$APPLY" != true ]]; then
  echo
  echo "预览结束。确认只针对开发 Docker context 后，使用 --apply 执行。"
  exit 0
fi

if [[ "$YES" != true ]]; then
  echo
  printf '将删除以上范围内的开发资源，输入项目名 %s 继续: ' "$PROJECT_NAME"
  read -r confirmation
  if [[ "$confirmation" != "$PROJECT_NAME" ]]; then
    echo "已取消。"
    exit 0
  fi
fi

if [[ -n "$containers" ]]; then
  echo "==> 删除项目容器"
  printf '%s\n' "$containers" | xargs docker rm -f >/dev/null
fi

if [[ -n "$anonymous_volumes" ]]; then
  echo "==> 删除目标容器挂载的匿名卷"
  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    docker volume rm "$volume" >/dev/null || echo "WARN: 无法删除匿名卷: $volume" >&2
  done <<< "$anonymous_volumes"
fi

if [[ -n "$named_volumes" ]]; then
  echo "==> 删除项目命名卷"
  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    if docker volume rm "$volume" >/dev/null 2>&1; then
      echo "  已删除 $volume"
    else
      echo "WARN: 命名卷仍被其他容器使用，保留: $volume" >&2
    fi
  done <<< "$named_volumes"
fi

if [[ "$network_exists" == true ]]; then
  remaining="$(docker network inspect "$network_name" --format '{{range $id, $container := .Containers}}{{$container.Name}} {{end}}' 2>/dev/null \
    | tr ' ' '\n' | sed '/^$/d' || true)"
  if [[ -z "$remaining" ]]; then
    docker network rm "$network_name" >/dev/null 2>&1 || true
    echo "==> 已删除网络 $network_name"
  else
    echo "WARN: 网络仍有其他容器连接，保留: $network_name" >&2
    print_list "保留的网络连接" "$remaining"
  fi
fi

if [[ "$REMOVE_IMAGES" == true && -n "$image_ids" ]]; then
  echo "==> 删除项目容器使用的镜像（仍被其他容器使用的镜像会保留）"
  remaining_containers="$(docker ps -aq)"
  while IFS= read -r image_id; do
    [[ -n "$image_id" ]] || continue
    referenced=""
    if [[ -n "$remaining_containers" ]]; then
      referenced="$(printf '%s\n' "$remaining_containers" \
        | xargs docker inspect --format '{{.Id}} {{.Image}}' 2>/dev/null \
        | awk -v image_id="$image_id" '$2 == image_id {print $1}')"
    fi
    if [[ -n "$referenced" ]]; then
      echo "  保留仍被其他容器引用的镜像 $image_id"
    elif docker image rm "$image_id" >/dev/null 2>&1; then
      echo "  已删除 $image_id"
    else
      echo "WARN: 无法删除镜像 $image_id" >&2
    fi
  done <<< "$image_ids"
fi

echo "开发 Docker 环境清理完成。BuildKit 全局缓存未处理。"
