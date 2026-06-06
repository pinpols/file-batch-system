#!/usr/bin/env bash
# ADR-sim 4day · P0 清空脏数据(保留所有 config)
# - 平台库 batch.* 运行时表:显式 allowlist + CASCADE(config 永远是 FK 父表,不会被截断)
# - 业务库 batch_business 的 biz.* 数据表
# - MinIO batch-dev/ 全清后重建 outbound prefix
# - mockserver 仅清请求日志(保留 expectations)
# 跑前/跑后断言 config 行数不变,防误删。
set -euo pipefail

PG=batch-postgres-primary
PGU=batch_user
MINIO=batch-minio
MC_ALIAS=local
BUCKET=batch-dev

psql_plat() { docker exec -i "$PG" psql -U "$PGU" -d batch_platform -v ON_ERROR_STOP=1 "$@"; }
psql_biz()  { docker exec -i "$PG" psql -U "$PGU" -d batch_business -v ON_ERROR_STOP=1 "$@"; }

echo "==> 0/4 config 基线快照(截断后须不变)"
CFG_BEFORE=$(psql_plat -tAc "select
  (select count(*) from batch.tenant)||'/'||
  (select count(*) from batch.job_definition)||'/'||
  (select count(*) from batch.pipeline_definition)||'/'||
  (select count(*) from batch.file_template_config)||'/'||
  (select count(*) from batch.file_channel_config)||'/'||
  (select count(*) from batch.workflow_definition)")
echo "    tenant/job/pipeline/template/channel/workflow = $CFG_BEFORE"

echo "==> 1/4 截断平台运行时表(batch.*,CASCADE)"
psql_plat <<'SQL'
TRUNCATE TABLE
  batch.file_record, batch.file_audit_log, batch.file_error_record,
  batch.file_dispatch_record, batch.file_channel_health,
  batch.pipeline_instance, batch.pipeline_step_run, batch.pipeline_progress,
  batch.job_instance, batch.job_partition, batch.job_task,
  batch.job_step_instance, batch.job_execution_log,
  batch.workflow_run, batch.workflow_node_run,
  batch.outbox_event, batch.event_delivery_log, batch.event_outbox_retry,
  batch.worker_report_outbox,
  batch.trigger_outbox_event, batch.trigger_request,
  batch.trigger_misfire_pending, batch.trigger_runtime_state,
  batch.batch_day_instance, batch.batch_day_operation_audit,
  batch.batch_day_replay_entry, batch.batch_day_replay_session,
  batch.batch_day_waiting_launch,
  batch.dead_letter_task, batch.retry_schedule,
  batch.compensation_command, batch.compensation_checkpoint,
  batch.approval_command, batch.idempotency_record, batch.result_version,
  batch.alert_event, batch.notification_delivery_log, batch.webhook_delivery_log,
  batch.console_push_approval_notification, batch.console_push_job_notification,
  batch.quota_runtime_state, batch.tenant_scheduler_snapshot,
  batch.data_quality_check, batch.forensic_export_log,
  batch.console_operation_audit, batch.console_ai_audit_log,
  batch.process_staging, batch.shedlock
RESTART IDENTITY CASCADE;
SQL
echo "    平台运行时表已截断"

echo "==> 2/4 截断业务库 biz.* 数据表"
psql_biz <<'SQL'
TRUNCATE TABLE
  biz.customer_account, biz.customer_processed, biz.transaction, biz.risk_score,
  biz.settlement_batch, biz.settlement_detail, biz.risk_alert,
  biz.process_order_event, biz.process_account_summary
RESTART IDENTITY CASCADE;
SQL
echo "    biz.* 已截断"

echo "==> 3/4 清 MinIO bucket + 重建 outbound prefix"
docker exec "$MINIO" mc alias set "$MC_ALIAS" http://localhost:9000 minioadmin minioadmin123 >/dev/null 2>&1 || true
docker exec "$MINIO" mc rm --recursive --force "$MC_ALIAS/$BUCKET/" >/dev/null 2>&1 || true
for p in ingress ta/outbound/report tb/outbound/statement tc/outbound/risk-alert; do
  echo "init" | docker exec -i "$MINIO" mc pipe "$MC_ALIAS/$BUCKET/$p/.keep" >/dev/null 2>&1 || true
done
echo "    MinIO 已清空并重建 prefix"

echo "==> 4/4 mockserver 清请求日志(保留 expectations)"
curl -s -X PUT "http://localhost:11080/mockserver/clear?type=LOG" -H 'content-type: application/json' -d '{}' >/dev/null 2>&1 \
  && echo "    mockserver 日志已清" || echo "    mockserver 未响应(跳过,P4 前会重载 stub)"

echo "==> 校验:config 不变 + 运行时归零"
CFG_AFTER=$(psql_plat -tAc "select
  (select count(*) from batch.tenant)||'/'||
  (select count(*) from batch.job_definition)||'/'||
  (select count(*) from batch.pipeline_definition)||'/'||
  (select count(*) from batch.file_template_config)||'/'||
  (select count(*) from batch.file_channel_config)||'/'||
  (select count(*) from batch.workflow_definition)")
echo "    config after = $CFG_AFTER"
[ "$CFG_BEFORE" = "$CFG_AFTER" ] && echo "    ✅ config 保留完好" || { echo "    ❌ config 行数变了!BEFORE=$CFG_BEFORE AFTER=$CFG_AFTER"; exit 1; }
psql_plat -tAc "select 'runtime残留 file_record='||count(*) from batch.file_record union all select 'pipeline_instance='||count(*) from batch.pipeline_instance union all select 'job_instance='||count(*) from batch.job_instance union all select 'outbox_event='||count(*) from batch.outbox_event union all select 'pipeline_step_run='||count(*) from batch.pipeline_step_run"
echo "==> P0 清空完成"
