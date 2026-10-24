#!/usr/bin/env bash
# Read-only inspection for NEW dead-letter tasks. Approved replay is handled by Console.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

usage() {
  cat <<'USAGE'
Usage: bash scripts/ops/inspect-dead-letters.sh [--tenant TENANT] [--source-type TYPE] [--limit N]

Read-only summary and candidate listing for NEW dead-letter tasks. This command
does not call replay APIs. Submit manual replay through Console approval; an
EXTERNAL_OUTCOME_UNKNOWN task requires verified non-execution evidence and
independent approval before its one-time recovery.

Options:
  --tenant TENANT       Limit to a tenant (default: all tenants)
  --source-type TYPE    Limit to a source type (default: all source types)
  --limit N             Maximum candidate rows to list (default: 100)
  --dry-run             Accepted for explicit read-only invocation
  --help, -h            Show this help
USAGE
}

tenant="${BATCH_INSPECT_DLQ_TENANT:-${BATCH_HEAL_DLQ_TENANT:-}}"
source_type="${BATCH_INSPECT_DLQ_SOURCE_TYPE:-${BATCH_HEAL_DLQ_SOURCE_TYPE:-}}"
limit="${BATCH_INSPECT_DLQ_LIMIT:-100}"

while (($#)); do
  case "$1" in
    --dry-run) shift ;;
    --tenant)
      (($# >= 2)) || { echo "--tenant requires a value" >&2; exit 2; }
      tenant="$2"
      shift 2
      ;;
    --source-type)
      (($# >= 2)) || { echo "--source-type requires a value" >&2; exit 2; }
      source_type="$2"
      shift 2
      ;;
    --limit)
      (($# >= 2)) || { echo "--limit requires a value" >&2; exit 2; }
      limit="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "unsupported option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "${BATCH_HEAL_DRY_RUN:-true}" != "true" ]]; then
  echo "BATCH_HEAL_DRY_RUN must be true; dead-letter replay requires Console approval" >&2
  exit 2
fi
if ! [[ "$limit" =~ ^[1-9][0-9]*$ ]]; then
  echo "--limit must be a positive integer" >&2
  exit 2
fi
if ((limit > 1000)); then
  echo "--limit must not exceed 1000" >&2
  exit 2
fi

psql_file() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
    -X -A -t -F '|' -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" \
    -v tenant="$tenant" -v source_type="$source_type" "$@"
}

echo "==> inspect-dead-letters.sh (read-only)"
echo "    tenant      = ${tenant:-<all>}"
echo "    source_type = ${source_type:-<all>}"
echo "    limit       = ${limit}"
echo "    PG          = ${PGHOST}:${PGPORT}/${PGDATABASE} (user=${PGUSER})"
echo
echo "==> NEW dead-letter summary (tenant|source_type|count):"
psql_file -f "$OPS_SQL_DIR/inspect-dead-letters-summary.sql"

total="$(psql_file -f "$OPS_SQL_DIR/inspect-dead-letters-count.sql" | tr -d '[:space:]')"
if ! [[ "$total" =~ ^[0-9]+$ ]]; then
  echo "invalid dead-letter count returned by psql: $total" >&2
  exit 1
fi

echo
echo "==> Candidate IDs (id|tenant_id), at most ${limit}:"
psql_file -v row_limit="$limit" -f "$OPS_SQL_DIR/inspect-dead-letters-list.sql"
echo
echo "DRY-RUN: found ${total} NEW dead-letter task(s); no replay API was called."
if ((total > limit)); then
  echo "Candidate listing is limited to ${limit} rows."
fi
echo "Submit approved replay through Console; unknown external outcomes require verified non-execution evidence."
