#!/usr/bin/env bash
# 用途：数据库迁移安全。
# 用法：在仓库根目录执行 bash scripts/ci/check-migration-safety.sh；参数与前置环境见下方说明及所属 README。
# 守护:迁移安全 lint(squawk)—— 只扫本 PR 新增/改动的迁移,历史豁免。
#
# 背景:Flyway 迁移直接对 prod 跑 DDL。危险写法(DROP COLUMN 丢数据、加 UNIQUE 约束取
# ACCESS EXCLUSIVE 长锁、改列类型重写大表、重命名列破坏 mybatis 绑定)在评审里容易漏。
# 2026-06-10 分区脚本实跑致 orchestrator outbox 全写失败回滚就是同类痛。
#
# 策略:squawk 对存量历史会大量报(1875 条),故只对「相对 base 新增/改动有效 SQL」的迁移跑;
# 纯 SQL 注释 / COMMENT ON 元数据改动不触发结构安全 lint。规则集见 .squawk.toml。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASE_REF="${1:-${SQUAWK_BASE_REF:-origin/main}}"
GATE_CODE="MIGRATION_SAFETY"
GATE_NAME="迁移安全 lint"
LOCAL_SQUAWK="$ROOT_DIR/.github/ci-tools/node_modules/.bin/squawk"

# 优先使用仓库锁定版本，避免全局包装器缺少当前平台的 optional dependency。
if [[ -x "$LOCAL_SQUAWK" ]]; then
  SQUAWK_BIN="$LOCAL_SQUAWK"
elif command -v squawk >/dev/null 2>&1; then
  SQUAWK_BIN="$(command -v squawk)"
else
  SQUAWK_BIN=""
fi

if [[ -z "$SQUAWK_BIN" ]]; then
  echo "❌ 不通过 | code=${GATE_CODE} | gate=${GATE_NAME} | exit_code=1"
  echo "  - 未找到 squawk（可运行 npm ci --prefix .github/ci-tools 安装仓库锁定版本）"
  exit 1
fi

# 找出相对 base 新增(A)/改动(M)的迁移文件，并覆盖本地「未暂存」与「已 git add 未提交」两种状态。
# 四个来源缺一不可：--cached 用 AM 而非 M，否则「新增且已暂存、尚未 commit」的迁移整类漏扫（本地假绿）。
# CI 中后三个来源为空（工作树干净），但本地必须能扫到未提交的迁移。
mapfile -t changed < <(
  {
    git diff --name-only --diff-filter=AM "${BASE_REF}"...HEAD -- 'db/migration/*.sql' 2>/dev/null || true
    git diff --name-only --diff-filter=AM -- 'db/migration/*.sql'
    git diff --cached --name-only --diff-filter=AM -- 'db/migration/*.sql'
    git ls-files --others --exclude-standard -- 'db/migration/*.sql'
  } | sort -u
)

if [[ "${#changed[@]}" -eq 0 ]]; then
  echo "⏭️ 跳过 | code=${GATE_CODE} | gate=${GATE_NAME} | reason=本次无新增/改动迁移文件"
  exit 0
fi

to_lint=()
comment_only_count=0
if ! classifications="$(python3 "$ROOT_DIR/scripts/ci/flyway_sql_diff.py" "$BASE_REF" "${changed[@]}")"; then
  echo "❌ ERROR: cannot classify migration changes safely"
  exit 1
fi
while IFS=$'\t' read -r classification migration; do
  case "$classification" in
    COMMENT) comment_only_count=$((comment_only_count + 1)) ;;
    SQL|NEW) to_lint+=("$migration") ;;
  esac
done <<< "$classifications"

if [[ "${#to_lint[@]}" -eq 0 ]]; then
  echo "⏭️ 跳过 | code=${GATE_CODE} | gate=${GATE_NAME} | reason=仅有 SQL 注释/COMMENT ON 元数据变更 (${comment_only_count} 个文件)"
  exit 0
fi

echo "ℹ️  对 ${#to_lint[@]} 个新增/改动迁移跑 squawk:"
printf '   - %s\n' "${to_lint[@]}"
echo

# squawk 命中危险规则即非零退出 → fail PR。
"$SQUAWK_BIN" "${to_lint[@]}"
echo "✅ 通过 | code=${GATE_CODE} | gate=${GATE_NAME}"
