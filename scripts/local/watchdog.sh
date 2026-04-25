#!/usr/bin/env bash
# =============================================================
# watchdog.sh — 本地 java -jar 模式下的 worker 进程守护
#
# 解决场景：
#   start-all.sh 用 java -jar 启动 worker；macOS 长时间闲置（数小时）后系统会
#   把闲置的 JVM 进程回收（OOM killer / energy saver），导致 worker 静默消失。
#   docker-compose 模式靠 docker 自带 restart 不会有这个问题；本地脚本模式没有
#   这层兜底，本脚本补上。
#
# 行为：
#   - 每 :interval_seconds (默认 30s) 检查一次：trigger / orchestrator / console-api /
#     worker-import / worker-export / worker-dispatch 6 个进程是否存在。
#   - 缺失则自动调用 scripts/local/restart.sh 把缺失的拉起来。
#   - 持续运行，Ctrl+C 退出（不是 daemon）。
#
# 用法：
#   ./scripts/local/watchdog.sh                # 默认 30s 检查
#   INTERVAL=10 ./scripts/local/watchdog.sh    # 10s 一次
#
# 推荐：在另一个 terminal tab 跑这个，长时间联调期保持开着。
# =============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INTERVAL="${INTERVAL:-30}"

# 监控的 6 个模块（与 jar 名匹配）
MODULES=(trigger orchestrator console worker-import worker-export worker-dispatch)

is_running() {
  local mod="$1"
  jps -l 2>/dev/null | grep -q "build/runtime-jars/${mod}.jar" && return 0 || return 1
}

restart_missing() {
  local missing=()
  for m in "${MODULES[@]}"; do
    if ! is_running "$m"; then
      missing+=("$m")
    fi
  done
  if [ "${#missing[@]}" -eq 0 ]; then
    return 0
  fi
  echo "$(date '+%Y-%m-%d %H:%M:%S') [watchdog] 检测到掉线模块: ${missing[*]}; 调 restart.sh 重启"
  "$ROOT/scripts/local/restart.sh" "${missing[@]}" || \
    echo "$(date '+%Y-%m-%d %H:%M:%S') [watchdog] restart 失败，下轮再试"
}

echo "=== watchdog 启动，每 ${INTERVAL}s 检查一次 ${MODULES[*]} ==="
echo "Ctrl+C 退出"
while true; do
  restart_missing
  sleep "$INTERVAL"
done
