#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
OUT_DIR="${OUT_DIR:-$LOAD_DIR/target/worker-load-data}"
RUN_ID="${RUN_ID:-ltw-$(date +%Y%m%d%H%M%S)}"
BIZ_DATE="${BIZ_DATE:-2026-05-05}"
PROCESS_SOURCE_ROWS="${PROCESS_SOURCE_ROWS:-5000}"
PROCESS_ACCOUNT_COUNT="${PROCESS_ACCOUNT_COUNT:-500}"
PROCESS_EVENT_ID_START="${PROCESS_EVENT_ID_START:-$(($(date +%s) * 10000000))}"
PROCESS_ACCOUNT_WIDTH="${PROCESS_ACCOUNT_WIDTH:-$(n=$((PROCESS_ACCOUNT_COUNT - 1)); digits=${#n}; if [[ "$digits" -lt 4 ]]; then echo 4; else echo "$digits"; fi)}"
PROCESS_AGG_MAX_STAGED_ROWS="${PROCESS_AGG_MAX_STAGED_ROWS:-$((PROCESS_ACCOUNT_COUNT + 1000))}"
PROCESS_COPY_MAX_STAGED_ROWS="${PROCESS_COPY_MAX_STAGED_ROWS:-$((PROCESS_SOURCE_ROWS + 1000))}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-batch_user}"
PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
PLATFORM_DB="${PLATFORM_DB:-batch_platform}"
BUSINESS_DB="${BUSINESS_DB:-batch_business}"
export PGPASSWORD

mkdir -p "$OUT_DIR" /tmp/batch/load-test /tmp/batch/local-dispatch

write_import_csv() {
  local rows="$1"
  local file="$2"
  {
    printf 'customerNo,customerName,customerType\n'
    seq 1 "$rows" | awk '{printf "#{traceId}-IMP-%06d,Load Test Customer %06d,PERSONAL\n", $1, $1}'
  } > "$file"
}

write_import_params() {
  local csv="$1"
  local json="$2"
  local rows="$3"
  jq -n \
    --arg fileName "$(basename "$csv")" \
    --rawfile content "$csv" \
    --arg runId "$RUN_ID" \
    --argjson rows "$rows" \
    '{
      templateCode: "import_customer_v1",
      fileName: $fileName,
      originalFileName: $fileName,
      bizType: "LOAD_TEST",
      fileFormatType: "DELIMITED",
      charset: "UTF-8",
      delimiter: ",",
      headerRows: 1,
      withHeader: true,
      sourceType: "API",
      content: $content,
      metadata: {runId: $runId, expectedRows: $rows}
    }' > "$json"
}

write_import_csv 20 "$OUT_DIR/import-small.csv"
write_import_csv 1000 "$OUT_DIR/import-medium.csv"
write_import_csv 10000 "$OUT_DIR/import-large.csv"
write_import_params "$OUT_DIR/import-small.csv" "$OUT_DIR/import-small.params.json" 20
write_import_params "$OUT_DIR/import-medium.csv" "$OUT_DIR/import-medium.params.json" 1000
write_import_params "$OUT_DIR/import-large.csv" "$OUT_DIR/import-large.params.json" 10000

cat > "$OUT_DIR/export.params.json" <<JSON
{
  "templateCode": "export_settlement_v1",
  "batchNo": "${RUN_ID}-SETTLEMENT",
  "bizDate": "${BIZ_DATE}",
  "fileName": "#{traceId}-${RUN_ID}-settlement.csv",
  "targetPath": "exports/load-test/#{traceId}-${RUN_ID}-settlement.csv",
  "metadata": {"runId": "${RUN_ID}", "expectedRows": 5000}
}
JSON

cat > "$OUT_DIR/dispatch.params.json" <<JSON
{
  "fileCode": "${RUN_ID}-DISPATCH-FILE",
  "channelCode": "local_dispatch",
  "externalRequestId": "#{traceId}-${RUN_ID}-dispatch",
  "ackRequired": false,
  "metadata": {"runId": "${RUN_ID}", "expectedFiles": 1}
}
JSON

cat > "$OUT_DIR/process.params.json" <<JSON
{
  "bizDate": "${BIZ_DATE}",
  "batchKey": "#{traceId}-${RUN_ID}-process",
  "metadata": {"runId": "${RUN_ID}", "expectedRows": ${PROCESS_SOURCE_ROWS}, "expectedOutputRows": ${PROCESS_ACCOUNT_COUNT}, "benchmarkModule": "process-aggregate"}
}
JSON

cat > "$OUT_DIR/process-copy.params.json" <<JSON
{
  "bizDate": "${BIZ_DATE}",
  "batchKey": "#{traceId}-${RUN_ID}-process-copy",
  "metadata": {"runId": "${RUN_ID}", "expectedRows": ${PROCESS_SOURCE_ROWS}, "expectedOutputRows": ${PROCESS_SOURCE_ROWS}, "benchmarkModule": "process-copy"}
}
JSON

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business <<SQL
BEGIN;

SELECT setval(pg_get_serial_sequence('biz.settlement_batch', 'id'), COALESCE((SELECT max(id) FROM biz.settlement_batch), 1), true);
SELECT setval(pg_get_serial_sequence('biz.settlement_detail', 'id'), COALESCE((SELECT max(id) FROM biz.settlement_detail), 1), true);
SELECT setval(pg_get_serial_sequence('biz.customer_account', 'id'), COALESCE((SELECT max(id) FROM biz.customer_account), 1), true);

INSERT INTO biz.settlement_batch (
  tenant_id, batch_no, biz_date, accounting_period, snapshot_mode, snapshot_ts,
  consistency_policy, batch_status, total_record_count, total_amount, currency,
  created_by, updated_by, created_at, updated_at
) VALUES (
  'default-tenant', '${RUN_ID}-SETTLEMENT', DATE '${BIZ_DATE}', to_char(DATE '${BIZ_DATE}', 'YYYY-MM'),
  'BATCH', now(), 'EXPORT_SNAPSHOT', 'READY', 5000, 500000.00, 'CNY',
  'load-test', 'load-test', now(), now()
) ON CONFLICT (tenant_id, batch_no) DO UPDATE SET
  biz_date = EXCLUDED.biz_date,
  batch_status = 'READY',
  total_record_count = EXCLUDED.total_record_count,
  total_amount = EXCLUDED.total_amount,
  updated_at = now();

WITH b AS (
  SELECT id FROM biz.settlement_batch
  WHERE tenant_id = 'default-tenant' AND batch_no = '${RUN_ID}-SETTLEMENT'
)
INSERT INTO biz.settlement_detail (
  tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
  order_no, gross_amount, fee_amount, net_amount, currency, settlement_status,
  exported_version, source_trace_id, created_by, updated_by, created_at, updated_at
)
SELECT
  'default-tenant',
  b.id,
  '${RUN_ID}-SET-' || lpad(gs::text, 6, '0'),
  '${RUN_ID}-CUST-' || lpad((gs % 1000)::text, 4, '0'),
  DATE '${BIZ_DATE}',
  to_char(DATE '${BIZ_DATE}', 'YYYY-MM'),
  '${RUN_ID}-ORD-' || lpad(gs::text, 6, '0'),
  100.00,
  1.00,
  99.00,
  'CNY',
  'READY',
  0,
  '${RUN_ID}',
  'load-test',
  'load-test',
  now(),
  now()
FROM b, generate_series(1, 5000) gs
ON CONFLICT (tenant_id, settlement_no) DO UPDATE SET
  settlement_status = 'READY',
  updated_at = now();

CREATE TABLE IF NOT EXISTS biz.process_event_copy (
    tenant_id        VARCHAR(32)    NOT NULL,
    event_id         BIGINT         NOT NULL,
    account_id       VARCHAR(32)    NOT NULL,
    biz_date         DATE           NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL,
    high_water_mark  BIGINT         NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);

DELETE FROM biz.process_event_copy
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE '${RUN_ID}-ACCT-%';

DELETE FROM biz.process_account_summary
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE '${RUN_ID}-ACCT-%';

DELETE FROM biz.process_order_event
WHERE tenant_id = 'default-tenant'
  AND account_id LIKE '${RUN_ID}-ACCT-%';

INSERT INTO biz.process_order_event (tenant_id, account_id, biz_date, event_id, amount)
SELECT
  'default-tenant',
  '${RUN_ID}-ACCT-' || lpad((gs % ${PROCESS_ACCOUNT_COUNT})::text, ${PROCESS_ACCOUNT_WIDTH}, '0'),
  DATE '${BIZ_DATE}',
  ${PROCESS_EVENT_ID_START} + gs,
  (gs % 100 + 1)::numeric
FROM generate_series(0, ${PROCESS_SOURCE_ROWS} - 1) gs;

COMMIT;
SQL

DISPATCH_FILE="/tmp/batch/load-test/${RUN_ID}-dispatch.txt"
printf 'load-test-dispatch %s\n' "$RUN_ID" > "$DISPATCH_FILE"
DISPATCH_FILE_SIZE="$(wc -c < "$DISPATCH_FILE" | tr -d ' ')"

psql_platform <<SQL
BEGIN;

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type,
  schedule_type, timezone, priority, queue_code, worker_group,
  calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
  retry_policy, retry_max_count, timeout_seconds, enabled, version,
  description, created_by, updated_by, created_at, updated_at
) VALUES
  ('default-tenant', 'lt_dispatch_local_job', 'Load Test Dispatch Local', 'DISPATCH', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'dispatch_queue', 'DISPATCH',
   'default-calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 600, true, 1, 'local dispatch load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'lt_process_sql_job', 'Load Test Process SQL Aggregate', 'PROCESS', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
   'default-calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 900, true, 1, 'sql aggregate process load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'lt_process_copy_job', 'Load Test Process Staging Copy', 'PROCESS', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
   'default-calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 1800, true, 1, 'one source row to one staging row process load test job', 'load-test', 'load-test', now(), now())
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
  enabled = true,
  window_code = EXCLUDED.window_code,
  queue_code = EXCLUDED.queue_code,
  worker_group = EXCLUDED.worker_group,
  updated_at = now();

DELETE FROM batch.pipeline_step_definition
WHERE pipeline_definition_id IN (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code IN ('lt_process_sql_job', 'lt_process_copy_job')
);

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'lt_process_sql_job', 'Load Test Process SQL Pipeline',
    'PROCESS', 'LOAD_TEST', 'PROCESS', 1, true, 'load test sql transform pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_sql_job'
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_sql_job'
  ORDER BY id DESC
  LIMIT 1
)
INSERT INTO batch.pipeline_step_definition (
  pipeline_definition_id, step_code, step_name, stage_code, step_order,
  impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
  enabled, created_at, updated_at
)
SELECT id, 'PROCESS_PREPARE', 'Prepare', 'PREPARE', 1,
  'PROCESS_PREPARE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMPUTE', 'Compute', 'COMPUTE', 2,
  'sqlTransformCompute',
  jsonb_build_object('sqlTransformCompute', jsonb_build_object(
    'sourceSql',
    'select tenant_id, account_id, biz_date, sum(amount) as total_amount, max(event_id) as high_water_mark from biz.process_order_event where tenant_id = :tenantId and biz_date = :bizDate::date and account_id like ''${RUN_ID}-ACCT-%'' group by tenant_id, account_id, biz_date',
    'targetSchema', 'biz',
    'targetTable', 'process_account_summary',
    'writeMode', 'UPSERT',
    'columns', jsonb_build_array(
      jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
      jsonb_build_object('source', 'account_id', 'target', 'account_id'),
      jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
      jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
      jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')
    ),
    'conflictColumns', jsonb_build_array('tenant_id', 'account_id', 'biz_date'),
    'validations', jsonb_build_array(
      jsonb_build_object(
        'name', 'staged_rows_present',
        'checkSql', 'select count(*) > 0 as pass, ''expected staged rows'' as message from batch.process_staging where batch_key = :batchKey'
      )
    ),
    'emptyResultPolicy', 'FAIL',
    'maxStagedRows', ${PROCESS_AGG_MAX_STAGED_ROWS}
  )),
  600, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
  'PROCESS_VALIDATE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
  'PROCESS_COMMIT', '{}'::jsonb, 300, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
  'PROCESS_FEEDBACK', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd;

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'lt_process_copy_job', 'Load Test Process Staging Copy Pipeline',
    'PROCESS', 'LOAD_TEST', 'PROCESS', 1, true, 'load test one row to one staging row pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_copy_job'
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_copy_job'
  ORDER BY id DESC
  LIMIT 1
)
INSERT INTO batch.pipeline_step_definition (
  pipeline_definition_id, step_code, step_name, stage_code, step_order,
  impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
  enabled, created_at, updated_at
)
SELECT id, 'PROCESS_PREPARE', 'Prepare', 'PREPARE', 1,
  'PROCESS_PREPARE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMPUTE', 'Compute', 'COMPUTE', 2,
  'sqlTransformCompute',
  jsonb_build_object('sqlTransformCompute', jsonb_build_object(
    'sourceSql',
    'select tenant_id, event_id, account_id, biz_date, amount, event_id as high_water_mark from biz.process_order_event where tenant_id = :tenantId and biz_date = :bizDate::date and account_id like ''${RUN_ID}-ACCT-%''',
    'targetSchema', 'biz',
    'targetTable', 'process_event_copy',
    'writeMode', 'UPSERT',
    'columns', jsonb_build_array(
      jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
      jsonb_build_object('source', 'event_id', 'target', 'event_id'),
      jsonb_build_object('source', 'account_id', 'target', 'account_id'),
      jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
      jsonb_build_object('source', 'amount', 'target', 'amount'),
      jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')
    ),
    'conflictColumns', jsonb_build_array('tenant_id', 'event_id'),
    'validations', jsonb_build_array(
      jsonb_build_object(
        'name', 'staged_rows_present',
        'checkSql', 'select count(*) > 0 as pass, ''expected staged rows'' as message from batch.process_staging where batch_key = :batchKey'
      )
    ),
    'emptyResultPolicy', 'FAIL',
    'maxStagedRows', ${PROCESS_COPY_MAX_STAGED_ROWS}
  )),
  1200, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
  'PROCESS_VALIDATE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
  'PROCESS_COMMIT', '{}'::jsonb, 900, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
  'PROCESS_FEEDBACK', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd;

WITH existing AS (
  SELECT id FROM batch.file_record
  WHERE tenant_id = 'default-tenant' AND file_code = '${RUN_ID}-DISPATCH-FILE'
)
INSERT INTO batch.file_record (
  tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
  file_ext, file_format_type, charset, mime_type, file_size_bytes, checksum_type,
  checksum_value, storage_type, storage_path, storage_bucket, file_version,
  file_generation_no, is_latest, source_type, source_ref, file_status, biz_date,
  trace_id, metadata_json, created_at, updated_at
) SELECT
  'default-tenant', '${RUN_ID}-DISPATCH-FILE', 'LOAD_TEST', 'OUTPUT',
  '${RUN_ID}-dispatch.txt', '${RUN_ID}-dispatch.txt', 'txt', 'DELIMITED',
  'UTF-8', 'text/plain', ${DISPATCH_FILE_SIZE}, 'NONE', '${RUN_ID}-dispatch-checksum',
  'LOCAL', '${DISPATCH_FILE}', 'batch-dev', 'v1', 1, true, 'GENERATED',
  '${RUN_ID}', 'GENERATED', DATE '${BIZ_DATE}', '${RUN_ID}',
  jsonb_build_object('runId', '${RUN_ID}', 'loadTest', true), now(), now()
WHERE NOT EXISTS (SELECT 1 FROM existing);

UPDATE batch.file_record
SET file_status = 'GENERATED',
    storage_path = '${DISPATCH_FILE}',
    file_size_bytes = ${DISPATCH_FILE_SIZE},
    updated_at = now()
WHERE tenant_id = 'default-tenant' AND file_code = '${RUN_ID}-DISPATCH-FILE';

UPDATE batch.worker_registry
SET status = 'ONLINE',
    heartbeat_at = now(),
    updated_at = now(),
    drain_started_at = NULL,
    drain_deadline_at = NULL
WHERE tenant_id = 'default-tenant'
  AND worker_group = 'PROCESS'
  AND worker_code = 'process-node-1'
  AND status = 'DECOMMISSIONED';

COMMIT;
SQL

DISPATCH_FILE_ID="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -Atc "select id from batch.file_record where tenant_id='default-tenant' and file_code='${RUN_ID}-DISPATCH-FILE'")"
jq --arg fileId "$DISPATCH_FILE_ID" '. + {fileId: $fileId}' "$OUT_DIR/dispatch.params.json" > "$OUT_DIR/dispatch.params.tmp.json"
mv "$OUT_DIR/dispatch.params.tmp.json" "$OUT_DIR/dispatch.params.json"

cat > "$OUT_DIR/run.env" <<ENV
RUN_ID=${RUN_ID}
BIZ_DATE=${BIZ_DATE}
OUT_DIR=${OUT_DIR}
IMPORT_SMALL_PARAMS=${OUT_DIR}/import-small.params.json
IMPORT_MEDIUM_PARAMS=${OUT_DIR}/import-medium.params.json
IMPORT_LARGE_PARAMS=${OUT_DIR}/import-large.params.json
EXPORT_PARAMS=${OUT_DIR}/export.params.json
DISPATCH_PARAMS=${OUT_DIR}/dispatch.params.json
PROCESS_PARAMS=${OUT_DIR}/process.params.json
PROCESS_COPY_PARAMS=${OUT_DIR}/process-copy.params.json
PROCESS_SOURCE_ROWS=${PROCESS_SOURCE_ROWS}
PROCESS_ACCOUNT_COUNT=${PROCESS_ACCOUNT_COUNT}
PROCESS_ACCOUNT_WIDTH=${PROCESS_ACCOUNT_WIDTH}
PROCESS_EVENT_ID_START=${PROCESS_EVENT_ID_START}
PROCESS_AGG_MAX_STAGED_ROWS=${PROCESS_AGG_MAX_STAGED_ROWS}
PROCESS_COPY_MAX_STAGED_ROWS=${PROCESS_COPY_MAX_STAGED_ROWS}
ENV

echo "Prepared worker load-test data"
echo "RUN_ID=${RUN_ID}"
echo "OUT_DIR=${OUT_DIR}"
