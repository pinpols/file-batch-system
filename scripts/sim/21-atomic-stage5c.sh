#!/usr/bin/env bash
# =========================================================
# 21-atomic-stage5c.sh:Atomic HTTP / timeout / cancel 验证
#
# 覆盖:
#   - HTTP executor 访问非 loopback 公网 endpoint 真成功
#   - SQL executor statement_timeout 触发 TIMEOUT 失败分类
#   - task cancel API 对 RUNNING shell 任务置 cancel_requested,验证最终语义
#
# 参数配置位于 docs/test-data/sim-stage5c-atomic-params.json,脚本只负责编排和断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="atomic-stage5c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"


batch_require_python

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/atomic-stage5c.log"
import json, os, subprocess, sys, time, urllib.error, urllib.request

BASE = os.environ["TRIGGER_BASE"]
ORCH = os.environ["ORCH_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
TENANT = os.environ["BATCH_DEFAULT_TENANT_ID"]
PG_CONTAINER = os.environ["PG_CONTAINER"]
PG_USER = os.environ["POSTGRES_USER"]
PLATFORM_DB = os.environ["PLATFORM_DB"]
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")
PARAMS_FILE = "docs/test-data/sim-stage5c-atomic-params.json"

with open(PARAMS_FILE, "r", encoding="utf-8") as fh:
    ATOMIC_PARAMS = json.load(fh)

def psql(sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", PG_CONTAINER, "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", PG_USER, "-d", PLATFORM_DB,
        "-P", "pager=off",
    ]
    if tuples:
        args += ["-t", "-A", "-F", "\x1f"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    with open(os.path.join(SQL_DIR, sql_file), encoding="utf-8") as sql:
        return subprocess.run(
            args, check=True, capture_output=capture_output,
            text=True, input=sql.read())

def post_json(url, body, timeout=30):
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": TENANT,
            "X-Internal-Secret": SECRET,
            "X-Request-Id": body.get("requestId", BATCH),
            "Idempotency-Key": body.get("requestId", BATCH),
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode()

def launch(job, label, params):
    rid = f"sim-stage5c-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": TENANT,
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": params | {"batchNo": BATCH},
    }
    status, text = post_json(f"{BASE}/api/triggers/launch", body)
    ok = status == 200 and '"SUCCESS"' in text
    print(f"  [launch] {label:14s} {'✓' if ok else '✗'}", flush=True)
    if not ok:
        print(text[:500], flush=True)
        raise RuntimeError(f"launch failed: {label}")
    return rid

def instance_for_request(rid):
    out = psql(
        "select-request-instance-status.sql",
        {"tenant_id": TENANT, "request_id": rid},
        tuples=True,
    )
    value = (out.stdout or "").strip()
    if not value:
        return None, None
    iid, status = value.split("|", 1)
    return iid, status

def wait_terminal(rid, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        iid, status = instance_for_request(rid)
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            return iid, status
        time.sleep(2)
    raise TimeoutError(f"timeout waiting {rid}")

def wait_running_task(rid, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = psql(
            "select-running-atomic-task.sql",
            {"tenant_id": TENANT, "request_id": rid},
            tuples=True,
        )
        value = (out.stdout or "").strip()
        if value:
            iid, tid, status = value.split("|", 2)
            if status == "RUNNING":
                return iid, tid
        time.sleep(1)
    raise TimeoutError(f"running task not found for {rid}")

print("==> launch atomic HTTP success", flush=True)
rid_http = launch("atomic_http_demo", "http-success", ATOMIC_PARAMS["httpSuccess"])
http_instance, http_status = wait_terminal(rid_http)

print("==> launch atomic SQL timeout", flush=True)
rid_timeout = launch("atomic_sql_demo", "sql-timeout", ATOMIC_PARAMS["sqlTimeout"])
timeout_instance, timeout_status = wait_terminal(rid_timeout)

print("==> launch atomic shell cancel", flush=True)
rid_cancel = launch("atomic_shell_demo", "shell-cancel", ATOMIC_PARAMS["shellCancel"])
cancel_instance, cancel_task = wait_running_task(rid_cancel)
cancel_url = f"{ORCH}/internal/tasks/{cancel_task}/cancel"
req = urllib.request.Request(
    cancel_url,
    data=json.dumps({"tenantId": TENANT, "reason": "sim-stage5c-cancel"}).encode(),
    method="POST",
    headers={"Content-Type": "application/json", "X-Internal-Secret": SECRET},
)
cancel_http = ""
try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        cancel_http = str(resp.status)
        resp.read()
except urllib.error.HTTPError as ex:
    cancel_http = str(ex.code)
    ex.read()
_, cancel_status = wait_terminal(rid_cancel, timeout=90)

print("\n-- atomic_stage5c_status --", flush=True)
psql(
    "select-atomic-request-status-details.sql",
    {
        "tenant_id": TENANT,
        "request_ids": ",".join([rid_http, rid_timeout, rid_cancel]),
    },
    capture_output=False,
)

out = psql(
    "select-atomic-assertion-summary.sql",
    {
        "tenant_id": TENANT,
        "http_request_id": rid_http,
        "timeout_request_id": rid_timeout,
        "cancel_request_id": rid_cancel,
    },
    tuples=True,
)
summary = (out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary.replace(chr(31), '|')}|cancelHttp={cancel_http}", flush=True)

parts = summary.split("\x1f")
if len(parts) != 3:
    print("❌ Atomic Stage5c malformed summary", flush=True)
    sys.exit(1)
http_ok = parts[0] == "SUCCESS"
timeout_ok = parts[1].startswith("FAILED:TIMEOUT:") or parts[1].startswith("FAILED:WORKER_EXECUTION_TIMEOUT:")
cancel_marked = parts[2].startswith("FAILED:FAILED:") and parts[2].endswith(":true") and cancel_http == "200"
if not http_ok or not timeout_ok or not cancel_marked:
    print("❌ Atomic Stage5c assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 5c atomic scenario PASS: http={http_instance}/{http_status} timeout={timeout_instance}/{timeout_status} cancel={cancel_instance}/{cancel_status}", flush=True)
PY
