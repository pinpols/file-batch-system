#!/usr/bin/env bash
# 守护:迁移安全 lint(squawk)—— 只扫本 PR 新增/改动的迁移,历史豁免。
#
# 背景:Flyway 迁移直接对 prod 跑 DDL。危险写法(DROP COLUMN 丢数据、加 UNIQUE 约束取
# ACCESS EXCLUSIVE 长锁、改列类型重写大表、重命名列破坏 mybatis 绑定)在评审里容易漏。
# 2026-06-10 分区脚本实跑致 orchestrator outbox 全写失败回滚就是同类痛。
#
# 策略:squawk 对存量历史会大量报(1875 条),故只对「相对 base 新增/改动」的 db/migration/*.sql 跑;
# 规则集见 .squawk.toml(只留危险锁/数据丢失/破坏应用类)。无改动迁移则直接通过。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
# shellcheck source=scripts/ci/lib/migration-rebaseline.sh
source "$ROOT_DIR/scripts/ci/lib/migration-rebaseline.sh"

BASE_REF="${1:-${SQUAWK_BASE_REF:-origin/main}}"

if ! command -v squawk >/dev/null 2>&1; then
  echo "❌ 未找到 squawk(CI 应经 action 安装;本地: npm i -g squawk-cli)"
  exit 1
fi

# 找出相对 base 新增(A)/改动(M)的迁移文件，并覆盖本地尚未暂存的新迁移。
# CI 中后者为空；本地不能因为文件还没 git add 就漏掉安全扫描。
mapfile -t changed < <(
  {
    git diff --name-only --diff-filter=AM "${BASE_REF}"...HEAD -- 'db/migration/*.sql' 2>/dev/null || true
    git diff --name-only --diff-filter=M -- 'db/migration/*.sql'
    git diff --cached --name-only --diff-filter=M -- 'db/migration/*.sql'
    git ls-files --others --exclude-standard -- 'db/migration/*.sql'
  } | sort -u
)

if [[ "${#changed[@]}" -eq 0 ]]; then
  echo "✅ 本次无新增/改动迁移文件,跳过 squawk"
  exit 0
fi

lint_targets=()
for file in "${changed[@]}"; do
  if git cat-file -e "$BASE_REF:$file" 2>/dev/null \
    && is_authorized_migration_rebaseline "$file" \
    && migration_sql_semantics_unchanged "$BASE_REF" "$file"; then
    echo "⚠️  跳过已精确授权且 SQL 语义未变的基线重发文件:$file"
    continue
  fi
  lint_targets+=("$file")
done

if [[ "${#lint_targets[@]}" -eq 0 ]]; then
  echo "✅ 本次迁移改动均为已授权的注释基线重发,无需运行 squawk"
  exit 0
fi

echo "ℹ️  对 ${#lint_targets[@]} 个新增/改动迁移跑 squawk:"
printf '   - %s\n' "${lint_targets[@]}"
echo

# squawk 命中危险规则即非零退出 → fail PR。
squawk "${lint_targets[@]}"
echo "✅ 迁移安全 lint 通过"
