#!/usr/bin/env bash
# 守护:OpenAPI 破坏性变更(oasdiff)。
#
# 背景:已有 check-console-openapi-paths.py 守「controller ↔ openapi 路由存在性同步」,
# 但不查语义破坏 —— 删端点 / 删必填字段 / 收窄枚举 / 改类型,FE 等独立消费方会运行时崩。
# 本守护对每个受管 openapi,比 base 分支版本,命中 ERR 级 breaking 即 fail(additive/deprecation 仅 warning,放行)。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASE_REF="${1:-${OASDIFF_BASE_REF:-origin/main}}"

SPECS=(
  "docs/api/console-api.openapi.yaml"
  "docs/api/orchestrator-internal.openapi.yaml"
)

if ! command -v oasdiff >/dev/null 2>&1; then
  echo "❌ 未找到 oasdiff(CI 应经 action 安装)"
  exit 1
fi

fail=0
for spec in "${SPECS[@]}"; do
  if ! git cat-file -e "${BASE_REF}:${spec}" 2>/dev/null; then
    echo "ℹ️  $spec 在 base 不存在(新增 spec),跳过 breaking 比对"
    continue
  fi
  base_tmp="$(mktemp)"
  clarification_ignore_tmp="$(mktemp)"
  git show "${BASE_REF}:${spec}" > "$base_tmp"
  echo "===== $spec(vs $BASE_REF)====="
  # 旧 schema 完全未声明 data 类型时,补成实际 object/array 只是契约澄清,不会改变 wire。
  # oasdiff 会把 blank -> object/array 误判为 response-property-type-changed ERR。
  # 仅动态忽略这一种精确形态;已有明确类型之间的变化仍由 breaking gate 拦截。
  oasdiff breaking "$base_tmp" "$spec" --format singleline 2>/dev/null \
    | grep -E 'response.s property type/format changed from ``/`` to `(object|array)`/``.*\[response-property-type-changed\]' \
      > "$clarification_ignore_tmp" || true
  # 仅 ERR 级 breaking 才 fail;成功时静默(该 spec 有重复参数定义,oasdiff 会刷大量
  # request-parameter-removed 的 WARN 噪音,失败时才打全量便于定位)。
  out="$(oasdiff breaking "$base_tmp" "$spec" --fail-on ERR \
    --err-ignore "$clarification_ignore_tmp" 2>&1)" && rc=0 || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "✅ 无 ERR 级破坏性变更"
  else
    echo "$out"
    echo "❌ $spec 存在 ERR 级破坏性变更(见上)。如确为有意 breaking,需走版本化端点 + 弃用流程。"
    fail=1
  fi
  rm -f "$base_tmp" "$clarification_ignore_tmp"
  echo
done

if [[ "$fail" -ne 0 ]]; then
  echo "💥 OpenAPI 破坏性变更门禁未通过。"
  exit 1
fi
echo "✅ 所有受管 OpenAPI 无破坏性变更"
