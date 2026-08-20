#!/usr/bin/env bash
# =============================================================================
# sonar-scan.sh — 本地一键 SonarQube 扫描 + 导出报告
#
# 用法：
#   ./scripts/dev/sonar-scan.sh               # 默认全量扫描
#   ./scripts/dev/sonar-scan.sh --skip-build  # 跳过 mvn install（已构建时）
#   ./scripts/dev/sonar-scan.sh --stop        # 停止并删除 SonarQube 容器
#
# 输出（reports/sonar/<timestamp>/）：
#   sonar-report.csv   — 全量 issue 明细（severity/type/组件/行号/规则/描述）
#   sonar-report.md    — 摘要报告（各模块 BLOCKER/CRITICAL 分布 + 关键指标）
#   reports/sonar/latest -> <timestamp>  （软链，始终指向最新一次）
#
# 依赖：docker、mvn（Java 21）、curl、Python 3
# =============================================================================
set -euo pipefail

# ── 参数 ──────────────────────────────────────────────────────────────────────
SKIP_BUILD=false
STOP_ONLY=false
for arg in "$@"; do
  case $arg in
    --skip-build) SKIP_BUILD=true ;;
    --stop)       STOP_ONLY=true  ;;
  esac
done

# ── 配置 ──────────────────────────────────────────────────────────────────────
SONAR_CONTAINER="sonarqube-batch"
SONAR_PORT="${SONAR_PORT:-9001}"
SONAR_URL="http://localhost:${SONAR_PORT}"
SONAR_ADMIN_USER="admin"
SONAR_ADMIN_PASS="admin"
PROJECT_KEY="file-batch-system"
PROJECT_NAME="File Batch System"
SONAR_MAVEN_PLUGIN_VERSION="${SONAR_MAVEN_PLUGIN_VERSION:-5.7.0.6970}"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../lib/process.sh
source "${PROJECT_ROOT}/scripts/lib/process.sh"
# shellcheck source=../lib/python-runtime.sh
source "${PROJECT_ROOT}/scripts/lib/python-runtime.sh"
SCAN_TS="$(date +%Y-%m-%d_%H-%M-%S)"
OUT_DIR="${PROJECT_ROOT}/reports/sonar/${SCAN_TS}"
TOKEN_NAME="batch-scan-$(date +%s)"

# ── 颜色 ──────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERR ]${NC}  $*" >&2; }

# ── --stop ────────────────────────────────────────────────────────────────────
if $STOP_ONLY; then
  info "Stopping SonarQube container..."
  docker rm -f "$SONAR_CONTAINER" 2>/dev/null && ok "Container removed." || warn "Container not found."
  exit 0
fi

# ── 检查依赖 ──────────────────────────────────────────────────────────────────
for cmd in docker mvn curl "$PYTHON_BIN"; do
  command -v "$cmd" &>/dev/null || { error "Required command not found: $cmd"; exit 1; }
done

# ── 1. 启动 SonarQube ─────────────────────────────────────────────────────────
info "Step 1/5 — Starting SonarQube (port ${SONAR_PORT})..."

if docker ps --filter "name=${SONAR_CONTAINER}" --format '{{.Names}}' | grep -q "$SONAR_CONTAINER"; then
  ok "Container already running, reusing."
elif docker ps -a --filter "name=${SONAR_CONTAINER}" --format '{{.Names}}' | grep -q "$SONAR_CONTAINER"; then
  info "Starting existing container..."
  docker start "$SONAR_CONTAINER"
else
  # 检查端口是否被占用
  if process_port_is_listening "$SONAR_PORT"; then
    error "Port ${SONAR_PORT} already in use. Set SONAR_PORT=<other> to override."
    exit 1
  fi
  docker run -d --name "$SONAR_CONTAINER" \
    -p "${SONAR_PORT}:9000" \
    -e SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true \
    sonarqube:community
  ok "Container started."
fi

# ── 2. 等待就绪 ───────────────────────────────────────────────────────────────
info "Step 2/5 — Waiting for SonarQube to be ready..."
WAIT=0
until curl -sf "${SONAR_URL}/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; do
  sleep 5; WAIT=$((WAIT+5))
  if [ $WAIT -ge 180 ]; then
    error "SonarQube did not start within 3 minutes."
    docker logs "$SONAR_CONTAINER" --tail 30
    exit 1
  fi
  echo -n "."
done
echo ""
ok "SonarQube is UP (${SONAR_URL})"

# 清除 admin 的强制改密标记（H2 直连，容器需处于运行态但刚就绪时 H2 TCP 端口已开）
# SonarQube 启动 H2 TCP server 在 9092，通过端口转发直连
H2_PORT=9092
SONAR_RESET_SQL="$(< "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sql/sonar-reset-password-flag.sql")"
docker exec "$SONAR_CONTAINER" java \
  -cp /opt/sonarqube/lib/jdbc/h2/h2-2.3.232.jar org.h2.tools.Shell \
  -url "jdbc:h2:tcp://127.0.0.1:${H2_PORT}/sonar;NON_KEYWORDS=VALUE" \
  -user "" -password "" \
  -sql "${SONAR_RESET_SQL//:login/${SONAR_ADMIN_USER}}" \
  &>/dev/null || true

# 关闭强制登录，Dashboard 可匿名访问
curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/settings/set" \
  -d "key=sonar.forceAuthentication&value=false" &>/dev/null || true

# ── 3. 生成分析 token ─────────────────────────────────────────────────────────
info "Step 3/5 — Generating analysis token..."

# 清理同名旧 token（忽略错误）
curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/user_tokens/revoke" \
  -d "name=${TOKEN_NAME}" &>/dev/null || true

TOKEN_JSON=$(curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/user_tokens/generate" \
  -d "name=${TOKEN_NAME}&type=GLOBAL_ANALYSIS_TOKEN")
SONAR_TOKEN=$(echo "$TOKEN_JSON" | "$PYTHON_BIN" -c "import json,sys; print(json.load(sys.stdin)['token'])")
ok "Token generated."

# ── 3.5 质量配置：S3776 认知复杂度阈值 15→20 ─────────────────────────────
# 团队约定（避免把 CC 16-21 的轻度越限当作必须重构项）。内置 Sonar way 不可改，
# 这里复制一份自定义 profile 并激活新阈值后绑定项目；幂等，每次扫描前执行。
info "Step 3.5/5 — Applying quality profile (S3776 Threshold=20)..."
CUSTOM_PROFILE_NAME="Batch Platform Sonar Way"
# 提前创建项目，便于绑定 profile（已存在时忽略失败）
curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/projects/create" \
  --data-urlencode "name=${PROJECT_NAME}" \
  --data-urlencode "project=${PROJECT_KEY}" &>/dev/null || true
CUSTOM_PROFILE_KEY=$(curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" \
  "${SONAR_URL}/api/qualityprofiles/search?language=java" \
  | "$PYTHON_BIN" -c "
import json,sys
ps=json.load(sys.stdin)['profiles']
print(next((p['key'] for p in ps if p['name']=='${CUSTOM_PROFILE_NAME}'), ''))")
if [ -z "${CUSTOM_PROFILE_KEY}" ]; then
  FROM_KEY=$(curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" \
    "${SONAR_URL}/api/qualityprofiles/search?language=java" \
    | "$PYTHON_BIN" -c "import json,sys; print(next(p['key'] for p in json.load(sys.stdin)['profiles'] if p['isDefault']))")
  CUSTOM_PROFILE_KEY=$(curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
    "${SONAR_URL}/api/qualityprofiles/copy" \
    --data-urlencode "fromKey=${FROM_KEY}" \
    --data-urlencode "toName=${CUSTOM_PROFILE_NAME}" \
    | "$PYTHON_BIN" -c "import json,sys; print(json.load(sys.stdin)['key'])")
fi
curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/qualityprofiles/activate_rule" \
  --data-urlencode "key=${CUSTOM_PROFILE_KEY}" \
  --data-urlencode "rule=java:S3776" \
  --data-urlencode "params=Threshold=20" >/dev/null
# add_project 的 API 参数是 language + qualityProfile(名称),不是 profile key;
# 先 remove 再 add 保证幂等(重复绑定会 400)。
curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/qualityprofiles/remove_project" \
  --data-urlencode "language=java" \
  --data-urlencode "qualityProfile=${CUSTOM_PROFILE_NAME}" \
  --data-urlencode "project=${PROJECT_KEY}" &>/dev/null || true
if curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/qualityprofiles/add_project" \
  --data-urlencode "language=java" \
  --data-urlencode "qualityProfile=${CUSTOM_PROFILE_NAME}" \
  --data-urlencode "project=${PROJECT_KEY}" >/dev/null; then
  ok "S3776 threshold set to 20 (profile ${CUSTOM_PROFILE_KEY})"
else
  warn "S3776 quality profile activation failed; report may use default threshold 15."
fi

# ── 4. 构建 + 扫描 ────────────────────────────────────────────────────────────
cd "$PROJECT_ROOT"

if ! $SKIP_BUILD; then
  info "Step 4/5 — Running tests and generating JaCoCo XML reports..."
  BUILD_LOG=$(mktemp)
  set +e
  mvn clean test "org.jacoco:jacoco-maven-plugin:0.8.14:report" -q \
    --projects '!batch-e2e-tests' 2>&1 \
    | tee "$BUILD_LOG" \
    | grep -E "ERROR|BUILD"
  BUILD_STATUS=${PIPESTATUS[0]}
  set -e
  if [ "$BUILD_STATUS" -ne 0 ]; then
    error "Build failed; Sonar analysis was not started."
    tail -n 80 "$BUILD_LOG"
    rm -f "$BUILD_LOG"
    exit "$BUILD_STATUS"
  fi
  rm -f "$BUILD_LOG"
  ok "Build complete."
else
  info "Step 4/5 — Skipping test/report generation (--skip-build)."
fi

COVERAGE_REPORT_COUNT=$(find "$PROJECT_ROOT" \
  -path '*/target/site/jacoco/jacoco.xml' \
  -not -path '*/batch-e2e-tests/*' \
  -type f \
  | wc -l | tr -d ' ')
if [ "$COVERAGE_REPORT_COUNT" -eq 0 ]; then
  error "No JaCoCo XML report found. Run without --skip-build or generate target/site/jacoco/jacoco.xml before scanning."
  exit 1
fi
ok "Found $COVERAGE_REPORT_COUNT JaCoCo XML report(s)."

info "         Running Sonar analysis..."
SONAR_LOG=$(mktemp)
set +e
mvn "org.sonarsource.scanner.maven:sonar-maven-plugin:${SONAR_MAVEN_PLUGIN_VERSION}:sonar" \
  --projects '!batch-e2e-tests' \
  -Dsonar.host.url="${SONAR_URL}" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.projectKey="${PROJECT_KEY}" \
  -Dsonar.projectName="${PROJECT_NAME}" \
  -Dsonar.java.source=21 \
  -Dsonar.java.target=21 \
  -Dsonar.java.skipUnchanged=false \
  -Dsonar.analysisCache.enabled=false \
  2>&1 | tee "$SONAR_LOG" | grep -E "INFO.*task|INFO.*More|ERROR.*Unable|BUILD (SUCCESS|FAILURE)"
SONAR_STATUS=${PIPESTATUS[0]}
set -e

if [ "$SONAR_STATUS" -ne 0 ]; then
  error "Sonar analysis failed."
  tail -n 80 "$SONAR_LOG"
  rm -f "$SONAR_LOG"
  exit "$SONAR_STATUS"
fi

# 从同一份日志提取 task id
TASK_URL=$(grep -oE 'http://[^ ]+/api/ce/task\?id=[a-z0-9-]+' "$SONAR_LOG" | tail -1 || echo "")
rm -f "$SONAR_LOG"

if [ -n "$TASK_URL" ]; then
  TASK_ID="${TASK_URL##*id=}"
  info "         Waiting for server-side analysis (task: ${TASK_ID})..."
  WAIT=0
  while true; do
    TASK_STATUS="$(curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" \
      "${SONAR_URL}/api/ce/task?id=${TASK_ID}" \
      | "$PYTHON_BIN" -c "import json,sys; print(json.load(sys.stdin)['task']['status'])" 2>/dev/null || true)"
    case "$TASK_STATUS" in
      SUCCESS)
        break
        ;;
      FAILED|CANCELED)
        error "Server-side analysis ${TASK_STATUS}; reports were not exported."
        exit 1
        ;;
    esac
    sleep 3; WAIT=$((WAIT+3))
    if [ $WAIT -ge 120 ]; then
      error "Timed out waiting for server-side analysis; reports were not exported."
      exit 1
    fi
  done
fi
ok "Analysis complete."

# 项目设为 Public，匿名可直接打开 Dashboard
curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" -X POST \
  "${SONAR_URL}/api/projects/update_visibility" \
  -d "project=${PROJECT_KEY}&visibility=public" &>/dev/null || true

# ── 5. 导出报告 ───────────────────────────────────────────────────────────────
info "Step 5/5 — Exporting reports to reports/sonar/${SCAN_TS}/..."
mkdir -p "$OUT_DIR"

SONAR_PORT="$SONAR_PORT" \
SONAR_ADMIN_USER="$SONAR_ADMIN_USER" \
SONAR_ADMIN_PASS="$SONAR_ADMIN_PASS" \
SONAR_URL="$SONAR_URL" \
PROJECT_KEY="$PROJECT_KEY" \
OUT_DIR="$OUT_DIR" \
"$PYTHON_BIN" - <<'PYEOF'
import base64
import csv
import json
import os
import urllib.request
from datetime import datetime

BASE = f"http://localhost:{os.environ['SONAR_PORT']}"
AUTH = base64.b64encode(
    f"{os.environ['SONAR_ADMIN_USER']}:{os.environ['SONAR_ADMIN_PASS']}".encode()
).decode()
PROJECT_KEY = os.environ["PROJECT_KEY"]
OUT_DIR = os.environ["OUT_DIR"]
SONAR_URL = os.environ["SONAR_URL"]

def get(path):
    req = urllib.request.Request(BASE + path)
    req.add_header("Authorization", f"Basic {AUTH}")
    return json.load(urllib.request.urlopen(req))

# ── 全量 issues ──────────────────────────────────────────────────────────────
all_issues = []
page = 1
while True:
    data = get(f"/api/issues/search?componentKeys={PROJECT_KEY}&ps=500&p={page}&s=SEVERITY&asc=false")
    all_issues += data["issues"]
    if len(all_issues) >= data["total"]:
        break
    page += 1

# ── CSV ──────────────────────────────────────────────────────────────────────
csv_path = f"{OUT_DIR}/sonar-report.csv"
with open(csv_path, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["severity", "type", "component", "line", "rule", "message", "status", "effort"])
    for i in all_issues:
        comp = i.get("component", "").split(":")[-1]
        w.writerow([
            i.get("severity", ""), i.get("type", ""), comp, i.get("line", ""),
            i.get("rule", ""), i.get("message", ""), i.get("status", ""), i.get("effort", ""),
        ])

# ── 汇总指标 ─────────────────────────────────────────────────────────────────
metrics = get(f"/api/measures/component?component={PROJECT_KEY}"
    "&metricKeys=bugs,vulnerabilities,code_smells,security_hotspots,"
    "coverage,duplicated_lines_density,ncloc,sqale_index,"
    "reliability_rating,security_rating,sqale_rating")
m = {x["metric"]: x.get("value","?") for x in metrics["component"]["measures"]}

# ── 按模块 × severity 统计 ───────────────────────────────────────────────────
from collections import defaultdict
mod_sev = defaultdict(lambda: defaultdict(int))
for i in all_issues:
    comp = i.get("component", "").split(":")[-1].split("/")[0]
    mod_sev[comp][i.get("severity","?")] += 1

SEVS = ["BLOCKER","CRITICAL","MAJOR","MINOR","INFO"]
RATING = {"1.0":"A","2.0":"B","3.0":"C","4.0":"D","5.0":"E"}

# ── Markdown 报告 ─────────────────────────────────────────────────────────────
md_path = f"{OUT_DIR}/sonar-report.md"
now = datetime.now().strftime("%Y-%m-%d %H:%M")
with open(md_path, "w", encoding="utf-8") as f:
    f.write(f"# SonarQube Scan Report — File Batch System\n\n")
    f.write(f"扫描时间：{now}   |   SonarQube: {SONAR_URL}/dashboard?id={PROJECT_KEY}\n\n")
    f.write("## 整体指标\n\n")
    f.write("| 指标 | 数值 | 评级 |\n|---|---|---|\n")
    f.write(f"| 代码行数（NCLOC） | {m.get('ncloc','?')} | — |\n")
    f.write(f"| Bug | {m.get('bugs','?')} | {RATING.get(m.get('reliability_rating','?'), m.get('reliability_rating','?'))} |\n")
    f.write(f"| Vulnerability | {m.get('vulnerabilities','?')} | {RATING.get(m.get('security_rating','?'), m.get('security_rating','?'))} |\n")
    f.write(f"| Security Hotspot | {m.get('security_hotspots','?')} | 待审查 |\n")
    f.write(f"| Code Smell | {m.get('code_smells','?')} | {RATING.get(m.get('sqale_rating','?'), m.get('sqale_rating','?'))} |\n")
    f.write(f"| 技术债 | {int(m.get('sqale_index','0'))//60}h {int(m.get('sqale_index','0'))%60}m | — |\n")
    f.write(f"| 重复率 | {m.get('duplicated_lines_density','?')}% | — |\n")
    f.write(f"| 覆盖率 | {m.get('coverage','?')}% | — |\n\n")

    f.write("## 各模块 Issue 分布\n\n")
    f.write(f"| {'模块':<45} | " + " | ".join(f"{s}" for s in SEVS) + " | 合计 |\n")
    f.write("|" + "-"*47 + "|" + "|".join(["------"]*len(SEVS)) + "|-------|\n")
    mods = sorted(mod_sev.keys())
    totals = defaultdict(int)
    for mod in mods:
        counts = [mod_sev[mod][s] for s in SEVS]
        total = sum(counts)
        f.write(f"| {mod:<45} | " + " | ".join(f"{c:>6}" for c in counts) + f" | {total:>5} |\n")
        for s, c in zip(SEVS, counts):
            totals[s] += c
        totals["TOTAL"] += total
    f.write(f"| {'**合计**':<45} | " + " | ".join(f"**{totals[s]}**" for s in SEVS) + f" | **{totals['TOTAL']}** |\n\n")

    # BLOCKER 明细
    blockers = [i for i in all_issues if i.get("severity") == "BLOCKER"]
    if blockers:
        f.write("## BLOCKER 明细\n\n")
        f.write("| 类型 | 文件 | 行 | 描述 |\n|---|---|---|---|\n")
        for i in blockers:
            comp = i.get("component","").split(":")[-1]
            f.write(f"| {i.get('type','')} | `{comp}` | {i.get('line','')} | {i.get('message','')} |\n")
        f.write("\n")

    f.write(f"---\n*详细明细见 `sonar-report.csv`（{len(all_issues)} 条）*\n")

print(f"CSV:{csv_path}  ({len(all_issues)} issues)")
print(f"MD: {md_path}")
PYEOF

# ── latest 软链 ───────────────────────────────────────────────────────────────
LATEST_LINK="${PROJECT_ROOT}/reports/sonar/latest"
ln -sfn "${SCAN_TS}" "$LATEST_LINK"

echo ""
ok "Reports written:"
echo "   reports/sonar/${SCAN_TS}/sonar-report.csv  — $(wc -l < "${OUT_DIR}/sonar-report.csv") lines"
echo "   reports/sonar/${SCAN_TS}/sonar-report.md"
echo "   reports/sonar/latest  ->  ${SCAN_TS}  (symlink)"
echo ""
echo -e "${GREEN}Dashboard:${NC} ${SONAR_URL}/dashboard?id=${PROJECT_KEY}"
echo -e "${YELLOW}Tip:${NC} Run with --stop to shut down the SonarQube container when done."
