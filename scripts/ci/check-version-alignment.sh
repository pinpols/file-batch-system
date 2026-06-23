#!/usr/bin/env bash
# 版本对齐校验（CI 门禁）
#   1. 应用版本关键落点对齐（pom / load-tests / helm / OpenAPI / SDK 文档 / CHANGELOG）
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

PROD_IMAGE_TAG="$(python3 -c "
import re
txt = open('$ROOT/helm/values-prod.yaml').read()
m = re.search(r'^image:\\s*\\n(?:  .+\\n)*?  tag:\\s*\"?([^\"\\s]+)\"?\\s*$', txt, re.M)
print(m.group(1) if m else '')
")"

ORCH_OPENAPI_VER="$(python3 -c "
import re
txt = open('$ROOT/docs/api/orchestrator-internal.openapi.yaml').read()
m = re.search(r'^info:\\s*\\n(?:  .+\\n)*?  version:\\s*\"?([^\"\\s]+)\"?\\s*$', txt, re.M)
print(m.group(1) if m else '')
")"

SDK_QUICKSTART_VER="$(python3 -c "
import re
txt = open('$ROOT/docs/sdk/quickstart.md').read()
m = re.search(r'<artifactId>batch-worker-sdk</artifactId>\\s*\\n\\s*<version>([^<]+)</version>', txt)
print(m.group(1).strip() if m else '')
")"

echo "── 应用版本 ─────"
echo "pom.xml       <revision> = ${POM_REV}"
echo "Chart.yaml    appVersion = ${CHART_APPVERSION}"
echo "values-prod   image.tag  = ${PROD_IMAGE_TAG}"
echo "orchestrator  OpenAPI    = ${ORCH_OPENAPI_VER}"
echo "sdk quickstart dependency= ${SDK_QUICKSTART_VER}"

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

if [[ "$POM_REV" == *-* ]]; then
  EXPECTED_DEPLOY_TAG="$CHART_APPVERSION"
else
  EXPECTED_DEPLOY_TAG="$POM_REV"
fi

if [[ -z "$PROD_IMAGE_TAG" ]]; then
  echo "  ✗ 未能从 helm/values-prod.yaml 解析 image.tag" >&2
  FAIL=1
elif [[ "$PROD_IMAGE_TAG" != "$EXPECTED_DEPLOY_TAG" ]]; then
  echo "  ✗ values-prod image.tag 应为 ${EXPECTED_DEPLOY_TAG},实际 ${PROD_IMAGE_TAG}" >&2
  FAIL=1
else
  echo "  ✓ values-prod image.tag 对齐"
fi

if [[ "$POM_REV" != *-* ]]; then
  CHANGELOG_STATUS="$(ROOT="$ROOT" POM_REV="$POM_REV" python3 <<'PY'
import os
import pathlib
import re

root = pathlib.Path(os.environ["ROOT"])
version = os.environ["POM_REV"]
path = root / "CHANGELOG.md"
if not path.exists():
    print("MISSING_FILE")
    raise SystemExit

text = path.read_text()
heading = re.compile(rf"^##\s+\[?{re.escape(version)}\]?\s+-\s+\d{{4}}-\d{{2}}-\d{{2}}\s*$", re.M)
match = heading.search(text)
if not match:
    print("MISSING_SECTION")
    raise SystemExit

next_heading = re.search(r"^##\s+", text[match.end():], re.M)
body = text[match.end(): match.end() + next_heading.start()] if next_heading else text[match.end():]
has_category = re.search(r"^###\s+(Added|Changed|Fixed|Removed|Notes)\s*$", body, re.M)
has_bullet = re.search(r"^-\s+\S+", body, re.M)
has_todo = re.search(r"\b(TODO|待补充)\b", body, re.I)
if not has_category or not has_bullet:
    print("EMPTY_SECTION")
elif has_todo:
    print("TODO_SECTION")
else:
    print("OK")
PY
)"
  case "$CHANGELOG_STATUS" in
    OK)
      echo "  ✓ CHANGELOG.md 含 ${POM_REV} 发布小节"
      ;;
    MISSING_FILE)
      echo "  ✗ 缺少 CHANGELOG.md；GA 发布必须有发布说明" >&2
      FAIL=1
      ;;
    MISSING_SECTION)
      echo "  ✗ CHANGELOG.md 缺少 '## [${POM_REV}] - YYYY-MM-DD' 发布小节" >&2
      FAIL=1
      ;;
    EMPTY_SECTION)
      echo "  ✗ CHANGELOG.md 的 ${POM_REV} 小节缺少分类标题或条目" >&2
      FAIL=1
      ;;
    TODO_SECTION)
      echo "  ✗ CHANGELOG.md 的 ${POM_REV} 小节仍含 TODO/待补充" >&2
      FAIL=1
      ;;
    *)
      echo "  ✗ CHANGELOG.md 校验返回未知状态: ${CHANGELOG_STATUS}" >&2
      FAIL=1
      ;;
  esac
else
  echo "  • pre-release 版本:跳过正式 CHANGELOG.md 小节校验"
fi

for pair in "orchestrator OpenAPI:${ORCH_OPENAPI_VER}" "SDK quickstart:${SDK_QUICKSTART_VER}"; do
  label="${pair%%:*}"
  value="${pair#*:}"
  if [[ -z "$value" ]]; then
    echo "  ✗ 未能解析 ${label} 版本" >&2
    FAIL=1
  elif [[ "$value" != "$POM_REV" ]]; then
    echo "  ✗ ${label} 版本与根 <revision> 不一致（${value} != ${POM_REV}）" >&2
    FAIL=1
  else
    echo "  ✓ ${label} 与根版本对齐"
  fi
done

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

# 注:缓存服务运行 Valkey 镜像,但保留 REDIS_IMAGE_TAG 兼容旧 env / 脚本命名。
TAGS=(POSTGRES_IMAGE_TAG KAFKA_IMAGE_TAG KAFKA_UI_IMAGE_TAG MINIO_IMAGE_TAG MINIO_MC_IMAGE_TAG REDIS_IMAGE_TAG VALKEY_IMAGE_TAG \
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
