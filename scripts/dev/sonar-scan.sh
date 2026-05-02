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
# 依赖：docker、mvn（Java 21）、curl、python3
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
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
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
for cmd in docker mvn curl python3; do
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
  if lsof -iTCP:"$SONAR_PORT" -sTCP:LISTEN &>/dev/null; then
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
docker exec "$SONAR_CONTAINER" java \
  -cp /opt/sonarqube/lib/jdbc/h2/h2-2.3.232.jar org.h2.tools.Shell \
  -url "jdbc:h2:tcp://127.0.0.1:${H2_PORT}/sonar;NON_KEYWORDS=VALUE" \
  -user "" -password "" \
  -sql "UPDATE USERS SET RESET_PASSWORD=FALSE WHERE LOGIN='${SONAR_ADMIN_USER}';" \
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
SONAR_TOKEN=$(echo "$TOKEN_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
ok "Token generated."

# ── 4. 构建 + 扫描 ────────────────────────────────────────────────────────────
cd "$PROJECT_ROOT"

if ! $SKIP_BUILD; then
  info "Step 4/5 — Building project (mvn install -DskipTests)..."
  mvn install -DskipTests -q --projects '!batch-e2e-tests' 2>&1 \
    | grep -E "ERROR|BUILD" || true
  ok "Build complete."
else
  info "Step 4/5 — Skipping build (--skip-build)."
fi

info "         Running Sonar analysis..."
SONAR_LOG=$(mktemp)
mvn sonar:sonar \
  --projects '!batch-e2e-tests' \
  -Dsonar.host.url="${SONAR_URL}" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.projectKey="${PROJECT_KEY}" \
  -Dsonar.projectName="${PROJECT_NAME}" \
  -Dsonar.java.source=21 \
  -Dsonar.java.target=21 \
  2>&1 | tee "$SONAR_LOG" | grep -E "INFO.*task|INFO.*More|ERROR.*Unable|BUILD (SUCCESS|FAILURE)" || true

# 从同一份日志提取 task id
TASK_URL=$(grep -oE 'http://[^ ]+/api/ce/task\?id=[a-z0-9-]+' "$SONAR_LOG" | tail -1 || echo "")
rm -f "$SONAR_LOG"

if [ -n "$TASK_URL" ]; then
  TASK_ID="${TASK_URL##*id=}"
  info "         Waiting for server-side analysis (task: ${TASK_ID})..."
  WAIT=0
  until curl -sf -u "${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}" \
    "${SONAR_URL}/api/ce/task?id=${TASK_ID}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); exit(0 if d['task']['status']=='SUCCESS' else 1)" 2>/dev/null; do
    sleep 3; WAIT=$((WAIT+3))
    if [ $WAIT -ge 120 ]; then warn "Timed out waiting for task; report may be incomplete."; break; fi
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

python3 - <<PYEOF
import json, urllib.request, base64, csv, io, sys
from datetime import datetime

BASE = "http://localhost:${SONAR_PORT}"
AUTH = base64.b64encode(b"${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASS}").decode()

def get(path):
    req = urllib.request.Request(BASE + path)
    req.add_header("Authorization", f"Basic {AUTH}")
    return json.load(urllib.request.urlopen(req))

# ── 全量 issues ──────────────────────────────────────────────────────────────
all_issues = []
page = 1
while True:
    data = get(f"/api/issues/search?componentKeys=${PROJECT_KEY}&ps=500&p={page}&s=SEVERITY&asc=false")
    all_issues += data["issues"]
    if len(all_issues) >= data["total"]:
        break
    page += 1

# ── CSV ──────────────────────────────────────────────────────────────────────
csv_path = "${OUT_DIR}/sonar-report.csv"
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
metrics = get("/api/measures/component?component=${PROJECT_KEY}"
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
md_path = "${OUT_DIR}/sonar-report.md"
now = datetime.now().strftime("%Y-%m-%d %H:%M")
with open(md_path, "w", encoding="utf-8") as f:
    f.write(f"# SonarQube Scan Report — File Batch System\n\n")
    f.write(f"扫描时间：{now}   |   SonarQube: ${SONAR_URL}/dashboard?id=${PROJECT_KEY}\n\n")
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
