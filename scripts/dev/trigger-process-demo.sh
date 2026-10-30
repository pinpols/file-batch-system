#!/usr/bin/env bash
# 用途：触发真实 Process 作业并核查处理结果。
# 用法：在仓库根目录执行 bash scripts/dev/trigger-process-demo.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# trigger-process-demo.sh - 真实下发一条 PROCESS 任务,验证
#   sqlTransformCompute plugin + ProcessMetrics 4 个指标实
#   际打点。一次性脚本,跑完后业务库会留一张 demo target 表。
# =========================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/env-common.sh
source "$ROOT_DIR/scripts/lib/env-common.sh"
batch_require_internal_secret
PG_HOST="${PG_HOST:-$PGHOST}"
PG_PORT="${PG_PORT:-$PGPORT}"
PG_USER="${PG_USER:-$PGUSER}"
DEMO_TENANT_ID="${DEMO_TENANT_ID:-$BATCH_DEFAULT_TENANT_ID}"
TRIGGER_URL="${TRIGGER_URL:-${TRIGGER_BASE_URL}/api/triggers/launch}"
WORKER_PROCESS_PROM="${WORKER_PROCESS_PROM:-http://localhost:${WORKER_PROCESS_PORT}/actuator/prometheus}"
SQL_DIR="$ROOT_DIR/scripts/dev/sql"

JOB_CODE="process_demo_aggregate_job"

echo "==> 1. 业务库 biz.process_demo_source / biz.process_demo_target 建表 + 灌 source 数据"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 \
  -f "$SQL_DIR/trigger-process-demo-business.sql"

echo "==> 2. 平台库配置 PROCESS Job 参数并关联受管 Workflow"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 \
  -v tenant_id="$DEMO_TENANT_ID" \
  -v job_code="$JOB_CODE" \
  -f "$SQL_DIR/trigger-process-demo-platform.sql"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 \
  -v tenant_id="$DEMO_TENANT_ID" \
  -v job_code="$JOB_CODE" \
  -v fixture_tag=process-demo \
  -f "$ROOT_DIR/scripts/local/sql/provision-single-job-workflow.sql"
WORKFLOW_ID="$(PLATFORM_DB="$PLATFORM_DB" POSTGRES_USER="$PG_USER" \
  "$PYTHON_BIN" "$ROOT_DIR/scripts/sim/launch_contract.py" "$DEMO_TENANT_ID" "$JOB_CODE")"

echo "==> 3. POST trigger /api/triggers/launch"
IDEMPOTENCY="demo-$(date +%s)"
RESPONSE=$(curl -fsS -X POST "$TRIGGER_URL" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Secret: $BATCH_INTERNAL_SECRET" \
  -H "Idempotency-Key: $IDEMPOTENCY" \
  -d "{
    \"tenantId\": \"${DEMO_TENANT_ID}\",
    \"workflowDefinitionId\": ${WORKFLOW_ID},
    \"bizDate\": \"2026-04-28\",
    \"triggerType\": \"MANUAL\",
    \"params\": {}
  }")
echo "trigger response: $RESPONSE"

echo "==> 4. 等 30s 让 worker-process 跑完五段"
sleep 30

echo "==> 5. 验收 target 表"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 \
  -f "$SQL_DIR/trigger-process-demo-result.sql"

echo "==> 6. 抓 worker-process /actuator/prometheus 中 process_* 指标"
curl -sS "$WORKER_PROCESS_PROM" | grep -E '^process_(compute|commit|validation|stage)' || \
  echo "(no process_* metrics yet — worker may still be processing,可再等一会重跑这一段)"
