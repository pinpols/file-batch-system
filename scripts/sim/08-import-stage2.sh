#!/usr/bin/env bash
# =========================================================
# 08-import-stage2.sh:Import 业务分支系统级验证
#
# 覆盖:
#   - XML 成功态:TA_IMPORT_CUSTOMER_XML -> biz.customer_account
#   - FIXED_WIDTH 成功态:TA_IMPORT_CUSTOMER_FIXED -> biz.customer_account
#   - XML malformed 失败态
#   - FIXED_WIDTH 短行失败态
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-import。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="import-stage2"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap(XML/FIXED_WIDTH runtime config)"
pg_platform -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null

START_TS="$(pg_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2.log"
import json, os, time, urllib.request, subprocess

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

# psql 命令前缀:platform / business 双容器路由(env-common.sh 已 export,Citus 下被 env-citus.sh 覆盖)
PG_PLAT = ["docker", "exec", os.environ.get("PG_PLATFORM_CONTAINER", "batch-postgres-primary"),
           "psql", "-U", os.environ.get("PG_PLATFORM_USER", "batch_user"),
           "-d", os.environ.get("PG_PLATFORM_DB", "batch_platform")]
PG_BIZ = ["docker", "exec", os.environ.get("PG_BUSINESS_CONTAINER", "batch-postgres-primary"),
          "psql", "-U", os.environ.get("PG_BUSINESS_USER", "batch_user"),
          "-d", os.environ.get("PG_BUSINESS_DB", "batch_business")]

def fixed_row(customer_no, name, status):
    fields = [
        customer_no[:12].ljust(12),
        name[:20].ljust(20),
        "PERSONAL".ljust(10),
        ("CERT" + customer_no)[-16:].ljust(16),
        "13900001234".ljust(12),
        "s2@x.io".ljust(8),
        status[:8].ljust(8),
    ]
    return "".join(fields)

XML_OK = """<?xml version="1.0" encoding="UTF-8"?>
<customers>
  <customer>
    <customer_no>S2XML000001</customer_no>
    <customer_name>Stage2 XML A</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2XMLCERT000001</certificate_no>
    <mobile_no>13900000001</mobile_no>
    <email>s2a@x.io</email>
    <status>ACTIVE</status>
  </customer>
  <customer>
    <customer_no>S2XML000002</customer_no>
    <customer_name>Stage2 XML B</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2XMLCERT000002</certificate_no>
    <mobile_no>13900000002</mobile_no>
    <email>s2b@x.io</email>
    <status>INACTIVE</status>
  </customer>
</customers>
"""

FIXED_OK = "\n".join([
    fixed_row("S2FIX000001", "Stage2 Fixed A", "ACTIVE"),
    fixed_row("S2FIX000002", "Stage2 Fixed B", "INACTIVE"),
]) + "\n"

SCENARIOS = [
    ("xml_ok", "TA_IMPORT_CUSTOMER_XML", {
        "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
        "fileFormatType": "XML",
        "content": XML_OK,
        "batchNo": BATCH,
    }, "SUCCESS"),
    ("fixed_ok", "TA_IMPORT_CUSTOMER_FIXED", {
        "templateCode": "TA_IMPORT_CUSTOMER_FIXED_TPL",
        "fileFormatType": "FIXED_WIDTH",
        "content": FIXED_OK,
        "batchNo": BATCH,
    }, "SUCCESS"),
    ("xml_bad", "TA_IMPORT_CUSTOMER_XML", {
        "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
        "fileFormatType": "XML",
        "content": "<customers><customer><customer_no>S2XMLBAD001</customer_no></customers>",
        "batchNo": BATCH + "-badxml",
    }, "FAILED"),
    ("xml_validate_bad", "TA_IMPORT_CUSTOMER_XML", {
        "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
        "fileFormatType": "XML",
        "content": """<?xml version="1.0" encoding="UTF-8"?>
<customers>
  <customer>
    <customer_no>S2XMLBAD002</customer_no>
    <customer_name>Stage2 XML Bad</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2XMLCERTBAD002</certificate_no>
    <mobile_no>13900000003</mobile_no>
    <email>s2c@x.io</email>
    <status>BLOCKED</status>
  </customer>
</customers>
""",
        "batchNo": BATCH + "-badvalidate",
    }, "FAILED"),
    ("fixed_bad", "TA_IMPORT_CUSTOMER_FIXED", {
        "templateCode": "TA_IMPORT_CUSTOMER_FIXED_TPL",
        "fileFormatType": "FIXED_WIDTH",
        "content": "SHORT\n",
        "batchNo": BATCH + "-badfixed",
    }, "FAILED"),
]

def launch(label, job, params):
    rid = f"sim-stage2-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": params,
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

for label, job, params, expected in SCENARIOS:
    launch(label, job, params)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 120
expected_jobs = len(SCENARIOS)
while time.time() < deadline:
    sql = (
        "select count(*) from batch.job_instance "
        f"where tenant_id='ta' and created_at >= '{START_TS}' "
        "and job_code in ('TA_IMPORT_CUSTOMER_XML','TA_IMPORT_CUSTOMER_FIXED') "
        "and instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')"
    )
    out = subprocess.run(
        PG_PLAT + ["-t", "-A", "-c", sql],
        capture_output=True, text=True)
    done = int((out.stdout or "0").strip() or "0")
    if done >= expected_jobs:
        break
    time.sleep(3)

queries = {
    "job_status": (
        "select i.id,i.job_code,i.instance_status,t.task_status,t.error_code,"
        "left(coalesce(t.error_message,''),160) as error_message "
        "from batch.job_instance i left join batch.job_task t on t.job_instance_id=i.id "
        f"where i.tenant_id='ta' and i.created_at >= '{START_TS}' "
        "and i.job_code in ('TA_IMPORT_CUSTOMER_XML','TA_IMPORT_CUSTOMER_FIXED') "
        "order by i.created_at,i.id"
    ),
    "file_status": (
        "select id,biz_type,file_status,file_format_type,file_size_bytes,created_at "
        "from batch.file_record "
        f"where tenant_id='ta' and created_at >= '{START_TS}' "
        "and biz_type in ('TA_IMPORT_CUSTOMER_XML','TA_IMPORT_CUSTOMER_FIXED') "
        "order by created_at"
    ),
}
for title, sql in queries.items():
    print(f"\n-- {title} --", flush=True)
    subprocess.run(
        PG_PLAT + ["-P", "pager=off", "-c", sql],
        check=False)

print("\n-- business counts --", flush=True)
subprocess.run(
    PG_BIZ + ["-P", "pager=off", "-c",
    "select tenant_id, count(*) filter (where customer_no like 'S2XML%') as xml_rows, "
    "count(*) filter (where customer_no like 'S2FIX%') as fixed_rows "
    "from biz.customer_account where tenant_id='ta' group by tenant_id"
], check=False)

print(f"\n==> Stage 2 import scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
