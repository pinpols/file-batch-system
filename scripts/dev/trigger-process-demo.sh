#!/usr/bin/env bash
# =========================================================
# trigger-process-demo.sh - 真实下发一条 PROCESS 任务,验证
#   sqlTransformCompute plugin + ProcessMetrics 4 个指标实
#   际打点。一次性脚本,跑完后业务库会留一张 demo target 表。
# =========================================================
set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-15432}"
PG_USER="${PG_USER:-batch_user}"
export PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
TRIGGER_URL="${TRIGGER_URL:-http://localhost:18081/api/triggers/launch}"
WORKER_PROCESS_PROM="${WORKER_PROCESS_PROM:-http://localhost:18086/actuator/prometheus}"

JOB_CODE="process_demo_aggregate_job"
PIPELINE_CODE="process_demo_aggregate_pipeline"

echo "==> 1. 业务库 biz.process_demo_source / biz.process_demo_target 建表 + 灌 source 数据"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d batch_business <<'SQL'
DROP TABLE IF EXISTS biz.process_demo_source;
DROP TABLE IF EXISTS biz.process_demo_target;
CREATE TABLE biz.process_demo_source (
  id BIGSERIAL PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  account_no TEXT NOT NULL,
  amount NUMERIC(18,2) NOT NULL,
  biz_date DATE NOT NULL
);
CREATE TABLE biz.process_demo_target (
  account_no TEXT PRIMARY KEY,
  total_amount NUMERIC(18,2) NOT NULL,
  txn_count BIGINT NOT NULL,
  computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO biz.process_demo_source (tenant_id, account_no, amount, biz_date) VALUES
  ('default-tenant','A001',100.00,'2026-04-28'),
  ('default-tenant','A001',250.50,'2026-04-28'),
  ('default-tenant','A002', 80.00,'2026-04-28'),
  ('default-tenant','A002',120.00,'2026-04-28'),
  ('default-tenant','A003',999.99,'2026-04-28');
SQL

echo "==> 2. 平台库 INSERT job_definition + pipeline_definition + 5 step (PREPARE/COMPUTE/VALIDATE/COMMIT/FEEDBACK)"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d batch_platform <<SQL
BEGIN;

-- 清理旧的(允许重跑)
DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id IN
  (SELECT id FROM batch.pipeline_definition WHERE job_code='${JOB_CODE}');
DELETE FROM batch.pipeline_definition WHERE job_code='${JOB_CODE}';
DELETE FROM batch.job_definition WHERE job_code='${JOB_CODE}';

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone,
  priority, queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled,
  shard_strategy, retry_policy, retry_max_count, timeout_seconds, execution_handler,
  param_schema, default_params, version, enabled, description, created_by, updated_by, created_at, updated_at
) VALUES (
  'default-tenant', '${JOB_CODE}', 'Process Demo Aggregate', 'PROCESS', 'DEMO',
  'MANUAL', NULL, 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
  'default-calendar', 'always_open', 'MANUAL', FALSE, 'NONE', 'NONE', 0, 600,
  'com.example.ProcessDemoHandler',
  jsonb_build_object('type','object'), jsonb_build_object(),
  1, TRUE, 'PROCESS demo for metric verification', 'system', 'system', now(), now()
);

INSERT INTO batch.pipeline_definition (
  tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
  version, enabled, description, created_at, updated_at
) VALUES (
  'default-tenant', '${JOB_CODE}', 'Process Demo Pipeline', 'PROCESS', 'DEMO', 'PROCESS',
  1, TRUE, 'PROCESS demo for metric verification', now(), now()
);

WITH pd AS (SELECT id FROM batch.pipeline_definition WHERE job_code='${JOB_CODE}')
INSERT INTO batch.pipeline_step_definition (
  pipeline_definition_id, step_code, step_name, stage_code, step_order, impl_code,
  step_params, timeout_seconds, retry_policy, retry_max_count, enabled, created_at, updated_at
)
SELECT pd.id, 'PROCESS_PREPARE',  'Prepare',  'PREPARE',  1, 'PROCESS_PREPARE',  '{}'::jsonb, 60,  'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_COMPUTE',  'Compute',  'COMPUTE',  2, 'sqlTransformCompute',
  jsonb_build_object('sqlTransformCompute', jsonb_build_object(
    'sourceSql', 'SELECT account_no, sum(amount) as total_amount, count(*) as txn_count FROM biz.process_demo_source WHERE tenant_id = :tenantId AND biz_date = :bizDate::date GROUP BY account_no',
    'targetSchema', 'biz',
    'targetTable', 'process_demo_target',
    'writeMode', 'UPSERT',
    'columns', jsonb_build_array(
      jsonb_build_object('source','account_no','target','account_no'),
      jsonb_build_object('source','total_amount','target','total_amount'),
      jsonb_build_object('source','txn_count','target','txn_count')
    ),
    'conflictColumns', jsonb_build_array('account_no'),
    'emptyResultPolicy','FAIL'
  )),
  120, 'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3, 'PROCESS_VALIDATE', '{}'::jsonb, 60,  'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_COMMIT',   'Commit',   'COMMIT',   4, 'PROCESS_COMMIT',   '{}'::jsonb, 120, 'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5, 'PROCESS_FEEDBACK', '{}'::jsonb, 60,  'NONE', 0, TRUE, now(), now() FROM pd;

COMMIT;
SQL

echo "==> 3. POST trigger /api/triggers/launch"
IDEMPOTENCY="demo-$(date +%s)"
RESPONSE=$(curl -sS -X POST "$TRIGGER_URL" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY" \
  -d "{
    \"tenantId\": \"default-tenant\",
    \"jobCode\": \"${JOB_CODE}\",
    \"bizDate\": \"2026-04-28\",
    \"triggerType\": \"MANUAL\",
    \"params\": {}
  }")
echo "trigger response: $RESPONSE"

echo "==> 4. 等 30s 让 worker-process 跑完五段"
sleep 30

echo "==> 5. 验收 target 表"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d batch_business -c \
  "SELECT account_no, total_amount, txn_count FROM biz.process_demo_target ORDER BY account_no;"

echo "==> 6. 抓 worker-process /actuator/prometheus 中 process_* 指标"
curl -sS "$WORKER_PROCESS_PROM" | grep -E '^process_(compute|commit|validation|stage)' || \
  echo "(no process_* metrics yet — worker may still be processing,可再等一会重跑这一段)"
