#!/usr/bin/env bash
# =========================================================
# 28-import-mainline.sh:三租户 IMPORT 主线系统级验证
#
# 覆盖:
#   - ta/TA_IMPORT_CUSTOMER -> biz.customer_account
#   - tb/TB_IMPORT_TRANSACTION -> biz.transaction(NUMERIC + DATE typed load)
#   - tc/TC_IMPORT_RISK_SCORE -> biz.risk_score(NUMERIC + DATE typed load)
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-import -> biz.*。
# 本脚本只验证 IMPORT,不混入 export / dispatch / workflow 的下游结果。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export SIM_STAGE_NAME="import-mainline"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python

export ROWS="${ROWS:-5}"

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/import-mainline.log"
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
PG = os.environ.get("PG_CONTAINER", "batch-postgres-primary")
PGU = os.environ.get("POSTGRES_USER", "batch_user")
PLATFORM_DB = os.environ["PLATFORM_DB"]
BUSINESS_DB = os.environ["BUSINESS_DB"]
ROWS = int(os.environ.get("ROWS", "5"))
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

TOKEN = "".join(ch for ch in BATCH if ch.isalnum())[-14:]


def customer_row(i):
    return (
        f"C{TOKEN}{i:03d},Mainline Customer {i},PERSONAL,"
        f"ID{TOKEN[-9:]}{i:03d},138{i:08d},main{i}@sim.local,ACTIVE"
    )


def transaction_row(i):
    return (
        f"T{TOKEN}{i:03d},ACC{i:010d},DEPOSIT,{100 + i}.50,CNY,{BIZ},"
        f"sim-mainline-{BATCH}-{i}"
    )


def risk_row(i):
    band = "LOW" if i % 2 else "MEDIUM"
    return f"E{TOKEN}{i:03d},ACCOUNT,{500 + i % 400},{band},{BIZ}"


IMPORTS = {
    "ta": (
        "TA_IMPORT_CUSTOMER",
        "TA_IMPORT_CUSTOMER_TPL",
        "customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status",
        customer_row,
    ),
    "tb": (
        "TB_IMPORT_TRANSACTION",
        "TB_IMPORT_TRANSACTION_TPL",
        "txn_no,account_no,txn_type,amount,currency_code,txn_date,remark",
        transaction_row,
    ),
    "tc": (
        "TC_IMPORT_RISK_SCORE",
        "TC_IMPORT_RISK_SCORE_TPL",
        "entity_id,entity_type,score_value,score_band,score_date",
        risk_row,
    ),
}
TERMINAL = {"SUCCESS", "FAILED", "COMPENSATED", "CANCELLED", "TERMINATED", "REJECTED"}
REQUEST_IDS = []


def run_sql_file(db, sql_file, variables=None):
    args = [
        "docker", "exec", "-i", PG, "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", PGU, "-d", db, "-tA", "-P", "pager=off",
    ]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    with open(os.path.join(SQL_DIR, sql_file), encoding="utf-8") as sql:
        result = subprocess.run(
            args, input=sql.read(), text=True, capture_output=True, check=True)
    return result.stdout.strip()


def launch(tenant, job, template_code, header, row_builder):
    request_id = f"{BATCH}-{tenant}-{job}"
    rows = "\n".join(row_builder(i) for i in range(1, ROWS + 1))
    content = f"{header}\n{rows}\n"
    body = {
        "tenantId": tenant,
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": request_id,
        "params": {
            "templateCode": template_code,
            "content": content,
            "batchNo": BATCH,
        },
    }
    request = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": tenant,
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": request_id,
            "X-Request-Id": request_id,
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode())
        ok = response.status == 200 and payload.get("code") == "SUCCESS"
        print(
            f"  [launch] {tenant}/{job:24s} {'PASS' if ok else 'FAIL'} requestId={request_id}",
            flush=True,
        )
        if not ok:
            raise RuntimeError(payload)
    REQUEST_IDS.append(request_id)


def wait_instances():
    variables = {"request_ids": ",".join(REQUEST_IDS)}
    last_rows = []
    for _ in range(90):
        rows = [line for line in run_sql_file(
            PLATFORM_DB, "select-import-mainline-instance-status.sql", variables
        ).splitlines() if line]
        if rows != last_rows:
            print("status:", flush=True)
            for row in rows:
                print(f"  {row}", flush=True)
            last_rows = rows
        statuses = [row.split("|")[1] for row in rows]
        if len(rows) == len(REQUEST_IDS) and all(status in TERMINAL for status in statuses):
            return rows
        time.sleep(2)
    raise TimeoutError("import instances did not reach terminal status")


def assert_business_counts():
    counts = run_sql_file(
        BUSINESS_DB, "select-import-mainline-business-counts.sql",
        {"token": TOKEN, "biz_date": BIZ, "batch_no": BATCH})
    print("business_counts:")
    print(counts)
    actual = dict(line.split("|", 1) for line in counts.splitlines() if line)
    expected = {
        "ta.customer_account": str(ROWS),
        "tb.transaction": str(ROWS),
        "tc.risk_score": str(ROWS),
    }
    if actual != expected:
        raise AssertionError(f"business counts mismatch: expected={expected}, actual={actual}")


def print_task_rows():
    rows = run_sql_file(
        PLATFORM_DB, "select-import-mainline-task-status.sql",
        {"request_ids": ",".join(REQUEST_IDS)})
    print("task_rows:")
    print(rows)


print(f"==> import mainline:rows={ROWS} bizDate={BIZ} batchNo={BATCH}")
for tenant, spec in IMPORTS.items():
    launch(tenant, *spec)

final_rows = wait_instances()
assert_business_counts()
print_task_rows()

failed = [row for row in final_rows if "|SUCCESS|" not in row]
if failed:
    print("failed_instances:")
    print("\n".join(failed))
    sys.exit(1)
PY
