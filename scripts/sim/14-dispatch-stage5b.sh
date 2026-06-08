#!/usr/bin/env bash
# =========================================================
# 14-dispatch-stage5b.sh:Dispatch no-retry 失败补偿分支验证
#
# 覆盖:
#   - API channel 返回 500
#   - retry_policy=NONE 下 job/partition/task 直接 FAILED
#   - dispatch_record 进入 COMPENSATED
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-dispatch。
# SQL seed 独立放在 docs/test-data,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="dispatch-stage5b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> preflight dispatch stage5 job/channel"
if [[ "$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select count(*) from batch.job_definition where tenant_id='tb' and job_code='TB_DISPATCH_STAGE5_FAIL_ONCE' and enabled=true")" != "1" ]]; then
  echo "❌ missing TB_DISPATCH_STAGE5_FAIL_ONCE fixture" >&2
  exit 1
fi
if [[ "$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select count(*) from batch.file_channel_config where tenant_id='tb' and channel_code='tb_api_fail' and enabled=true")" != "1" ]]; then
  echo "❌ missing tb_api_fail channel fixture" >&2
  exit 1
fi

echo "==> seed dispatch file_record"
FILE_ID="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -t -A -f /dev/stdin < docs/test-data/sim-stage5-dispatch-file.sql | tail -1)"
export FILE_ID
START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/dispatch-stage5b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
FILE_ID = os.environ["FILE_ID"].strip()

rid = f"sim-stage5b-dispatch-{int(time.time()*1000)%100000000}"
body = {
    "tenantId": "tb",
    "jobCode": "TB_DISPATCH_STAGE5_FAIL_ONCE",
    "triggerType": "API",
    "bizDate": BIZ,
    "requestId": rid,
    "params": {
        "fileId": FILE_ID,
        "channelCode": "tb_api_fail",
        "batchNo": BATCH,
        "externalRequestId": BATCH,
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
    print(f"  [launch] fileId={FILE_ID} {'✓' if ok else '✗'}", flush=True)
    if not ok:
        print(text[:500], flush=True)
        sys.exit(1)

def psql(sql, tuples=False):
    args = ["docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user", "-d", "batch_platform", "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

deadline = time.time() + 150
instance_id = None
while time.time() < deadline:
    out = psql(
        "select i.id || '|' || coalesce(i.instance_status,'') "
        "from batch.trigger_request tr "
        "join batch.job_instance i on i.id=tr.related_job_instance_id "
        f"where tr.tenant_id='tb' and tr.request_id='{rid}' order by tr.created_at desc limit 1",
        tuples=True,
    )
    value = (out.stdout or "").strip()
    if value:
        iid, status = value.split("|", 1)
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            instance_id = iid
            print(f"  [result] instance={iid} status={status}", flush=True)
            if status != "FAILED":
                sys.exit(1)
            break
    time.sleep(3)
if not instance_id:
    raise TimeoutError("timeout waiting dispatch stage5b")

print("\n-- dispatch_status --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select i.instance_status,p.partition_status,t.task_status,t.error_code,"
    "d.dispatch_status,d.error_code as dispatch_error "
    "from batch.job_instance i "
    "left join batch.job_partition p on p.job_instance_id=i.id "
    "left join batch.job_task t on t.job_partition_id=p.id "
    "left join batch.file_dispatch_record d on d.file_id=" + FILE_ID + " and d.channel_code='tb_api_fail' "
    "where i.id=" + instance_id
], check=False)

out = psql(
    "select i.instance_status || '|' || p.partition_status || '|' || t.task_status || '|' || "
    "coalesce(d.dispatch_status,'') "
    "from batch.job_instance i "
    "join batch.job_partition p on p.job_instance_id=i.id "
    "join batch.job_task t on t.job_partition_id=p.id "
    "left join batch.file_dispatch_record d on d.file_id=" + FILE_ID + " and d.channel_code='tb_api_fail' "
    "where i.id=" + instance_id,
    tuples=True,
)
summary = (out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "FAILED|FAILED|FAILED|COMPENSATED":
    print("❌ Dispatch Stage5b assertion failed, expected FAILED|FAILED|FAILED|COMPENSATED", flush=True)
    sys.exit(1)

print(f"\n==> Stage 5b dispatch scenario PASS: batchNo={BATCH} fileId={FILE_ID} instance={instance_id}", flush=True)
PY
