#!/usr/bin/env bash
# =========================================================
# 17-import-stage2c.sh:Import APPEND/UPSERT/REPLACE 小矩阵验证
#
# 覆盖:
#   - APPEND/no-conflict:同内容同 batchNo 跑两次产生 2 行
#   - BATCH_UPSERT:同业务键跑两次只保留 1 行且字段更新
#   - PARTITION_REPLACE_COPY:同 tenant_id + source_batch_no 先清 stale 再 COPY 新数据
#
# SQL fixture 位于 docs/test-data,脚本只负责编排、API 触发和断言。
#
# 注意:PARTITION_REPLACE_COPY 与 line-based checkpoint 语义互斥。checkpoint 默认开启后,
# 本阶段作为 load mode 正向矩阵,会临时以 batch.worker.checkpoint.enabled=false 重启
# worker-import;结束后恢复默认配置。checkpoint=true 下的拒跑保护由 Stage 2b 覆盖。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="import-stage2c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

__RESTARTED_IMPORT_WITH_CHECKPOINT_DISABLED=0
wait_import_worker() {
  local port="${BATCH_WORKER_IMPORT_PORT:-18083}"
  for _ in $(seq 1 90); do
    if curl -sf --max-time 5 --connect-timeout 2 "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "ERROR: worker-import did not become healthy on port ${port}" >&2
  return 1
}

restore_import_default() {
  if [[ "$__RESTARTED_IMPORT_WITH_CHECKPOINT_DISABLED" == "1" && "${RESTORE_IMPORT_AFTER_STAGE2C:-1}" == "1" ]]; then
    echo "==> restore worker-import default config"
    bash "$ROOT/scripts/local/restart.sh" worker-import >/dev/null || true
    wait_import_worker || true
  fi
}
trap restore_import_default EXIT

if [[ "${RESTART_IMPORT_WITH_CHECKPOINT_DISABLED:-1}" == "1" ]]; then
  echo "==> restart worker-import with checkpoint disabled for PARTITION_REPLACE_COPY matrix"
  COMPOSE_ENV_FILE=/dev/null \
  BATCH_WORKER_CHECKPOINT_ENABLED=false \
  JAVA_OPTS="${JAVA_OPTS:-} -Dbatch.worker.checkpoint.enabled=false" \
    bash "$ROOT/scripts/local/restart.sh" worker-import >/dev/null
  wait_import_worker
  __RESTARTED_IMPORT_WITH_CHECKPOINT_DISABLED=1
fi

echo "==> apply bootstrap + stage2c fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v mockserver_host_port="${MOCKSERVER_HOST_PORT:-11080}" \
  -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2c-import-matrix-business.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2c-import-matrix-fixtures.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO-replace" \
  -f /dev/stdin < docs/test-data/sim-stage2c-import-matrix-stale.sql >/dev/null

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2c.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")
request_ids = []

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

def launch(label, job, template, content, batch_no):
    rid = f"sim-stage2c-{label}-{int(time.time()*1000)%100000000}"
    request_ids.append(rid)
    body = {
        "tenantId": "ta",
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "templateCode": template,
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
        print(f"  [launch] {label:16s} {job:28s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {label}")
    return rid

def wait_for(job, rid, expected="SUCCESS"):
    deadline = time.time() + 180
    while time.time() < deadline:
        out = psql(
            os.environ["PLATFORM_DB"], "select-request-job-status.sql",
            {"tenant_id": "ta", "request_id": rid, "job_code": job}, tuples=True)
        status = (out.stdout or "").strip()
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            marker = "✓" if status == expected else "✗"
            print(f"  [result] {job:28s} {status:14s} expected={expected} {marker}", flush=True)
            if status != expected:
                sys.exit(1)
            return
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {job}/{rid}")

append_xml = xml_payload([{
    "no": "S2CAPP000001",
    "name": "Stage2c Append",
    "cert": "S2CAPP000001CERT",
    "mobile": "13900009201",
    "email": "s2c-append@x.io",
    "status": "ACTIVE",
}])
upsert_first = xml_payload([{
    "no": "S2CUPS000001",
    "name": "Stage2c Upsert Original",
    "cert": "S2CUPS000001CERT",
    "mobile": "13900009202",
    "email": "s2c-upsert@x.io",
    "status": "ACTIVE",
}])
upsert_second = xml_payload([{
    "no": "S2CUPS000001",
    "name": "Stage2c Upsert Updated",
    "cert": "S2CUPS000001CERT",
    "mobile": "13900009203",
    "email": "s2c-upsert-updated@x.io",
    "status": "INACTIVE",
}])
replace_xml = xml_payload([
    {
        "no": "S2CREP000001",
        "name": "Stage2c Replace A",
        "cert": "S2CREP000001CERT",
        "mobile": "13900009204",
        "email": "s2c-replace-a@x.io",
        "status": "ACTIVE",
    },
    {
        "no": "S2CREP000002",
        "name": "Stage2c Replace B",
        "cert": "S2CREP000002CERT",
        "mobile": "13900009205",
        "email": "s2c-replace-b@x.io",
        "status": "INACTIVE",
    },
])

print("==> APPEND twice", flush=True)
for label in ("append_first", "append_second"):
    rid = launch(label, "TA_IMPORT_STAGE2C_APPEND", "TA_IMPORT_STAGE2C_APPEND_TPL", append_xml, BATCH + "-append")
    wait_for("TA_IMPORT_STAGE2C_APPEND", rid)

print("==> UPSERT twice", flush=True)
rid = launch("upsert_first", "TA_IMPORT_STAGE2C_UPSERT", "TA_IMPORT_STAGE2C_UPSERT_TPL", upsert_first, BATCH + "-upsert-1")
wait_for("TA_IMPORT_STAGE2C_UPSERT", rid)
rid = launch("upsert_second", "TA_IMPORT_STAGE2C_UPSERT", "TA_IMPORT_STAGE2C_UPSERT_TPL", upsert_second, BATCH + "-upsert-2")
wait_for("TA_IMPORT_STAGE2C_UPSERT", rid)

print("==> PARTITION_REPLACE_COPY", flush=True)
rid = launch("replace", "TA_IMPORT_STAGE2C_REPLACE", "TA_IMPORT_STAGE2C_REPLACE_TPL", replace_xml, BATCH + "-replace")
wait_for("TA_IMPORT_STAGE2C_REPLACE", rid)

print("\n-- job_status --", flush=True)
psql(
    os.environ["PLATFORM_DB"], "select-stage2c-import-status.sql",
    {"tenant_id": "ta", "request_ids": ",".join(request_ids)}, capture_output=False)

print("\n-- import_stage2c_rows --", flush=True)
psql(
    os.environ["BUSINESS_DB"], "select-stage2c-import-rows.sql",
    {"tenant_id": "ta", "batch_no": BATCH}, capture_output=False)

common_variables = {"tenant_id": "ta", "batch_no": BATCH}
append_check = (psql(
    os.environ["BUSINESS_DB"], "count-stage2c-appended-rows.sql",
    common_variables, tuples=True).stdout or "").strip()
upsert_check = (psql(
    os.environ["BUSINESS_DB"], "select-stage2c-upsert-assertion.sql",
    {"tenant_id": "ta"}, tuples=True).stdout or "").strip()
replace_check = (psql(
    os.environ["BUSINESS_DB"], "select-stage2c-replace-assertion.sql",
    common_variables, tuples=True).stdout or "").strip()
summary = f"{append_check}|{upsert_check}|{replace_check}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "2|1|Stage2c Upsert Updated|INACTIVE|2|0":
    print("❌ Import Stage2c assertion failed, expected 2|1|Stage2c Upsert Updated|INACTIVE|2|0", flush=True)
    sys.exit(1)

print(f"\n==> Stage 2c import matrix PASS: batchNo={BATCH}", flush=True)
PY
