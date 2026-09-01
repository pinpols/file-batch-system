#!/usr/bin/env bash
# ADR-sim 4day · P4 四天驱动:连续 4 个 bizDate 跑批,行数逐日递增(增量放大)。
# 用法: bash 41-run-4days.sh [startDate YYYY-MM-DD] [baseRows]
#   bash 41-run-4days.sh 2026-06-06 300
# 每天之间等 WAIT 秒(默认 150)让 worker 把当天的 import/export/dispatch/workflow 跑完。
set -uo pipefail
START="${1:-2026-06-06}"; BASE="${2:-300}"; WAIT="${WAIT:-150}"
FINAL_WAIT="${FINAL_WAIT:-600}"; FINAL_POLL="${FINAL_POLL:-15}"
SIM4DAY_RUN_ID="${SIM4DAY_RUN_ID:-sim4day-$(date -u '+%Y%m%dT%H%M%SZ')}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
# shellcheck source=scripts/lib/python-runtime.sh
source "$ROOT/scripts/lib/python-runtime.sh"
batch_require_python
# shellcheck source=scripts/lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
SIM4DAY_LOG_DIR="${SIM4DAY_LOG_DIR:-$(log_run_dir "$ROOT" sim-4day "sim-4day-4days-${START//-/}")}"
log_link_dir "$ROOT" sim-4day "$SIM4DAY_LOG_DIR"
exec > >(tee -a "$SIM4DAY_LOG_DIR/00-run-4days.log") 2>&1
nextday(){ "$PYTHON_BIN" -c "import datetime as d;print((d.date.fromisoformat('$1')+d.timedelta(days=$2)).isoformat())"; }

echo "########## 4 天批量调度 起=$START 基准行=$BASE 每日间隔=${WAIT}s ##########"
echo "########## 日志目录: $SIM4DAY_LOG_DIR ##########"
echo "########## 本轮 run 标识: $SIM4DAY_RUN_ID ##########"

verify_platform() {
  docker exec -i "${PG_CONTAINER:-batch-postgres-primary}" psql \
    -U "${POSTGRES_USER:-batch_user}" -d "${PLATFORM_DB}" -At \
    -v run_prefix="${SIM4DAY_RUN_ID}%" -f /dev/stdin < "$HERE/sql/verify-platform-run.sql"
}

verify_business() {
  docker exec -i "${PG_CONTAINER:-batch-postgres-primary}" psql \
    -U "${POSTGRES_USER:-batch_user}" -d "${BUSINESS_DB}" -At \
    -f /dev/stdin < "$HERE/sql/verify-business-counts.sql"
}

verify_workflow_fixtures() {
  docker exec -i "${PG_CONTAINER:-batch-postgres-primary}" psql \
    -U "${POSTGRES_USER:-batch_user}" -d "${BUSINESS_DB}" -At \
    -f /dev/stdin < "$HERE/sql/verify-workflow-fixtures.sql"
}

verify_export_files() {
  docker exec -i "${PG_CONTAINER:-batch-postgres-primary}" psql \
    -U "${POSTGRES_USER:-batch_user}" -d "${PLATFORM_DB}" -At \
    -v run_prefix="${SIM4DAY_RUN_ID}%" -f /dev/stdin < "$HERE/sql/verify-export-files.sql"
}

baseline_business_snapshot="$(verify_business 2>/dev/null || true)"
IFS='|' read -r baseline_customer_count baseline_transaction_count baseline_risk_count <<<"$baseline_business_snapshot"
baseline_customer_count="${baseline_customer_count:-0}"
baseline_transaction_count="${baseline_transaction_count:-0}"
baseline_risk_count="${baseline_risk_count:-0}"
baseline_fixture_snapshot="$(verify_workflow_fixtures 2>/dev/null || true)"
IFS='|' read -r baseline_fixture_customer_count baseline_fixture_transaction_count baseline_fixture_risk_count <<<"$baseline_fixture_snapshot"
baseline_fixture_customer_count="${baseline_fixture_customer_count:-0}"
baseline_fixture_transaction_count="${baseline_fixture_transaction_count:-0}"
baseline_fixture_risk_count="${baseline_fixture_risk_count:-0}"
echo "########## 业务基线: customer_account=$baseline_customer_count transaction=$baseline_transaction_count risk_score=$baseline_risk_count ##########"
echo "########## Workflow fixture 基线: customer=$baseline_fixture_customer_count transaction=$baseline_fixture_transaction_count risk_score=$baseline_fixture_risk_count ##########"
# Day0 先投大文件一次(演示大文件摄取)
echo "==> Day0 预热:投放大文件到 MinIO ingress"
ROWS_BIG=${ROWS_BIG:-800000} bash "$HERE/30-gen-bigfiles.sh" "${START//-/}" \
  > "$SIM4DAY_LOG_DIR/01-day0-bigfiles.log" 2>&1 || true

for d in 0 1 2 3; do
  BD=$(nextday "$START" "$d")
  ROWS=$(( BASE * (d+1) ))   # 逐日递增:300/600/900/1200
  echo; echo "########## ===== DAY $d  bizDate=$BD  rows/import=$ROWS ===== ##########"
  day_log="$SIM4DAY_LOG_DIR/$(printf '%02d' $((d + 2)))-day${d}-${BD}.log"
  SIM4DAY_LOG_DIR="$SIM4DAY_LOG_DIR" SIM4DAY_CAPTURED=1 SIM4DAY_RUN_ID="$SIM4DAY_RUN_ID" ROWS="$ROWS" bash "$HERE/40-run-day.sh" "$BD" \
    > "$day_log" 2>&1
  echo "==> DAY $d 触发日志: $day_log"
  echo "==> 等 ${WAIT}s 让 worker 跑完当天…"; sleep "$WAIT"
  watch_log="$SIM4DAY_LOG_DIR/$(printf '%02d' $((d + 2)))-day${d}-${BD}-watch.log"
  SIM4DAY_LOG_DIR="$SIM4DAY_LOG_DIR" SIM4DAY_CAPTURED=1 bash "$HERE/50-watch.sh" \
    > "$watch_log" 2>&1 || true
  echo "==> DAY $d 观测快照: $watch_log"
done

echo "########## 4 天触发阶段结束，开始终态收敛（最长 ${FINAL_WAIT}s） ##########"
deadline=$(( $(date +%s) + FINAL_WAIT ))
platform_snapshot=""
while true; do
  platform_snapshot="$(verify_platform 2>/dev/null || true)"
  IFS='|' read -r instance_total instance_non_terminal instance_failed task_total task_non_terminal task_failed outbox_pending <<<"$platform_snapshot"
  if [[ "${instance_non_terminal:-1}" == "0" && "${task_non_terminal:-1}" == "0" \
        && "${instance_failed:-1}" == "0" && "${task_failed:-1}" == "0" \
        && "${outbox_pending:-1}" == "0" ]]; then
    break
  fi
  if (( $(date +%s) >= deadline )); then
    echo "❌ 4 天终态验收失败: instances=${instance_total:-?}/${instance_non_terminal:-?}/${instance_failed:-?} tasks=${task_total:-?}/${task_non_terminal:-?}/${task_failed:-?} outbox_pending=${outbox_pending:-?}" >&2
    echo "   run_prefix=${SIM4DAY_RUN_ID}" >&2
    verify_platform >&2 || true
    exit 1
  fi
  echo "   等待收敛: instances non_terminal=${instance_non_terminal:-?}, failed=${instance_failed:-?}; tasks non_terminal=${task_non_terminal:-?}, failed=${task_failed:-?}; outbox_pending=${outbox_pending:-?}"
  sleep "$FINAL_POLL"
done

fixture_customer_delta=$((4 - baseline_fixture_customer_count)); (( fixture_customer_delta < 0 )) && fixture_customer_delta=0
fixture_transaction_delta=$((3 - baseline_fixture_transaction_count)); (( fixture_transaction_delta < 0 )) && fixture_transaction_delta=0
fixture_risk_delta=$((3 - baseline_fixture_risk_count)); (( fixture_risk_delta < 0 )) && fixture_risk_delta=0
customer_expected=$((baseline_customer_count + BASE * 40 + fixture_customer_delta))
transaction_expected=$((baseline_transaction_count + BASE * 30 + fixture_transaction_delta))
risk_expected=$((baseline_risk_count + BASE * 30 + fixture_risk_delta))
business_snapshot="$(verify_business 2>/dev/null || true)"
IFS='|' read -r customer_count transaction_count risk_count <<<"$business_snapshot"
if [[ "$customer_count" != "$customer_expected" || "$transaction_count" != "$transaction_expected" || "$risk_count" != "$risk_expected" ]]; then
  echo "❌ 业务数据验收失败: customer_account=${customer_count:-?}/${customer_expected}, transaction=${transaction_count:-?}/${transaction_expected}, risk_score=${risk_count:-?}/${risk_expected}" >&2
  echo "   run_prefix=${SIM4DAY_RUN_ID}" >&2
  exit 1
fi

export_snapshot="$(verify_export_files 2>/dev/null || true)"
IFS='|' read -r export_total export_invalid <<<"$export_snapshot"
if [[ "${export_total:-0}" == "0" || "${export_invalid:-1}" != "0" ]]; then
  echo "❌ 导出文件验收失败: exports=${export_total:-?}, invalid=${export_invalid:-?}" >&2
  echo "   run_prefix=${SIM4DAY_RUN_ID}" >&2
  verify_export_files >&2 || true
  exit 1
fi

echo "✅ 4 天终态验收通过: $platform_snapshot"
echo "✅ 业务数据验收通过: customer_account=$customer_count transaction=$transaction_count risk_score=$risk_count"
echo "   其中业务日增量=${BASE}*40/${BASE}*30/${BASE}*30，Workflow fixture 本轮新增 ${fixture_customer_delta}/${fixture_transaction_delta}/${fixture_risk_delta} 行"
echo "✅ 导出文件验收通过: exports=$export_total invalid=$export_invalid"
echo "########## 4 天验证完成。用 bash 50-watch.sh --loop 持续观测 ##########"
