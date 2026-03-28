#!/usr/bin/env bash
# =========================================================
# stop-all.sh - 停止 start-all.sh 启动的本地进程
# Notes:
# 1) 读取 logs/start-all.pids 并逐个终止 Java 进程。
# 2) 可选使用 STOP_DOCKER=1 关闭 Docker Compose 依赖。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PID_FILE="$ROOT/logs/start-all.pids"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"

if [[ ! -f "$PID_FILE" ]]; then
  echo "未找到 $PID_FILE（可能没有执行过 ./scripts/local/start-all.sh）"
  exit 0
fi

echo "==> 停止 Spring Boot 进程..."
while read -r name pid; do
  [[ -z "${pid:-}" ]] && continue
  if kill -0 "$pid" 2>/dev/null; then
    echo "  kill $name (pid=$pid)"
    kill "$pid" 2>/dev/null || true
  else
    echo "  跳过 $name (pid=$pid 不存在)"
  fi
done <"$PID_FILE"

rm -f "$PID_FILE"
echo "已清除 PID 文件。"

if [[ "${STOP_DOCKER:-}" == "1" ]]; then
  echo "==> Docker Compose 停止（STOP_DOCKER=1）..."
  cd "$ROOT" && docker compose --env-file "$COMPOSE_ENV_FILE" down
else
  echo "提示：仅停止 Java 进程；基础依赖仍在运行。停止容器请执行: docker compose down"
  echo "     或: COMPOSE_ENV_FILE=.env.test STOP_DOCKER=1 ./scripts/local/stop-all.sh"
fi
