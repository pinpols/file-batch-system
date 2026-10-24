#!/usr/bin/env bash
# Read-only inspection for stale RUNNING pipeline instances.
# Orchestrator's stale sweep owns the state transition for pipelines and steps.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

usage() {
  cat <<'USAGE'
Usage: bash scripts/ops/inspect-stale-pipelines.sh [--dry-run]

Read-only diagnostic for stale RUNNING pipeline instances. The Orchestrator
stale sweep owns state transitions; this script does not modify database rows.

Optional environment:
  BATCH_HEAL_DRY_RUN=true                       (default; false is rejected)
  BATCH_HEAL_ZOMBIE_MAX_AGE_SECONDS=604800      (default: 7 days)
  BATCH_HEAL_ZOMBIE_TENANT=<tenant>             (default: all tenants)
USAGE
}

for argument in "$@"; do
  case "$argument" in
    --dry-run) ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "unsupported option: $argument" >&2
      usage >&2
      exit 2
      ;;
  esac
done

DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"
MAX_AGE="${BATCH_HEAL_ZOMBIE_MAX_AGE_SECONDS:-604800}"
TENANT="${BATCH_HEAL_ZOMBIE_TENANT:-}"

if [[ "$DRY_RUN" != "true" ]]; then
  echo "BATCH_HEAL_DRY_RUN must be true; this diagnostic is read-only" >&2
  exit 2
fi
if ! [[ "$MAX_AGE" =~ ^[1-9][0-9]*$ ]]; then
  echo "BATCH_HEAL_ZOMBIE_MAX_AGE_SECONDS must be a positive integer" >&2
  exit 2
fi

psql_file() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
    -X -A -t -F '|' -v ON_ERROR_STOP=1 \
    -v max_age_seconds="$MAX_AGE" \
    -v tenant="$TENANT" "$@"
}

echo "==> inspect-stale-pipelines.sh"
echo "    MAX_AGE  = ${MAX_AGE}s ($((MAX_AGE / 86400)) days)"
echo "    TENANT   = ${TENANT:-<all>}"
echo "    PG       = ${PGHOST}:${PGPORT}/${PGDATABASE} (user=${PGUSER})"
echo
echo "==> stale RUNNING pipeline_instance candidates:"
echo "    id|tenant_id|job_code|pipeline_type|started_at|age_seconds"
psql_file -f "$OPS_SQL_DIR/inspect-stale-pipelines-list.sql" | sed 's/^/    /'

stale_count="$(psql_file -f "$OPS_SQL_DIR/inspect-stale-pipelines-count.sql" | tr -d '[:space:]')"
if ! [[ "$stale_count" =~ ^[0-9]+$ ]]; then
  echo "invalid stale pipeline count returned by psql: $stale_count" >&2
  exit 1
fi

echo
echo "DRY-RUN: found ${stale_count} stale RUNNING pipeline(s); no database writes were made."
echo "The Orchestrator stale sweep owns pipeline and step terminal transitions."
