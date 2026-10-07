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
#   - 任何 Commons-Clause / SSPL(Server-Side Public License)
#   - Business Source License / Elastic License / CC-BY-NC 等商用限制许可
#
# 仅允许精确组件 RocksDB JNI 的 GPL-2.0 行；其 SBOM 同时声明 Apache-2.0，项目选择 Apache 路径。

set -euo pipefail

if [[ "${BATCH_CI_SKIP_LICENSE_GATE:-0}" == "1" ]]; then
  echo "[license-gate] BATCH_CI_SKIP_LICENSE_GATE=1 — 跳过(仅 dev 本地 debug 应使用)"
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
source "${ROOT_DIR}/scripts/ci/license-allowlist.sh"
bash "${ROOT_DIR}/scripts/ci/tests/test-dependency-license-allowlist.sh"

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

# 红线 license 模式
RED_LINE_PATTERNS=(
  'GNU General Public License.*[Vv]ersion 2'
  'GNU General Public License.*[Vv]ersion 3'
  'GNU Affero General Public License'
  'AGPL'
  'Server[ -]Side Public License'
  'SSPL'
  'Commons Clause'
  'Business Source License'
  'BUSL'
  'Elastic License'
  'Elastic-2\.0'
  'CC-BY-NC'
  'Creative Commons.*NonCommercial'
)

VIOLATIONS=()
for pat in "${RED_LINE_PATTERNS[@]}"; do
  while IFS= read -r line; do
    # 只豁免 SBOM 已确认的 RocksDB JNI 双许可行，避免包名子串扩大豁免范围。
    if license_line_has_approved_alternative "$line"; then
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
  echo "  2. 如果是双许可且选另一路径合规,先在 docs/compliance/license-risk-assessment.md 记录组件与许可证证据，再按精确组件规则评审 allowlist"
  echo "  3. 详见 docs/compliance/license-risk-assessment.md §4 红线表"
  exit 1
fi

echo "[license-gate] PASS: 无红线 license 引入(checked $(wc -l < "$REPORT_FILE") lines in $REPORT_FILE)"
