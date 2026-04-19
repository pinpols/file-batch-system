#!/usr/bin/env bash
# 版本对齐校验（CI 门禁）
#   1. pom.xml <revision>  ==  Chart.yaml appVersion   （应用版本）
#   2. 4 个 .env 文件里每个 *_IMAGE_TAG 值一致           （基础服务版本跨环境不漂移）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FAIL=0

# ─── 1. 应用版本对齐 ──────────────────────────────────────────────
POM_REV="$(python3 -c "
import re
with open('$ROOT/pom.xml') as f:
    m = re.search(r'<revision>([^<]+)</revision>', f.read())
    print(m.group(1) if m else '')
")"

CHART_APPVERSION="$(python3 -c "
import re
with open('$ROOT/helm/batch-platform/Chart.yaml') as f:
    m = re.search(r'^appVersion:\s*\"?([^\"\s]+)\"?\s*$', f.read(), re.M)
    print(m.group(1) if m else '')
")"

echo "── 应用版本 ─────"
echo "pom.xml       <revision> = ${POM_REV}"
echo "Chart.yaml    appVersion = ${CHART_APPVERSION}"

if [[ "$POM_REV" != "$CHART_APPVERSION" ]]; then
  echo "  ✗ 应用版本不一致" >&2
  FAIL=1
else
  echo "  ✓ 对齐"
fi

# ─── 2. 基础服务镜像版本（4 个 .env 之间对齐）─────────────────────
echo ""
echo "── 基础服务镜像（4 个 .env 对比） ─────"

TAGS=(POSTGRES_IMAGE_TAG KAFKA_IMAGE_TAG MINIO_IMAGE_TAG MINIO_MC_IMAGE_TAG REDIS_IMAGE_TAG \
      REDIS_EXPORTER_IMAGE_TAG POSTGRES_EXPORTER_IMAGE_TAG KAFKA_EXPORTER_IMAGE_TAG)

# .env.example 是仓内模板（tracked），.env.local/test/prod 由开发者各自持有（.gitignore）。
# 校验逻辑：以 .env.example 为基准，所有本地存在的 .env.* 必须匹配。
for tag in "${TAGS[@]}"; do
  base=$(grep -E "^${tag}=" "$ROOT/.env.example" 2>/dev/null | head -1 | cut -d= -f2-)
  if [[ -z "$base" ]]; then
    echo "  ✗ ${tag} 在 .env.example 缺失（模板必须给出）" >&2
    FAIL=1
    continue
  fi

  drift=""
  for env in local test prod; do
    f="$ROOT/.env.${env}"
    [[ ! -f "$f" ]] && continue                    # 本机没有该环境文件 → 跳过
    v=$(grep -E "^${tag}=" "$f" 2>/dev/null | head -1 | cut -d= -f2-)
    if [[ -z "$v" ]]; then
      drift+=" ${env}=MISSING"
    elif [[ "$v" != "$base" ]]; then
      drift+=" ${env}=${v}"
    fi
  done

  if [[ -n "$drift" ]]; then
    echo "  ✗ ${tag} = ${base} (example)；漂移：${drift}" >&2
    FAIL=1
  else
    echo "  ✓ ${tag} = ${base}"
  fi
done

echo ""
if (( FAIL == 1 )); then
  echo "ERROR: 有版本不一致，见上方 ✗" >&2
  exit 1
fi
echo "✅ 全部对齐"
