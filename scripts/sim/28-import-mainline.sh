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

SIM_STAGE_NAME="import-mainline"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python

export ROWS="${ROWS:-5}"
export START_TS
START_TS="$(docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc "select now()")"

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


def run_cmd(db, sql):
    result = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            PG,
            "psql",
            "-U",
            PGU,
            "-d",
            db,
            "-tA",
            "-P",
            "pager=off",
        ],
        input=sql,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
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
    request_list = ",".join("'" + request_id + "'" for request_id in REQUEST_IDS)
    status_sql = f"""
select tenant_id || '/' || job_code || '|' || instance_status || '|' || id || '|' || instance_no
from batch.job_instance
where dedup_key in ({request_list})
order by tenant_id, job_code;
"""
    last_rows = []
    for _ in range(90):
        rows = [line for line in run_cmd(PLATFORM_DB, status_sql).splitlines() if line]
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
    counts = run_cmd(
        BUSINESS_DB,
        f"""
select 'ta.customer_account|' || count(*)
from biz.customer_account
where tenant_id='ta' and customer_no like 'C{TOKEN}%'
union all
select 'tb.transaction|' || count(*)
from biz.transaction
where tenant_id='tb' and txn_date='{BIZ}' and remark like 'sim-mainline-{BATCH}-%'
union all
select 'tc.risk_score|' || count(*)
from biz.risk_score
where tenant_id='tc' and score_date='{BIZ}' and entity_id like 'E{TOKEN}%';
""",
    )
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
    request_list = ",".join("'" + request_id + "'" for request_id in REQUEST_IDS)
    rows = run_cmd(
        PLATFORM_DB,
        f"""
select coalesce(tenant_id,'') || '/' || coalesce(task_type,'') || '/'
    || coalesce(task_status,'') || '/' || coalesce(error_code,'') || '/'
    || left(coalesce(error_message,''),160)
from batch.job_task
where job_instance_id in (
    select id from batch.job_instance where dedup_key in ({request_list})
)
order by id;
""",
    )
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
