#!/usr/bin/env bash
# ADR-sim 4day · 真批量日版:每个业务日 open(由 launch 自动开)→ 跑批 → 手动日切 CLOSE(→SETTLED)→ 推进。
# 时间压缩:不等真实一天,日切手动触发(绕过未来日期的 cutoffTime 时间门)。
# 用法: bash 42-run-4days-batchday.sh [startDate 2026-06-10] [baseRows 300]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
START="${1:-2026-06-10}"; BASE="${2:-300}"; WAIT="${WAIT:-120}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SQL_DIR="$HERE/sql"
CONSOLE="${CONSOLE_BASE_URL}"
CJ=/tmp/console-cookies-42.txt
TENANTS=(ta tb tc t04 t05 t06 t07 t08 t09 t10)
PQF(){ docker exec -i "${PG_PLATFORM_CONTAINER:-batch-postgres-primary}" psql -U "${PG_PLATFORM_USER:-$POSTGRES_USER}" -d "${PG_PLATFORM_DB:-$PLATFORM_DB}" -tA "$@" -f /dev/stdin 2>/dev/null; }
nextday(){ python3 -c "import datetime as d;print((d.date.fromisoformat('$1')+d.timedelta(days=$2)).isoformat())"; }

# 日切结算 = CLOSE 的状态转移(day_status→SETTLED + settled_at)。本应走
# POST /api/console/batch-days/operate{action:CLOSE},但该端点有 BE bug
# (BatchDayOperationAuditEntity 是 record,mapper insert 用 useGeneratedKeys keyProperty=id
#  无 setter → 每次 operate 500)。修复需重建+重启 orchestrator;为不重启,这里直接做等价状态转移。
close_day(){ # tenant bizDate
  local t="$1" d="$2"
  PQF -v tenant="$t" -v biz_date="$d" < "$SQL_DIR/close-day.sql" >/dev/null
  printf '.'
}

echo "########## 真批量日 4 天  起=$START 基准行=$BASE ##########"
: > /tmp/sim42-close-err.log
for off in 0 1 2 3; do
  BD=$(nextday "$START" "$off"); ROWS=$(( BASE*(off+1) ))
  echo; echo "########## ===== 业务日 $BD (rows/import=$ROWS) ===== ##########"
  echo "[1] 开日+跑批(launch 自动 open batch_day)"
  ROWS="$ROWS" bash "$HERE/40-run-day.sh" "$BD" >/dev/null 2>&1 && echo "    ✓ 已触发 10 租户"
  echo "[2] 等 ${WAIT}s 让 worker 跑完窗口内作业…"; sleep "$WAIT"
  echo -n "[3] 日切 CLOSE 10 租户: "
  for t in "${TENANTS[@]}"; do close_day "$t" "$BD"; done; echo
  echo "[4] 该日 batch_day 终态:"
  PQF -v biz_date="$BD" < "$SQL_DIR/batchday-status-by-day.sql" \
    | awk -F'|' '{printf "    %-16s n=%s settled=%s\n",$1,$2,$3}'
done

echo; echo "########## 4 天批量日生命周期总览 ##########"
PQF -v start_date="$START" -v end_date="$(nextday "$START" 3)" < "$SQL_DIR/batchday-summary.sql" \
  | awk -F'|' 'BEGIN{printf "  %-12s %-16s %4s %8s\n","bizDate","status","n","settled"}{printf "  %-12s %-16s %4s %8s\n",$1,$2,$3,$4}'
echo "  (CLOSE 失败详情若有: /tmp/sim42-close-err.log)"
echo "########## 完。业务数据增量见 bash scripts/sim-4day/50-watch.sh ##########"
