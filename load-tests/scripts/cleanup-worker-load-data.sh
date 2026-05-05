#!/usr/bin/env bash
set -euo pipefail

RUN_ID="${RUN_ID:?RUN_ID is required, for example RUN_ID=ltw-20260505093000}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-batch_user}"
PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
PLATFORM_DB="${PLATFORM_DB:-batch_platform}"
BUSINESS_DB="${BUSINESS_DB:-batch_business}"
export PGPASSWORD

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_platform <<SQL
BEGIN;

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
),
tasks AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.job_step_instance WHERE job_task_id IN (SELECT id FROM tasks);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.workflow_run WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
DELETE FROM batch.compensation_command WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
    AND (
      trace_id LIKE '${RUN_ID}%'
      OR params_snapshot::text LIKE '%${RUN_ID}%'
      OR batch_no = '${RUN_ID}-SETTLEMENT'
    )
)
UPDATE batch.trigger_request
SET related_job_instance_id = NULL
WHERE related_job_instance_id IN (SELECT id FROM ji);

DELETE FROM batch.job_instance
WHERE tenant_id = 'default-tenant'
  AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job')
  AND (
    trace_id LIKE '${RUN_ID}%'
    OR params_snapshot::text LIKE '%${RUN_ID}%'
    OR batch_no = '${RUN_ID}-SETTLEMENT'
  );

DELETE FROM batch.trigger_request
WHERE tenant_id = 'default-tenant'
  AND (
    request_id LIKE '%${RUN_ID}%'
    OR dedup_key LIKE '%${RUN_ID}%'
    OR trace_id LIKE '%${RUN_ID}%'
  );

DELETE FROM batch.file_dispatch_record
WHERE tenant_id = 'default-tenant'
  AND (external_request_id LIKE '%${RUN_ID}%' OR file_id IN (
    SELECT id FROM batch.file_record WHERE tenant_id = 'default-tenant' AND metadata_json::text LIKE '%${RUN_ID}%'
  ));

DELETE FROM batch.file_audit_log
WHERE tenant_id = 'default-tenant'
  AND file_id IN (
    SELECT id FROM batch.file_record
    WHERE tenant_id = 'default-tenant'
      AND (file_code LIKE '${RUN_ID}%' OR metadata_json::text LIKE '%${RUN_ID}%')
  );

DELETE FROM batch.file_record
WHERE tenant_id = 'default-tenant'
  AND (file_code LIKE '${RUN_ID}%' OR metadata_json::text LIKE '%${RUN_ID}%');

COMMIT;
SQL

psql_business <<SQL
BEGIN;
DELETE FROM biz.customer_account
WHERE tenant_id = 'default-tenant' AND customer_no LIKE '${RUN_ID}-IMP-%';
DELETE FROM biz.settlement_detail
WHERE tenant_id = 'default-tenant' AND settlement_no LIKE '${RUN_ID}-SET-%';
DELETE FROM biz.settlement_batch
WHERE tenant_id = 'default-tenant' AND batch_no = '${RUN_ID}-SETTLEMENT';
DELETE FROM biz.process_account_summary
WHERE tenant_id = 'default-tenant' AND account_id LIKE 'LTACCT-%';
DELETE FROM biz.process_order_event
WHERE tenant_id = 'default-tenant' AND event_id BETWEEN 9100000000 AND 9100004999;
COMMIT;
SQL

rm -rf "/tmp/batch/load-test/${RUN_ID}-dispatch.txt"
rm -f /tmp/batch/local-dispatch/*"${RUN_ID}"* 2>/dev/null || true

echo "Cleaned worker load-test data for RUN_ID=${RUN_ID}"
