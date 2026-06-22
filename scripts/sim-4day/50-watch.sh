#!/usr/bin/env bash
# ADR-sim 4day · P5 监控仪表盘:批量调度过程一屏看。
# 用法: bash 50-watch.sh            # 跑一次
#        bash 50-watch.sh --loop     # 每 20s 刷新(Ctrl-C 退)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
# shellcheck source=scripts/lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
SQL_DIR="$HERE/sql"
SIM4DAY_LOG_DIR="${SIM4DAY_LOG_DIR:-$(log_run_dir "$ROOT" sim-4day sim-4day-watch)}"
log_link_dir "$ROOT" sim-4day "$SIM4DAY_LOG_DIR"
MINIO_CONTAINER="${MINIO_CONTAINER:-batch-minio}"
MC_ALIAS="${MC_ALIAS:-local}"
PQF(){ docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tA -f /dev/stdin 2>/dev/null; }
BQF(){ docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" -tA -f /dev/stdin 2>/dev/null; }

dash() {
  echo "================ SIM 4day 仪表盘  $(date '+%F %T') ================"
  echo "── 各租户 job_instance 状态(近 4 天)"
  PQF < "$SQL_DIR/watch-job-instance-status.sql" \
    | awk -F'|' 'BEGIN{printf "   %-5s %6s %6s %6s %6s\n","tenant","succ","run","fail","total"}{printf "   %-5s %6s %6s %6s %6s\n",$1,$2,$3,$4,$5}'
  echo "── pipeline_instance 按类型×状态"
  PQF < "$SQL_DIR/watch-pipeline-status.sql" \
    | awk -F'|' '{printf "   %-10s %-10s %s\n",$1,$2,$3}'
  echo "── biz 业务数据行数(增量增长)"
  BQF < "$SQL_DIR/watch-business-counts.sql" \
    | awk -F'|' '{printf "   %-18s %s\n",$1,$2}'
  echo "── 导出文件(MinIO outbound)"
  echo -n "   "; docker exec "$MINIO_CONTAINER" mc ls --recursive "$MC_ALIAS/$BATCH_S3_BUCKET/outbound/" 2>/dev/null | grep -ivE '\.keep' | wc -l | tr -d ' ';
  echo "── outbox 积压 / dead_letter"
  PQF < "$SQL_DIR/watch-outbox-deadletter.sql" | sed 's/^/   /'
  echo "── 最近失败(top5)"
  PQF < "$SQL_DIR/watch-recent-failures.sql" | sed 's/^/   ✗ /'
  echo "=================================================================="
}

if [ "${1:-}" = "--loop" ]; then
  while true; do clear; dash; sleep 20; done
else
  if [[ "${SIM4DAY_CAPTURED:-0}" == "1" ]]; then
    dash
  else
    dash | tee "$SIM4DAY_LOG_DIR/watch-$(date +%Y%m%d-%H%M%S).log"
  fi
fi
