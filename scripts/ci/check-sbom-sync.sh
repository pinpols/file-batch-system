#!/usr/bin/env bash
# 校验入库 CycloneDX SBOM 与当前 Maven 依赖图一致。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

GENERATED_SBOM="target/bom.json"
TRACKED_SBOM="docs/compliance/sbom.json"

if [[ "${1:-}" != "--reuse-generated" || ! -f "$GENERATED_SBOM" ]]; then
  ./mvnw -q -P compliance cyclonedx:makeAggregateBom -DskipTests
fi

if [[ ! -f "$GENERATED_SBOM" ]]; then
  echo "❌ 未生成 $GENERATED_SBOM" >&2
  exit 1
fi
if [[ ! -f "$TRACKED_SBOM" ]]; then
  echo "❌ 缺少入库 SBOM：$TRACKED_SBOM" >&2
  exit 1
fi

if ! python3 scripts/ci/compare-sbom.py "$TRACKED_SBOM" "$GENERATED_SBOM"; then
  echo "❌ 入库 SBOM 与当前 Maven 依赖图不一致。" >&2
  echo "   请运行：./mvnw -q -P compliance cyclonedx:makeAggregateBom -DskipTests" >&2
  echo "   然后同步：cp target/bom.json docs/compliance/sbom.json" >&2
  exit 1
fi

echo "✅ SBOM 与当前 Maven 依赖图一致"
