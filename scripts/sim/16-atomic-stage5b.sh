#!/usr/bin/env bash
# =========================================================
# 16-atomic-stage5b.sh:Atomic shell/sql/stored-proc 成功态验证
#
# 覆盖:
#   - atomic_shell_demo worker 终态 SUCCESS
#   - atomic_sql_demo worker 终态 SUCCESS
#   - atomic_stored_proc_demo worker 终态 SUCCESS
#
# Atomic HTTP 真成功需非 loopback allowlisted endpoint,本脚本不覆盖。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="atomic-stage5b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/atomic-stage5b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
TENANT = os.environ["BATCH_DEFAULT_TENANT_ID"]
PG_CONTAINER = os.environ["PG_CONTAINER"]
PG_USER = os.environ["POSTGRES_USER"]
PLATFORM_DB = os.environ["PLATFORM_DB"]
JOBS = ["atomic_shell_demo", "atomic_sql_demo", "atomic_stored_proc_demo"]
request_ids = {}

def launch(job):
    rid = f"sim-stage5b-{job}-{int(time.time()*1000)%100000000}"
    request_ids[job] = rid
    body = {
        "tenantId": TENANT,
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": TENANT,
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {job:24s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            sys.exit(1)

def psql(sql, tuples=False):
    args = ["docker", "exec", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PLATFORM_DB, "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

for job in JOBS:
    launch(job)

deadline = time.time() + 150
while time.time() < deadline:
    req_list = ",".join("'" + rid + "'" for rid in request_ids.values())
    out = psql(
        "select count(*) from batch.trigger_request tr "
        "join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
        f"where tr.tenant_id='{TENANT}' and tr.request_id in ({req_list}) "
        "and i.instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')",
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= len(JOBS):
        break
    time.sleep(3)

print("\n-- atomic_status --", flush=True)
req_list = ",".join("'" + rid + "'" for rid in request_ids.values())
status_sql = (
    "select i.job_code,i.instance_status,t.task_status,t.error_code "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
    "left join batch.job_task t on t.job_instance_id=i.id and t.tenant_id=i.tenant_id "
    f"where tr.tenant_id='{TENANT}' and tr.request_id in ({req_list}) "
    "order by i.job_code"
)
subprocess.run([
    "docker", "exec", PG_CONTAINER, "psql", "-U", PG_USER,
    "-d", PLATFORM_DB, "-P", "pager=off", "-c", status_sql
], check=False)

out = psql(
    "select count(*) from (" + status_sql + ") s "
    "where instance_status='SUCCESS' and task_status='SUCCESS'",
    tuples=True,
)
success = int((out.stdout or "0").strip() or "0")
print(f"\n-- assertion_summary --\n{success}/{len(JOBS)}", flush=True)
if success != len(JOBS):
    print("❌ Atomic Stage5b assertion failed", flush=True)
    sys.exit(1)

print("\n==> Stage 5b atomic scenario PASS", flush=True)
PY
