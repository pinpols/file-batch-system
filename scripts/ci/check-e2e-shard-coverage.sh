#!/usr/bin/env bash
# 用途：E2E shard 覆盖。
# 用法：在仓库根目录执行 bash scripts/ci/check-e2e-shard-coverage.sh；参数与前置环境见下方说明及所属 README。
# 守护:e2e shard 清单漂移。
#
# 背景:full-ci-gate / staging-gate 的 e2e 用硬编码 `-Dtest=<逗号分隔类名>` 分 shard 跑,
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

# e2e shard 实跑的是 batch-e2e-tests 模块(failsafe 只抓该模块 **/*E2eIT.java),
# 故覆盖比对的"实际"集合限定在 batch-e2e-tests,而非全仓。
# (全仓 find 会把别模块里误命名为 *E2eIT 的测试拽进来——它们不在 shard 跑的模块里,既无法被 shard 执行,
#  又逼清单虚列 → 与 check-e2e-run-completeness「列了必须真跑」冲突。误命名的应改回 *IntegrationTest。)
actual="$(find batch-e2e-tests -name '*E2eIT.java' -not -path '*/target/*' \
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
  # 方法分片使用明确的 Class#method+method 选择器，不支持通配符。
  listed_raw="$(grep -E '^[[:space:]]+[A-Za-z][A-Za-z0-9]*E2eIT(#[A-Za-z][A-Za-z0-9_+]+)?,?[[:space:]]*$' "$wf" \
    | sed -E 's/[[:space:],]//g' | sort || true)"
  duplicates="$(printf '%s\n' "$listed_raw" | uniq -d)"
  listed="$(printf '%s\n' "$listed_raw" | sed 's/#.*//' | sort -u)"
  listed_count="$(printf '%s\n' "$listed" | grep -c . || true)"

  missing="$(comm -23 <(printf '%s\n' "$actual") <(printf '%s\n' "$listed"))"
  stale="$(comm -13 <(printf '%s\n' "$actual") <(printf '%s\n' "$listed"))"

  # 只对按方法拆分的类检查：方法集合必须与源码一致，且不能混用整类和部分方法。
  method_error=0
  if ! python3 - "$wf" <<'PY'
import collections
import re
import sys
from pathlib import Path

workflow = Path(sys.argv[1])
pattern = re.compile(r"^\s+([A-Za-z][A-Za-z0-9]*E2eIT)(?:#([A-Za-z][A-Za-z0-9_+]+))?,?\s*$")
selection = collections.defaultdict(list)
for line in workflow.read_text(encoding="utf-8").splitlines():
    match = pattern.fullmatch(line)
    if match:
        selection[match.group(1)].append(match.group(2))
    elif re.match(r"^\s+[A-Za-z][A-Za-z0-9]*E2eIT#", line):
        sys.exit(f"❌ 非法的方法选择器: {line.strip()}")
for cls, groups in selection.items():
    if all(group is None for group in groups):
        continue
    if any(group is None for group in groups):
        sys.exit(f"❌ 整类和方法分片混用: {cls}")
    files = list(Path("batch-e2e-tests").rglob(f"{cls}.java"))
    files = [path for path in files if "target" not in path.parts]
    if len(files) != 1:
        sys.exit(f"❌ 找不到唯一测试源码: {cls}")
    source = files[0].read_text(encoding="utf-8")
    annotations = re.findall(r"^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b", source, re.M)
    methods = re.findall(r'@Test\s+@DisplayName\([^\n]*\)\s+void\s+(\w+)\s*\(', source)
    if len(methods) != len(annotations) or len(set(methods)) != len(methods):
        sys.exit(f"❌ 无法完整识别测试方法，拒绝方法分片: {cls}")
    selected = [method for group in groups for method in group.split("+")]
    if len(selected) != len(set(selected)) or set(selected) != set(methods):
        sys.exit(f"❌ 方法分片漏项或重复: {cls}; expected={methods}; actual={selected}")
PY
  then
    fail=1
    method_error=1
  fi

  echo
  echo "===== $wf (清单 ${listed_count} 个) ====="
  if [[ -z "$missing" && -z "$stale" && -z "$duplicates" && "$method_error" -eq 0 ]]; then
    echo "✅ 覆盖完整,无漂移"
    continue
  fi
  if [[ -n "$missing" ]]; then
    echo "❌ 漏跑(仓库有、清单没列):"
    printf '%s\n' "$missing" | sed 's/^/   - /'
    fail=1
  fi
  if [[ -n "$stale" ]]; then
    echo "❌ stale(清单列了、仓库已无,-Dtest 静默匹配不到):"
    printf '%s\n' "$stale" | sed 's/^/   - /'
    fail=1
  fi
  if [[ -n "$duplicates" ]]; then
    echo "❌ 重复登记(同一测试类会在多个分片重复执行):"
    printf '%s\n' "$duplicates" | sed 's/^/   - /'
    fail=1
  fi
done

echo
if [[ "$fail" -ne 0 ]]; then
  echo "💥 e2e shard 清单与实际 *E2eIT 不一致 —— 请同步上述 workflow 的 shard tests 清单。"
  exit 1
fi
echo "✅ 所有 workflow 的 e2e shard 清单均覆盖完整"
