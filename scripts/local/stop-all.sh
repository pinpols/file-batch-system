#!/usr/bin/env bash
# =========================================================
# stop-all.sh - 停止本地 batch 平台 Java 进程
# Notes:
# 1) 通过进程命令行匹配 jar 名来发现进程，不单纯依赖 PID 文件。
# 2) PID 文件存在时也会参考，但即使缺失仍可正常工作。
# 3) 可选使用 STOP_DOCKER=1 停止 Docker Compose 依赖（仅 stop，不 down）。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PID_FILE="$ROOT/logs/start-all.pids"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"

MODULES=(
  batch-orchestrator
  batch-trigger
  batch-console-api
  batch-worker-import
  batch-worker-export
  batch-worker-dispatch
)

killed=0
already_killed=()

kill_pid() {
  local label="$1" pid="$2"
  for prev in "${already_killed[@]+"${already_killed[@]}"}"; do
    [[ "$prev" == "$pid" ]] && return 0
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "  kill $label (pid=$pid)"
    kill "$pid" 2>/dev/null || true
    already_killed+=("$pid")
    ((killed+=1))
  fi
}

echo "==> 停止 Spring Boot 进程..."

# ── 第一轮：PID 文件 ──
if [[ -f "$PID_FILE" ]]; then
  while read -r name pid; do
    [[ -z "${pid:-}" ]] && continue
    kill_pid "$name" "$pid"
  done <"$PID_FILE"
  rm -f "$PID_FILE"
fi

# ── 第二轮：按 jar 名扫描残余 ──
for mod in "${MODULES[@]}"; do
  while read -r pid; do
    [[ -z "$pid" ]] && continue
    kill_pid "$mod" "$pid"
  done < <(pgrep -f "${mod}-[0-9].*\\.jar" 2>/dev/null || true)
done

if (( killed > 0 )); then
  echo "  等待进程退出..."
  sleep 2
  # 强杀仍存活的
  for pid in "${already_killed[@]+"${already_killed[@]}"}"; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "  force kill pid=$pid"
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
  echo "已停止 ${killed} 个进程。"
else
  echo "  未发现运行中的 batch 进程。"
fi

[[ -f "$PID_FILE" ]] && rm -f "$PID_FILE"

if [[ "${STOP_DOCKER:-}" == "1" ]]; then
  echo "==> Docker Compose 停止（STOP_DOCKER=1，保持环境不删除）..."
  _LOCAL_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  # shellcheck source=docker-path.sh
  source "${_LOCAL_SCRIPT_DIR}/docker-path.sh"
  ensure_docker_on_path
  unset _LOCAL_SCRIPT_DIR
  cd "$ROOT" && docker compose --env-file "$COMPOSE_ENV_FILE" stop
else
  echo "提示：仅停止 Java 进程；基础依赖（含 Redis）仍在运行。停止容器请执行: ./scripts/docker/down-apps.sh"
  echo "     或: COMPOSE_ENV_FILE=.env.test STOP_DOCKER=1 ./scripts/local/stop-all.sh"
fi
