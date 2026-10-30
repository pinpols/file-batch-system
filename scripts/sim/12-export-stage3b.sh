#!/usr/bin/env bash
# 用途：export-stage3b.sh:Export 多分片业务完整性验证。
# 用法：在仓库根目录执行 bash scripts/sim/12-export-stage3b.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 12-export-stage3b.sh:Export 多分片业务完整性验证
#
# 覆盖:
#   - STATIC 4 分片系统级导出
# 夹具保留旧 partition_keyset_range 配置，当前运行时统一使用哈希分片。
#   - 4 个 task / 4 个 file_record / 文件名 / recordCount 汇总及产物业务键无重无漏
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-export。
# SQL fixture 独立放在 test-data/sim,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export SIM_STAGE_NAME="export-stage3b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python
echo "==> apply bootstrap + stage3b fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v mockserver_host_port="${MOCKSERVER_HOST_PORT}" \
  -f /dev/stdin < test-data/sim/sim-e2e-bootstrap.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < test-data/sim/sim-stage3b-export-fixtures.sql >/dev/null

echo "==> seed export partition source rows"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" \
  -f /dev/stdin < test-data/sim/sim-stage3b-export-source.sql >/dev/null

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/export-stage3b.log"
import json, os, subprocess, sys, time, urllib.request
from scripts.sim.export_partition_evidence import verify_partition_contents
from scripts.sim.launch_contract import workflow_id_for_fixture

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
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

rid = f"sim-stage3b-keyset4-{int(time.time()*1000)%100000000}"
body = {
    "tenantId": "ta",
    "workflowDefinitionId": workflow_id_for_fixture("ta", "TA_EXPORT_REPORT_STATIC"),
    "triggerType": "API",
    "bizDate": BIZ,
    "requestId": rid,
    "params": {
        "templateCode": "TA_EXPORT_REPORT_JSON_KEYSET_TPL",
        "batchNo": BATCH,
        "bizType": "TA_EXPORT_REPORT_JSON_KEYSET",
        "partitionCount": 4,
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
    print(f"  [launch] TA_EXPORT_REPORT_STATIC partitionCount=4 {'✓' if ok else '✗'}", flush=True)
    if not ok:
        print(text[:500], flush=True)
        sys.exit(1)

deadline = time.time() + 180
instance_id = None
while time.time() < deadline:
    out = psql(
        os.environ["PLATFORM_DB"], "select-request-instance-status.sql",
        {"tenant_id": "ta", "request_id": rid}, tuples=True)
    value = (out.stdout or "").strip()
    if value:
        iid, status = value.split("|", 1)
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            instance_id = iid
            print(f"  [result] instance={iid} status={status}", flush=True)
            if status != "SUCCESS":
                sys.exit(1)
            break
    time.sleep(3)
if not instance_id:
    raise TimeoutError("timeout waiting export stage3b")

print("\n-- task_status --", flush=True)
query_variables = {"tenant_id": "ta", "instance_id": instance_id, "batch_no": BATCH}
psql(
    os.environ["PLATFORM_DB"], "select-export-partition-task-status.sql",
    query_variables, capture_output=False)

print("\n-- file_records --", flush=True)
psql(
    os.environ["PLATFORM_DB"], "select-export-file-records.sql",
    query_variables, capture_output=False)

out = psql(
    os.environ["PLATFORM_DB"], "select-export-stage3b-assertion.sql",
    query_variables, tuples=True)
summary = (out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "4|4|4|40":
    print("❌ Export Stage3b assertion failed, expected 4|4|4|40", flush=True)
    sys.exit(1)

objects_result = psql(
    os.environ["PLATFORM_DB"], "select-export-partition-objects.sql",
    {"tenant_id": "ta", "source_ref": BATCH}, tuples=True)
records = json.loads(objects_result.stdout)
alias = os.environ.get("MINIO_MC_ALIAS") or "local"
objects = [f"{alias}/{record['bucket']}/{record['path']}" for record in records]
def read_object(path):
    return subprocess.check_output(["bash", "scripts/lib/minio-mc.sh", "cat", path], text=True)
verify_partition_contents(objects, 4, 40, read_object)
print("  [contents] 40 customer keys present exactly once", flush=True)

print(f"\n==> Stage 3b export scenario PASS: batchNo={BATCH} instance={instance_id}", flush=True)
PY
