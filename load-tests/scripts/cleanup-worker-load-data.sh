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

psql_platform -v run_id="$RUN_ID" -f "$SQL_DIR/cleanup-worker-load-platform.sql"
psql_business -v run_id="$RUN_ID" -f "$SQL_DIR/cleanup-worker-load-business.sql"

rm -rf "/tmp/batch/load-test/${RUN_ID}-dispatch.txt"
rm -f /tmp/batch/local-dispatch/*"${RUN_ID}"* 2>/dev/null || true

echo "Cleaned worker load-test data for RUN_ID=${RUN_ID}"
