#!/usr/bin/env bash
# ADR-sim 4day · P5 监控仪表盘:批量调度过程一屏看。
# 用法: bash 50-watch.sh            # 跑一次
#        bash 50-watch.sh --loop     # 每 20s 刷新(Ctrl-C 退)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SQL_DIR="$HERE/sql"
PQF(){ docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tA -f /dev/stdin 2>/dev/null; }
BQF(){ docker exec -i batch-postgres-primary psql -U batch_user -d batch_business -tA -f /dev/stdin 2>/dev/null; }

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
  echo -n "   "; docker exec batch-minio mc ls --recursive local/batch-dev/outbound/ 2>/dev/null | grep -ivE '\.keep' | wc -l | tr -d ' ';
  echo "── outbox 积压 / dead_letter"
  PQF < "$SQL_DIR/watch-outbox-deadletter.sql" | sed 's/^/   /'
  echo "── 最近失败(top5)"
  PQF < "$SQL_DIR/watch-recent-failures.sql" | sed 's/^/   ✗ /'
  echo "=================================================================="
}

if [ "${1:-}" = "--loop" ]; then
  while true; do clear; dash; sleep 20; done
else
  dash
fi
