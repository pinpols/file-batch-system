#!/usr/bin/env bash
# 守护:依赖许可证合规 + SBOM 生成(R8 守护)。
#
# 背景:仓库已有 -P compliance(cyclonedx + license-maven-plugin),但只「生成」THIRD-PARTY.txt,
# 不「拦」禁用许可证。本守护在 CI 跑 profile 生成清单 + SBOM,并对强 copyleft 许可证 fail。
#
# 禁用(强/网络 copyleft,会传染闭源):AGPL / SSPL / CPAL / EUPL / 纯 GPL(无 Classpath Exception)。
# 放行:GPL+CPE(jakarta.* / JMH 等标准 Java 生态库,CPE 明确豁免链接义务)、LGPL、EPL、MPL、CDDL、
#       Apache / MIT / BSD / EDL / Public Domain 等。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

THIRD_PARTY="target/generated-sources/license/THIRD-PARTY.txt"

# CI 调用前应已跑 mvn -P compliance ...;本地未生成则补跑。
if [[ ! -f "$THIRD_PARTY" ]]; then
  echo "ℹ️  生成第三方许可证清单 + SBOM(-P compliance)..."
  mvn -q -P compliance license:aggregate-add-third-party cyclonedx:makeAggregateBom -DskipTests
fi

if [[ ! -f "$THIRD_PARTY" ]]; then
  echo "❌ 未生成 $THIRD_PARTY"
  exit 1
fi

# 强 copyleft 关键词;GPL 单独处理(要排掉 Classpath Exception / LGPL)。
forbidden="$(grep -iE "AGPL|Affero|SSPL|Server Side Public|CPAL|Common Public Attribution|EUPL" "$THIRD_PARTY" || true)"

# 纯 GPL:含 GPL,但排除两类合法情形:
#   ① Classpath Exception 变体:classpath / CPE / +CE / GPLv2+CE(链接豁免,不传染);LGPL/Lesser 弱 copyleft。
#   ② 双授权:同行还列了 permissive 许可证(Apache/MIT/BSD/EPL/EDL/MPL/CDDL),可选 permissive 一侧。
pure_gpl="$(grep -iE "GPL|General Public License" "$THIRD_PARTY" \
  | grep -ivE "classpath|CPE|\+CE|GPLv2\+CE|LGPL|Lesser|Library General" \
  | grep -ivE "Apache|MIT|BSD|EPL|Eclipse Public|EDL|MPL|Mozilla|CDDL" || true)"

if [[ -n "$forbidden$pure_gpl" ]]; then
  echo "❌ 发现禁用的强 copyleft 许可证依赖:"
  [[ -n "$forbidden" ]] && printf '%s\n' "$forbidden"
  [[ -n "$pure_gpl" ]] && printf '%s\n' "$pure_gpl"
  echo
  echo "💥 强/网络 copyleft(AGPL/SSPL/CPAL/EUPL/纯 GPL)会传染闭源,禁止引入。"
  echo "   如为 GPL+Classpath-Exception 被误报,请在依赖名里确认含 'Classpath exception' 字样。"
  exit 1
fi

echo "✅ 许可证合规:无强 copyleft 依赖(SBOM 见 target/bom.json)"
