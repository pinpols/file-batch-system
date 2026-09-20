#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"
RUN_ID="${RUN_ID:-ltw-$(date +%Y%m%d%H%M%S)}"
OUT_DIR="${OUT_DIR:-$LOAD_DIR/target/worker-load-data/$RUN_ID}"
BIZ_DATE="${BIZ_DATE:-2026-05-05}"
PROCESS_SOURCE_ROWS="${PROCESS_SOURCE_ROWS:-5000}"
PROCESS_ACCOUNT_COUNT="${PROCESS_ACCOUNT_COUNT:-500}"
if [[ -z "${PROCESS_EVENT_ID_START:-}" ]]; then
  RUN_ID_CHECKSUM="$(printf '%s' "$RUN_ID" | cksum | awk '{print $1}')"
  PROCESS_EVENT_ID_START="$((RUN_ID_CHECKSUM * 1000000000))"
fi
PROCESS_ACCOUNT_WIDTH="${PROCESS_ACCOUNT_WIDTH:-$(n=$((PROCESS_ACCOUNT_COUNT - 1)); digits=${#n}; if [[ "$digits" -lt 4 ]]; then echo 4; else echo "$digits"; fi)}"
PROCESS_AGG_MAX_STAGED_ROWS="${PROCESS_AGG_MAX_STAGED_ROWS:-$((PROCESS_ACCOUNT_COUNT + 1000))}"
PROCESS_COPY_MAX_STAGED_ROWS="${PROCESS_COPY_MAX_STAGED_ROWS:-$((PROCESS_SOURCE_ROWS + 1000))}"
DISPATCH_FIXTURE_COUNT="${DISPATCH_FIXTURE_COUNT:-1}"

if [[ ! "$DISPATCH_FIXTURE_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  echo "DISPATCH_FIXTURE_COUNT must be a positive integer" >&2
  exit 2
fi

validate_load_test_run_id "$RUN_ID"

mkdir -p "$OUT_DIR" /tmp/batch/load-test /tmp/batch/local-dispatch

write_import_csv() {
  local rows="$1"
  local file="$2"
  {
    printf 'customerNo,customerName,customerType\n'
    seq 1 "$rows" | awk -v run_id="$RUN_ID" '{printf "#{traceId}-IMP-%06d,Load Test Customer %s %06d,PERSONAL\n", $1, run_id, $1}'
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

psql_business \
  -v run_id="$RUN_ID" \
  -v biz_date="$BIZ_DATE" \
  -v process_account_count="$PROCESS_ACCOUNT_COUNT" \
  -v process_account_width="$PROCESS_ACCOUNT_WIDTH" \
  -v process_event_id_start="$PROCESS_EVENT_ID_START" \
  -v process_source_rows="$PROCESS_SOURCE_ROWS" \
  -f "$LOAD_DIR/sql/prepare-worker-load-business.sql"

DISPATCH_DIR="/tmp/batch/load-test"
for ((fixture_no = 1; fixture_no <= DISPATCH_FIXTURE_COUNT; fixture_no++)); do
  printf -v fixture_suffix '%06d' "$fixture_no"
  printf 'load-test-dispatch %s fixture=%s\n' "$RUN_ID" "$fixture_suffix" \
    > "$DISPATCH_DIR/${RUN_ID}-dispatch-${fixture_suffix}.txt"
done
DISPATCH_FILE_SIZE="$(wc -c < "$DISPATCH_DIR/${RUN_ID}-dispatch-000001.txt" | tr -d ' ')"

psql_platform \
  -v run_id="$RUN_ID" \
  -v biz_date="$BIZ_DATE" \
  -v dispatch_dir="$DISPATCH_DIR" \
  -v dispatch_file_size="$DISPATCH_FILE_SIZE" \
  -v dispatch_fixture_count="$DISPATCH_FIXTURE_COUNT" \
  -v process_agg_max_staged_rows="$PROCESS_AGG_MAX_STAGED_ROWS" \
  -v process_copy_max_staged_rows="$PROCESS_COPY_MAX_STAGED_ROWS" \
  -f "$LOAD_DIR/sql/prepare-worker-load-platform.sql"

DISPATCH_FILE_IDS_CSV="$(psql_platform -At -v run_id="$RUN_ID" -f "$LOAD_DIR/sql/select-worker-load-dispatch-file-id.sql")"
if [[ -z "$DISPATCH_FILE_IDS_CSV" ]]; then
  echo "No dispatch fixture file IDs were created" >&2
  exit 1
fi
jq --arg fileId '#{fileId}' '. + {fileId: $fileId}' "$OUT_DIR/dispatch.params.json" > "$OUT_DIR/dispatch.params.tmp.json"
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
DISPATCH_FILE_IDS_CSV=${DISPATCH_FILE_IDS_CSV}
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
