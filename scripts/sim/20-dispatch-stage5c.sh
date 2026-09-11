#!/usr/bin/env bash
# =========================================================
# 20-dispatch-stage5c.sh:Dispatch LOCAL / NAS stub / SFTP manifest 验证
#
# 覆盖:
#   - LOCAL 渠道写 outbox 信封
#   - NAS 渠道在 local profile 下写 stub 信封并标记 transportStub=true
#   - SFTP 渠道真上传文件 + sidecar manifest(.chk)
#
# SQL seed 独立放在 docs/test-data,脚本只负责编排、造实际文件、触发、轮询、断言。
# SFTP 在本地需要 dispatch worker 带 BATCH_SECURITY_SSRF_GUARD_ALLOW_PRIVATE=true
# 启动,否则 DNS guard 会拒绝 127.0.0.1 / 私网地址。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="dispatch-stage5c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

read -r SFTP_HOST SFTP_PORT <<< "$(sim_sftp_endpoint)"
export SFTP_HOST SFTP_PORT
export SOURCE_DIR="${SOURCE_DIR:-/tmp/batch/stage5c-source}"
export STORAGE_PATH="$SOURCE_DIR/$BATCH_NO.json"
mkdir -p "$REPORT_DIR" "$SOURCE_DIR" /tmp/batch/stage5c-local /tmp/batch/stage5c-nas

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

cat > "$STORAGE_PATH" <<JSON
{"batchNo":"$BATCH_NO","scenario":"dispatch-stage5c","bizDate":"$BIZ_DATE"}
JSON
rm -f /tmp/batch/stage5c-local/tb_stage5c_local-"$BATCH_NO"*.json
rm -f /tmp/batch/stage5c-nas/tb_stage5c_nas-"$BATCH_NO"*.json
docker exec sftp sh -lc 'rm -f /home/tb/outbound/stage5c-dispatch.json /home/tb/outbound/stage5c-dispatch.json.chk' >/dev/null 2>&1 || true

echo "==> seed dispatch stage5c fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v sftp_host="$SFTP_HOST" -v sftp_port="$SFTP_PORT" \
  -f /dev/stdin < docs/test-data/sim-stage5c-dispatch-channels-platform.sql >/dev/null
FILE_ID="$(docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" -v storage_path="$STORAGE_PATH" \
  -t -A -f /dev/stdin < docs/test-data/sim-stage5c-dispatch-file.sql | tail -1)"
export FILE_ID
START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/dispatch-stage5c.log"
import glob, json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
FILE_ID = os.environ["FILE_ID"].strip()
START_TS = os.environ["START_TS"].strip()
JOB = "TB_DISPATCH_STAGE5C_CHANNELS"
CHANNELS = ["tb_stage5c_local", "tb_stage5c_nas", "tb_stage5c_sftp"]
request_ids = {}
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

def psql(sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", os.environ["POSTGRES_USER"],
        "-d", os.environ["PLATFORM_DB"], "-P", "pager=off",
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

def launch(channel):
    rid = f"sim-stage5c-{channel}-{int(time.time()*1000)%100000000}"
    request_ids[channel] = rid
    body = {
        "tenantId": "tb",
        "jobCode": JOB,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "fileId": FILE_ID,
            "channelCode": channel,
            "batchNo": BATCH,
            "externalRequestId": f"{BATCH}-{channel}",
            "receiptCode": f"R-{BATCH}-{channel}",
        },
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": "tb",
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {channel:18s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {channel}")

for channel in CHANNELS:
    launch(channel)

deadline = time.time() + 180
while time.time() < deadline:
    out = psql(
        "count-terminal-request-instances.sql",
        {"tenant_id": "tb", "request_ids": ",".join(request_ids.values())},
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done == len(CHANNELS):
        break
    time.sleep(3)

print("\n-- instance_status --", flush=True)
request_variables = {
    "tenant_id": "tb",
    "request_ids": ",".join(request_ids.values()),
}
psql("select-request-task-status-details.sql", request_variables, capture_output=False)

print("\n-- dispatch_records --", flush=True)
dispatch_variables = {"tenant_id": "tb", "file_id": FILE_ID}
psql("select-dispatch-record-details.sql", dispatch_variables, capture_output=False)

status_out = psql(
    "select-dispatch-status-summary.sql",
    dispatch_variables,
    tuples=True,
)
status_summary = ",".join([line for line in (status_out.stdout or "").splitlines() if line.strip()])
success_out = psql(
    "count-successful-request-instances.sql",
    request_variables,
    tuples=True,
)
success_count = int((success_out.stdout or "0").strip() or "0")

local_files = glob.glob(f"/tmp/batch/stage5c-local/tb_stage5c_local-{BATCH}-tb_stage5c_local*.json")
nas_files = glob.glob(f"/tmp/batch/stage5c-nas/tb_stage5c_nas-{BATCH}-tb_stage5c_nas*.json")
nas_stub = False
if nas_files:
    with open(nas_files[0], "r", encoding="utf-8") as fh:
        nas_stub = bool(json.load(fh).get("transportStub"))

sftp_ls = subprocess.run(
    ["docker", "exec", "sftp", "sh", "-lc", "test -f /home/tb/outbound/stage5c-dispatch.json && test -f /home/tb/outbound/stage5c-dispatch.json.chk"],
    check=False,
    capture_output=True,
    text=True,
)
sftp_ok = sftp_ls.returncode == 0

summary = f"instances={success_count}/{len(CHANNELS)}|records={status_summary}|local={len(local_files)}|nas={len(nas_files)}:{nas_stub}|sftp={sftp_ok}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
expected = {
    "tb_stage5c_local:ACKED:SUCCESS",
    "tb_stage5c_nas:ACKED:SUCCESS",
    "tb_stage5c_sftp:ACKED:SUCCESS",
}
actual = set(status_summary.split(",")) if status_summary else set()
if success_count != len(CHANNELS) or actual != expected or not local_files or not nas_stub or not sftp_ok:
    print("❌ Dispatch Stage5c assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 5c dispatch scenario PASS: batchNo={BATCH} fileId={FILE_ID} startTs={START_TS}", flush=True)
PY
