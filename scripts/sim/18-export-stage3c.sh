#!/usr/bin/env bash
# =========================================================
# 18-export-stage3c.sh:Export 8 分片 + 多租户 + 幂等重放验证
#
# 覆盖:
#   - STATIC 8 分片小规模导出
#   - 同 requestId 重放不重复创建 job_instance
#   - ta/tb/tc 三租户并发导出均 SUCCESS 且登记 file_record
#
# SQL seed 独立放在 docs/test-data,脚本只负责编排、API 触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="export-stage3c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap + stage3b fixture + stage3c source"
pg_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
pg_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage3b-export-fixtures.sql >/dev/null
pg_business \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" \
  -f /dev/stdin < docs/test-data/sim-stage3c-export-source.sql >/dev/null

START_TS="$(pg_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/export-stage3c.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

# psql 命令前缀:platform / business 双容器路由(env-common.sh 已 export,Citus 下被 env-citus.sh 覆盖)
PG_PLAT = ["docker", "exec", os.environ.get("PG_PLATFORM_CONTAINER", "batch-postgres-primary"),
           "psql", "-U", os.environ.get("PG_PLATFORM_USER", "batch_user"),
           "-d", os.environ.get("PG_PLATFORM_DB", "batch_platform")]
PG_BIZ = ["docker", "exec", os.environ.get("PG_BUSINESS_CONTAINER", "batch-postgres-primary"),
          "psql", "-U", os.environ.get("PG_BUSINESS_USER", "batch_user"),
          "-d", os.environ.get("PG_BUSINESS_DB", "batch_business")]

def psql(sql, tuples=False):
    args = PG_PLAT + ["-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def launch(tenant, job, rid, params):
    body = {
        "tenantId": tenant,
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": params,
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": tenant,
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {tenant}:{job:26s} {rid} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {tenant}:{job}")

rid_ta = f"sim-stage3c-ta8-{int(time.time()*1000)%100000000}"
rid_tb = f"sim-stage3c-tb-{int(time.time()*1000)%100000000}"
rid_tc = f"sim-stage3c-tc-{int(time.time()*1000)%100000000}"

print("==> launch ta 8-shard twice with same requestId", flush=True)
ta_params = {
    "templateCode": "TA_EXPORT_REPORT_JSON_KEYSET_TPL",
    "batchNo": BATCH + "-ta8",
    "bizType": "TA_EXPORT_REPORT_JSON_KEYSET",
    "partitionCount": 8,
}
launch("ta", "TA_EXPORT_REPORT_STATIC", rid_ta, ta_params)
launch("ta", "TA_EXPORT_REPORT_STATIC", rid_ta, ta_params)

print("==> launch tb/tc multi-tenant", flush=True)
launch("tb", "TB_EXPORT_STATEMENT", rid_tb, {
    "templateCode": "TB_EXPORT_STATEMENT_TPL",
    "batchNo": BATCH + "-tb",
    "bizType": "STATEMENT",
})
launch("tc", "TC_EXPORT_RISK_ALERT", rid_tc, {
    "templateCode": "TC_EXPORT_RISK_ALERT_TPL",
    "batchNo": BATCH + "-tc",
    "bizType": "RISK",
})

deadline = time.time() + 240
while time.time() < deadline:
    out = psql(
        "select count(*) from batch.trigger_request tr "
        "join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
        f"where tr.request_id in ('{rid_ta}','{rid_tb}','{rid_tc}') "
        "and i.instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')",
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= 3:
        break
    time.sleep(3)

print("\n-- job_status --", flush=True)
subprocess.run(PG_PLAT + [
    "-P", "pager=off", "-c",
    "select tr.tenant_id,tr.request_id,i.id,i.job_code,i.instance_status,i.expected_partition_count "
    "from batch.trigger_request tr join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
    f"where tr.request_id in ('{rid_ta}','{rid_tb}','{rid_tc}') order by tr.tenant_id,i.id"
], check=False)

print("\n-- ta_partition_status --", flush=True)
ta_instance = (psql(
    "select i.id from batch.trigger_request tr join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
    f"where tr.tenant_id='ta' and tr.request_id='{rid_ta}' order by tr.created_at desc limit 1",
    tuples=True,
).stdout or "").strip()
subprocess.run(PG_PLAT + [
    "-P", "pager=off", "-c",
    "select p.partition_no,p.partition_status,t.task_status,t.error_code "
    "from batch.job_partition p left join batch.job_task t on t.job_partition_id=p.id and t.tenant_id=p.tenant_id "
    f"where p.job_instance_id={ta_instance} order by p.partition_no"
], check=False)

print("\n-- file_records --", flush=True)
subprocess.run(PG_PLAT + [
    "-P", "pager=off", "-c",
    "select tenant_id,source_ref,file_format_type,file_status,count(*) as files,"
    "coalesce(sum((metadata_json->>'recordCount')::int),0) as rows "
    "from batch.file_record "
    f"where source_ref in ('{BATCH}-ta8','{BATCH}-tb','{BATCH}-tc') and source_type='GENERATED' "
    "group by tenant_id,source_ref,file_format_type,file_status order by tenant_id,source_ref,file_format_type"
], check=False)

dedup = (psql(
    "select count(*) || '|' || count(distinct related_job_instance_id) "
    f"from batch.trigger_request where tenant_id='ta' and request_id='{rid_ta}'",
    tuples=True,
).stdout or "").strip()
ta_check = (psql(
    "with tasks as ("
    "select count(*) filter (where t.task_status='SUCCESS') as success_tasks "
    "from batch.job_task t join batch.job_partition p on p.id=t.job_partition_id and p.tenant_id=t.tenant_id "
    f"where p.job_instance_id={ta_instance}"
    "), files as ("
    "select count(*) as file_count, "
    "count(*) filter (where file_name ~ '_p[1-8]of8\\.json$') as tagged_files, "
    "coalesce(sum((metadata_json->>'recordCount')::int),0) as exported_rows "
    "from batch.file_record "
    f"where tenant_id='ta' and source_ref='{BATCH}-ta8' and source_type='GENERATED'"
    ") select success_tasks || '|' || file_count || '|' || tagged_files || '|' || exported_rows from tasks cross join files",
    tuples=True,
).stdout or "").strip()
tenant_check = (psql(
    "select count(*) from ("
    "select tenant_id,source_ref from batch.file_record "
    f"where source_ref in ('{BATCH}-ta8','{BATCH}-tb','{BATCH}-tc') "
    "and source_type='GENERATED' and file_status='GENERATED' "
    "group by tenant_id,source_ref"
    ") s",
    tuples=True,
).stdout or "").strip()
status_check = (psql(
    "select count(*) from batch.trigger_request tr join batch.job_instance i on i.id=tr.related_job_instance_id and tr.tenant_id=i.tenant_id "
    f"where tr.request_id in ('{rid_ta}','{rid_tb}','{rid_tc}') and i.instance_status='SUCCESS'",
    tuples=True,
).stdout or "").strip()
summary = f"{dedup}|{ta_check}|{tenant_check}|{status_check}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "1|1|8|8|8|80|3|3":
    print("❌ Export Stage3c assertion failed, expected 1|1|8|8|8|80|3|3", flush=True)
    sys.exit(1)

print(f"\n==> Stage 3c export scenario PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
