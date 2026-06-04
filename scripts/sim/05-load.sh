#!/usr/bin/env bash
if (( BASH_VERSINFO[0] < 4 )); then
  echo "❌ 需 bash 4+,macOS 默认 3.2 不支持 declare -A。请 brew install bash 后用 /usr/local/bin/bash 跑。" >&2
  exit 1
fi
# =========================================================
# 05-load.sh:中量触发 ta/tb/tc 的 4 类 job(IMPORT / EXPORT / DISPATCH / WORKFLOW)
#
# 不依赖 k6,纯 curl 循环。中量负载(默认每 job 跑 5 次,共 75 次触发)。
#
# 用法:
#   bash scripts/sim/05-load.sh                  # 默认 5 次 / job
#   ROUNDS=10 bash scripts/sim/05-load.sh        # 10 次 / job
#   ONLY=ta bash scripts/sim/05-load.sh          # 只触 ta
# =========================================================
set -uo pipefail

TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
ROUNDS="${ROUNDS:-5}"
ONLY="${ONLY:-}"      # ta|tb|tc 或空表示全部
BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
# /api/triggers/launch 走内部鉴权 + 幂等键;secret 默认 internal-secret,本地若 .env.local 注入 BATCH_INTERNAL_SECRET 则用之
INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"

declare -A JOBS=(
  [ta]="TA_IMPORT_CUSTOMER TA_IMPORT_ORDER TA_EXPORT_REPORT TA_DISPATCH_ORDER TA_WF_SETTLEMENT"
  [tb]="TB_IMPORT_TRANSACTION TB_EXPORT_STATEMENT TB_DISPATCH_SETTLE TB_WF_RECONCILE"
  [tc]="TC_IMPORT_RISK_SCORE TC_EXPORT_RISK_ALERT TC_DISPATCH_REVIEW TC_WF_RISK_PIPELINE TC_WF_GATEWAY_ALL TC_WF_GATEWAY_N_OF"
)

TENANTS=(ta tb tc)
[[ -n "$ONLY" ]] && TENANTS=("$ONLY")

total=0; succ=0
echo "==> 启动 ${ROUNDS} 轮 × $(for t in "${TENANTS[@]}"; do echo "${JOBS[$t]}" | wc -w; done | paste -sd+ - | bc) jobs"
START=$(date +%s)

for round in $(seq 1 "$ROUNDS"); do
  for tenant in "${TENANTS[@]}"; do
    for job in ${JOBS[$tenant]}; do
      total=$((total + 1))
      req_id="sim-${round}-${tenant}-${job}-$(date +%s%N | tail -c 8)"
      resp=$(curl -sf --max-time 30 --connect-timeout 5 -X POST \
        -H "Content-Type: application/json" \
        -H "X-Tenant-Id: $tenant" \
        -H "X-Internal-Secret: $INTERNAL_SECRET" \
        -H "Idempotency-Key: $req_id" \
        -H "X-Request-Id: $req_id" \
        "$TRIGGER_BASE/api/triggers/launch" \
        -d "{\"tenantId\":\"$tenant\",\"jobCode\":\"$job\",\"triggerType\":\"API\",\"bizDate\":\"$BIZ_DATE\",\"requestId\":\"$req_id\"}" 2>&1)
      if echo "$resp" | grep -qE '"code"\s*:\s*"(SUCCESS|OK)"'; then
        succ=$((succ + 1))
        printf "."
      else
        printf "x"
      fi
    done
  done
done
echo
ELAPSED=$(($(date +%s) - START))
echo "==> ✅ 完成:total=$total succ=$succ time=${ELAPSED}s rate=$(echo "scale=1; $total/$ELAPSED" | bc 2>/dev/null || echo '?')/s"
echo
echo "提示:launch 仅是触发 ack,worker 真正写 MinIO/biz.* 还要 60~120s。"
echo "      建议 sleep 120 后再跑 scripts/sim/06-verify.sh(EXPORT 写文件偏慢)。"
echo "      若 06 报 EXPORT 0 文件,先看 06 PREREQ 段(worker-export 是否在跑 / job_definition 是否齐)。"
