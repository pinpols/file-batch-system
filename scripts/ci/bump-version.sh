#!/usr/bin/env bash
# =========================================================================
# bump-version.sh — 一条命令升级应用版本，同步改两处
#
#   用法：bash scripts/ci/bump-version.sh <NEW_VERSION>
#
#   例：  bash scripts/ci/bump-version.sh 1.0.1
#        bash scripts/ci/bump-version.sh 1.1.0
#
#   自动修改：
#     - pom.xml   <revision>NEW_VERSION</revision>
#     - helm/batch-platform/Chart.yaml   appVersion: "NEW_VERSION"
#
#   Chart.yaml 的 version 字段（chart 自身版本）不动——除非你同时改了 chart
#   模板/values 结构，那属于另一件事，手工递增。
#
#   完成后自动跑 check-version-alignment.sh 校验。
# =========================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

NEW="${1:-}"
if [[ -z "$NEW" ]]; then
  echo "用法: $0 <NEW_VERSION>" >&2
  echo "例:   $0 1.0.1" >&2
  exit 1
fi

# 粗校验：X.Y.Z 或带后缀（rc1/SNAPSHOT）
if ! [[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$ ]]; then
  echo "ERROR: 版本号格式不对（期望 X.Y.Z 或 X.Y.Z-rc1）：${NEW}" >&2
  exit 1
fi

echo "==> 升级到 ${NEW}"

# 1) pom.xml <revision>
python3 <<PY
import re, pathlib
p = pathlib.Path("$ROOT/pom.xml")
text = p.read_text()
new = re.sub(r'<revision>[^<]+</revision>', '<revision>${NEW}</revision>', text, count=1)
p.write_text(new)
print(f"  ✓ pom.xml <revision> → ${NEW}")
PY

# 2) Chart.yaml appVersion
python3 <<PY
import re, pathlib
p = pathlib.Path("$ROOT/helm/batch-platform/Chart.yaml")
text = p.read_text()
new = re.sub(r'^appVersion:.*$', 'appVersion: "${NEW}"', text, count=1, flags=re.M)
p.write_text(new)
print(f"  ✓ Chart.yaml appVersion → \"${NEW}\"")
PY

echo ""
echo "==> 校验对齐"
bash "$ROOT/scripts/ci/check-version-alignment.sh"

echo ""
echo "==> Done"
echo "下一步："
echo "  1. 可选：人工更新 CHANGELOG.md 加一节 '## ${NEW}'"
echo "  2. 可选：如 chart 模板/values 也改了，手工递增 Chart.yaml 的 version"
echo "  3. git diff; git commit -am 'chore: bump version to ${NEW}'"
echo "  4. 重新构建镜像：./scripts/docker/build-apps.sh  （tag 会自动是 ${NEW}）"
