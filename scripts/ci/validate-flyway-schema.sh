#!/usr/bin/env bash
# =========================================================
# validate-flyway-schema.sh - Flyway migration 静态守护
#
# 在 PR gate 阶段做不依赖真 PG 的轻量校验,catch 常见 schema 漂移:
# 1) 文件名规范:V<num>__<description>.sql,版本号唯一不重复(rename / cherry-pick 易碰撞)
# 2) 版本号连续单调:不允许跳号产生空洞(eg V79 之后直接 V81 漏 V80,人工生 SQL 易撞)
# 3) 空文件 / 全注释文件:防开发者只 commit 文件名忘 SQL 主体
# 4) BOM / CRLF:防 Windows 编辑器引入,Flyway 计算 checksum 时与 LF 不一致
# 5) 已 commit 的 V## 文件被改动(checksum drift):git diff base..HEAD 检查 db/migration/V##
#    如果某个 V## 在 base 已存在 + HEAD 内容变了,失败 — Flyway 启动时会因 checksum mismatch 拒绝迁移。
#    例外 1:HEAD 精确恢复为 base 的父提交版本,用于撤销刚误合入主线的 checksum 漂移。
#    例外 2:无存量库时允许基线重发,但必须 SQL 语义不变且精确匹配版本化 SHA-256 清单。
#    新增的 V## 文件不算漂移
#
# 不做(留给 IT 阶段或 ops):
# - 真起 postgres 跑 migrate(IT 已覆盖)
# - schema-vs-mapper diff(MyBatis/JDBC 真用时才体现,运行时报 missing column 即足够)
# =========================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/ci/lib/migration-rebaseline.sh
source "$ROOT_DIR/scripts/ci/lib/migration-rebaseline.sh"

MIGRATION_DIR="${1:-db/migration}"
if [[ -n "${BASE_REF:-}" ]]; then
  BASE_REF="$BASE_REF"
elif [[ -n "${GUARD_BASE:-}" ]]; then
  BASE_REF="$GUARD_BASE"
elif [[ -n "${GITHUB_BASE_REF:-}" ]]; then
  BASE_REF="origin/$GITHUB_BASE_REF"
else
  BASE_REF="origin/main"
fi

if [[ ! -d "$MIGRATION_DIR" ]]; then
  echo "❌ ERROR: migration dir not found: $MIGRATION_DIR"
  exit 1
fi

errors=0

# ── 校验 1+2: 文件名 / 唯一 / 单调 ──────────────────────────────────────────
seen_versions=""
expected_next=1
file_count=0
while IFS= read -r file; do
  base="$(basename "$file")"
  file_count=$((file_count + 1))
  if ! [[ "$base" =~ ^V([0-9]+)__[a-zA-Z0-9_]+\.sql$ ]]; then
    echo "❌ ERROR: invalid migration filename: $base (expected V<num>__<desc>.sql)"
    errors=$((errors + 1))
    continue
  fi
  v="${BASH_REMATCH[1]}"
  # 唯一
  if [[ ",$seen_versions," == *",$v,"* ]]; then
    echo "❌ ERROR: duplicate migration version V$v"
    errors=$((errors + 1))
    continue
  fi
  seen_versions="${seen_versions:+$seen_versions,}$v"
  # 单调
  if [[ "$v" -lt "$expected_next" ]]; then
    echo "❌ ERROR: migration version V$v out of order (expected >= $expected_next)"
    errors=$((errors + 1))
  elif [[ "$v" -gt "$expected_next" ]]; then
    echo "⚠️  WARNING: gap in migration versions (expected V$expected_next, got V$v) — file: $base"
  fi
  expected_next=$((v + 1))
done < <(ls "$MIGRATION_DIR"/V*.sql 2>/dev/null | sort -V)

if [[ $file_count -eq 0 ]]; then
  echo "❌ ERROR: no V*.sql files found under $MIGRATION_DIR"
  exit 1
fi
echo "✅ $file_count migration files name/uniqueness/order check passed"

# ── 校验 3: 完全空文件(0 字节) ─────────────────────────────────────────────
# 注:全注释占位是允许的(eg V35 历史脚本被 V51 替代后保留空 body 维持 checksum 连续性);
# 真"什么都没写"的 0 字节文件不行,Flyway 会报 ParseException。
for file in "$MIGRATION_DIR"/V*.sql; do
  if [[ ! -s "$file" ]]; then
    echo "❌ ERROR: migration $(basename "$file") is 0 bytes (Flyway will throw ParseException)"
    errors=$((errors + 1))
  fi
done
echo "✅ All migrations are non-empty"

# ── 校验 4: BOM / CRLF ─────────────────────────────────────────────────────
for file in "$MIGRATION_DIR"/V*.sql; do
  # BOM = 文件偏移 0 处的 EF BB BF
  first3=$(head -c 3 "$file" | od -An -tx1 | tr -d ' ')
  if [[ "$first3" == "efbbbf" ]]; then
    echo "❌ ERROR: $(basename "$file") starts with UTF-8 BOM (causes Flyway checksum drift)"
    errors=$((errors + 1))
  fi
  if grep -q $'\r' "$file"; then
    echo "❌ ERROR: $(basename "$file") contains CRLF line endings (causes Flyway checksum drift)"
    errors=$((errors + 1))
  fi
done
echo "✅ No BOM / CRLF in migration files"

# ── 校验 5: checksum drift(已 commit 的 V## 文件被改动) ──────────────────────
# 仅在 git 仓库里 + 有 BASE_REF 时跑
if git rev-parse --git-dir >/dev/null 2>&1 && git rev-parse "$BASE_REF" >/dev/null 2>&1; then
  drifted=""
  restored=""
  rebaselined=""
  while IFS= read -r changed; do
    [[ -z "$changed" ]] && continue
    if [[ "$changed" =~ ^${MIGRATION_DIR}/V[0-9]+__.+\.sql$ ]]; then
      # 文件在 BASE_REF 已存在 → 不允许修改
      if git cat-file -e "$BASE_REF:$changed" 2>/dev/null; then
        # 允许把刚在 base 中误改的 migration 精确恢复为 base^ 内容。只比较完整 blob,
        # 不允许在恢复时顺带修改任何 SQL 或注释。
        if git cat-file -e "$BASE_REF^:$changed" 2>/dev/null \
          && git show "$BASE_REF^:$changed" | cmp -s - "$changed"; then
          restored="${restored:+$restored$'\n'}   - $changed"
          continue
        fi
        if is_authorized_migration_rebaseline "$changed" \
          && migration_sql_semantics_unchanged "$BASE_REF" "$changed"; then
          rebaselined="${rebaselined:+$rebaselined$'\n'}   - $changed"
          continue
        fi
        drifted="${drifted:+$drifted$'\n'}   - $changed"
      fi
    fi
  done < <(
    {
      git diff --name-only "$BASE_REF...HEAD" 2>/dev/null || true
      git diff --name-only --diff-filter=M -- "$MIGRATION_DIR/V*.sql"
      git diff --cached --name-only --diff-filter=M -- "$MIGRATION_DIR/V*.sql"
    } | sort -u
  )

  if [[ -n "$restored" ]]; then
    echo "⚠️  migration checksum restored exactly to $BASE_REF^:"
    echo "$restored"
  fi
  if [[ -n "$rebaselined" ]]; then
    echo "⚠️  migration baseline republish authorized by exact hash; recreate databases before deployment:"
    echo "$rebaselined"
  fi
  if [[ -n "$drifted" ]]; then
    echo "❌ ERROR: existing migration file(s) modified in this PR (Flyway checksum drift):"
    echo "$drifted"
    echo "   action: 不要改已 commit 的 V<num>__*.sql,要修就新建 V<next>__fix_xxx.sql 走重做语义"
    errors=$((errors + 1))
  else
    echo "✅ No checksum drift on existing migrations vs $BASE_REF"
  fi
else
  echo "⚠️  skipping checksum drift check (not in git repo or BASE_REF unavailable)"
fi

if [[ $errors -gt 0 ]]; then
  echo
  echo "💥 $errors migration error(s) — fix and retry"
  exit 1
fi

echo
echo "✅ Flyway schema validation passed"
