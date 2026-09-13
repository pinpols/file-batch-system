#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

export BATCH_ENV_COMMON_ROOT="$ROOT"
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"

DOCKER_ARGS_FILE="$TMP_DIR/docker-args.txt"
DOCKER_STDIN_FILE="$TMP_DIR/docker-stdin.sql"

docker() {
  if [[ "${1:-}" == "inspect" ]]; then
    printf 'true\n'
    return
  fi
  if [[ "${1:-}" == "exec" ]]; then
    printf '%s\n' "$*" > "$DOCKER_ARGS_FILE"
    cat > "$DOCKER_STDIN_FILE"
    return
  fi
  printf 'unexpected docker invocation: %s\n' "$*" >&2
  return 2
}

export BATCH_PG_CLIENT_MODE=docker
export PG_CONTAINER=test-postgres
SQL_FILE="$TMP_DIR/query.sql"
printf 'select :\047tenant_id\047;\n' > "$SQL_FILE"

psql -h localhost -p 15432 -U batch_user -d batch_platform \
  -v tenant_id=tenant-a -v ON_ERROR_STOP=1 -f "$SQL_FILE"

cmp -s "$SQL_FILE" "$DOCKER_STDIN_FILE" || {
  echo "PostgreSQL Docker fallback did not stream the host SQL file" >&2
  exit 1
}
grep -Fq 'exec -i test-postgres psql -U batch_user -d batch_platform' "$DOCKER_ARGS_FILE"
grep -Fq -- '-v tenant_id=tenant-a -v ON_ERROR_STOP=1' "$DOCKER_ARGS_FILE"
if grep -Eq -- '(^| )(-h|-p|-f)( |$)' "$DOCKER_ARGS_FILE"; then
  echo "PostgreSQL Docker fallback leaked host-only connection/file arguments" >&2
  exit 1
fi

if psql -f "$TMP_DIR/missing.sql" >/dev/null 2>&1; then
  echo "PostgreSQL Docker fallback accepted a missing SQL file" >&2
  exit 1
fi
if psql -f "$SQL_FILE" -f "$SQL_FILE" >/dev/null 2>&1; then
  echo "PostgreSQL Docker fallback accepted multiple host SQL files" >&2
  exit 1
fi

echo "PostgreSQL client fallback guard passed"
