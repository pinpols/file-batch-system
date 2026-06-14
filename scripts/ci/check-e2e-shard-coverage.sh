#!/usr/bin/env bash
# 守护:e2e shard 清单漂移。
#
# 背景:full-ci-gate / staging-gate 的 e2e 用硬编码 `-Dtest=<逗号分隔类名>` 分 4 shard 跑,
# 是 LPT 均衡的人工分片。风险:新增 / 重命名 *E2eIT 后忘记同步清单 → 该类被静默跳过,
# CI 仍绿,造成「全量已跑」的假象(2026-06-14 实测 full-ci-gate 漏 5 个、staging-gate 漏 2 个)。
#
# 本守护对每个 workflow 做双向差集:
#   A. 仓库有、清单没列        → 漏跑(FAIL)
#   B. 清单列了、仓库已无此类  → stale,-Dtest 静默匹配不到(FAIL)
#
# 解析策略:只取 `tests:` 折叠块里「整行 = 缩进 + 类名 + 可选逗号」的行,
# 天然排除注释 / label / 散文里出现的 E2eIT 字样。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 受守护的 workflow:每个都应覆盖全部 *E2eIT。
WORKFLOWS=(
  ".github/workflows/full-ci-gate.yml"
  ".github/workflows/staging-gate.yml"
)

# 全仓实际存在的 *E2eIT 类名(去路径去后缀)。
actual="$(find . -name '*E2eIT.java' -not -path '*/target/*' \
  | sed -E 's#.*/##; s/\.java$//' | sort -u)"

if [[ -z "$actual" ]]; then
  echo "❌ 未找到任何 *E2eIT.java —— 守护脚本路径或仓库结构可能已变,请检查。"
  exit 1
fi

actual_count="$(printf '%s\n' "$actual" | grep -c . || true)"
echo "ℹ️  仓库实际 *E2eIT: ${actual_count} 个"

fail=0
for wf in "${WORKFLOWS[@]}"; do
  if [[ ! -f "$wf" ]]; then
    echo "❌ workflow 不存在: $wf"
    fail=1
    continue
  fi
  # 只匹配「整行就是一个类名(可带尾逗号)」的清单项。
  listed="$(grep -E '^[[:space:]]+[A-Za-z][A-Za-z0-9]*E2eIT,?[[:space:]]*$' "$wf" \
    | sed -E 's/[[:space:],]//g' | sort -u || true)"
  listed_count="$(printf '%s\n' "$listed" | grep -c . || true)"

  missing="$(comm -23 <(printf '%s\n' "$actual") <(printf '%s\n' "$listed"))"
  stale="$(comm -13 <(printf '%s\n' "$actual") <(printf '%s\n' "$listed"))"

  echo
  echo "===== $wf (清单 ${listed_count} 个) ====="
  if [[ -z "$missing" && -z "$stale" ]]; then
    echo "✅ 覆盖完整,无漂移"
    continue
  fi
  if [[ -n "$missing" ]]; then
    echo "❌ 漏跑(仓库有、清单没列):"
    printf '   - %s\n' $missing
    fail=1
  fi
  if [[ -n "$stale" ]]; then
    echo "❌ stale(清单列了、仓库已无,-Dtest 静默匹配不到):"
    printf '   - %s\n' $stale
    fail=1
  fi
done

echo
if [[ "$fail" -ne 0 ]]; then
  echo "💥 e2e shard 清单与实际 *E2eIT 不一致 —— 请同步上述 workflow 的 shard tests 清单。"
  exit 1
fi
echo "✅ 所有 workflow 的 e2e shard 清单均覆盖完整"
