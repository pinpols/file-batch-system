#!/usr/bin/env bash
# 守护:依赖许可证合规 + SBOM 生成(R8 守护)。
#
# 背景:仓库已有 -P compliance(cyclonedx + license-maven-plugin),但只「生成」THIRD-PARTY.txt,
# 不「拦」禁用许可证。本守护在 CI 跑 profile 生成清单 + SBOM,并对强 copyleft 许可证 fail。
#
# 禁用(强/网络 copyleft 或 source-available):AGPL / SSPL / BUSL / CPAL / EUPL /
# Commons Clause / Elastic / PolyForm / 纯 GPL(无 Classpath Exception)。未知许可证也阻断。
# 放行:GPL+CPE(jakarta.* / JMH 等标准 Java 生态库,CPE 明确豁免链接义务)、LGPL、EPL、MPL、CDDL、
#       Apache / MIT / BSD / EDL / Public Domain 等。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

THIRD_PARTY="target/generated-sources/license/THIRD-PARTY.txt"

reuse_generated=0
if [[ "${1:-}" == "--reuse-generated" ]]; then
  reuse_generated=1
elif [[ $# -gt 0 ]]; then
  echo "用法：$0 [--reuse-generated]" >&2
  exit 2
fi

# 默认始终重建，避免 target 中的旧产物掩盖 POM 漂移；调用方确认刚生成时才复用。
if ((reuse_generated == 0)) || [[ ! -f "$THIRD_PARTY" || ! -f target/bom.json ]]; then
  echo "ℹ️  生成第三方许可证清单 + SBOM(-P compliance)..."
  ./mvnw -q -P compliance license:aggregate-add-third-party cyclonedx:makeAggregateBom -DskipTests
fi

bash scripts/ci/check-sbom-sync.sh --reuse-generated

if [[ ! -f "$THIRD_PARTY" ]]; then
  echo "❌ 未生成 $THIRD_PARTY"
  exit 1
fi

# 强 copyleft 关键词;GPL 单独处理(要排掉 Classpath Exception / LGPL)。
forbidden="$(grep -iE "AGPL|Affero|SSPL|Server Side Public|BUSL|Business Source|CPAL|Common Public Attribution|EUPL|Commons Clause|Elastic License|PolyForm" "$THIRD_PARTY" || true)"
unknown="$(grep -iE '^\s+\((unknown|no license|unlicensed|none|noassertion)\)' "$THIRD_PARTY" || true)"

# 纯 GPL:含 GPL,但排除两类合法情形:
#   ① Classpath Exception 变体:classpath / CPE / +CE / GPLv2+CE(链接豁免,不传染);LGPL/Lesser 弱 copyleft。
#   ② 双授权:同行还列了 permissive 许可证(Apache/MIT/BSD/EPL/EDL/MPL/CDDL),可选 permissive 一侧。
pure_gpl="$(grep -iE "GPL|General Public License" "$THIRD_PARTY" \
  | grep -ivE "classpath|CPE|\+CE|GPLv2\+CE|LGPL|Lesser|Library General" \
  | grep -ivE "Apache|MIT|BSD|EPL|Eclipse Public|EDL|MPL|Mozilla|CDDL" || true)"

if [[ -n "$forbidden$pure_gpl$unknown" ]]; then
  echo "❌ 发现禁用、source-available 或未知许可证依赖:"
  [[ -n "$forbidden" ]] && printf '%s\n' "$forbidden"
  [[ -n "$pure_gpl" ]] && printf '%s\n' "$pure_gpl"
  [[ -n "$unknown" ]] && printf '%s\n' "$unknown"
  echo
  echo "💥 强/网络 copyleft、source-available 与未知许可证禁止直接引入。"
  echo "   如为 GPL+Classpath-Exception 被误报,请在依赖名里确认含 'Classpath exception' 字样。"
  exit 1
fi

echo "✅ 许可证合规:无强 copyleft、source-available 或未知许可证依赖(SBOM 见 target/bom.json)"
