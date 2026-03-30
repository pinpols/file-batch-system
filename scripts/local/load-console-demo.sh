#!/usr/bin/env bash
# ============================================================
# load-console-demo.sh — 一键导入 / 一键还原 Console 演示数据
#
# 用法:
#   bash scripts/local/load-console-demo.sh          # 导入
#   bash scripts/local/load-console-demo.sh --reset   # 清空+导入（等同默认行为）
#   bash scripts/local/load-console-demo.sh --counts   # 只看各表行数
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SEED_SQL="${ROOT_DIR}/scripts/local/console-demo-seed.sql"

PG_HOST="${BATCH_PLATFORM_DB_HOST:-localhost}"
PG_PORT="${BATCH_PLATFORM_DB_PORT:-15432}"
PG_USER="${BATCH_PLATFORM_DB_USERNAME:-batch_user}"
PG_DB="${BATCH_PLATFORM_DB_NAME:-batch_platform}"
export PGPASSWORD="${BATCH_PLATFORM_DB_PASSWORD:-batch_pass_123}"

run_psql() {
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 "$@"
}

show_counts() {
  echo "──────────────────────────────────────────"
  echo "Table row counts (default-tenant):"
  echo "──────────────────────────────────────────"
  run_psql -t -A <<'SQL'
SELECT format('%-35s %s', t.tbl, coalesce(cnt::text, '0'))
FROM (VALUES
  ('job_definition'),('workflow_definition'),('trigger_request'),
  ('job_instance'),('job_partition'),('job_task'),
  ('job_step_instance'),('file_record'),('file_template_config'),
  ('file_channel_config'),('pipeline_definition'),('pipeline_instance'),
  ('pipeline_step_run'),('file_dispatch_record'),('file_audit_log'),
  ('file_error_record'),('outbox_event'),('event_outbox_retry'),
  ('event_delivery_log'),('workflow_run'),('workflow_node_run'),
  ('batch_day_instance'),('approval_command'),('alert_event'),
  ('dead_letter_task'),('retry_schedule'),('compensation_command'),
  ('worker_registry'),('job_execution_log'),('console_ai_audit_log'),
  ('config_release'),('secret_version'),('config_change_log'),
  ('resource_queue'),('tenant_quota_policy'),('batch_window'),
  ('business_calendar'),('quota_runtime_state'),
  ('tenant_scheduler_snapshot'),('file_channel_health')
) AS t(tbl)
LEFT JOIN LATERAL (
  SELECT count(*)::bigint AS cnt
  FROM batch_platform.batch.job_definition WHERE TRUE
) x ON FALSE
CROSS JOIN LATERAL (
  SELECT count(*) AS cnt FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'batch' AND c.relname = t.tbl AND c.relkind = 'r'
) tbl_exists
CROSS JOIN LATERAL (
  SELECT res AS cnt FROM (
    SELECT count(*) AS res
    FROM batch_platform.batch.job_definition WHERE t.tbl = 'job_definition'
  ) sub
) dummy
ORDER BY t.tbl;
SQL
  # simpler approach: just count each table
  run_psql -t <<'SQL'
DO $$
DECLARE
  tbl text;
  cnt bigint;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'job_definition','workflow_definition','trigger_request',
    'job_instance','job_partition','job_task',
    'job_step_instance','file_record','file_template_config',
    'file_channel_config','pipeline_definition','pipeline_instance',
    'pipeline_step_run','file_dispatch_record','file_audit_log',
    'file_error_record','outbox_event','event_outbox_retry',
    'event_delivery_log','workflow_run','workflow_node_run',
    'batch_day_instance','approval_command','alert_event',
    'dead_letter_task','retry_schedule','compensation_command',
    'worker_registry','job_execution_log','console_ai_audit_log',
    'config_release','secret_version','config_change_log',
    'resource_queue','tenant_quota_policy','batch_window',
    'business_calendar','quota_runtime_state',
    'tenant_scheduler_snapshot','file_channel_health'
  ] LOOP
    EXECUTE format('SELECT count(*) FROM batch.%I', tbl) INTO cnt;
    RAISE NOTICE '%-35s %s', tbl, cnt;
  END LOOP;
END $$;
SQL
}

case "${1:-}" in
  --counts)
    show_counts
    exit 0
    ;;
  *)
    echo "=== Loading console demo seed into ${PG_HOST}:${PG_PORT}/${PG_DB} ==="
    run_psql -f "$SEED_SQL"
    echo ""
    echo "=== Done. Row counts: ==="
    show_counts
    ;;
esac
