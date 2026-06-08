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
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SQL_DIR="$ROOT_DIR/scripts/dev/sql"

JOB_CODE="process_demo_aggregate_job"
PIPELINE_CODE="process_demo_aggregate_pipeline"

echo "==> 1. 业务库 biz.process_demo_source / biz.process_demo_target 建表 + 灌 source 数据"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d batch_business -v ON_ERROR_STOP=1 \
  -f "$SQL_DIR/trigger-process-demo-business.sql"

echo "==> 2. 平台库 INSERT job_definition + pipeline_definition + 5 step (PREPARE/COMPUTE/VALIDATE/COMMIT/FEEDBACK)"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d batch_platform -v ON_ERROR_STOP=1 \
  -v job_code="$JOB_CODE" \
  -f "$SQL_DIR/trigger-process-demo-platform.sql"

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
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d batch_business -v ON_ERROR_STOP=1 \
  -f "$SQL_DIR/trigger-process-demo-result.sql"

echo "==> 6. 抓 worker-process /actuator/prometheus 中 process_* 指标"
curl -sS "$WORKER_PROCESS_PROM" | grep -E '^process_(compute|commit|validation|stage)' || \
  echo "(no process_* metrics yet — worker may still be processing,可再等一会重跑这一段)"
