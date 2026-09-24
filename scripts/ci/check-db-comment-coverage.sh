#!/usr/bin/env bash
# 守护：新增数据库对象必须在同一 Flyway 迁移中补充可读注释。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASE_REF="${1:-${DB_COMMENT_BASE_REF:-origin/main}}"

if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  echo "❌ 无法解析基线分支: $BASE_REF"
  exit 1
fi

mapfile -t migrations < <(
  {
    git diff --name-only --diff-filter=AM "${BASE_REF}"...HEAD -- 'db/migration/V*.sql'
    git ls-files --others --exclude-standard -- 'db/migration/V*.sql'
  } | sort -u
)

if [[ "${#migrations[@]}" -eq 0 ]]; then
  echo "✅ 本次无 Flyway 迁移，跳过数据库注释增量检查"
  exit 0
fi

fail=0
key_column_pattern='(status|policy|strategy|type|mode|payload|params|json|dedup|idempotency|secret|key_ref|hash|timeout|window|timezone|version|trace_id|retry|priority|weight|target_ref|source_ref|endpoint|checksum)'

for migration in "${migrations[@]}"; do
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

if [[ "$fail" -ne 0 ]]; then
  echo "💥 新增业务对象的注释检查失败。"
  exit 1
fi
echo "✅ 新增业务表与关键字段均已在同一迁移中声明注释"
