#!/usr/bin/env bash
# ADR-sim 4day · P4 四天驱动:连续 4 个 bizDate 跑批,行数逐日递增(增量放大)。
# 用法: bash 41-run-4days.sh [startDate YYYY-MM-DD] [baseRows]
#   bash 41-run-4days.sh 2026-06-06 300
# 每天之间等 WAIT 秒(默认 150)让 worker 把当天的 import/export/dispatch/workflow 跑完。
set -uo pipefail
START="${1:-2026-06-06}"; BASE="${2:-300}"; WAIT="${WAIT:-150}"
HERE="$(cd "$(dirname "$0")" && pwd)"
nextday(){ python3 -c "import datetime as d;print((d.date.fromisoformat('$1')+d.timedelta(days=$2)).isoformat())"; }

echo "########## 4 天批量调度 起=$START 基准行=$BASE 每日间隔=${WAIT}s ##########"
# Day0 先投大文件一次(演示大文件摄取)
echo "==> Day0 预热:投放大文件到 MinIO ingress"
ROWS_BIG=${ROWS_BIG:-800000} bash "$HERE/30-gen-bigfiles.sh" "${START//-/}" || true

for d in 0 1 2 3; do
  BD=$(nextday "$START" "$d")
  ROWS=$(( BASE * (d+1) ))   # 逐日递增:300/600/900/1200
  echo; echo "########## ===== DAY $d  bizDate=$BD  rows/import=$ROWS ===== ##########"
  ROWS="$ROWS" bash "$HERE/40-run-day.sh" "$BD"
  echo "==> 等 ${WAIT}s 让 worker 跑完当天…"; sleep "$WAIT"
  bash "$HERE/50-watch.sh" || true
done
echo "########## 4 天驱动结束。用 bash 50-watch.sh --loop 持续观测 ##########"
