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

# Chart.yaml appVersion 跟随**最近一次 GA**（见 Chart.yaml 头注释「禁止落 SNAPSHOT 到 chart」）。
# 因此:
#   - 根 <revision> 是 GA(无 -SNAPSHOT/-RC/-alpha 等预发后缀)时 → 必须与 appVersion 严格一致;
#   - 根 <revision> 是预发态(main 默认 X.Y.Z-SNAPSHOT / RC)时 → appVersion 合法地停在上一个 GA,
#     此时强制相等是错的(正是本脚本过去没接 CI 的原因:main 常态会被误判失败)。
#     仅要求 appVersion 不是预发态(不能落 SNAPSHOT 到 chart)。
if [[ "$POM_REV" == *-* ]]; then
  # 预发态:只校验 appVersion 自身不是预发(GA 形态)。
  if [[ "$CHART_APPVERSION" == *-* ]]; then
    echo "  ✗ pom 预发态(${POM_REV})下 appVersion 不应为预发(${CHART_APPVERSION});chart 禁落 SNAPSHOT/RC" >&2
    FAIL=1
  else
    echo "  ✓ pom 预发态(${POM_REV});appVersion 停在上一 GA(${CHART_APPVERSION})—— 符合 chart 跟随 GA 规则"
  fi
elif [[ "$POM_REV" != "$CHART_APPVERSION" ]]; then
  echo "  ✗ GA 版本不一致:发布时必须把 appVersion 同步到 ${POM_REV}" >&2
  FAIL=1
else
  echo "  ✓ GA 对齐"
fi

# load-tests 是独立 reactor（未纳入根 reactor），无法继承根 ${revision}，版本字面量手工同步。
# CLAUDE.md 点名为高危点 → 必须与根 <revision> 一致。
# 取 <artifactId>batch-load-tests</artifactId> 紧随其后的 project <version>（非 dependency 里的）。
LOADTEST_VER="$(python3 -c "
import re
with open('$ROOT/load-tests/pom.xml') as f:
    txt = f.read()
m = re.search(r'<artifactId>batch-load-tests</artifactId>.*?<version>([^<]+)</version>', txt, re.S)
print(m.group(1).strip() if m else '')
")"

echo "load-tests    <version>  = ${LOADTEST_VER}"
if [[ -z "$LOADTEST_VER" ]]; then
  echo "  ✗ 未能从 load-tests/pom.xml 解析到 batch-load-tests <version>" >&2
  FAIL=1
elif [[ "$LOADTEST_VER" != "$POM_REV" ]]; then
  echo "  ✗ load-tests 版本与根 <revision> 不一致（${LOADTEST_VER} != ${POM_REV}）" >&2
  FAIL=1
else
  echo "  ✓ load-tests 与根版本对齐"
fi

# ─── 2. 基础服务镜像版本（4 个 .env 之间对齐）─────────────────────
echo ""
echo "── 基础服务镜像（4 个 .env 对比） ─────"

# 注:缓存服务已从 Redis 迁到 Valkey,镜像 key 是 VALKEY_IMAGE_TAG（旧 REDIS_IMAGE_TAG 已不存在）。
TAGS=(POSTGRES_IMAGE_TAG KAFKA_IMAGE_TAG MINIO_IMAGE_TAG MINIO_MC_IMAGE_TAG VALKEY_IMAGE_TAG \
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
