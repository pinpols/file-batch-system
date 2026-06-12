#!/usr/bin/env bash
# =========================================================
# 25-import-stage2e-checkpoint-crash.sh
#
# Import checkpoint 真实崩溃续跑 fault-injection profile:
#   1. 以 checkpoint enabled 启动 worker-import
#   2. API 触发 XML import
#   3. 等 LOAD 推进至少 1 个 chunk 后 kill worker-import
#   4. 等同一 instance/partition 进入 FAILED, 调 partition retry
#   5. 重启 worker-import, 验证同一 pipeline_instance 从 checkpoint 续跑并最终 SUCCESS
#
# SQL fixture: docs/test-data/sim-stage2e-import-checkpoint.sql
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="import-stage2e-checkpoint-crash"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

restart_import_with_checkpoint() {
  echo "==> restart worker-import with checkpoint enabled"
  # Citus 拓扑:restart.sh 是单机配置(15432),会让 worker 连错库。检测 env-citus
  # (PG_PLATFORM_CONTAINER=citus-coord)时直接用 citus 连接命令起,checkpoint 打开。
  if [[ "${PG_PLATFORM_CONTAINER:-}" == "citus-coord" ]] && declare -F citus_restart_worker >/dev/null; then
    # Citus:restart.sh 是单机配置(15432),改用 env-citus.sh 的 citus_restart_worker
    # helper(连接配置集中在 env-citus,此处不硬编码)。checkpoint 作为额外 -D 透传。
    citus_restart_worker worker-import -Dbatch.worker.checkpoint.enabled=true
    local s=$SECONDS
    until curl -sf -m2 http://localhost:18083/actuator/health -o /dev/null 2>/dev/null || [ $((SECONDS-s)) -gt 120 ]; do sleep 5; done
  else
    COMPOSE_ENV_FILE=/dev/null \
    BATCH_WORKER_CHECKPOINT_ENABLED=true \
    JAVA_OPTS="${JAVA_OPTS:-} -Dbatch.worker.checkpoint.enabled=true" \
      bash "$ROOT/scripts/local/restart.sh" worker-import >/dev/null
  fi
}

echo "==> apply bootstrap + checkpoint fixture"
pg_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
pg_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2e-import-checkpoint.sql >/dev/null
pg_business \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage2e-import-checkpoint-business.sql >/dev/null

if [[ "${RESTART_IMPORT_WITH_CHECKPOINT:-1}" == "1" ]]; then
  restart_import_with_checkpoint
else
  echo "==> skip restart; expecting existing worker-import has batch.worker.checkpoint.enabled=true"
fi

export EXPECTED_ROWS="${EXPECTED_ROWS:-20000}"
export CHECKPOINT_MIN_MARKER="${CHECKPOINT_MIN_MARKER:-50}"
export WORKER_IMPORT_PORT="${WORKER_IMPORT_PORT:-18083}"

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2e-checkpoint-crash.log"
import json
import os
import subprocess
import sys
import time
import urllib.request

BASE = os.environ["TRIGGER_BASE"]
ORCH = os.environ["ORCH_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
EXPECTED_ROWS = int(os.environ["EXPECTED_ROWS"])
MIN_MARKER = int(os.environ["CHECKPOINT_MIN_MARKER"])
WORKER_PORT = os.environ["WORKER_IMPORT_PORT"]
MINIO_CONTAINER = os.environ.get("MINIO_CONTAINER", "batch-minio")
MINIO_BUCKET = os.environ["BATCH_S3_BUCKET"]
MINIO_ACCESS_KEY = os.environ["BATCH_S3_ACCESS_KEY"]
MINIO_SECRET_KEY = os.environ["BATCH_S3_SECRET_KEY"]

def sh(args, check=False):
    return subprocess.run(args, check=check, capture_output=True, text=True)

# psql 命令前缀:platform / business 双容器路由(env-common.sh 已 export,Citus 下被 env-citus.sh 覆盖)
PG_PLAT = ["docker", "exec", os.environ.get("PG_PLATFORM_CONTAINER", "batch-postgres-primary"),
           "psql", "-U", os.environ.get("PG_PLATFORM_USER", "batch_user")]
PG_BIZ = ["docker", "exec", os.environ.get("PG_BUSINESS_CONTAINER", "batch-postgres-primary"),
          "psql", "-U", os.environ.get("PG_BUSINESS_USER", "batch_user")]
_BIZ_DB = os.environ.get("PG_BUSINESS_DB", "batch_business")

def psql(db, sql, tuples=False):
    base = PG_BIZ if db == _BIZ_DB else PG_PLAT
    args = list(base) + ["-d", db, "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return sh(args)

def xml_payload(rows):
    body = ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<customers>"]
    for i in range(1, rows + 1):
        no = f"S2ECKPT{i:06d}"
        body.append(f"""  <customer>
    <customer_no>{no}</customer_no>
    <customer_name>Stage2e Checkpoint {i}</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>{no}CERT</certificate_no>
    <mobile>138{i % 100000000:08d}</mobile>
    <email>{no.lower()}@x.io</email>
    <status>ACTIVE</status>
  </customer>""")
    body.append("</customers>")
    return "\n".join(body) + "\n"

def upload_object(object_name, data):
    sh([
        "docker", "exec", MINIO_CONTAINER, "mc", "alias", "set", "local",
        "http://localhost:9000", MINIO_ACCESS_KEY, MINIO_SECRET_KEY,
    ], check=True)
    sh(["docker", "exec", MINIO_CONTAINER, "mc", "mb", "-p", f"local/{MINIO_BUCKET}"], check=True)
    proc = subprocess.run(
        ["docker", "exec", "-i", MINIO_CONTAINER, "mc", "pipe", f"local/{MINIO_BUCKET}/{object_name}"],
        input=data,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"failed to upload object: {proc.stderr.strip()}")

def launch():
    rid = f"sim-stage2e-ckpt-{int(time.time()*1000)%100000000}"
    object_name = f"ingress/ta/stage2e-checkpoint/{rid}.xml"
    upload_object(object_name, xml_payload(EXPECTED_ROWS))
    body = {
        "tenantId": "ta",
        "jobCode": "TA_IMPORT_CUSTOMER_XML",
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
            "fileFormatType": "XML",
            "storageType": "S3",
            "storageBucket": MINIO_BUCKET,
            "storagePath": object_name,
            "batchNo": BATCH,
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
    with urllib.request.urlopen(req, timeout=60) as resp:
        text = resp.read().decode()
        if resp.status != 200 or '"SUCCESS"' not in text:
            raise RuntimeError(f"launch failed: {text[:500]}")
    print(f"  [launch] requestId={rid}", flush=True)
    return rid

def state_row(rid):
    sql = (
        "select coalesce(i.id::text,''), coalesce(p.id::text,''), "
        "coalesce(pi.id::text,''), coalesce(pp.position_marker,'0'), "
        "coalesce(pp.processed_count::text,'0'), coalesce(pp.completed::text,'false'), "
        "coalesce(i.instance_status,''), coalesce(p.partition_status,''), "
        "coalesce(tr.request_status,''), coalesce(oe.publish_status,''), "
        "left(coalesce(oe.last_error,''), 180), coalesce(t.task_status,''), "
        "coalesce(p.lease_expire_at::text,'') "
        "from batch.trigger_request tr "
        "left join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
        "left join batch.job_partition p on p.job_instance_id=i.id and p.tenant_id=i.tenant_id "
        "left join batch.job_task t on t.job_partition_id=p.id and t.tenant_id=p.tenant_id "
        "left join batch.pipeline_instance pi on pi.related_job_instance_id=i.id "
        "left join batch.pipeline_progress pp on pp.pipeline_instance_id=pi.id and pp.stage='LOAD' "
        "left join batch.trigger_outbox_event oe on oe.tenant_id=tr.tenant_id "
        " and oe.request_id=tr.request_id "
        f"where tr.tenant_id='ta' and tr.request_id='{rid}' "
        "order by oe.id desc nulls last, p.id desc limit 1"
    )
    out = (psql(os.environ["PG_PLATFORM_DB"], sql, tuples=True).stdout or "").strip()
    if not out:
        return None
    parts = out.split("|")
    return {
        "instance_id": parts[0],
        "partition_id": parts[1],
        "pipeline_id": parts[2],
        "marker": int(parts[3] or "0"),
        "processed": int(parts[4] or "0"),
        "completed": parts[5] == "true",
        "instance_status": parts[6],
        "partition_status": parts[7],
        "request_status": parts[8],
        "outbox_status": parts[9],
        "outbox_error": parts[10],
        "task_status": parts[11],
        "lease_expire_at": parts[12],
        "raw": out,
    }

def wait_for_checkpoint(rid):
    deadline = time.time() + 240
    last = None
    while time.time() < deadline:
        row = state_row(rid)
        if row:
            last = row
            if row["outbox_status"] == "FAILED":
                raise RuntimeError(f"trigger outbox failed before launch: {row['raw']}")
            if row["instance_status"] in ("SUCCESS", "FAILED", "PARTIAL_FAILED"):
                raise RuntimeError(f"task reached terminal before kill: {row['raw']}")
            if row["marker"] >= MIN_MARKER:
                print(f"  [checkpoint-before-kill] {row['raw']}", flush=True)
                return row
        time.sleep(0.2)
    raise TimeoutError(f"checkpoint marker not reached; last={last}")

def kill_worker_import():
    out = sh(["bash", "-lc", f"lsof -tiTCP:{WORKER_PORT} -sTCP:LISTEN | head -1"]).stdout.strip()
    if not out:
        raise RuntimeError("worker-import listener pid not found")
    print(f"  [kill] worker-import pid={out}", flush=True)
    sh(["kill", "-9", out], check=True)

def wait_after_crash_reclaim(rid):
    deadline = time.time() + 420
    last = None
    while time.time() < deadline:
        row = state_row(rid)
        if row:
            last = row
            if row["partition_status"] == "FAILED" or row["instance_status"] == "FAILED":
                print(f"  [after-crash] {row['raw']}", flush=True)
                return row, "FAILED"
            if (
                row["instance_status"] == "RUNNING"
                and row["partition_status"] == "READY"
                and row["task_status"] == "READY"
                and row["lease_expire_at"] == ""
            ):
                print(f"  [after-crash-reclaimed] {row['raw']}", flush=True)
                return row, "READY"
            if row["instance_status"] == "SUCCESS":
                raise RuntimeError(f"unexpected success after killed worker: {row['raw']}")
        time.sleep(3)
    raise TimeoutError(f"partition was not reclaimed after worker kill; last={last}")

def retry_partition(partition_id):
    req = urllib.request.Request(
        f"{ORCH}/internal/instances/partitions/{partition_id}/retry?tenantId=ta",
        data=b"",
        headers={"X-Internal-Secret": SECRET},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        if resp.status != 200:
            raise RuntimeError(f"retry failed: {text[:500]}")
    print(f"  [retry] partitionId={partition_id}", flush=True)

def restart_worker():
    # Citus 拓扑:restart.sh 单机配置(15432)会让 worker 连错库;直接用 citus 连接命令起。
    if os.environ.get("PG_PLATFORM_CONTAINER") == "citus-coord":
        # Citus:复用 env-citus.sh 的 citus_restart_worker helper(连接配置集中,不在此硬编码)
        subprocess.run(
            ["bash", "-lc",
             "source scripts/sim/env-citus.sh >/dev/null 2>&1; "
             "citus_restart_worker worker-import -Dbatch.worker.checkpoint.enabled=true"],
            check=False)
        deadline = time.time() + 120
        while time.time() < deadline:
            try:
                urllib.request.urlopen("http://localhost:18083/actuator/health", timeout=2)
                break
            except Exception:
                time.sleep(5)
    else:
        env = os.environ.copy()
        env["COMPOSE_ENV_FILE"] = "/dev/null"
        env["BATCH_WORKER_CHECKPOINT_ENABLED"] = "true"
        env["JAVA_OPTS"] = (env.get("JAVA_OPTS", "") + " -Dbatch.worker.checkpoint.enabled=true").strip()
        subprocess.run(
            ["bash", "scripts/local/restart.sh", "worker-import"],
            check=True,
            env=env,
            capture_output=True,
            text=True,
        )
    print("  [restart] worker-import", flush=True)

def wait_success(rid):
    deadline = time.time() + 420
    last = None
    while time.time() < deadline:
        row = state_row(rid)
        if row:
            last = row
            if row["instance_status"] == "SUCCESS":
                print(f"  [final] {row['raw']}", flush=True)
                return row
            if row["instance_status"] in ("FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
                raise RuntimeError(f"retry ended non-success: {row['raw']}")
        time.sleep(3)
    raise TimeoutError(f"retry did not succeed; last={last}")

rid = launch()
before = wait_for_checkpoint(rid)
kill_worker_import()
after_crash, crash_mode = wait_after_crash_reclaim(rid)
if crash_mode == "FAILED":
    retry_partition(after_crash["partition_id"])
restart_worker()
final = wait_success(rid)

count = (psql(os.environ["PG_BUSINESS_DB"], (
    "select count(*) from biz.customer_account "
    "where tenant_id='ta' and customer_no like 'S2ECKPT%'"
), tuples=True).stdout or "").strip()

if int(count) != EXPECTED_ROWS:
    raise RuntimeError(f"business row count mismatch: {count} != {EXPECTED_ROWS}")
if before["pipeline_id"] != final["pipeline_id"]:
    raise RuntimeError(f"pipeline instance changed: {before['pipeline_id']} -> {final['pipeline_id']}")
if final["processed"] != EXPECTED_ROWS or not final["completed"]:
    raise RuntimeError(f"checkpoint final mismatch: {final['raw']}")

print("\n-- assertion_summary --", flush=True)
print(
    f"requestId={rid}|instance={final['instance_id']}|partition={final['partition_id']}|"
    f"pipeline={final['pipeline_id']}|markerBeforeKill={before['marker']}|"
    f"processedFinal={final['processed']}|rows={count}|status={final['instance_status']}",
    flush=True,
)
print("\n==> Stage 2e import checkpoint crash-resume PASS", flush=True)
PY
