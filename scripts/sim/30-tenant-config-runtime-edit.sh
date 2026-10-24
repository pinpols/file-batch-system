#!/usr/bin/env bash
# =========================================================
# 30-tenant-config-runtime-edit.sh:运行中更新作业配置的实例边界 SIM
#
# Atomic shell 任务执行 /bin/sleep 时，通过 Console API 更新默认参数、重试策略和超时。
# 断言在途实例仍使用创建时快照，更新后的新实例使用新定义；最后恢复原配置。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export SIM_STAGE_NAME="tenant-config-runtime-edit"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python
export SIM_RUNTIME_CONFIG_SQL_DIR="$ROOT/scripts/sim/sql"

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/tenant-config-runtime-edit.log"
import http.cookiejar
import json
import os
import signal
from scripts.sim.launch_contract import workflow_id_for_fixture
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from datetime import date, timedelta

TENANT = os.environ.get("SIM_ATOMIC_TENANT_ID", "default-tenant")
JOB_CODE = os.environ.get("SIM_ATOMIC_JOB_CODE", "atomic_shell_demo")
CONSOLE = os.environ["CONSOLE_BASE"].rstrip("/")
TRIGGER = os.environ["TRIGGER_BASE"].rstrip("/")
SECRET = os.environ["INTERNAL_SECRET"]
SQL_DIR = Path(os.environ["SIM_RUNTIME_CONFIG_SQL_DIR"])
PG_CONTAINER = os.environ["PG_CONTAINER"]
PG_USER = os.environ["POSTGRES_USER"]
PLATFORM_DB = os.environ["PLATFORM_DB"]
BIZ_DATE = os.environ["BIZ_DATE"]


def interrupt_run(signum, _frame):
    raise SystemExit(128 + signum)


def psql_file(name, variables):
    command = [
        "docker", "exec", "-i", PG_CONTAINER, "psql", "-X", "-q",
        "-v", "ON_ERROR_STOP=1", "-U", PG_USER, "-d", PLATFORM_DB,
        "-t", "-A", "-F", "|",
    ]
    for key, value in variables.items():
        command.extend(["-v", f"{key}={value}"])
    command.extend(["-f", "/dev/stdin"])
    result = subprocess.run(
        command,
        input=(SQL_DIR / name).read_text(encoding="utf-8"),
        text=True,
        capture_output=True,
        check=True,
    )
    return result.stdout.strip()


cookie_jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))
token = os.environ.get("CONSOLE_TOKEN", "")


def request(method, url, body=None, headers=None, timeout=30, console=True):
    request_headers = dict(headers or {})
    if console:
        request_headers["X-Tenant-Id"] = TENANT
        if token:
            request_headers["Cookie"] = f"batch_console_token={token}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    if data is not None:
        request_headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with opener.open(req, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8")


def console_auth():
    if token:
        return
    status, _ = request("GET", f"{CONSOLE}/api/console/auth/check", console=True)
    if status in (200, 204):
        return
    status, body = request(
        "POST",
        f"{CONSOLE}/api/console/auth/login",
        {
            "username": os.environ.get("CONSOLE_USER", "admin"),
            "password": os.environ.get("CONSOLE_PASS", "admin123"),
        },
        console=False,
    )
    if status != 200:
        raise RuntimeError(f"Console login failed HTTP={status}: {body[:400]}")


def update_definition(job_id, default_params, retry_policy, retry_max_count, timeout_seconds):
    status, body = request(
        "PUT",
        f"{CONSOLE}/api/console/job-definitions/{job_id}",
        {
            "tenantId": TENANT,
            "defaultParams": json.dumps(default_params, separators=(",", ":")),
            "retryPolicy": retry_policy,
            "retryMaxCount": retry_max_count,
            "timeoutSeconds": timeout_seconds,
        },
    )
    if status != 200 or json.loads(body).get("code") != "SUCCESS":
        raise RuntimeError(f"job definition update failed HTTP={status}: {body[:500]}")


def launch(label, biz_date):
    request_id = f"sim-tenant-runtime-edit-{label}-{int(time.time() * 1000)}"
    status, body = request(
        "POST",
        f"{TRIGGER}/api/triggers/launch",
        {
            "tenantId": TENANT,
            "workflowDefinitionId": workflow_id_for_fixture(TENANT, JOB_CODE),
            "triggerType": "API",
            "bizDate": biz_date,
            "requestId": request_id,
            "params": {},
        },
        headers={
            "X-Internal-Secret": SECRET,
            "X-Request-Id": request_id,
            "Idempotency-Key": request_id,
        },
        console=False,
    )
    if status != 200 or '"SUCCESS"' not in body:
        raise RuntimeError(f"launch failed HTTP={status}: {body[:500]}")
    return request_id


def observe(request_id):
    output = psql_file(
        "select-sim-runtime-config-observation.sql",
        {"tenant_id": TENANT, "request_id": request_id},
    )
    if not output:
        return None
    return output.split("|")


def wait_for_observation(request_id, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        row = observe(request_id)
        if row is not None:
            return row
        time.sleep(1)
    raise TimeoutError(f"instance snapshot was not created: requestId={request_id}")


def wait_for_task(request_id, expected_status, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        output = psql_file(
            "select-sim-running-task-id.sql",
            {"tenant_id": TENANT, "request_id": request_id},
        )
        if output:
            instance_id, task_id, task_status = output.split("|", 2)
            if task_status == expected_status:
                return instance_id, task_id
        time.sleep(1)
    raise TimeoutError(f"task did not reach {expected_status}: requestId={request_id}")


def wait_terminal(request_id, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        row = observe(request_id)
        if row and row[1] in {"SUCCESS", "FAILED", "CANCELLED", "TERMINATED"}:
            return row
        time.sleep(1)
    raise TimeoutError(f"task did not finish: requestId={request_id}")


job_id = psql_file(
    "select-sim-job-definition-id.sql", {"tenant_id": TENANT, "job_code": JOB_CODE}
)
if not job_id:
    raise RuntimeError(f"required SIM job not found: tenant={TENANT} job={JOB_CODE}")

console_auth()
status, body = request(
    "GET",
    f"{CONSOLE}/api/console/job-definitions/{job_id}?tenantId={urllib.parse.quote(TENANT)}",
)
if status != 200:
    raise RuntimeError(f"job definition read failed HTTP={status}: {body[:500]}")
original = json.loads(body).get("data", {})
original_params = original.get("defaultParams")
if isinstance(original_params, str):
    original_params = json.loads(original_params)
if not isinstance(original_params, dict):
    original_params = {}
original_policy = original.get("retryPolicy") or "NONE"
original_retry_count = original.get("retryMaxCount") or 0
original_timeout = original.get("timeoutSeconds") or 0

# 更新运行态配置前安装处理器；异常退出仍进入 finally 恢复原定义。
signal.signal(signal.SIGTERM, interrupt_run)
signal.signal(signal.SIGINT, interrupt_run)

sleep_params = {"taskType": "shell", "command": "/bin/sleep", "args": ["20"]}
edited_params = {"taskType": "shell", "command": "/bin/echo", "args": ["updated-config"]}
old_instance_request = None
try:
    update_definition(job_id, sleep_params, "EXPONENTIAL", 1, 300)
    old_instance_request = launch("in-flight", BIZ_DATE)
    instance_id, task_id = wait_for_task(old_instance_request, "RUNNING")
    print(f"  [running] instance={instance_id} task={task_id}", flush=True)

    update_definition(job_id, edited_params, "NONE", 0, 60)
    old_snapshot = observe(old_instance_request)
    assert old_snapshot is not None, "in-flight instance disappeared after definition update"
    assert old_snapshot[2:9] == ["/bin/sleep", "20", "EXPONENTIAL", "1", "300", "/bin/sleep", "20"], (
        f"in-flight instance drifted after update: observation={old_snapshot}"
    )
    print("  ✓ running instance retains its creation-time execution and retry snapshot", flush=True)

    new_instance_biz_date = (
        date.fromisoformat(BIZ_DATE) + timedelta(days=1)
    ).isoformat()
    new_instance_request = launch("after-edit", new_instance_biz_date)
    new_snapshot = wait_for_observation(new_instance_request)
    assert new_snapshot[2:7] == ["/bin/echo", "updated-config", "NONE", "0", "60"], (
        f"new instance did not use the updated definition: observation={new_snapshot}"
    )
    new_terminal = wait_terminal(new_instance_request)
    assert new_terminal[1] == "SUCCESS", f"updated-config instance failed: {new_terminal}"
    old_terminal = wait_terminal(old_instance_request)
    assert old_terminal[1] == "SUCCESS", f"in-flight instance failed: {old_terminal}"
    print(
        f"  ✓ old instance SUCCESS; new instance SUCCESS; requestIds="
        f"{old_instance_request},{new_instance_request}",
        flush=True,
    )
finally:
    # 恢复期间忽略重复中断，避免第二个信号打断配置回滚。
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    update_definition(
        job_id,
        original_params,
        original_policy,
        original_retry_count,
        original_timeout,
    )
    print("  ✓ original SIM job definition restored", flush=True)

print("==> ✅ 运行中修改作业配置 SIM 完成；只验证执行/重试快照边界", flush=True)
PY
