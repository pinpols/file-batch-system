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

batch_require_python

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/atomic-stage5b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
TENANT = os.environ["BATCH_DEFAULT_TENANT_ID"]
PG_CONTAINER = os.environ["PG_CONTAINER"]
PG_USER = os.environ["POSTGRES_USER"]
PLATFORM_DB = os.environ["PLATFORM_DB"]
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")
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

def psql(sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", PG_CONTAINER, "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", PG_USER, "-d", PLATFORM_DB,
        "-P", "pager=off",
    ]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    with open(os.path.join(SQL_DIR, sql_file), encoding="utf-8") as sql:
        return subprocess.run(
            args, check=True, capture_output=capture_output,
            text=True, input=sql.read())

for job in JOBS:
    launch(job)

deadline = time.time() + 150
while time.time() < deadline:
    out = psql(
        "count-terminal-request-instances.sql",
        {"tenant_id": TENANT, "request_ids": ",".join(request_ids.values())},
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= len(JOBS):
        break
    time.sleep(3)

print("\n-- atomic_status --", flush=True)
query_variables = {
    "tenant_id": TENANT,
    "request_ids": ",".join(request_ids.values()),
}
psql("select-atomic-request-task-status.sql", query_variables, capture_output=False)

out = psql(
    "count-successful-atomic-requests.sql",
    query_variables,
    tuples=True,
)
success = int((out.stdout or "0").strip() or "0")
print(f"\n-- assertion_summary --\n{success}/{len(JOBS)}", flush=True)
if success != len(JOBS):
    print("❌ Atomic Stage5b assertion failed", flush=True)
    sys.exit(1)

print("\n==> Stage 5b atomic scenario PASS", flush=True)
PY
