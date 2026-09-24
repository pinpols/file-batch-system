#!/usr/bin/env bash

# 仅允许经过评审、SQL 语义未变化且内容哈希精确匹配的迁移基线重发。
MIGRATION_REBASELINE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MIGRATION_REBASELINE_MANIFEST="${MIGRATION_REBASELINE_MANIFEST:-$MIGRATION_REBASELINE_ROOT/db/migration-rebaseline-1.0.0.sha256}"

migration_sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

migration_sha256_file() {
  local file="$1"
  migration_sha256_stream < "$file"
}

is_authorized_migration_rebaseline() {
  local file="$1"
  local actual_hash
  [[ -f "$MIGRATION_REBASELINE_MANIFEST" ]] || return 1
  actual_hash="$(migration_sha256_file "$file")"
  awk -v expected_hash="$actual_hash" -v expected_file="$file" '
    $1 == expected_hash && $2 == expected_file { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "$MIGRATION_REBASELINE_MANIFEST"
}

migration_sql_semantics_unchanged() {
  local base_ref="$1"
  local file="$2"
  local base_hash
  local current_hash
  base_hash="$(
    git show "$base_ref:$file" \
      | sed -E 's/--.*$//' \
      | tr -d '[:space:]' \
      | migration_sha256_stream
  )"
  current_hash="$(
    sed -E 's/--.*$//' "$file" \
      | tr -d '[:space:]' \
      | migration_sha256_stream
  )"
  [[ "$base_hash" == "$current_hash" ]]
}
