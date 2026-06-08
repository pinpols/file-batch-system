#!/usr/bin/env bash
# =========================================================
# 10-process-stage4.sh:Process 业务分支系统级验证
#
# 覆盖:
#   - JSONB staging 成功态
#   - DIRECT fast path 成功态
#   - VALIDATE 失败态
#   - empty result SUCCESS 策略
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-process。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-process-stage4-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-process-stage4-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "需要 python3" >&2; exit 1; }

echo "==> seed process business tables"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_business \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" -f /dev/stdin >/dev/null <<'SQL'
CREATE TABLE IF NOT EXISTS biz.process_stage4_source (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    scenario TEXT NOT NULL,
    account_id TEXT NOT NULL,
    biz_date DATE NOT NULL,
    event_id BIGINT NOT NULL,
    amount NUMERIC(18,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS biz.process_stage4_target (
    tenant_id TEXT NOT NULL,
    scenario TEXT NOT NULL,
    account_id TEXT NOT NULL,
    biz_date DATE NOT NULL,
    total_amount NUMERIC(18,2) NOT NULL,
    event_count BIGINT NOT NULL,
    high_water_mark BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, scenario, account_id, biz_date)
);

DELETE FROM biz.process_stage4_source WHERE tenant_id = 'ta';
DELETE FROM biz.process_stage4_target WHERE tenant_id = 'ta';

INSERT INTO biz.process_stage4_source (
    tenant_id, scenario, account_id, biz_date, event_id, amount
)
SELECT *
FROM (VALUES
    ('ta', 'JSONB',         'A001', :'biz_date'::date, 1, 100.00::numeric),
    ('ta', 'JSONB',         'A001', :'biz_date'::date, 2,  50.00::numeric),
    ('ta', 'JSONB',         'A002', :'biz_date'::date, 3,  25.50::numeric),
    ('ta', 'DIRECT',        'D001', :'biz_date'::date, 4, 200.00::numeric),
    ('ta', 'DIRECT',        'D002', :'biz_date'::date, 5, 300.00::numeric),
    ('ta', 'VALIDATE_FAIL', 'V001', :'biz_date'::date, 6,  10.00::numeric)
) AS v(tenant_id, scenario, account_id, biz_date, event_id, amount);
SQL

echo "==> seed process platform jobs"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" -f /dev/stdin >/dev/null <<'SQL'
BEGIN;

DELETE FROM batch.pipeline_step_definition
WHERE pipeline_definition_id IN (
    SELECT id FROM batch.pipeline_definition
    WHERE tenant_id = 'ta'
      AND job_code IN (
        'TA_PROCESS_STAGE4_JSONB',
        'TA_PROCESS_STAGE4_DIRECT',
        'TA_PROCESS_STAGE4_VALIDATE_FAIL',
        'TA_PROCESS_STAGE4_EMPTY_SUCCESS'
      )
);

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT 'ta', m.job_code, m.job_name, 'PROCESS', 'PROCESS_STAGE4',
       'MANUAL', null, 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
       'default-calendar', 'always_open', 'API', false, 'NONE',
       'NONE', 0, 600, null, '{}'::jsonb, '{}'::jsonb, 1,
       true, m.description, 'sim-e2e', 'sim-e2e', 'FULL',
       'INHERIT', null
FROM (VALUES
    ('TA_PROCESS_STAGE4_JSONB', 'Stage4 JSONB process', 'Stage 4 JSONB staging success'),
    ('TA_PROCESS_STAGE4_DIRECT', 'Stage4 DIRECT process', 'Stage 4 DIRECT success'),
    ('TA_PROCESS_STAGE4_VALIDATE_FAIL', 'Stage4 validation failure process', 'Stage 4 validation failure'),
    ('TA_PROCESS_STAGE4_EMPTY_SUCCESS', 'Stage4 empty success process', 'Stage 4 empty result success')
) AS m(job_code, job_name, description)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    trigger_mode = EXCLUDED.trigger_mode,
    queue_code = EXCLUDED.queue_code,
    worker_group = EXCLUDED.worker_group,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    timeout_seconds = EXCLUDED.timeout_seconds,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP,
    execution_mode = 'FULL';

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description
)
SELECT 'ta', m.job_code, m.pipeline_name, 'PROCESS', 'PROCESS_STAGE4',
       'PROCESS', 1, true, m.description
FROM (VALUES
    ('TA_PROCESS_STAGE4_JSONB', 'Stage4 JSONB process pipeline', 'Stage 4 JSONB staging success'),
    ('TA_PROCESS_STAGE4_DIRECT', 'Stage4 DIRECT process pipeline', 'Stage 4 DIRECT success'),
    ('TA_PROCESS_STAGE4_VALIDATE_FAIL', 'Stage4 validation failure pipeline', 'Stage 4 validation failure'),
    ('TA_PROCESS_STAGE4_EMPTY_SUCCESS', 'Stage4 empty success pipeline', 'Stage 4 empty result success')
) AS m(job_code, pipeline_name, description)
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    pipeline_type = EXCLUDED.pipeline_type,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH pd AS (
    SELECT id, job_code
    FROM batch.pipeline_definition
    WHERE tenant_id = 'ta'
      AND job_code IN (
        'TA_PROCESS_STAGE4_JSONB',
        'TA_PROCESS_STAGE4_DIRECT',
        'TA_PROCESS_STAGE4_VALIDATE_FAIL',
        'TA_PROCESS_STAGE4_EMPTY_SUCCESS'
      )
), specs AS (
    SELECT *
    FROM (VALUES
      (
        'TA_PROCESS_STAGE4_JSONB',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''JSONB'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'validations', jsonb_build_array(
              jsonb_build_object(
                'name', 'jsonb_two_accounts',
                'checkSql', 'select count(*) = 2 as pass, ''expected 2 staged accounts'' as message from batch.process_staging where tenant_id = :tenantId and target_schema = :targetSchema and target_table = :targetTable and batch_key = :batchKey')),
            'emptyResultPolicy', 'FAIL',
            'maxStagedRows', 10))
      ),
      (
        'TA_PROCESS_STAGE4_DIRECT',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''DIRECT'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'stagingMode', 'DIRECT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'emptyResultPolicy', 'SUCCESS',
            'watermarkColumn', 'high_water_mark'))
      ),
      (
        'TA_PROCESS_STAGE4_VALIDATE_FAIL',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''VALIDATE_FAIL'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'validations', jsonb_build_array(
              jsonb_build_object(
                'name', 'validate_fail_two_accounts',
                'checkSql', 'select count(*) = 2 as pass, ''expected validation failure: only 1 staged account'' as message from batch.process_staging where tenant_id = :tenantId and target_schema = :targetSchema and target_table = :targetTable and batch_key = :batchKey')),
            'emptyResultPolicy', 'FAIL',
            'maxStagedRows', 10))
      ),
      (
        'TA_PROCESS_STAGE4_EMPTY_SUCCESS',
        jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
            'SELECT tenant_id, scenario, account_id, biz_date, sum(amount) AS total_amount, count(*) AS event_count, max(event_id) AS high_water_mark FROM biz.process_stage4_source WHERE tenant_id = :tenantId AND biz_date = cast(:bizDate as date) AND scenario = ''EMPTY'' GROUP BY tenant_id, scenario, account_id, biz_date LIMIT 10',
            'targetSchema', 'biz',
            'targetTable', 'process_stage4_target',
            'writeMode', 'UPSERT',
            'columns', jsonb_build_array(
              jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
              jsonb_build_object('source', 'scenario', 'target', 'scenario'),
              jsonb_build_object('source', 'account_id', 'target', 'account_id'),
              jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
              jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
              jsonb_build_object('source', 'event_count', 'target', 'event_count'),
              jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')),
            'conflictColumns', jsonb_build_array('tenant_id', 'scenario', 'account_id', 'biz_date'),
            'emptyResultPolicy', 'SUCCESS',
            'maxStagedRows', 10))
      )
    ) AS s(job_code, step_params)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
    enabled
)
SELECT pd.id, 'PROCESS_PREPARE', 'Prepare', 'PREPARE', 1,
       'PROCESS_PREPARE', '{}'::jsonb, 60, 'NONE', 0, true
FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_COMPUTE', 'Compute', 'COMPUTE', 2,
       'sqlTransformCompute', specs.step_params, 180, 'NONE', 0, true
FROM pd JOIN specs USING (job_code)
UNION ALL
SELECT pd.id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
       'PROCESS_VALIDATE', '{}'::jsonb, 60, 'NONE', 0, true
FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
       'PROCESS_COMMIT', '{}'::jsonb, 180, 'NONE', 0, true
FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
       'PROCESS_FEEDBACK', '{}'::jsonb, 60, 'NONE', 0, true
FROM pd;

COMMIT;
SQL

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4.log"
import json
import os
import subprocess
import time
import urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

SCENARIOS = [
    ("jsonb_ok", "TA_PROCESS_STAGE4_JSONB", BATCH + "-jsonb", "SUCCESS"),
    ("direct_ok", "TA_PROCESS_STAGE4_DIRECT", BATCH + "-direct", "SUCCESS"),
    ("validate_fail", "TA_PROCESS_STAGE4_VALIDATE_FAIL", BATCH + "-validate-fail", "FAILED"),
    ("empty_success", "TA_PROCESS_STAGE4_EMPTY_SUCCESS", BATCH + "-empty", "SUCCESS"),
]

def launch(label, job_code, batch_key):
    rid = f"sim-stage4-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": job_code,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "batchNo": BATCH,
            "batchKey": batch_key,
            "bizDate": BIZ,
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
        print(f"  [launch] {label:14s} {job_code:34s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)

for label, job_code, batch_key, expected in SCENARIOS:
    launch(label, job_code, batch_key)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 180
job_codes = ",".join("'" + s[1] + "'" for s in SCENARIOS)
while time.time() < deadline:
    sql = (
        "select count(*) from batch.job_instance "
        f"where tenant_id='ta' and job_code in ({job_codes}) and created_at >= '{START_TS}' "
        "and instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')"
    )
    out = subprocess.run([
        "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
        "-d", "batch_platform", "-t", "-A", "-c", sql
    ], capture_output=True, text=True)
    done = int((out.stdout or "0").strip() or "0")
    if done >= len(SCENARIOS):
        break
    time.sleep(3)

print("\n-- job_status --", flush=True)
job_sql = (
    "select i.id,i.job_code,i.instance_status,t.task_status,t.error_code,"
    "left(coalesce(t.error_message,''),180) as error_message "
    "from batch.job_instance i left join batch.job_task t on t.job_instance_id=i.id "
    f"where i.tenant_id='ta' and i.job_code in ({job_codes}) and i.created_at >= '{START_TS}' "
    "order by i.created_at,i.id"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", job_sql
], check=False)

print("\n-- target_rows --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    "select scenario, account_id, total_amount, event_count, high_water_mark "
    "from biz.process_stage4_target where tenant_id='ta' order by scenario, account_id"
], check=False)

print("\n-- staging_leftover --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    "select tenant_id,target_table,batch_key,count(*) as rows "
    "from batch.process_staging where tenant_id='ta' and batch_key like '" + BATCH + "%' "
    "group by tenant_id,target_table,batch_key order by batch_key"
], check=False)

print("\n-- expectation_check --", flush=True)
check_sql = (
    "with actual as ("
    "select job_code, instance_status from batch.job_instance "
    f"where tenant_id='ta' and job_code in ({job_codes}) and created_at >= '{START_TS}'"
    "), expected(job_code, expected_status) as (values "
    "('TA_PROCESS_STAGE4_JSONB','SUCCESS'),"
    "('TA_PROCESS_STAGE4_DIRECT','SUCCESS'),"
    "('TA_PROCESS_STAGE4_VALIDATE_FAIL','FAILED'),"
    "('TA_PROCESS_STAGE4_EMPTY_SUCCESS','SUCCESS')) "
    "select e.job_code,e.expected_status,coalesce(a.instance_status,'MISSING') as actual_status,"
    "(coalesce(a.instance_status,'MISSING') = e.expected_status) as ok "
    "from expected e left join actual a using(job_code) order by e.job_code"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", check_sql
], check=False)

fail_sql = "select count(*) from (" + check_sql + ") s where not ok"
out = subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-t", "-A", "-c", fail_sql
], capture_output=True, text=True)
failures = int((out.stdout or "0").strip() or "0")
print(f"\n==> Stage 4 process scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
if failures:
    raise SystemExit(f"Stage 4 process expectation failed: {failures} mismatch(es)")
PY
