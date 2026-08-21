#!/usr/bin/env bash
set -euo pipefail

RUN_ID="${RUN_ID:?RUN_ID is required, for example RUN_ID=ltw-20260505093000}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"
SQL_DIR="$ROOT_DIR/load-tests/sql"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

run_with_retry() {
  local label="$1"
  shift
  local attempt
  for attempt in 1 2 3 4 5 6; do
    if "$@"; then
      return 0
    fi
    if [[ "$attempt" -eq 6 ]]; then
      echo "${label} failed after ${attempt} attempts" >&2
      return 1
    fi
    echo "${label} attempt ${attempt} failed; retrying after lock/backoff" >&2
    sleep $((attempt * 2))
  done
}

wait_for_stable_platform_rows() {
  local previous=-1 current stable=0 attempt
  for attempt in $(seq 1 18); do
    current="$(psql_platform -tA -v run_id="$RUN_ID" \
      -f "$SQL_DIR/p2-run-instance-residue-count.sql")"
    current="${current//[[:space:]]/}"
    if [[ "$current" == "$previous" ]]; then
      stable=$((stable + 1))
      [[ "$stable" -ge 2 ]] && return 0
    else
      stable=0
      previous="$current"
    fi
    sleep 5
  done
  echo "platform load-test row count did not stabilize for RUN_ID=${RUN_ID}" >&2
  return 1
}

assert_no_platform_residue() {
  local remaining
  remaining="$(psql_platform -tA -v run_id="$RUN_ID" \
    -f "$SQL_DIR/p2-run-instance-residue-count.sql")"
  if [[ "${remaining//[[:space:]]/}" != "0" ]]; then
    echo "platform load-test cleanup left ${remaining} job_instance rows for RUN_ID=${RUN_ID}" >&2
    return 1
  fi
}

wait_for_stable_platform_rows
run_with_retry "platform load-test cleanup" \
  psql_platform -v run_id="$RUN_ID" -f "$SQL_DIR/cleanup-worker-load-platform.sql"
run_with_retry "business load-test cleanup" \
  psql_business -v run_id="$RUN_ID" -f "$SQL_DIR/cleanup-worker-load-business.sql"
assert_no_platform_residue

rm -rf "/tmp/batch/load-test/${RUN_ID}-dispatch.txt"
rm -f /tmp/batch/local-dispatch/*"${RUN_ID}"* 2>/dev/null || true

echo "Cleaned worker load-test data for RUN_ID=${RUN_ID}"
