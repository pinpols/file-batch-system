#!/usr/bin/env bash
# =========================================================
# 17-import-stage2c.sh:Import APPEND/UPSERT/REPLACE 小矩阵验证
#
# 覆盖:
#   - APPEND/no-conflict:同内容同 batchNo 跑两次产生 2 行
#   - BATCH_UPSERT:同业务键跑两次只保留 1 行且字段更新
#   - PARTITION_REPLACE_COPY:同 tenant_id + source_batch_no 先清 stale 再 COPY 新数据
#
# SQL fixture 位于 docs/test-data,脚本只负责编排、API 触发和断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="import-stage2c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap + stage2c fixtures"
pg_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
pg_business \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2c-import-matrix-business.sql >/dev/null
pg_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2c-import-matrix-fixtures.sql >/dev/null
pg_business \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO-replace" \
  -f /dev/stdin < docs/test-data/sim-stage2c-import-matrix-stale.sql >/dev/null

START_TS="$(pg_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2c.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()
request_ids = []

# psql 命令前缀:platform / business 双容器路由(env-common.sh 已 export,Citus 下被 env-citus.sh 覆盖)
PG_PLAT = ["docker", "exec", os.environ.get("PG_PLATFORM_CONTAINER", "batch-postgres-primary"),
           "psql", "-U", os.environ.get("PG_PLATFORM_USER", "batch_user"),
           "-d", os.environ.get("PG_PLATFORM_DB", "batch_platform")]
PG_BIZ = ["docker", "exec", os.environ.get("PG_BUSINESS_CONTAINER", "batch-postgres-primary"),
          "psql", "-U", os.environ.get("PG_BUSINESS_USER", "batch_user"),
          "-d", os.environ.get("PG_BUSINESS_DB", "batch_business")]

def xml_payload(rows):
    items = []
    for row in rows:
        items.append(f"""  <customer>
    <customer_no>{row['no']}</customer_no>
    <customer_name>{row['name']}</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>{row['cert']}</certificate_no>
    <mobile_no>{row['mobile']}</mobile_no>
    <email>{row['email']}</email>
    <status>{row['status']}</status>
  </customer>""")
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<customers>\n" + "\n".join(items) + "\n</customers>\n"

def psql(db, sql, tuples=False):
    prefix = PG_BIZ if db == "batch_business" else PG_PLAT
    args = prefix + ["-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def launch(label, job, template, content, batch_no):
    rid = f"sim-stage2c-{label}-{int(time.time()*1000)%100000000}"
    request_ids.append(rid)
    body = {
        "tenantId": "ta",
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "templateCode": template,
            "fileFormatType": "XML",
            "content": content,
            "batchNo": batch_no,
        },
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": "ta",
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {label:16s} {job:28s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {label}")
    return rid

def wait_for(job, rid, expected="SUCCESS"):
    deadline = time.time() + 180
    while time.time() < deadline:
        out = psql("batch_platform", (
            "select coalesce(i.instance_status,'') "
            "from batch.trigger_request tr "
            "left join batch.job_instance i on i.id = tr.related_job_instance_id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' and tr.job_code='{job}' "
            "order by tr.created_at desc limit 1"
        ), tuples=True)
        status = (out.stdout or "").strip()
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            marker = "✓" if status == expected else "✗"
            print(f"  [result] {job:28s} {status:14s} expected={expected} {marker}", flush=True)
            if status != expected:
                sys.exit(1)
            return
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {job}/{rid}")

append_xml = xml_payload([{
    "no": "S2CAPP000001",
    "name": "Stage2c Append",
    "cert": "S2CAPP000001CERT",
    "mobile": "13900009201",
    "email": "s2c-append@x.io",
    "status": "ACTIVE",
}])
upsert_first = xml_payload([{
    "no": "S2CUPS000001",
    "name": "Stage2c Upsert Original",
    "cert": "S2CUPS000001CERT",
    "mobile": "13900009202",
    "email": "s2c-upsert@x.io",
    "status": "ACTIVE",
}])
upsert_second = xml_payload([{
    "no": "S2CUPS000001",
    "name": "Stage2c Upsert Updated",
    "cert": "S2CUPS000001CERT",
    "mobile": "13900009203",
    "email": "s2c-upsert-updated@x.io",
    "status": "INACTIVE",
}])
replace_xml = xml_payload([
    {
        "no": "S2CREP000001",
        "name": "Stage2c Replace A",
        "cert": "S2CREP000001CERT",
        "mobile": "13900009204",
        "email": "s2c-replace-a@x.io",
        "status": "ACTIVE",
    },
    {
        "no": "S2CREP000002",
        "name": "Stage2c Replace B",
        "cert": "S2CREP000002CERT",
        "mobile": "13900009205",
        "email": "s2c-replace-b@x.io",
        "status": "INACTIVE",
    },
])

print("==> APPEND twice", flush=True)
for label in ("append_first", "append_second"):
    rid = launch(label, "TA_IMPORT_STAGE2C_APPEND", "TA_IMPORT_STAGE2C_APPEND_TPL", append_xml, BATCH + "-append")
    wait_for("TA_IMPORT_STAGE2C_APPEND", rid)

print("==> UPSERT twice", flush=True)
rid = launch("upsert_first", "TA_IMPORT_STAGE2C_UPSERT", "TA_IMPORT_STAGE2C_UPSERT_TPL", upsert_first, BATCH + "-upsert-1")
wait_for("TA_IMPORT_STAGE2C_UPSERT", rid)
rid = launch("upsert_second", "TA_IMPORT_STAGE2C_UPSERT", "TA_IMPORT_STAGE2C_UPSERT_TPL", upsert_second, BATCH + "-upsert-2")
wait_for("TA_IMPORT_STAGE2C_UPSERT", rid)

print("==> PARTITION_REPLACE_COPY", flush=True)
rid = launch("replace", "TA_IMPORT_STAGE2C_REPLACE", "TA_IMPORT_STAGE2C_REPLACE_TPL", replace_xml, BATCH + "-replace")
wait_for("TA_IMPORT_STAGE2C_REPLACE", rid)

print("\n-- job_status --", flush=True)
request_list = ",".join("'" + rid + "'" for rid in request_ids)
subprocess.run(PG_PLAT + [
    "-P", "pager=off", "-c",
    "select i.id,i.job_code,i.instance_status,t.task_status,t.error_code,left(coalesce(t.error_message,''),160) as error_message "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "left join batch.job_task t on t.job_instance_id = i.id "
    f"where tr.request_id in ({request_list}) order by i.created_at,i.id,t.id"
], check=False)

print("\n-- import_stage2c_rows --", flush=True)
subprocess.run(PG_BIZ + [
    "-P", "pager=off", "-c",
    "select source_batch_no, customer_no, count(*) as rows, max(customer_name) as max_name "
    "from biz.import_stage2c_customer where tenant_id='ta' and source_batch_no like '" + BATCH + "%' "
    "group by source_batch_no, customer_no order by source_batch_no, customer_no"
], check=False)

append_check = (psql("batch_business", (
    "select count(*) from biz.import_stage2c_customer "
    f"where tenant_id='ta' and source_batch_no='{BATCH}-append' and customer_no='S2CAPP000001'"
), tuples=True).stdout or "").strip()
upsert_check = (psql("batch_business", (
    "select count(*) || '|' || coalesce(max(customer_name),'') || '|' || coalesce(max(status),'') "
    "from biz.customer_account where tenant_id='ta' and customer_no='S2CUPS000001'"
), tuples=True).stdout or "").strip()
replace_check = (psql("batch_business", (
    "select count(*) || '|' || count(*) filter (where customer_no='S2CREPSTALE') "
    "from biz.import_stage2c_customer "
    f"where tenant_id='ta' and source_batch_no='{BATCH}-replace'"
), tuples=True).stdout or "").strip()
summary = f"{append_check}|{upsert_check}|{replace_check}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "2|1|Stage2c Upsert Updated|INACTIVE|2|0":
    print("❌ Import Stage2c assertion failed, expected 2|1|Stage2c Upsert Updated|INACTIVE|2|0", flush=True)
    sys.exit(1)

print(f"\n==> Stage 2c import matrix PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
