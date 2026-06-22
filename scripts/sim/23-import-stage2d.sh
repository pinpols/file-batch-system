#!/usr/bin/env bash
# =========================================================
# 23-import-stage2d.sh:Import bad-record skip 阈值系统级验证
#
# 前置:
#   worker-import 必须以以下配置启动:
#     BATCH_WORKER_IMPORT_SKIP_ENABLED=true
#     BATCH_WORKER_IMPORT_SKIP_THRESHOLD_MODE=ABSOLUTE
#     BATCH_WORKER_IMPORT_SKIP_MAX_SKIP_COUNT=1
#     BATCH_WORKER_IMPORT_ERROR_SINK_TYPE=ERROR_TABLE
#
# 覆盖:
#   - 1 条校验坏记录在阈值内被 skip,同批有效记录继续 LOAD
#   - 2 条校验坏记录超过阈值,任务失败为 IMPORT_SKIP_THRESHOLD_EXCEEDED
#
# SQL fixture 位于 docs/test-data 的 bootstrap,脚本只负责编排、API 触发和断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="import-stage2d"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap(XML import runtime config)"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2d-reset-errors.sql >/dev/null

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2d.log"
import json
import os
import subprocess
import sys
import time
import urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

def psql(db, sql, tuples=False):
    args = [
        "docker", "exec", "batch-postgres-primary", "psql",
        "-U", "batch_user", "-d", db, "-P", "pager=off",
    ]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

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

def launch(label, content, batch_no):
    rid = f"sim-stage2d-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": "TA_IMPORT_CUSTOMER_XML",
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
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
        print(f"  [launch] {label:20s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {label}")
    return rid

def wait_for(rid, expected):
    deadline = time.time() + 180
    while time.time() < deadline:
        out = psql("batch_platform", (
            "select coalesce(i.instance_status,'') || '|' || coalesce(t.task_status,'') || '|' || coalesce(t.error_code,'') "
            "from batch.trigger_request tr "
            "left join batch.job_instance i on i.id = tr.related_job_instance_id "
            "left join batch.job_task t on t.job_instance_id = i.id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' "
            "order by tr.created_at desc, t.id desc limit 1"
        ), tuples=True)
        status = (out.stdout or "").strip()
        terminal = status.split("|", 1)[0]
        if terminal in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            marker = "✓" if terminal == expected else "✗"
            print(f"  [result] {rid:34s} {status:45s} expected={expected} {marker}", flush=True)
            if terminal != expected:
                sys.exit(1)
            return status
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {rid}")

under_threshold = xml_payload([
    {
        "no": "S2DSKIPBAD001",
        "name": "Stage2d Bad One",
        "cert": "S2DSKIPBAD001CERT",
        "mobile": "13900009301",
        "email": "s2d-bad-one@x.io",
        "status": "BLOCKED",
    },
    {
        "no": "S2DSKIPOK001",
        "name": "Stage2d Skip OK",
        "cert": "S2DSKIPOK001CERT",
        "mobile": "13900009302",
        "email": "s2d-ok@x.io",
        "status": "ACTIVE",
    },
])

over_threshold = xml_payload([
    {
        "no": "S2DSKIPEXBAD001",
        "name": "Stage2d Exceed Bad A",
        "cert": "S2DSKIPEXBAD001CERT",
        "mobile": "13900009303",
        "email": "s2d-exceed-a@x.io",
        "status": "BLOCKED",
    },
    {
        "no": "S2DSKIPEXBAD002",
        "name": "Stage2d Exceed Bad B",
        "cert": "S2DSKIPEXBAD002CERT",
        "mobile": "13900009304",
        "email": "s2d-exceed-b@x.io",
        "status": "BLOCKED",
    },
    {
        "no": "S2DSKIPEXOK001",
        "name": "Stage2d Exceed Should Not Load",
        "cert": "S2DSKIPEXOK001CERT",
        "mobile": "13900009305",
        "email": "s2d-exceed-ok@x.io",
        "status": "ACTIVE",
    },
])

print("==> skip under threshold", flush=True)
rid_under = launch("skip_under_threshold", under_threshold, BATCH + "-under")
under_status = wait_for(rid_under, "SUCCESS")

print("==> skip threshold exceeded", flush=True)
rid_over = launch("skip_over_threshold", over_threshold, BATCH + "-over")
over_status = wait_for(rid_over, "FAILED")

print("\n-- job_status --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select tr.request_id,i.id,i.instance_status,t.task_status,t.error_code,left(coalesce(t.error_message,''),160) as error_message "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "left join batch.job_task t on t.job_instance_id = i.id "
    f"where tr.request_id in ('{rid_under}','{rid_over}') order by tr.created_at,t.id"
], check=False)

print("\n-- file_records --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select fr.id,fr.file_status,fr.metadata_json->>'badRecordCount' as bad_records,"
    "fr.metadata_json->>'skippedCount' as skipped,fr.metadata_json->>'validatedCount' as validated,"
    "fr.metadata_json->>'loadedCount' as loaded,fr.metadata_json->>'skipThresholdExceeded' as threshold_exceeded "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "join batch.pipeline_instance pi on pi.related_job_instance_id = i.id "
    "join batch.file_record fr on fr.id = pi.file_id "
    f"where tr.request_id in ('{rid_under}','{rid_over}') order by tr.created_at,fr.id"
], check=False)

print("\n-- error_records --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select tr.request_id,er.record_no,er.error_code,er.error_stage,er.is_skipped,er.skip_action "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "join batch.pipeline_instance pi on pi.related_job_instance_id = i.id "
    "join batch.file_record fr on fr.id = pi.file_id "
    "join batch.file_error_record er on er.file_id = fr.id "
    f"where tr.request_id in ('{rid_under}','{rid_over}') order by tr.created_at,er.record_no,er.id"
], check=False)

loaded_ok = (psql("batch_business", (
    "select count(*) from biz.customer_account "
    "where tenant_id='ta' and customer_no='S2DSKIPOK001'"
), tuples=True).stdout or "").strip()
loaded_bad = (psql("batch_business", (
    "select count(*) from biz.customer_account "
    "where tenant_id='ta' and customer_no in ('S2DSKIPBAD001','S2DSKIPEXBAD001','S2DSKIPEXBAD002','S2DSKIPEXOK001')"
), tuples=True).stdout or "").strip()
error_summary = (psql("batch_platform", (
    "select count(*) filter (where tr.request_id='" + rid_under + "' and er.is_skipped) || '|' || "
    "count(*) filter (where tr.request_id='" + rid_over + "' and er.is_skipped) "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "join batch.pipeline_instance pi on pi.related_job_instance_id = i.id "
    "join batch.file_record fr on fr.id = pi.file_id "
    "join batch.file_error_record er on er.file_id = fr.id "
    "where tr.request_id in ('" + rid_under + "','" + rid_over + "')"
), tuples=True).stdout or "").strip()

summary = f"{under_status}|{over_status}|loaded_ok={loaded_ok}|blocked_loaded={loaded_bad}|errors={error_summary}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if "SUCCESS|SUCCESS|" not in under_status + "|" or "FAILED|FAILED|IMPORT_SKIP_THRESHOLD_EXCEEDED" not in over_status:
    print("❌ task status/error_code assertion failed", flush=True)
    sys.exit(1)
if loaded_ok != "1" or loaded_bad != "0" or error_summary != "1|2":
    print("❌ data/error record assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 2d import skip threshold PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
