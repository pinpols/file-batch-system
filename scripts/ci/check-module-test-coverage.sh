#!/usr/bin/env bash
# 守护:根 reactor 里「有测试的模块」是否都被某个 CI workflow 的 -pl 覆盖。
#
# 背景:pr-gate / full-ci-gate 用硬编码 `-pl <模块列表>` 分 shard 跑测试。风险:
# 新模块进根 pom <modules> 后忘记加进任何 shard 的 -pl → 该模块全部测试被静默跳过,
# CI 仍绿(2026-06-14 实测:batch-worker-sdk×3 共 ~370 测试从未在 CI 跑)。
#
# 判定:根 pom <module> ∩ 有测试类的模块 − CI 的 -pl 并集 − 豁免清单 ≠ ∅ → FAIL。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 受守护的 workflow:从中抽取所有 `-pl a,b,c` 模块名作为「已覆盖」并集。
WORKFLOWS=(
  ".github/workflows/pr-gate.yml"
  ".github/workflows/full-ci-gate.yml"
)

# 豁免清单(每行一个模块名):确属设计内、不该被 CI -pl 覆盖的根 reactor 模块。
# 目前为空 —— 未来要豁免某模块时在此登记并注明依据(如:仅 release 时构建)。
EXEMPT_FILE="$(mktemp)"
trap 'rm -f "$EXEMPT_FILE"' EXIT
: > "$EXEMPT_FILE"

# 1) 根 pom <modules> 里、且含测试类(*Test.java / *IT.java)的模块。
tested="$(mktemp)"
for m in $(grep -oE '<module>[^<]+</module>' pom.xml | sed -E 's#</?module>##g'); do
  if [[ -d "$m/src/test" ]] && \
     find "$m/src/test" \( -name '*Test.java' -o -name '*IT.java' \) -print -quit 2>/dev/null | grep -q .; then
    echo "$m"
  fi
done | sort -u > "$tested"

# 2) CI workflow 里 -pl 覆盖的模块并集 ∪ 豁免清单。
accepted="$(mktemp)"
grep -hoE '\-pl [A-Za-z0-9,_-]+' "${WORKFLOWS[@]}" \
  | sed 's/-pl //' | tr ',' '\n' \
  | cat - "$EXEMPT_FILE" | sed '/^$/d' | sort -u > "$accepted"

echo "ℹ️  根 reactor 有测试的模块($(grep -c . < "$tested") 个):"
sed 's/^/   - /' "$tested"
echo "ℹ️  CI -pl 覆盖:$(tr '\n' ' ' < "$accepted")"

missing="$(comm -23 "$tested" "$accepted")"
rm -f "$tested" "$accepted"

if [[ -n "$missing" ]]; then
  echo
  echo "❌ 以下根 reactor 模块有测试,但 pr-gate/full-ci-gate 的 -pl 都没覆盖(会被静默漏跑):"
  printf '   - %s\n' $missing
  echo
  echo "💥 请把它们加进某个 unit shard 的 -pl,或(确属设计内)登记到本脚本 EXEMPT 并注明依据。"
  exit 1
fi

echo "✅ 所有有测试的根 reactor 模块均被 CI -pl 覆盖"
