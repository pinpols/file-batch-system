#!/usr/bin/env bash
# 五语言 BYO SDK 样例 worker 对真实 Orchestrator/Trigger 的端到端验证。
set -euo pipefail

LANG_ID="${1:?usage: run-sdk-orchestrator-e2e.sh <go|python|java|typescript|rust>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

export PGHOST="${BATCH_PLATFORM_DB_HOST:-localhost}"
export PGPORT="${POSTGRES_PORT:-15432}"
export PGUSER="${BATCH_PLATFORM_DB_USERNAME:-batch_user}"
export PGDATABASE="${BATCH_PLATFORM_DB_NAME:-batch_platform}"
export BATCH_PLATFORM_DB_PASSWORD="${BATCH_PLATFORM_DB_PASSWORD:?BATCH_PLATFORM_DB_PASSWORD required}"
export POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-batch-postgres-primary}"
export ORCH_URL="http://localhost:${ORCH_PORT:-18082}"
export TRIGGER_URL="http://localhost:${TRIGGER_PORT:-18081}"
export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-19092}"
export KAFKA_CONTAINER="${KAFKA_CONTAINER:-batch-kafka}"
export KAFKA_CONTAINER_BIN_DIR="${KAFKA_CONTAINER_BIN_DIR:-/opt/kafka/bin}"
export TENANT="${TENANT:-default-tenant}"
export BATCH_SCRIPT_RUNTIME="${BATCH_SCRIPT_RUNTIME:-docker}"
export SDK_E2E_JOB_CODE="sdk_echo_e2e_${LANG_ID}_$$"

# shellcheck source=../lib/sdk-e2e-common.sh
# shellcheck disable=SC1091 # CI 从仓库绝对路径加载共享库。
source "$ROOT/scripts/lib/sdk-e2e-common.sh"

WORKER_CODE="ci-e2e-${LANG_ID}-$$"
WORKER_LOG="/tmp/sdk-e2e-worker-${LANG_ID}.log"
WORKER_PID=""

dump_diagnostics() {
  echo "::group::SDK worker log"
  tail -n 120 "$WORKER_LOG" 2>/dev/null || true
  echo "::endgroup::"
}

cleanup() {
  if [[ -n "$WORKER_PID" ]] && kill -0 "$WORKER_PID" 2>/dev/null; then
    kill "$WORKER_PID" 2>/dev/null || true
    wait "$WORKER_PID" 2>/dev/null || true
  fi
  sdk_e2e_cleanup "$WORKER_CODE" || true
}
trap cleanup EXIT

sdk_e2e_say "0. real stack preconditions"
sdk_e2e_check_stack

sdk_e2e_say "1. self-contained API key, queue and job fixture"
RAW_KEY="$(sdk_e2e_seed_api_key "$WORKER_CODE")"
sdk_e2e_ensure_echo_job
sdk_e2e_precreate_topic "$WORKER_CODE"

sdk_e2e_say "2. start ${LANG_ID} sample worker"
WORKER_PID="$(sdk_e2e_start_worker "$LANG_ID" "$WORKER_CODE" "$RAW_KEY" "$WORKER_LOG")"

sdk_e2e_say "3. register"
sdk_e2e_assert_register "$WORKER_CODE" "$WORKER_PID" "$WORKER_LOG" \
  || { dump_diagnostics; exit 1; }
sdk_e2e_pass "registered"

sdk_e2e_say "4. launch, dispatch, claim, execute, report and terminal"
sdk_e2e_run_chain "$RAW_KEY" "$WORKER_LOG" || { dump_diagnostics; exit 1; }

printf 'register=1 dispatch=%s execute=%s report=%s terminal=%s\n' \
  "$STAGE_DISPATCH" "$STAGE_EXECUTE" "$STAGE_REPORT" "$STAGE_TERMINAL"
