#!/usr/bin/env bash
# =========================================================================
# bump-version.sh — 一条命令升级应用版本，同步关键落点
#
#   用法：bash scripts/ci/bump-version.sh <NEW_VERSION>
#
#   例：  bash scripts/ci/bump-version.sh 1.0.1
#        bash scripts/ci/bump-version.sh 1.1.0
#
#   自动修改：
#     - pom.xml   <revision>NEW_VERSION</revision>
#     - load-tests/pom.xml   <version>NEW_VERSION</version>
#     - docs/api/orchestrator-internal.openapi.yaml   info.version
#     - docs/sdk/quickstart.md   Java SDK dependency version
#     - GA 版本在 CHANGELOG.md 自动补正式版本小节（如已存在则不改）
#     - GA 版本额外同步 helm/batch-platform/Chart.yaml appVersion 与 helm/values-prod.yaml image.tag
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

ROOT="$ROOT" NEW_VERSION="$NEW" python3 <<'PY'
import os
import datetime
import re, pathlib
root = pathlib.Path(os.environ["ROOT"])
new_version = os.environ["NEW_VERSION"]
is_ga = "-" not in new_version

def replace(path, pattern, repl, flags=0, label=None):
    p = root / path
    text = p.read_text()
    updated, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"ERROR: failed to update {path}")
    p.write_text(updated)
    print(f"  ✓ {label or path} → {new_version}")

replace("pom.xml", r"<revision>[^<]+</revision>", f"<revision>{new_version}</revision>", label="pom.xml <revision>")
replace(
    "load-tests/pom.xml",
    r"(<artifactId>batch-load-tests</artifactId>.*?<version>)[^<]+(</version>)",
    rf"\g<1>{new_version}\g<2>",
    flags=re.S,
    label="load-tests <version>",
)
replace(
    "docs/api/orchestrator-internal.openapi.yaml",
    r"(^info:\s*\n(?:  .+\n)*?  version:\s*)\"?[^\s\"]+\"?",
    rf"\g<1>{new_version}",
    flags=re.M,
    label="orchestrator OpenAPI version",
)
replace(
    "docs/sdk/quickstart.md",
    r"(<artifactId>batch-worker-sdk</artifactId>\s*\n\s*<version>)[^<]+(</version>)",
    rf"\g<1>{new_version}\g<2>",
    label="SDK quickstart dependency",
)
replace(
    "docs/sdk/quickstart.md",
    r"(平台 `<revision>` 当前 `)[^`]+(`)",
    rf"\g<1>{new_version}\g<2>",
    label="SDK quickstart current revision",
)

if is_ga:
    replace(
        "helm/batch-platform/Chart.yaml",
        r"^appVersion:.*$",
        f'appVersion: "{new_version}"',
        flags=re.M,
        label="Chart.yaml appVersion",
    )
    replace(
        "helm/values-prod.yaml",
        r"(^image:\s*\n(?:  .+\n)*?  tag:\s*)\"?[^\s\"]+\"?",
        rf'\g<1>"{new_version}"',
        flags=re.M,
        label="values-prod image.tag",
    )
    changelog = root / "CHANGELOG.md"
    text = changelog.read_text()
    header_pattern = re.compile(rf"^##\s+\[?{re.escape(new_version)}\]?\s+-\s+\d{{4}}-\d{{2}}-\d{{2}}\s*$", re.M)
    if header_pattern.search(text):
        print(f"  • CHANGELOG.md 已存在 {new_version} 小节")
    else:
        today = datetime.date.today().isoformat()
        entry = (
            f"## [{new_version}] - {today}\n\n"
            "### Changed\n"
            f"- 发布 `{new_version}`，同步应用版本、部署镜像标签、OpenAPI 与 SDK 文档。\n\n"
            "---\n\n"
        )
        marker = "## [Unreleased]"
        idx = text.find(marker)
        if idx < 0:
            raise SystemExit("ERROR: failed to locate CHANGELOG.md [Unreleased] section")
        next_rule = text.find("---", idx)
        if next_rule < 0:
            raise SystemExit("ERROR: failed to locate CHANGELOG.md insertion point")
        insert_at = next_rule + len("---")
        updated = text[:insert_at] + "\n\n" + entry + text[insert_at:].lstrip("\n")
        changelog.write_text(updated)
        print(f"  ✓ CHANGELOG.md → add {new_version} release section")
else:
    print("  • pre-release 版本:保留 Chart.appVersion / values-prod image.tag 指向上一 GA")
PY

echo ""
echo "==> 校验对齐"
bash "$ROOT/scripts/ci/check-version-alignment.sh"

echo ""
echo "==> Done"
echo "下一步："
echo "  1. 按需扩充 CHANGELOG.md 中 '${NEW}' 的发布说明"
echo "  2. 可选：如 chart 模板/values 也改了，手工递增 Chart.yaml 的 version"
echo "  3. git diff; git commit -am 'chore: bump version to ${NEW}'"
echo "  4. 重新构建镜像：./scripts/docker/build-apps.sh  （tag 会自动是 ${NEW}）"
