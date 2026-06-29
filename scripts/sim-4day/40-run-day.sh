#!/usr/bin/env bash
# ADR-sim 4day · P4 单日驱动:对 10 租户按 archetype 跑 IMPORT→EXPORT→DISPATCH→WORKFLOW。
# IMPORT 走「内联 content + templateCode」(已验证的真加载路径);行 key 带 bizDate => 每天新增=增量。
# 用法: bash 40-run-day.sh <bizDate YYYY-MM-DD> [ROWS] [tenant...]
#   bash 40-run-day.sh 2026-06-06            # 全 10 租户,默认 300 行/导入
#   ROWS=1000 bash 40-run-day.sh 2026-06-07  # 每导入 1000 行
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
# shellcheck source=scripts/lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
BD="${1:?need bizDate YYYY-MM-DD}"; shift || true
ROWS="${ROWS:-${1:-300}}"; [[ "${1:-}" =~ ^[0-9]+$ ]] && shift || true
TENANTS=("$@"); [ ${#TENANTS[@]} -eq 0 ] && TENANTS=(ta tb tc t04 t05 t06 t07 t08 t09 t10)
BDC="${BD//-/}"   # yyyymmdd
SIM4DAY_LOG_DIR="${SIM4DAY_LOG_DIR:-$(log_run_dir "$ROOT" sim-4day "sim-4day-day-$BDC")}"
log_link_dir "$ROOT" sim-4day "$SIM4DAY_LOG_DIR"
if [[ "${SIM4DAY_CAPTURED:-0}" != "1" ]]; then
  exec > >(tee -a "$SIM4DAY_LOG_DIR/day-${BDC}.log") 2>&1
fi
TRG="${TRIGGER_BASE_URL}"
SECRET="${BATCH_INTERNAL_SECRET}"

# archetype 原型:retail(类 ta) / bank(类 tb) / risk(类 tc)
arche() { case "$1" in ta|t04|t07|t10) echo retail;; tb|t05|t08) echo bank;; tc|t06|t09) echo risk;; *) echo retail;; esac; }

launch() { # 参数:tenant job bizDate paramsJson
  local t="$1" job="$2" bd="$3" params="$4" rid; rid="sim-${BDC}-${t}-${job}-$(date +%s%N|tail -c 7)"
  local body; body=$(python3 -c "import json,sys;print(json.dumps({'tenantId':sys.argv[1],'jobCode':sys.argv[2],'triggerType':'API','bizDate':sys.argv[3],'requestId':sys.argv[4],'params':json.loads(sys.argv[5])}))" "$t" "$job" "$bd" "$rid" "$params")
  local resp; resp=$(curl -sf --max-time 40 -X POST "$TRG/api/triggers/launch" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $t" -H "X-Internal-Secret: $SECRET" \
    -H "Idempotency-Key: $rid" -H "X-Request-Id: $rid" -d "$body" 2>&1)
  if echo "$resp" | grep -qE '"code"\s*:\s*"(SUCCESS|OK)"'; then printf '.'; return 0; else printf 'x'; return 1; fi
}

import_content() { # 参数:tenant tpl header rowgen
  local t="$1" tpl="$2" header="$3" gen="$4"
  local csv; csv=$(printf '%s\n' "$header"; eval "$gen")
  local params; params=$(python3 -c "import json,sys;print(json.dumps({'templateCode':sys.argv[1],'content':sys.argv[2]}))" "$tpl" "$csv")
  launch "$t" "$IMPORT_JOB" "$BD" "$params"
}

# DISPATCH 需绑定一个已生成文件 + 渠道:取该租户最新 GENERATED 文件分发(自然形成 export→dispatch 链)。
dispatch_latest() { # 参数:tenant job channelCode
  local t="$1" job="$2" ch="$3" fid
  fid=$(docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc \
    "select id from batch.file_record where tenant_id='$t' and file_status='GENERATED' order by id desc limit 1" 2>/dev/null)
  if [ -n "$fid" ]; then launch "$t" "$job" "$BD" "{\"fileId\":$fid,\"channelCode\":\"$ch\"}"; else printf '_'; return 0; fi
}

echo "==> 单日驱动 bizDate=$BD rows=$ROWS tenants=${TENANTS[*]}"
total=0; ok=0
for t in "${TENANTS[@]}"; do
  a=$(arche "$t"); printf '  %-4s[%s] ' "$t" "$a"
  case "$a" in
    retail)
      IMPORT_JOB=TA_IMPORT_CUSTOMER
      total=$((total+1)); import_content "$t" TA_IMPORT_CUSTOMER_TPL \
        "customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status" \
        'for i in $(seq 1 '"$ROWS"'); do printf "%s-C-%s-%06d,企业%s号-%s,ENTERPRISE,9100%09d,139%08d,c%s%s@ex.com,ACTIVE\n" "'"$t"'" "'"$BDC"'" $i "$i" "'"$BDC"'" $i $i "'"$t"'" $i; done' && ok=$((ok+1))
      total=$((total+1)); launch "$t" TA_EXPORT_REPORT "$BD" "{\"templateCode\":\"TA_EXPORT_REPORT_TPL\",\"batchNo\":\"SIM-$BDC\"}" && ok=$((ok+1))
      total=$((total+1)); dispatch_latest "$t" TA_DISPATCH_ORDER ta_local_archive && ok=$((ok+1))
      total=$((total+1)); launch "$t" TA_WF_SETTLEMENT "$BD" "{\"batchNo\":\"SIM-WF-$BDC\"}" && ok=$((ok+1)) ;;
    bank)
      IMPORT_JOB=TB_IMPORT_TRANSACTION
      total=$((total+1)); import_content "$t" TB_IMPORT_TRANSACTION_TPL \
        "txn_no,account_no,txn_type,amount,currency_code,txn_date,remark" \
        'for i in $(seq 1 '"$ROWS"'); do printf "%s-T-%s-%08d,ACC%010d,DEPOSIT,%d.00,CNY,%s,auto-%s-%d\n" "'"$t"'" "'"$BDC"'" $i $i $((100+i)) "'"$BD"'" "'"$BDC"'" $i; done' && ok=$((ok+1))
      total=$((total+1)); launch "$t" TB_EXPORT_STATEMENT "$BD" "{\"templateCode\":\"TB_EXPORT_STATEMENT_TPL\",\"batchNo\":\"SIM-$BDC\"}" && ok=$((ok+1))
      total=$((total+1)); dispatch_latest "$t" TB_DISPATCH_SETTLE tb_api_push && ok=$((ok+1))
      total=$((total+1)); launch "$t" TB_WF_RECONCILE "$BD" "{\"batchNo\":\"SIM-WF-$BDC\"}" && ok=$((ok+1)) ;;
    risk)
      IMPORT_JOB=TC_IMPORT_RISK_SCORE
      total=$((total+1)); import_content "$t" TC_IMPORT_RISK_SCORE_TPL \
        "entity_id,entity_type,score_value,score_band,score_date" \
        'for i in $(seq 1 '"$ROWS"'); do b=$(( i%3==0 ? 1 : 0 )); printf "%s-E-%s-%06d,CUSTOMER,%d,%s,%s\n" "'"$t"'" "'"$BDC"'" $i $((i%100)) "$([ $b -eq 1 ] && echo HIGH || echo LOW)" "'"$BD"'"; done' && ok=$((ok+1))
      total=$((total+1)); launch "$t" TC_EXPORT_RISK_ALERT "$BD" "{\"templateCode\":\"TC_EXPORT_RISK_ALERT_TPL\",\"batchNo\":\"SIM-$BDC\"}" && ok=$((ok+1))
      total=$((total+1)); dispatch_latest "$t" TC_DISPATCH_REVIEW tc_api_risk_push && ok=$((ok+1))
      total=$((total+1)); launch "$t" TC_WF_RISK_PIPELINE "$BD" "{\"batchNo\":\"SIM-WF-$BDC\"}" && ok=$((ok+1)) ;;
  esac
  echo
done
echo "==> bizDate=$BD 触发完成: $ok/$total 接受。worker 真跑(写 biz/MinIO)再等 60-120s,用 50-watch.sh 看。"
