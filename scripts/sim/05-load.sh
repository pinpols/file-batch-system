#!/usr/bin/env bash
# =========================================================
# 05-load.sh:中量触发 ta/tb/tc 的 4 类 job(IMPORT / EXPORT / DISPATCH / WORKFLOW)
#
# 重建说明(2026-06-04):/api/triggers/launch 是「触发」入口,不是文件投递。各 job 类型
# 需要不同的 params 才能端到端跑通(经实测确认):
#   - IMPORT  : params.templateCode + params.content(内联 CSV,列必须匹配模板
#               query_param_schema.jdbcMappedImport.columnMappings 的 from 列)→ 解析 → 加载 biz.*
#   - EXPORT  : params.templateCode(export_data_ref 必须在模板配好)→ 读 biz.* → 写 MinIO outbound
#   - DISPATCH: params.fileId(派发一个已存在的文件,通常取最近 EXPORT 产物)→ 推到 channel/HTTP
#   - WORKFLOW: 裸 launch(orchestrator 编排子 JOB)
#
# 内部鉴权:/api/triggers/launch 需 X-Internal-Secret + Idempotency-Key。
#   secret 默认 internal-secret,本地若 .env.local 注入 BATCH_INTERNAL_SECRET 则用之。
#
# 用法:
#   bash scripts/sim/05-load.sh                  # 默认 5 行/批 × IMPORT,各 job 触发 1 次
#   ROUNDS=10 bash scripts/sim/05-load.sh        # 每个 import 批 10 行
#   ONLY=ta bash scripts/sim/05-load.sh          # 只触 ta
# =========================================================
set -uo pipefail

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
export ROWS="${ROUNDS:-5}"               # 每个 import 生成多少数据行
export ONLY="${ONLY:-}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

python3 - <<'PY'
import json, os, subprocess, time, urllib.request

BASE   = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ    = os.environ["BIZ_DATE"]
ROWS   = int(os.environ.get("ROWS", "5"))
ONLY   = os.environ.get("ONLY", "").strip()

# ── IMPORT 规格:job -> (template, [列], 行生成器) ────────────────────────────
def cust_row(i): return f"CUST-{i:06d},客户{i},PERSONAL,ID{i:09d},138{i:08d},u{i}@sim.com,ACTIVE"
def txn_row(i):  return f"TB-TXN-{i:06d},ACC{i:010d},DEPOSIT,{100+i}.50,CNY,{BIZ},sim-load-{i}"
def risk_row(i): return f"ENT-{i:06d},ACCOUNT,{500+i%400},{'LOW' if i%2 else 'MEDIUM'},{BIZ}"

IMPORTS = {
  "ta": [("TA_IMPORT_CUSTOMER", "TA_IMPORT_CUSTOMER_TPL",
          "customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status", cust_row)],
  "tb": [("TB_IMPORT_TRANSACTION", "TB_IMPORT_TRANSACTION_TPL",
          "txn_no,account_no,txn_type,amount,currency_code,txn_date,remark", txn_row)],
  "tc": [("TC_IMPORT_RISK_SCORE", "TC_IMPORT_RISK_SCORE_TPL",
          "entity_id,entity_type,score_value,score_band,score_date", risk_row)],
}
EXPORTS = {
  "ta": [("TA_EXPORT_REPORT", "TA_EXPORT_REPORT_TPL")],
  "tb": [("TB_EXPORT_STATEMENT", "TB_EXPORT_STATEMENT_TPL")],
  "tc": [("TC_EXPORT_RISK_ALERT", "TC_EXPORT_RISK_ALERT_TPL")],
}
# dispatch:job -> [channelCode...](派发到 mockserver 的 HTTP 渠道,需 fileId + channelCode)
DISPATCHES = {
  "ta": [("TA_DISPATCH_ORDER", "tb_api_push")],            # ta 无独立 mock 端点,占位
  "tb": [("TB_DISPATCH_SETTLE", "tb_api_push"),            # → /tb/callback
         ("TB_DISPATCH_SETTLE", "tb_api_ingest")],         # → /tb/ingest
  "tc": [("TC_DISPATCH_REVIEW", "tc_api_risk_push")],      # → /tc/ingest
}
WORKFLOWS  = {"ta": ["TA_WF_SETTLEMENT"], "tb": ["TB_WF_RECONCILE"],
              "tc": ["TC_WF_RISK_PIPELINE", "TC_WF_GATEWAY_ALL", "TC_WF_GATEWAY_N_OF"]}

TENANTS = [ONLY] if ONLY else ["ta", "tb", "tc"]

def launch(tenant, job, params):
    rid = f"sim-{tenant}-{job}-{int(time.time()*1000)%100000000}"
    body = {"tenantId": tenant, "jobCode": job, "triggerType": "API",
            "bizDate": BIZ, "requestId": rid, "params": params}
    req = urllib.request.Request(f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-Tenant-Id": tenant,
                 "X-Internal-Secret": SECRET, "Idempotency-Key": rid, "X-Request-Id": rid})
    try:
        r = urllib.request.urlopen(req, timeout=30)
        ok = b'"SUCCESS"' in r.read() or r.status == 200
        return ok, ""
    except Exception as e:
        return False, str(e)[:80]

tot = ok = 0
def run(label, tenant, job, params):
    global tot, ok
    tot += 1
    good, err = launch(tenant, job, params)
    ok += 1 if good else 0
    print(f"  [{label:8s}] {tenant}/{job:24s} {'✓' if good else '✗ '+err}")

print(f"==> sim load:tenants={TENANTS} rows/import={ROWS} bizDate={BIZ}")
for t in TENANTS:
    for job, tpl, header, gen in IMPORTS.get(t, []):
        csv = header + "\n" + "\n".join(gen(i) for i in range(1, ROWS+1)) + "\n"
        run("IMPORT", t, job, {"templateCode": tpl, "content": csv})
    for job, tpl in EXPORTS.get(t, []):
        run("EXPORT", t, job, {"templateCode": tpl})

# DISPATCH 依赖已存在文件:等 EXPORT 落库后,取该租户最近一个 file_record id 当 fileId
time.sleep(8)
def latest_file_id(tenant):
    sql = (f"select id from batch.file_record where tenant_id='{tenant}' "
           f"order by created_at desc limit 1")
    out = subprocess.run(["docker","exec","batch-postgres-primary","psql","-U","batch_user",
        "-d","batch_platform","-t","-A","-c",sql], capture_output=True, text=True)
    v = out.stdout.strip()
    return v if v.isdigit() else None

for t in TENANTS:
    fid = latest_file_id(t)
    for job, channel in DISPATCHES.get(t, []):
        if fid:
            run("DISPATCH", t, job, {"fileId": int(fid), "channelCode": channel})
        else:
            print(f"  [DISPATCH] {t}/{job}: 跳过(无 file_record 可派发)")
    for job in WORKFLOWS.get(t, []):
        run("WORKFLOW", t, job, {})

print(f"\n==> 完成:total={tot} succ={ok}")
print("提示:worker 真正写 MinIO/biz.* 需 60~120s,建议 sleep 120 后跑 06-verify.sh")
PY
