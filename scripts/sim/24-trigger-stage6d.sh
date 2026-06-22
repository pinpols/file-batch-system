#!/usr/bin/env bash
# =========================================================
# 24-trigger-stage6d.sh:Trigger P0 misfire / cron storm / outbox recovery
#
# 覆盖:
#   - 高频 cron fire（当前 wheel 本地参数只要求至少触发 1 次；亚分钟连续 fire 记录为限制）
#   - wheel 模式下 enabled=false/true 暂停恢复
#   - MANUAL_APPROVAL misfire + catch-up approve replay
#   - requestId / Idempotency-Key 重放去重
#   - trigger_outbox_event FAILED 积压恢复并最终 PUBLISHED
#   - API storm 全终态
#
# SQL seed / 状态注入独立放在 docs/test-data,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="trigger-stage6d"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

export STORM_COUNT="${STORM_COUNT:-80}"
export OUTBOX_COUNT="${OUTBOX_COUNT:-12}"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> seed trigger stage6d fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage6c-trigger-fixtures.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage6d-trigger-fixtures.sql >/dev/null
START_TS="$(docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/trigger-stage6d.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
STORM_COUNT = int(os.environ["STORM_COUNT"])
OUTBOX_COUNT = int(os.environ["OUTBOX_COUNT"])
START_TS = os.environ["START_TS"].strip()
API_JOB = "TA_PROCESS_STAGE4_EMPTY_SUCCESS"
CRON_JOB = "TA_TRIGGER_STAGE6C_SCHEDULED"

def psql(sql, tuples=False):
    args = ["docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"), "-d", os.environ.get("PLATFORM_DB", "batch_platform"), "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def psql_file(path, *vars):
    args = ["docker", "exec", "-i", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"), "-d", os.environ.get("PLATFORM_DB", "batch_platform"), "-v", "ON_ERROR_STOP=1"]
    for key, value in vars:
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    with open(path, "rb") as fh:
        return subprocess.run(args, check=True, stdin=fh)

def scalar(sql):
    out = psql(sql, tuples=True)
    if out.returncode != 0:
        print(out.stderr, flush=True)
        raise RuntimeError("psql failed")
    return (out.stdout or "").strip()

def launch(request_id, batch_key):
    body = {
        "tenantId": "ta",
        "jobCode": API_JOB,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": request_id,
        "params": {"batchNo": BATCH, "batchKey": batch_key, "bizDate": BIZ},
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": "ta",
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": request_id,
            "X-Request-Id": request_id,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        if resp.status != 200 or '"SUCCESS"' not in text:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {request_id}")

def wait_int(label, sql, predicate, timeout=180, interval=2):
    deadline = time.time() + timeout
    last = 0
    while time.time() < deadline:
        raw = scalar(sql) or "0"
        last = int(raw)
        if predicate(last):
            print(f"  [{label}] {last}", flush=True)
            return last
        time.sleep(interval)
    raise TimeoutError(f"timeout waiting {label}, last={last}")

print("==> high-frequency cron fire", flush=True)
scheduled_before = wait_int(
    "cron-fire",
    "select count(*) from batch.trigger_request "
    f"where tenant_id='ta' and job_code='{CRON_JOB}' "
    "and trigger_type='SCHEDULED' "
    f"and created_at >= '{START_TS}'",
    lambda v: v >= 1,
    timeout=120,
)

print("==> pause and resume scheduled trigger", flush=True)
psql_file("docs/test-data/sim-stage6d-trigger-pause.sql", ("job_code", CRON_JOB))
paused_count = int(scalar(
    "select count(*) from batch.trigger_request "
    f"where tenant_id='ta' and job_code='{CRON_JOB}' "
    "and trigger_type='SCHEDULED' "
    f"and created_at >= '{START_TS}'"
) or "0")
time.sleep(6)
paused_after = int(scalar(
    "select count(*) from batch.trigger_request "
    f"where tenant_id='ta' and job_code='{CRON_JOB}' "
    "and trigger_type='SCHEDULED' "
    f"and created_at >= '{START_TS}'"
) or "0")
print(f"  [paused-stable] {paused_count}->{paused_after}", flush=True)
if paused_after != paused_count:
    raise RuntimeError("scheduled trigger fired while paused")

psql_file("docs/test-data/sim-stage6d-trigger-resume.sql", ("job_code", CRON_JOB))
resumed_count = wait_int(
    "resume-fire",
    "select count(*) from batch.trigger_request "
    f"where tenant_id='ta' and job_code='{CRON_JOB}' "
    "and trigger_type='SCHEDULED' "
    f"and created_at >= '{START_TS}'",
    lambda v: v >= paused_count + 1,
    timeout=180,
)

print("==> misfire pending + catch-up replay", flush=True)
misfire_count = wait_int(
    "misfire-pending",
    "select count(*) from batch.trigger_misfire_pending "
    "where tenant_id='ta' and job_code='TA_TRIGGER_STAGE6C_MISFIRE' "
    f"and status='PENDING' and created_at >= '{START_TS}'",
    lambda v: v >= 1,
    timeout=90,
)
pending_link = scalar(
    "select id || '|' || coalesce(catch_up_request_id::text,'') "
    "from batch.trigger_misfire_pending "
    "where tenant_id='ta' and job_code='TA_TRIGGER_STAGE6C_MISFIRE' "
    f"and status='PENDING' and created_at >= '{START_TS}' "
    "order by id desc limit 1"
)
pending_id, linked_request_id = pending_link.split("|", 1)
if not linked_request_id:
    raise RuntimeError(f"misfire pending not linked to catch-up request: pending={pending_id}")
req = urllib.request.Request(
    f"{BASE}/api/triggers/catch-up/approve",
    data=json.dumps({"tenantId": "ta", "pendingId": int(pending_id), "reason": "sim-stage6d"}).encode(),
    headers={
        "Content-Type": "application/json",
        "X-Tenant-Id": "ta",
        "X-Internal-Secret": SECRET,
        "Idempotency-Key": linked_request_id + "-approve",
        "X-Request-Id": linked_request_id + "-approve",
    },
)
with urllib.request.urlopen(req, timeout=30) as resp:
    text = resp.read().decode()
    if resp.status != 200 or '"SUCCESS"' not in text:
        print(text[:500], flush=True)
        raise RuntimeError("catch-up approve failed")

deadline = time.time() + 120
replay_status = ""
while time.time() < deadline:
    replay_status = scalar(
        "select request_status || '|' || coalesce(related_job_instance_id::text,'') "
        f"from batch.trigger_request where tenant_id='ta' and id={linked_request_id}"
    )
    if replay_status.startswith("LAUNCHED|") and replay_status != "LAUNCHED|":
        break
    time.sleep(2)
print(f"  [replay] pending={pending_id} request={linked_request_id} status={replay_status}", flush=True)
if not replay_status.startswith("LAUNCHED|") or replay_status == "LAUNCHED|":
    raise RuntimeError("catch-up replay did not launch")

print("==> requestId dedup replay", flush=True)
dedup_id = BATCH + "-dedup"
launch(dedup_id, BATCH + "-dedup")
launch(dedup_id, BATCH + "-dedup")
dedup_summary = wait_int(
    "dedup-instance",
    "select count(distinct related_job_instance_id) "
    f"from batch.trigger_request where tenant_id='ta' and request_id='{dedup_id}'",
    lambda v: v == 1,
    timeout=90,
)
dedup_rows = int(scalar(
    "select count(*) from batch.trigger_request "
    f"where tenant_id='ta' and request_id='{dedup_id}'"
) or "0")
if dedup_rows != 1 or dedup_summary != 1:
    raise RuntimeError(f"dedup failed: rows={dedup_rows}, instances={dedup_summary}")

print(f"==> trigger outbox retry injection {OUTBOX_COUNT}", flush=True)
for i in range(OUTBOX_COUNT):
    launch(f"{BATCH}-outbox-{i:03d}", f"{BATCH}-outbox-{i:03d}")
wait_int(
    "outbox-created",
    "select count(*) from batch.trigger_outbox_event "
    f"where tenant_id='ta' and request_id like '{BATCH}-outbox-%'",
    lambda v: v >= OUTBOX_COUNT,
    timeout=90,
)
psql_file("docs/test-data/sim-stage6d-trigger-outbox-retry.sql", ("request_prefix", BATCH + "-outbox-"))
outbox_published = wait_int(
    "outbox-published",
    "select count(*) from batch.trigger_outbox_event "
    f"where tenant_id='ta' and request_id like '{BATCH}-outbox-%' "
    "and publish_status='PUBLISHED'",
    lambda v: v >= OUTBOX_COUNT,
    timeout=180,
)
outbox_instances = int(scalar(
    "select count(distinct related_job_instance_id) from batch.trigger_request "
    f"where tenant_id='ta' and request_id like '{BATCH}-outbox-%'"
) or "0")
if outbox_instances != OUTBOX_COUNT:
    raise RuntimeError(f"outbox retry duplicated/lost instances: {outbox_instances}/{OUTBOX_COUNT}")

print(f"==> API storm {STORM_COUNT}", flush=True)
for i in range(STORM_COUNT):
    launch(f"{BATCH}-storm-{i:03d}", f"{BATCH}-storm-{i:03d}")
terminal_count = wait_int(
    "storm-terminal",
    "select count(*) from batch.trigger_request tr "
    "join batch.job_instance ji on ji.id=tr.related_job_instance_id "
    f"where tr.tenant_id='ta' and tr.request_id like '{BATCH}-storm-%' "
    "and ji.instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED')",
    lambda v: v >= STORM_COUNT,
    timeout=240,
)

pending_outbox = int(scalar(
    "select count(*) from batch.trigger_outbox_event "
    f"where tenant_id='ta' and request_id like '{BATCH}%' "
    "and publish_status in ('NEW','FAILED','PUBLISHING')"
) or "0")
non_terminal = int(scalar(
    "select count(*) from batch.trigger_request tr "
    "join batch.job_instance ji on ji.id=tr.related_job_instance_id "
    f"where tr.tenant_id='ta' and tr.request_id like '{BATCH}%' "
    "and ji.instance_status not in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED')"
) or "0")

print("\n-- trigger_stage6d_status --", flush=True)
subprocess.run([
    "docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"),
    "-d", os.environ.get("PLATFORM_DB", "batch_platform"), "-P", "pager=off", "-c",
    "select trigger_type, request_status, count(*) "
    "from batch.trigger_request "
    f"where tenant_id='ta' and request_id like '{BATCH}%' "
    "group by trigger_type, request_status order by trigger_type, request_status"
], check=False)
subprocess.run([
    "docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"),
    "-d", os.environ.get("PLATFORM_DB", "batch_platform"), "-P", "pager=off", "-c",
    "select publish_status, count(*) "
    "from batch.trigger_outbox_event "
    f"where tenant_id='ta' and request_id like '{BATCH}%' "
    "group by publish_status order by publish_status"
], check=False)

summary = (
    f"cron_before={scheduled_before}|pause={paused_count}->{paused_after}|resume={resumed_count}|"
    f"misfire={misfire_count}|replay={replay_status}|dedup={dedup_rows}/{dedup_summary}|"
    f"outbox={outbox_published}/{OUTBOX_COUNT}:instances={outbox_instances}|"
    f"storm_terminal={terminal_count}/{STORM_COUNT}|pending_outbox={pending_outbox}|non_terminal={non_terminal}"
)
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if (
    scheduled_before < 1
    or paused_after != paused_count
    or resumed_count < paused_count + 1
    or misfire_count < 1
    or not replay_status.startswith("LAUNCHED|")
    or dedup_rows != 1
    or dedup_summary != 1
    or outbox_published < OUTBOX_COUNT
    or outbox_instances != OUTBOX_COUNT
    or terminal_count < STORM_COUNT
    or pending_outbox != 0
    or non_terminal != 0
):
    print("❌ Trigger Stage6d assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 6d trigger P0 scenario PASS: batchNo={BATCH}", flush=True)
PY
