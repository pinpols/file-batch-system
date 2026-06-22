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

export ROWS="${ROUNDS:-5}"               # 每个 import 生成多少数据行
export ONLY="${ONLY:-}"
export CLEAN_SIM_OUTPUTS="${CLEAN_SIM_OUTPUTS:-false}"
export MINIO_BUCKET="${MINIO_BUCKET:-batch-dev}"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

python3 - <<'PY'
import json, os, subprocess, sys, time, urllib.request

BASE   = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ    = os.environ["BIZ_DATE"]
BATCH  = os.environ["BATCH_NO"]
ROWS   = int(os.environ.get("ROWS", "5"))
ONLY   = os.environ.get("ONLY", "").strip()
CLEAN  = os.environ.get("CLEAN_SIM_OUTPUTS", "true").lower() == "true"
BUCKET = os.environ.get("MINIO_BUCKET", "batch-dev")

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
  "ta": [],                                                # ta 当前 fixture 无独立 HTTP mock 端点
  "tb": [("TB_DISPATCH_SETTLE", "tb_api_push"),            # → /tb/callback
         ("TB_DISPATCH_SETTLE", "tb_api_ingest")],         # → /tb/ingest
  "tc": [("TC_DISPATCH_REVIEW", "tc_api_risk_push")],      # → /tc/ingest
}
WORKFLOWS  = {"ta": ["TA_WF_SETTLEMENT"], "tb": ["TB_WF_RECONCILE"],
              "tc": ["TC_WF_RISK_PIPELINE", "TC_WF_GATEWAY_ALL", "TC_WF_GATEWAY_N_OF"]}

TENANTS = [ONLY] if ONLY else ["ta", "tb", "tc"]

EXPORT_BIZ_TYPES = {
  "ta": ["TA_EXPORT_REPORT"],
  "tb": ["TB_EXPORT_STATEMENT"],
  "tc": ["TC_EXPORT_RISK_ALERT"],
}

def run_cmd(args, input_text=None):
    return subprocess.run(args, input=input_text, capture_output=True, text=True)

def sql_value(sql):
    out = run_cmd(["docker","exec","batch-postgres-primary","psql","-U","batch_user",
        "-d","batch_platform","-t","-A","-c",sql])
    return out.stdout.strip()

def cleanup_outputs():
    tenants = TENANTS
    biz_types = [b for t in tenants for b in EXPORT_BIZ_TYPES.get(t, [])]
    if not biz_types:
        return
    in_tenants = ",".join("'" + t + "'" for t in tenants)
    in_biz = ",".join("'" + b + "'" for b in biz_types)
    sql = f"""
    with target_files as (
      select id from batch.file_record
       where tenant_id in ({in_tenants})
         and file_category = 'OUTPUT'
         and source_type = 'GENERATED'
         and biz_type in ({in_biz})
         and source_ref = '{BATCH}'
    )
    delete from batch.file_dispatch_record where file_id in (select id from target_files);
    delete from batch.file_record
     where tenant_id in ({in_tenants})
       and file_category = 'OUTPUT'
       and source_type = 'GENERATED'
       and biz_type in ({in_biz})
       and source_ref = '{BATCH}';
    """
    run_cmd(["docker","exec","-i","batch-postgres-primary","psql","-U","batch_user",
        "-d","batch_platform","-v","ON_ERROR_STOP=1"], sql)
    for biz in biz_types:
        run_cmd(["docker","exec","batch-minio","mc","rm","--recursive","--force",
            f"local/{BUCKET}/outbound/{biz}/{BIZ}/{BATCH}"])

def launch(tenant, job, params):
    params = dict(params)
    params.setdefault("batchNo", BATCH)
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
errors = []
def run(label, tenant, job, params):
    global tot, ok
    tot += 1
    good, err = launch(tenant, job, params)
    ok += 1 if good else 0
    if not good:
        errors.append(f"{label} {tenant}/{job} launch failed: {err}")
    print(f"  [{label:8s}] {tenant}/{job:24s} {'✓' if good else '✗ '+err}")
    return good

print(f"==> sim load:tenants={TENANTS} rows/import={ROWS} bizDate={BIZ} batchNo={BATCH}")
if not ONLY:
    print("    note: TA_DISPATCH_ORDER 跳过,当前 fixture 没有 ta 独立 HTTP channel;TB/TC 覆盖 HTTP dispatch")
if CLEAN:
    print("==> cleanup:清理当前 batchNo outbound 产物/file_record,保证同 batchNo 可重复跑")
    cleanup_outputs()
for t in TENANTS:
    for job, tpl, header, gen in IMPORTS.get(t, []):
        csv = header + "\n" + "\n".join(gen(i) for i in range(1, ROWS+1)) + "\n"
        run("IMPORT", t, job, {"templateCode": tpl, "content": csv})
    for job, tpl in EXPORTS.get(t, []):
        run("EXPORT", t, job, {"templateCode": tpl})

# DISPATCH 依赖已存在文件:等 EXPORT 写入数据库后,取该租户最近一个 file_record id 当 fileId
def latest_file_id(tenant):
    sql = (f"select id from batch.file_record where tenant_id='{tenant}' "
           f"and file_category='OUTPUT' and source_type='GENERATED' "
           f"and source_ref='{BATCH}' "
           f"and file_status in ('GENERATED','DISPATCHED') "
           f"order by created_at desc limit 1")
    v = sql_value(sql)
    return v if v.isdigit() else None

def wait_latest_file_id(tenant, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        fid = latest_file_id(tenant)
        if fid:
            return fid
        time.sleep(2)
    return None

def wait_dispatch_done(tenant, file_id, channel, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = sql_value(
            "select coalesce(dispatch_status,'') from batch.file_dispatch_record "
            f"where tenant_id='{tenant}' and file_id={file_id} and channel_code='{channel}' "
            "order by created_at desc limit 1")
        if status in ("ACKED", "SENT", "COMPENSATED", "FAILED"):
            return status
        time.sleep(2)
    return ""

for t in TENANTS:
    fid = wait_latest_file_id(t)
    for job, channel in DISPATCHES.get(t, []):
        if fid:
            run("DISPATCH", t, job, {"fileId": int(fid), "channelCode": channel})
            status = wait_dispatch_done(t, fid, channel)
            if status:
                print(f"             └─ dispatch record {channel}: {status}")
                if status == "FAILED":
                    errors.append(f"DISPATCH {t}/{job} channel={channel} failed for fileId={fid}")
            else:
                errors.append(f"DISPATCH {t}/{job} channel={channel} did not create a terminal dispatch record")
        else:
            msg = f"DISPATCH {t}/{job} missing OUTPUT/GENERATED file_record for batchNo={BATCH}"
            errors.append(msg)
            print(f"  [DISPATCH] {t}/{job}: ✗ {msg}")
    for job in WORKFLOWS.get(t, []):
        run("WORKFLOW", t, job, {"batchNo": BATCH})

print(f"\n==> 完成:total={tot} succ={ok}")
print("提示:worker 真正写 MinIO/biz.* 需 60~120s,建议 sleep 120 后跑 06-verify.sh")
if errors:
    print("\n==> 失败明细:", file=sys.stderr)
    for err in errors:
        print(f"  - {err}", file=sys.stderr)
    sys.exit(1)
PY
