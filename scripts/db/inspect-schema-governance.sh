#!/usr/bin/env bash
# 数据库结构治理画像入口。只读查询，不执行 DDL/DML。
# shellcheck disable=SC1091

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"

usage() {
  cat <<'USAGE'
Usage: scripts/db/inspect-schema-governance.sh [--help]

只读输出数据库结构治理画像，不执行 DDL/DML。

连接配置：
  PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE
  BATCH_SCHEMA_GOVERNANCE_SQL      可覆盖巡检 SQL 文件
  BATCH_SCHEMA_GOVERNANCE_REPORT   可输出报告到指定文件

说明：
  脚本不要求本地 Docker。未显式传入 PG* 时会加载仓库本地默认值，
  仅用于开发机 fallback；生产和测试应由 profile、Secret 或 CI 变量注入真实连接。
USAGE
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  "")
    ;;
  *)
    printf 'Unknown argument: %s\n' "$1" >&2
    usage >&2
    exit 2
    ;;
esac

batch_load_default_env

SQL_FILE="${BATCH_SCHEMA_GOVERNANCE_SQL:-$ROOT/scripts/db/inspect/schema-governance.sql}"
MIGRATION_VERSION_SQL="$ROOT/scripts/db/inspect/select-highest-flyway-success-version.sql"
REPORT_FILE="${BATCH_SCHEMA_GOVERNANCE_REPORT:-}"

if [[ ! -f "$SQL_FILE" ]]; then
  printf 'Schema governance SQL not found: %s\n' "$SQL_FILE" >&2
  exit 2
fi
if [[ ! -f "$MIGRATION_VERSION_SQL" ]]; then
  printf 'Flyway version SQL not found: %s\n' "$MIGRATION_VERSION_SQL" >&2
  exit 2
fi

run_psql() {
  psql -X \
    -h "${PGHOST}" \
    -p "${PGPORT}" \
    -U "${PGUSER}" \
    -d "${PGDATABASE}" \
    -v ON_ERROR_STOP=1 \
    -f "$SQL_FILE"
}

print_migration_file_state() {
  local highest_file_version=0 file base version db_version
  while IFS= read -r file; do
    base="$(basename "$file")"
    version="${base#V}"
    version="${version%%__*}"
    if [[ "$version" =~ ^[0-9]+$ && "$version" -gt "$highest_file_version" ]]; then
      highest_file_version="$version"
    fi
  done < <(find "$ROOT/db/migration" -maxdepth 1 -type f -name 'V*__*.sql' | sort)

  db_version="$(psql -X \
    -h "${PGHOST}" \
    -p "${PGPORT}" \
    -U "${PGUSER}" \
    -d "${PGDATABASE}" \
    -tA \
    -v ON_ERROR_STOP=1 \
    -f "$MIGRATION_VERSION_SQL" \
    2>/dev/null || printf 'UNKNOWN')"

  printf '\n== Migration file state ==\n'
  printf 'code_highest_version=%s\n' "$highest_file_version"
  printf 'database_highest_success_version=%s\n' "$db_version"
  if [[ "$db_version" =~ ^[0-9]+$ && "$highest_file_version" -gt "$db_version" ]]; then
    printf 'WARN: database has pending migration files: V%s..V%s\n' "$((db_version + 1))" "$highest_file_version"
  fi
}

run_report() {
  print_migration_file_state
  run_psql
}

if [[ -n "$REPORT_FILE" ]]; then
  mkdir -p "$(dirname "$REPORT_FILE")"
  run_report | tee "$REPORT_FILE"
else
  run_report
fi
