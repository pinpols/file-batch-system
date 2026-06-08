#!/usr/bin/env bash
# =========================================================
# 11-import-stage2b.sh:Import LOAD/幂等/分片保护业务分支验证
#
# 覆盖:
#   - BATCH_UPSERT 重跑幂等:同 tenant_id + customer_no 不重复,字段更新
#   - LOAD 目标表配置错误:系统级失败态
#   - PARTITION_REPLACE_COPY + partitionCount>1:fail-fast,防半量写入
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-import。
# 不重启服务;临时验证模板/job/pipeline 均幂等 upsert 到 DB。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
if [[ -z "${BATCH_INTERNAL_SECRET:-}" && -f .env.local ]]; then
  BATCH_INTERNAL_SECRET="$(grep -E '^BATCH_INTERNAL_SECRET=' .env.local | tail -1 | cut -d= -f2- || true)"
fi
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-import-stage2b-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-import-stage2b-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap + stage2b fixtures"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2b-import-fixtures.sql >/dev/null

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

CUSTOMER = "S2BUPS000001"
request_ids = []

def xml_payload(customer_no, name, status="ACTIVE"):
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<customers>
  <customer>
    <customer_no>{customer_no}</customer_no>
    <customer_name>{name}</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2B{customer_no}</certificate_no>
    <mobile_no>13900009001</mobile_no>
    <email>s2b@x.io</email>
    <status>{status}</status>
  </customer>
</customers>
"""

def launch(label, job, params):
    rid = f"sim-stage2b-{label}-{int(time.time()*1000)%100000000}"
    request_ids.append(rid)
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
        print(f"  [launch] {label:18s} {job:38s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {label}")
    return rid

def psql(db, sql, tuples=False):
    args = [
        "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
        "-d", db, "-P", "pager=off"
    ]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def wait_for(job, rid, expected):
    deadline = time.time() + 150
    while time.time() < deadline:
        sql = (
            "select coalesce(i.instance_status,'') "
            "from batch.trigger_request tr "
            "left join batch.job_instance i on i.id = tr.related_job_instance_id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' and tr.job_code='{job}' "
            "order by tr.created_at desc limit 1"
        )
        out = psql("batch_platform", sql, tuples=True)
        status = (out.stdout or "").strip()
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            marker = "✓" if status == expected else "✗"
            print(f"  [result] {job:38s} {status:14s} expected={expected} {marker}", flush=True)
            return status
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {job}/{rid}")

print("==> launch upsert first", flush=True)
rid_first = launch("upsert_first", "TA_IMPORT_CUSTOMER_XML", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
    "fileFormatType": "XML",
    "content": xml_payload(CUSTOMER, "Stage2b Original"),
    "batchNo": BATCH + "-upsert-1",
})
wait_for("TA_IMPORT_CUSTOMER_XML", rid_first, "SUCCESS")

print("==> launch upsert second", flush=True)
rid_second = launch("upsert_second", "TA_IMPORT_CUSTOMER_XML", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
    "fileFormatType": "XML",
    "content": xml_payload(CUSTOMER, "Stage2b Updated"),
    "batchNo": BATCH + "-upsert-2",
})
wait_for("TA_IMPORT_CUSTOMER_XML", rid_second, "SUCCESS")

print("==> launch LOAD bad target", flush=True)
rid_load_bad = launch("load_bad", "TA_IMPORT_CUSTOMER_XML_LOAD_BAD", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_LOAD_BAD_TPL",
    "fileFormatType": "XML",
    "content": xml_payload("S2BLOADBAD01", "Stage2b Load Bad"),
    "batchNo": BATCH + "-load-bad",
})
wait_for("TA_IMPORT_CUSTOMER_XML_LOAD_BAD", rid_load_bad, "FAILED")

print("==> launch partition replace copy guard", flush=True)
rid_partition = launch("partition_guard", "TA_IMPORT_CUSTOMER_XML_PARTITION_COPY", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_PARTITION_COPY_TPL",
    "fileFormatType": "XML",
    "content": xml_payload("S2BPARTGUARD", "Stage2b Partition Guard"),
    "batchNo": BATCH + "-partition-guard",
    "partitionCount": 2,
})
wait_for("TA_IMPORT_CUSTOMER_XML_PARTITION_COPY", rid_partition, "FAILED")

print("\n-- job_status --", flush=True)
request_list = ",".join("'" + rid + "'" for rid in request_ids)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select i.id,i.job_code,i.instance_status,i.expected_partition_count,"
    "t.task_status,t.error_code,left(coalesce(t.error_message,''),180) as error_message "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "left join batch.job_task t on t.job_instance_id = i.id "
    f"where tr.request_id in ({request_list}) "
    "order by i.created_at,i.id,t.id"
], check=False)

print("\n-- upsert_business_row --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    f"select tenant_id, customer_no, count(*) as rows, max(customer_name) as customer_name, "
    f"max(source_batch_no) as source_batch_no from biz.customer_account "
    f"where tenant_id='ta' and customer_no='{CUSTOMER}' "
    f"group by tenant_id, customer_no"
], check=False)

check = psql("batch_business", (
    f"select count(*) || '|' || coalesce(max(customer_name),'') "
    f"from biz.customer_account where tenant_id='ta' and customer_no='{CUSTOMER}'"
), tuples=True)
value = (check.stdout or "").strip()
if value != "1|Stage2b Updated":
    print(f"❌ UPSERT business assertion failed: {value}", flush=True)
    sys.exit(1)

print(f"\n==> Stage 2b import scenario PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
