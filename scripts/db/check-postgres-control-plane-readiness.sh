#!/usr/bin/env bash
# 外置 PostgreSQL 控制面容量预检。SQL 只读，阈值与退出策略由本脚本负责。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SQL_FILE="$ROOT/scripts/db/postgres-control-plane-readiness.sql"
DB_URL="${PG_READINESS_URL:-${DATABASE_URL:-}}"
STRICT="${PG_READINESS_STRICT:-0}"
EXPECTED_APP_CONNECTIONS="${PG_EXPECTED_APP_CONNECTIONS:-160}"
CONNECTION_RESERVE="${PG_CONNECTION_RESERVE:-40}"

if [[ -z "$DB_URL" ]]; then
  echo "PG_READINESS_URL or DATABASE_URL is required" >&2
  exit 2
fi

output="$(psql "$DB_URL" -X -v ON_ERROR_STOP=1 -f "$SQL_FILE")"
printf '%s\n' "$output"

value() {
  awk -F'|' -v key="$1" '$1 == key { print $2; exit }' <<<"$output"
}

failures=0
warnings=0
check() {
  local severity="$1" message="$2"
  if [[ "$severity" == FAIL ]]; then
    echo "FAIL: $message" >&2
    ((failures += 1))
  else
    echo "WARN: $message" >&2
    ((warnings += 1))
  fi
}

server_version_num="$(value server_version_num)"
max_connections="$(value max_connections)"
max_wal_size_bytes="$(value max_wal_size_bytes)"
checkpoint_timeout_seconds="$(value checkpoint_timeout_seconds)"
checkpoint_completion_target="$(value checkpoint_completion_target)"
required_connections=$((EXPECTED_APP_CONNECTIONS + CONNECTION_RESERVE))

(( server_version_num >= 170000 )) || check FAIL "PostgreSQL 17+ required, got $server_version_num"
(( max_connections >= required_connections )) || check FAIL \
  "max_connections=$max_connections is below app budget $EXPECTED_APP_CONNECTIONS + reserve $CONNECTION_RESERVE"
[[ "$(value wal_level)" == replica || "$(value wal_level)" == logical ]] || check FAIL \
  "wal_level must support replication/PITR"
(( max_wal_size_bytes >= 4294967296 )) || check FAIL "max_wal_size must be at least 4GiB"
(( checkpoint_timeout_seconds >= 600 )) || check FAIL "checkpoint_timeout must be at least 10min"
awk -v v="$checkpoint_completion_target" 'BEGIN { exit !(v >= 0.9) }' || check FAIL \
  "checkpoint_completion_target must be at least 0.9"
[[ "$(value autovacuum)" == on ]] || check FAIL "autovacuum must be enabled"
[[ "$(value track_io_timing)" == on ]] || check WARN "track_io_timing should be enabled for bottleneck diagnosis"
[[ "$(value data_checksums)" == on ]] || check WARN "data checksums should be enabled when the provider supports them"

while IFS='|' read -r kind table presence _; do
  [[ "$kind" == hot_table ]] || continue
  [[ "$presence" == PRESENT ]] || check FAIL "required hot table batch.$table is missing"
done <<<"$output"

echo "postgres readiness summary: failures=$failures warnings=$warnings strict=$STRICT"
if (( failures > 0 )) || { [[ "$STRICT" == 1 ]] && (( warnings > 0 )); }; then
  exit 1
fi
