#!/usr/bin/env bash
# 用途：import-stage2.sh:Import 业务分支系统级验证。
# 用法：在仓库根目录执行 bash scripts/sim/08-import-stage2.sh；参数与前置环境见下方说明及所属 README。
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

export SIM_STAGE_NAME="import-stage2"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

echo "==> apply bootstrap(XML/FIXED_WIDTH runtime config)"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v mockserver_host_port="${MOCKSERVER_HOST_PORT}" \
  -f /dev/stdin < test-data/sim/sim-e2e-bootstrap.sql >/dev/null

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2.log"
import json, os, time, urllib.request, subprocess
from datetime import date, timedelta
from scripts.sim.launch_contract import workflow_id_for_fixture

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
RUN_TOKEN = "".join(character for character in BATCH if character.isalnum())[-6:]
START_TS = os.environ["START_TS"].strip()
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

def psql(db, sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", os.environ["POSTGRES_USER"],
        "-d", db, "-P", "pager=off",
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

XML_OK = f"""<?xml version="1.0" encoding="UTF-8"?>
<customers>
  <customer>
    <customer_no>S2X{RUN_TOKEN}A01</customer_no>
    <customer_name>Stage2 XML A</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2XMLCERT{RUN_TOKEN}A01</certificate_no>
    <mobile_no>13900000001</mobile_no>
    <email>s2a@x.io</email>
    <status>ACTIVE</status>
  </customer>
  <customer>
    <customer_no>S2X{RUN_TOKEN}A02</customer_no>
    <customer_name>Stage2 XML B</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2XMLCERT{RUN_TOKEN}A02</certificate_no>
    <mobile_no>13900000002</mobile_no>
    <email>s2b@x.io</email>
    <status>INACTIVE</status>
  </customer>
</customers>
"""

FIXED_OK = "\n".join([
    fixed_row(f"S2F{RUN_TOKEN}A01", "Stage2 Fixed A", "ACTIVE"),
    fixed_row(f"S2F{RUN_TOKEN}A02", "Stage2 Fixed B", "INACTIVE"),
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

def launch(label, job, params, biz_date):
    rid = f"sim-stage2-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "workflowDefinitionId": workflow_id_for_fixture("ta", job),
        "triggerType": "API",
        "bizDate": biz_date,
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
    return rid

scenario_request_ids = {}
base_biz_date = date.fromisoformat(BIZ) + timedelta(days=1)
for scenario_index, (label, job, params, expected) in enumerate(SCENARIOS):
    # 对于给定租户和业务日，Workflow 会保持幂等，即使调用方提供了新的
    # requestId。为每个独立的解析器用例使用不同业务日，避免重复抑制
    # 把格式错误输入误判成启动已确认。使用未来
    # 业务日，确保重复运行 SIM 时不会重新开启之前已结算的日期。
    scenario_biz_date = (base_biz_date + timedelta(days=scenario_index)).isoformat()
    scenario_request_ids[label] = launch(label, job, params, scenario_biz_date)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 120
expected_jobs = len(SCENARIOS)
while time.time() < deadline:
    out = psql(
        os.environ["PLATFORM_DB"],
        "count-terminal-import-instances.sql",
        {"tenant_id": "ta", "start_ts": START_TS},
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= expected_jobs:
        break
    time.sleep(3)

request_ids = ",".join(scenario_request_ids.values())
outcomes = psql(
    os.environ["PLATFORM_DB"],
    "select-stage2-workflow-outcomes.sql",
    {"request_ids": request_ids},
    tuples=True,
)
actual = {}
for line in (outcomes.stdout or "").splitlines():
    fields = line.split("|")
    if len(fields) == 6:
        actual[fields[0]] = tuple(fields[1:])

expected_workflow_status = {label: expected for label, _, _, expected in SCENARIOS}
for label, request_id in scenario_request_ids.items():
    expected_job_status = expected_workflow_status[label]
    expected_status = "LAUNCHED|{0}|{0}|{0}|{0}".format(expected_job_status)
    actual_status = "|".join(actual.get(request_id, ()))
    if actual_status != expected_status:
        raise SystemExit(
            f"scenario {label} did not reach its expected workflow/job terminal state: "
            f"requestId={request_id} expected={expected_status} actual={actual_status or '<missing>'}"
        )
    print(f"  [terminal] {label:16s} {actual_status}", flush=True)

queries = {
    "job_status": "select-import-instance-status.sql",
    "file_status": "select-import-file-status.sql",
}
for title, sql_file in queries.items():
    print(f"\n-- {title} --", flush=True)
    psql(
        os.environ["PLATFORM_DB"], sql_file,
        {"tenant_id": "ta", "start_ts": START_TS}, capture_output=False)

print("\n-- business counts --", flush=True)
psql(
    os.environ["BUSINESS_DB"], "select-stage2-import-business-counts.sql",
    {"tenant_id": "ta"}, capture_output=False)

print(f"\n==> Stage 2 import scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
