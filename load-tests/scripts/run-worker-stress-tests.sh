#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
MAX_ERROR_PCT_OVERRIDE="${MAX_ERROR_PCT:-}"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"
IMPORT_PROFILE="${IMPORT_PROFILE:-medium}"
STEPS_CSV="${STEPS_CSV:-1,2,4,8}"
RAMP_SECONDS="${RAMP_SECONDS:-5}"
PIPELINE_MAX_POLLS="${PIPELINE_MAX_POLLS:-1}"
PIPELINE_POLL_INTERVAL_SEC="${PIPELINE_POLL_INTERVAL_SEC:-2}"
WAIT_TERMINAL_TIMEOUT_SECONDS="${WAIT_TERMINAL_TIMEOUT_SECONDS:-240}"
STRICT="${STRICT:-0}"
if [[ "$STRICT" != "0" && "$STRICT" != "1" ]]; then
  echo "STRICT must be 0 or 1" >&2
  exit 2
fi
if [[ "$STRICT" == "1" && -z "$MAX_ERROR_PCT_OVERRIDE" ]]; then
  # GatlingConfig 对零阈值使用失败数断言，严格模式可以真正要求零失败。
  MAX_ERROR_PCT="0.0"
fi

RUN_ID="${RUN_ID:-ltw-stress-$(date +%Y%m%d%H%M%S)}"
OUT_DIR="${OUT_DIR:-$LOAD_DIR/target/worker-load-data/$RUN_ID}"
IFS=',' read -r -a STEPS <<< "$STEPS_CSV"
DISPATCH_FIXTURE_COUNT=0
for step_users in "${STEPS[@]}"; do
  step_users="$(echo "$step_users" | xargs)"
  if [[ ! "$step_users" =~ ^[1-9][0-9]*$ ]]; then
    echo "Each STEPS_CSV value must be a positive integer: ${step_users}" >&2
    exit 2
  fi
  DISPATCH_FIXTURE_COUNT=$((DISPATCH_FIXTURE_COUNT + step_users))
done
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB DISPATCH_FIXTURE_COUNT OUT_DIR

# 同 run-worker-load-tests.sh 的 EXIT trap：压测产物按 RUN_ID 全清，避免历史 dead_letter 累积。
SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
on_exit_cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place for inspection"
    exit $rc
  fi
  echo "Auto-cleanup RUN_ID=${RUN_ID} ..." >&2
  RUN_ID="$RUN_ID" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2 || \
    echo "WARN: cleanup failed for RUN_ID=${RUN_ID}, run manually: RUN_ID=${RUN_ID} bash $LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2
  exit $rc
}
trap on_exit_cleanup EXIT

"$LOAD_DIR/scripts/prepare-worker-load-data.sh"
# shellcheck disable=SC1090
source "$OUT_DIR/run.env"
IFS=',' read -r -a DISPATCH_FILE_IDS <<< "$DISPATCH_FILE_IDS_CSV"
if [[ "${#DISPATCH_FILE_IDS[@]}" -ne "$DISPATCH_FIXTURE_COUNT" ]]; then
  echo "Expected ${DISPATCH_FIXTURE_COUNT} dispatch fixtures, got ${#DISPATCH_FILE_IDS[@]}" >&2
  exit 1
fi

case "$IMPORT_PROFILE" in
  small) IMPORT_PARAMS="$IMPORT_SMALL_PARAMS" ;;
  medium) IMPORT_PARAMS="$IMPORT_MEDIUM_PARAMS" ;;
  large) IMPORT_PARAMS="$IMPORT_LARGE_PARAMS" ;;
  *) echo "IMPORT_PROFILE must be small, medium, or large" >&2; exit 2 ;;
esac

# Kafka 的默认 request.max.bytes 约为 1 MiB；Trigger/Kafka 外层 JSON 还会
# 额外增加 envelope 和转义开销。large profile 仍是 inline payload，达到
# 800 KB 参数文件时就应明确提示改用对象存储导入，而不是把容量边界
# 伪装成 worker 失败并留下 ACCEPTED/outbox 垃圾数据。
IMPORT_PARAMS_BYTES="$(wc -c < "$IMPORT_PARAMS" | awk '{print $1}')"
if [[ "$IMPORT_PROFILE" == "large" && "${IMPORT_PARAMS_BYTES:-0}" -ge 800000 ]]; then
  echo "IMPORT_PROFILE=large is an inline payload of ${IMPORT_PARAMS_BYTES} bytes and exceeds the Kafka request safety budget." >&2
  echo "Use the object-backed import scenario for large files; use IMPORT_PROFILE=medium for inline stress tests." >&2
  exit 2
fi

LOGIN_RESPONSE="$(
  curl -i -fsS -X POST "$CONSOLE_BASE_URL/api/console/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' \
)"
TOKEN="$(printf '%s\n' "$LOGIN_RESPONSE" | tr -d '\r' | sed -n 's/^Set-Cookie: batch_console_token=\([^;]*\).*/\1/p' | head -1)"
if [[ -z "$TOKEN" ]]; then
  TOKEN="$(printf '%s\n' "$LOGIN_RESPONSE" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' | head -1)"
fi

if [[ -z "$TOKEN" ]]; then
  echo "Failed to acquire console token" >&2
  exit 1
fi

REPORT="$LOAD_DIR/target/worker-stress-report-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/worker-stress-logs/${RUN_ID}"
mkdir -p "$LOG_DIR"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

run_one() {
  local label="$1"
  local job_code="$2"
  local params_file="$3"
  local users="$4"
  local file_ids_csv="${5:-}"
  local log_file="$LOG_DIR/${label}-u${users}.log"
  local -a extra_args=()
  if [[ -n "$file_ids_csv" ]]; then
    extra_args+=("-Dlaunch.fileIdsCsv=${file_ids_csv}")
  fi

  echo "==> stress ${label}: users=${users}, job=${job_code}"
  local -a pipeline_status=()
  set +e
  (
    cd "$LOAD_DIR"
    mvn gatling:test \
      -Dsimulation=io.github.pinpols.batch.loadtest.simulations.LaunchPipelineCompletionSimulation \
      -Dtrigger.baseUrl="$TRIGGER_BASE_URL" \
      -Dconsole.baseUrl="$CONSOLE_BASE_URL" \
      -Dorchestrator.baseUrl="$ORCHESTRATOR_BASE_URL" \
      -Dinternal.secret="$INTERNAL_SECRET" \
      -DtenantId="$LOAD_TEST_TENANT_ID" \
      -DjobCode="$job_code" \
      -DbizDate="$BIZ_DATE" \
      -Dlaunch.paramsJsonFile="$params_file" \
      -Dpipeline.completion.users="$users" \
      -Dramp.seconds="$RAMP_SECONDS" \
      -Dpipeline.maxPolls="$PIPELINE_MAX_POLLS" \
      -Dpipeline.pollIntervalSec="$PIPELINE_POLL_INTERVAL_SEC" \
      -Dslo.maxErrorPct="$MAX_ERROR_PCT" \
      -Dconsole.accessToken="$TOKEN" \
      "${extra_args[@]}" \
      --batch-mode
  ) | tee "$log_file"
  pipeline_status=("${PIPESTATUS[@]}")
  set -e

  if [[ "${pipeline_status[0]}" -ne 0 ]]; then
    echo "==> stress ${label}: Gatling failed with exit code ${pipeline_status[0]}" >&2
    return "${pipeline_status[0]}"
  fi
  if [[ "${pipeline_status[1]}" -ne 0 ]]; then
    echo "==> stress ${label}: log capture failed with exit code ${pipeline_status[1]}" >&2
    return "${pipeline_status[1]}"
  fi

  local elapsed=0
  while [[ "$elapsed" -lt "$WAIT_TERMINAL_TIMEOUT_SECONDS" ]]; do
    local counts
    counts="$(
      psql_platform -tA -v tenant_id="$LOAD_TEST_TENANT_ID" \
        -v job_code="$job_code" -v run_id="$RUN_ID" -v stress_users="$users" \
        -f "$LOAD_DIR/sql/worker-stress-terminal-counts.sql"
    )"
    local total terminal success
    IFS='|' read -r total terminal success <<< "$counts"
    total="${total:-0}"
    terminal="${terminal:-0}"
    success="${success:-0}"
    if [[ "$total" -ge "$users" && "$terminal" -eq "$total" ]]; then
      echo "==> stress ${label}: terminal ${terminal}/${total}, success ${success}/${total}"
      if [[ "$success" -ne "$total" ]]; then
        echo "==> stress ${label}: ${total}-${success} instance(s) ended without SUCCESS" >&2
        return 1
      fi
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "==> stress ${label}: timeout waiting for terminal instances" >&2
  return 1
}

write_step_params() {
  local src="$1"
  local dst="$2"
  local users="$3"
  jq --argjson users "$users" '.metadata = ((.metadata // {}) + {stressUsers: $users})' "$src" > "$dst"
}

{
  echo "# Worker Stress Test Report - ${RUN_ID}"
  echo
  echo "- Time UTC start: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Steps: ${STEPS_CSV}"
  echo "- Import profile: ${IMPORT_PROFILE}"
  echo "- Strict mode: ${STRICT}"
  echo "- Maximum error percentage: ${MAX_ERROR_PCT}"
  echo "- Logs: ${LOG_DIR}"
  echo
} > "$REPORT"

failures=()
step_results=()
run_and_record() {
  local label="$1"
  local job_code="$2"
  local params_file="$3"
  local users="$4"
  local file_ids_csv="${5:-}"
  local rc=0
  if run_one "$label" "$job_code" "$params_file" "$users" "$file_ids_csv"; then
    step_results+=("${label}=PASS")
  else
    rc=$?
    step_results+=("${label}=FAIL(${rc})")
    failures+=("users=${users}:${label}:${rc}")
    if ((rc >= 128)); then
      return "$rc"
    fi
  fi
}

dispatch_fixture_offset=0
for users in "${STEPS[@]}"; do
  users="$(echo "$users" | xargs)"
  STEP_DIR="$OUT_DIR/step-u${users}"
  mkdir -p "$STEP_DIR"
  write_step_params "$IMPORT_PARAMS" "$STEP_DIR/import.params.json" "$users"
  write_step_params "$EXPORT_PARAMS" "$STEP_DIR/export.params.json" "$users"
  write_step_params "$DISPATCH_PARAMS" "$STEP_DIR/dispatch.params.json" "$users"
  write_step_params "$PROCESS_PARAMS" "$STEP_DIR/process.params.json" "$users"

  dispatch_step_ids=("${DISPATCH_FILE_IDS[@]:dispatch_fixture_offset:users}")
  dispatch_step_ids_csv="$(IFS=,; echo "${dispatch_step_ids[*]}")"
  dispatch_fixture_offset=$((dispatch_fixture_offset + users))

  step_results=()
  run_and_record import import_customer_job "$STEP_DIR/import.params.json" "$users"
  run_and_record export export_settlement_job "$STEP_DIR/export.params.json" "$users"
  run_and_record dispatch lt_dispatch_local_job "$STEP_DIR/dispatch.params.json" "$users" "$dispatch_step_ids_csv"
  run_and_record process lt_process_sql_job "$STEP_DIR/process.params.json" "$users"

  {
    echo "## Step users=${users}"
    echo
    echo "- Scenario results: ${step_results[*]}"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A \
      -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" -v stress_users="$users" \
      -f "$LOAD_DIR/sql/worker-stress-instance-summary.sql"
    echo '```'
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A \
      -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" -v stress_users="$users" \
      -f "$LOAD_DIR/sql/worker-stress-task-summary.sql"
    echo '```'
    echo
  } >> "$REPORT"
done

{
  echo "- Time UTC finish: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ "${#failures[@]}" -eq 0 ]]; then
    echo "- Overall result: PASS"
  else
    echo "- Overall result: FAIL"
    echo "- Failures: ${failures[*]}"
  fi
} >> "$REPORT"

echo "Worker stress-test report written: $REPORT"
if [[ "${#failures[@]}" -gt 0 ]]; then
  echo "Worker stress test failures: ${failures[*]}" >&2
  if [[ "$STRICT" == "1" ]]; then
    exit 1
  fi
fi
