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

BASE_REF="${1:-${SQUAWK_BASE_REF:-origin/main}}"
GATE_CODE="MIGRATION_SAFETY"
GATE_NAME="迁移安全 lint"

if ! command -v squawk >/dev/null 2>&1; then
  echo "❌ 不通过 | code=${GATE_CODE} | gate=${GATE_NAME} | exit_code=1"
  echo "  - 未找到 squawk(CI 应经 action 安装;本地: npm i -g squawk-cli)"
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

echo "ℹ️  对 ${#changed[@]} 个新增/改动迁移跑 squawk:"
printf '   - %s\n' "${changed[@]}"
echo

# squawk 命中危险规则即非零退出 → fail PR。
squawk "${changed[@]}"
echo "✅ 通过 | code=${GATE_CODE} | gate=${GATE_NAME}"
