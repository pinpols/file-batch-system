#!/usr/bin/env bash
# 守护：新增数据库对象必须在同一 Flyway 迁移中补充可读注释。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
# shellcheck source=../lib/gate-result.sh
source "$ROOT_DIR/scripts/lib/gate-result.sh"

GATE_CODE="DB_COMMENT_COVERAGE"
GATE_NAME="数据库注释覆盖"

BASE_REF="${1:-${DB_COMMENT_BASE_REF:-origin/main}}"

if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  echo "无法解析基线分支: $BASE_REF" >&2
  gate_result FAIL "$GATE_CODE" "$GATE_NAME" 2
  exit 1
fi

mapfile -t migrations < <(
  {
    git diff --name-only --diff-filter=AM "${BASE_REF}"...HEAD -- 'db/migration/V*.sql'
    git diff --name-only --diff-filter=AM -- 'db/migration/V*.sql'
    git diff --cached --name-only --diff-filter=AM -- 'db/migration/V*.sql'
    git ls-files --others --exclude-standard -- 'db/migration/V*.sql'
  } | sort -u
)

fail=0
checked=0
key_column_pattern='(status|policy|strategy|type|mode|payload|params|json|dedup|idempotency|secret|key_ref|hash|timeout|window|timezone|version|trace_id|retry|priority|weight|target_ref|source_ref|endpoint|checksum)'

for migration in "${migrations[@]}"; do
  if git cat-file -e "$BASE_REF:$migration" 2>/dev/null; then
    added_sql="$(git diff --no-ext-diff --unified=0 "$BASE_REF"...HEAD -- "$migration" \
      | awk '/^\+\+\+/ {next} /^\+/ {print substr($0, 2)}' | tr '\n' ' ')"
  else
    added_sql="$(tr '\n' ' ' < "$migration")"
  fi
  if ! grep -Eiq '(^|[[:space:]])create[[:space:]]+table[[:space:]]+(if[[:space:]]+(not[[:space:]]+)?exists[[:space:]]+)?(batch|archive)\.|(^|[[:space:]])alter[[:space:]]+table[[:space:]]+(if[[:space:]]+exists[[:space:]]+)?(batch|archive)\.[a-z0-9_]+[[:space:]]+add[[:space:]]+column' <<< "$added_sql"; then
    continue
  fi
  checked=$((checked + 1))

  content="$(tr '[:upper:]' '[:lower:]' < "$migration")"
  while IFS= read -r table; do
    [[ -z "$table" ]] && continue
    if ! grep -qE "comment[[:space:]]+on[[:space:]]+table[[:space:]]+${table//./\\.}([[:space:]]|$)" <<< "$content"; then
      echo "❌ $migration: 新建业务表 $table 缺少 COMMENT ON TABLE"
      fail=1
    fi
  done < <(sed -nE 's/^[[:space:]]*create[[:space:]]+table[[:space:]]+(if[[:space:]]+not[[:space:]]+exists[[:space:]]+)?((batch|archive)\.[a-z0-9_]+).*/\2/pI' "$migration")

  while IFS='|' read -r table column; do
    [[ -z "$table" || -z "$column" ]] && continue
    if [[ "$table" == batch.* && "$column" =~ $key_column_pattern ]] \
        && ! grep -qE "comment[[:space:]]+on[[:space:]]+column[[:space:]]+${table//./\\.}\\.${column}([[:space:]]|$)" <<< "$content"; then
      echo "❌ $migration: 关键字段 $table.$column 缺少 COMMENT ON COLUMN"
      fail=1
    fi
  done < <(
    awk '
      function tableName(line) {
        sub(/^.*(create|alter)[[:space:]]+table[[:space:]]+(if[[:space:]]+(not[[:space:]]+)?exists[[:space:]]+)?/, "", line)
        sub(/[[:space:](;].*$/, "", line)
        return line
      }
      function columnName(line) {
        sub(/^.*add[[:space:]]+column[[:space:]]+(if[[:space:]]+not[[:space:]]+exists[[:space:]]+)?/, "", line)
        sub(/[[:space:],].*$/, "", line)
        return line
      }
  {
    line = tolower($0)
    sub(/^[[:space:]]+/, "", line)
    sub(/[[:space:]]+$/, "", line)
    if (line ~ /^[[:space:]]*create[[:space:]]+table[[:space:]]+/) {
          create_table = tableName(line)
          in_create = (create_table ~ /^(batch|archive)\./)
          next
        }
        if (in_create && line ~ /^[[:space:]]*[a-z_][a-z0-9_]*/) {
          split(line, fields, /[[:space:]]+/)
          column = fields[1]
          if (column !~ /^(constraint|primary|unique|foreign|check|exclude)$/) print create_table "|" column
        }
        if (in_create && line ~ /^[[:space:]]*\);/) { in_create = 0; create_table = ""; next }

        if (line ~ /^[[:space:]]*alter[[:space:]]+table[[:space:]]+(if[[:space:]]+exists[[:space:]]+)?(batch|archive)\./) {
          alter_table = tableName(line)
          if (line ~ /add[[:space:]]+column/) print alter_table "|" columnName(line)
          next
        }
        if (alter_table ~ /^(batch|archive)\./ && line ~ /add[[:space:]]+column/) {
          print alter_table "|" columnName(line)
        }
        if (alter_table != "" && line ~ /;[[:space:]]*$/) alter_table = ""
      }
    ' "$migration"
  )
done

if [[ "$checked" -eq 0 ]]; then
  gate_skip "$GATE_CODE" "$GATE_NAME" "变更迁移未新增业务表或字段 DDL"
  exit 0
fi

if [[ "$fail" -ne 0 ]]; then
  gate_result FAIL "$GATE_CODE" "$GATE_NAME" 1
  exit 1
fi
gate_result PASS "$GATE_CODE" "$GATE_NAME"
