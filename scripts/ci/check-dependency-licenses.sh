#!/usr/bin/env bash
# scripts/ci/check-dependency-licenses.sh
#
# 拦截新依赖引入的不允许 license(GPL / AGPL / 无 CPE 的强 copyleft 等)。
# 由 license-risk-assessment.md §4 红线表 + §6 决策记录定义白/红名单。
#
# 用法:
#   ./scripts/ci/check-dependency-licenses.sh           # 跑完整流程,失败时 exit 1
#   BATCH_CI_SKIP_LICENSE_GATE=1 ./scripts/ci/...      # CI escape hatch (谨慎使用,仅 dev 本地 debug)
#
# 工作原理:
#   1. mvn -P compliance license:aggregate-add-third-party 产出 target/generated-sources/license/THIRD-PARTY.txt
#   2. grep 不允许的 license 模式
#   3. 若命中 → 打印命中行 + exit 1;否则 exit 0
#
# 红线 license(命中即 fail):
#   - GNU General Public License (GPL) without "Classpath Exception" / "CPE"
#   - GNU Affero General Public License (AGPL)
#   - 任何 commons-clause / SSPL(Server-Side Public License)
#
# 允许的双许可路径(grep 出现时不报警,因为我们已在 NOTICE / license-risk 中声明走 OR 的另一边):
#   - JSqlParser: LGPL-2.1 OR Apache-2.0 → 走 Apache-2.0
#   - Logback: EPL-2.0 OR LGPL-2.1 → 走 EPL-2.0(并保持库未修改)

set -euo pipefail

if [[ "${BATCH_CI_SKIP_LICENSE_GATE:-0}" == "1" ]]; then
  echo "[license-gate] BATCH_CI_SKIP_LICENSE_GATE=1 — 跳过(仅 dev 本地 debug 应使用)"
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="${ROOT_DIR}/target/license-aggregate-report"
REPORT_FILE="${REPORT_DIR}/THIRD-PARTY.txt"

echo "[license-gate] 跑 mvn -P compliance license:aggregate-add-third-party ..."
mkdir -p "$REPORT_DIR"
mvn -q -P compliance \
    license:aggregate-add-third-party \
    -Dlicense.outputDirectory="$REPORT_DIR" \
    -Dlicense.thirdPartyFilename=THIRD-PARTY.txt \
    -DskipTests

if [[ ! -f "$REPORT_FILE" ]]; then
  echo "[license-gate] FAIL: 未生成 $REPORT_FILE"
  exit 1
fi

# 已知双许可白名单 — 这些组件即使报告里出现 LGPL/EPL 关键字也不算红线(见脚本头部说明)
ALLOW_DUAL_LICENSE='(JSQLParser|Logback Classic Module|Logback Core Module|jakarta\.|Angus Mail|JUnit|Eclipse Public License - v 2\.0|EPL 2\.0)'

# 红线 license 模式
RED_LINE_PATTERNS=(
  'GNU General Public License.*[Vv]ersion 2'
  'GNU General Public License.*[Vv]ersion 3'
  'GNU Affero General Public License'
  'AGPL'
  'Server[ -]Side Public License'
  'SSPL'
  'Commons Clause'
)

VIOLATIONS=()
for pat in "${RED_LINE_PATTERNS[@]}"; do
  while IFS= read -r line; do
    # 跳过双许可白名单
    if echo "$line" | grep -qE "$ALLOW_DUAL_LICENSE"; then
      continue
    fi
    # 跳过 "with Classpath Exception" / "with CPE" 的 GPL(允许)
    if echo "$line" | grep -qiE 'classpath exception|with cpe|w/ cpe|w/cpe'; then
      continue
    fi
    VIOLATIONS+=("[$pat] $line")
  done < <(grep -E "$pat" "$REPORT_FILE" || true)
done

if [[ ${#VIOLATIONS[@]} -gt 0 ]]; then
  echo
  echo "[license-gate] FAIL: 检测到红线 license 命中 ${#VIOLATIONS[@]} 处:"
  echo
  for v in "${VIOLATIONS[@]}"; do
    echo "  $v"
  done
  echo
  echo "[license-gate] 处理建议:"
  echo "  1. 找到引入此依赖的 pom 改换实现(优先走 Apache-2.0 / MIT / BSD 替代)"
  echo "  2. 如果是双许可且选另一路径合规,在 docs/compliance/license-risk-assessment.md §2 添加判定记录,然后把组件名加到本脚本的 ALLOW_DUAL_LICENSE 白名单"
  echo "  3. 详见 docs/compliance/license-risk-assessment.md §4 红线表"
  exit 1
fi

echo "[license-gate] PASS: 无红线 license 引入(checked $(wc -l < "$REPORT_FILE") lines in $REPORT_FILE)"
