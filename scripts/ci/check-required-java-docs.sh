#!/usr/bin/env bash
# 守护：应用入口和 Spring 配置类型必须说明其架构职责。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

mapfile -t candidates < <(
  rg -l '@(SpringBootApplication|Configuration|AutoConfiguration|ConfigurationProperties)' \
    --glob '*/src/main/java/**/*.java' | sort -u
)

missing=0
for source in "${candidates[@]}"; do
  if ! awk '
    BEGIN { documented = 0; type_found = 0 }
    /^\/\*\*/ { documented = 1 }
    /^(public )?(abstract )?(final )?(class|interface|record|enum) / {
      type_found = 1
      exit(documented ? 0 : 1)
    }
    END { if (!type_found) exit 1 }
  ' "$source"; then
    echo "❌ $source: 应用入口或 Spring 配置类型缺少顶层 Javadoc"
    missing=1
  fi
done

if [[ "$missing" -ne 0 ]]; then
  echo "💥 Java 架构边界类型注释检查失败。"
  exit 1
fi

echo "✅ 应用入口和 Spring 配置类型均有顶层 Javadoc"
