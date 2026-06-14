#!/usr/bin/env bash
# 守护:禁止「静默禁用测试」—— @Disabled 必须带非空 reason(说明原因/ticket/doc)。
#
# 背景:裸 @Disabled / @Disabled() 会让测试被悄悄跳过、覆盖率被掏空,且无人追踪何时恢复。
# 强制写 reason 让禁用可追溯(此前实测仓库唯一一处 @Disabled 已规范带 reason 引 roadmap)。
#
# 豁免:条件禁用注解(@DisabledOnOs / @DisabledIf* / @DisabledForJreRange / @DisabledIfEnvironmentVariable)
# 是按环境跳过、非永久禁用,放行。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

violations=0
while IFS= read -r file; do
  # 逐行找以 @Disabled 起始(允许前导空白)、且非条件禁用变体的注解行。
  while IFS=: read -r lineno content; do
    [[ -z "$lineno" ]] && continue
    trimmed="${content#"${content%%[![:space:]]*}"}"
    case "$trimmed" in
      @DisabledOnOs* | @DisabledIf* | @DisabledForJreRange* | @DisabledIfEnvironmentVariable* | @DisabledIfSystemProperty*)
        continue
        ;;
    esac
    # 提取括号里的内容;裸 @Disabled / @Disabled() / @Disabled("") 都算无 reason。
    reason="$(printf '%s' "$trimmed" | sed -nE 's/^@Disabled\(?"?([^"]*)"?\)?.*/\1/p')"
    if [[ "$trimmed" == "@Disabled" || -z "${reason//[$' \t']/}" ]]; then
      echo "❌ $file:$lineno 裸 @Disabled 无 reason —— 请写明原因/ticket/doc:$trimmed"
      violations=$((violations + 1))
    fi
  done < <(grep -nE '^[[:space:]]*@Disabled([[:space:](]|$)' "$file" || true)
done < <(grep -rlE '^[[:space:]]*@Disabled' --include='*.java' batch-*/src/test 2>/dev/null || true)

if [[ "$violations" -ne 0 ]]; then
  echo
  echo "💥 发现 $violations 处静默禁用测试 —— @Disabled 必须带非空 reason。"
  exit 1
fi
echo "✅ 无裸 @Disabled(所有禁用测试均带 reason)"
