#!/usr/bin/env bash
# ADR-sim 4day · P5 监控仪表盘:批量调度过程一屏看。
# 用法: bash 50-watch.sh            # 跑一次
#        bash 50-watch.sh --loop     # 每 20s 刷新(Ctrl-C 退)
set -uo pipefail
PQ(){ docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tA -c "$1" 2>/dev/null; }
BQ(){ docker exec -i batch-postgres-primary psql -U batch_user -d batch_business -tA -c "$1" 2>/dev/null; }

dash() {
  echo "================ SIM 4day 仪表盘  $(date '+%F %T') ================"
  echo "── 各租户 job_instance 状态(近 4 天)"
  PQ "select tenant_id,
        sum((instance_status='SUCCESS')::int) succ,
        sum((instance_status='RUNNING')::int) run,
        sum((instance_status='FAILED')::int) fail,
        count(*) tot
      from batch.job_instance
      where tenant_id in ('ta','tb','tc','t04','t05','t06','t07','t08','t09','t10')
      group by tenant_id order by tenant_id" \
    | awk -F'|' 'BEGIN{printf "   %-5s %6s %6s %6s %6s\n","tenant","succ","run","fail","total"}{printf "   %-5s %6s %6s %6s %6s\n",$1,$2,$3,$4,$5}'
  echo "── pipeline_instance 按类型×状态"
  PQ "select pipeline_type, run_status, count(*) from batch.pipeline_instance group by pipeline_type,run_status order by 1,2" \
    | awk -F'|' '{printf "   %-10s %-10s %s\n",$1,$2,$3}'
  echo "── biz 业务数据行数(增量增长)"
  BQ "select 'customer_account',count(*) from biz.customer_account union all select 'transaction',count(*) from biz.transaction union all select 'risk_score',count(*) from biz.risk_score" \
    | awk -F'|' '{printf "   %-18s %s\n",$1,$2}'
  echo "── 导出文件(MinIO outbound)"
  echo -n "   "; docker exec batch-minio mc ls --recursive local/batch-dev/outbound/ 2>/dev/null | grep -ivE '\.keep' | wc -l | tr -d ' ';
  echo "── outbox 积压 / dead_letter"
  PQ "select 'outbox_pending='||count(*) from batch.outbox_event where publish_status<>'PUBLISHED' union all select 'dead_letter='||count(*) from batch.dead_letter_task" | sed 's/^/   /'
  echo "── 最近失败(top5)"
  PQ "select tenant_id||' '||coalesce(error_code,'?')||' '||left(coalesce(error_message,''),60) from batch.pipeline_step_run where step_status='FAILED' order by id desc limit 5" | sed 's/^/   ✗ /'
  echo "=================================================================="
}

if [ "${1:-}" = "--loop" ]; then
  while true; do clear; dash; sleep 20; done
else
  dash
fi
