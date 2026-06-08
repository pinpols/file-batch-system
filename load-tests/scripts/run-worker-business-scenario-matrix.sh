#!/usr/bin/env bash
# =========================================================
# run-worker-business-scenario-matrix.sh
#
# 本地真实上下游 worker 业务场景矩阵统一入口。
# 默认运行已稳定的小矩阵 Stage 2/2b/2c/3/3b/3c/4/4b/4c/5/5c/6/6c；Stage 1 基线需显式选择。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PROFILE="${PROFILE:-smoke}"
STAGES="${STAGES:-}"
RUN_ID="${RUN_ID:-worker-biz-matrix-$(date +%Y%m%d%H%M%S)}"
REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

usage() {
  cat <<'USAGE'
Usage:
  PROFILE=smoke bash load-tests/scripts/run-worker-business-scenario-matrix.sh
  STAGES=2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c bash load-tests/scripts/run-worker-business-scenario-matrix.sh
  STAGES=1,2,3,4 bash load-tests/scripts/run-worker-business-scenario-matrix.sh

Profiles:
  smoke  Stage 2/2b/2c/3/3b/3c/4/4b/4c/5/5c/6/6c: worker 业务小矩阵
  stage1 Stage 1: sim 主链路 baseline
  full   Stage 1 + smoke

Notes:
  Stage 2d 需要 worker-import 以 skip profile 启动:
    BATCH_WORKER_IMPORT_SKIP_ENABLED=true
    BATCH_WORKER_IMPORT_SKIP_THRESHOLD_MODE=ABSOLUTE
    BATCH_WORKER_IMPORT_SKIP_MAX_SKIP_COUNT=1
  Atomic HTTP 真成功通过 Stage 5c 使用非 loopback endpoint 覆盖。
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

case "$PROFILE" in
  smoke)
    DEFAULT_STAGES="2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c"
    ;;
  stage1)
    DEFAULT_STAGES="1"
    ;;
  full)
    DEFAULT_STAGES="1,2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c"
    ;;
  *)
    echo "unknown PROFILE=$PROFILE" >&2
    usage >&2
    exit 2
    ;;
esac

STAGES="${STAGES:-$DEFAULT_STAGES}"
IFS=',' read -r -a STAGE_LIST <<< "$STAGES"

log="$REPORT_DIR/worker-business-scenario-matrix.log"
summary="$REPORT_DIR/worker-business-scenario-matrix-summary.md"

run_stage() {
  local stage="$1"
  local name="$2"
  shift 2
  echo "==> Stage $stage: $name" | tee -a "$log"
  {
    echo
    echo "## Stage $stage - $name"
    echo
    echo "- started_at: $(date -Iseconds)"
    echo "- command: \`$*\`"
  } >> "$summary"
  if "$@" 2>&1 | tee -a "$log"; then
    echo "- status: PASS" >> "$summary"
  else
    echo "- status: FAIL" >> "$summary"
    return 1
  fi
  echo "- finished_at: $(date -Iseconds)" >> "$summary"
}

{
  echo "# Worker Business Scenario Matrix Summary"
  echo
  echo "- run_id: \`$RUN_ID\`"
  echo "- profile: \`$PROFILE\`"
  echo "- stages: \`$STAGES\`"
  echo "- started_at: $(date -Iseconds)"
} > "$summary"
: > "$log"

for stage in "${STAGE_LIST[@]}"; do
  case "$stage" in
    1)
      run_stage 1 "sim baseline" bash scripts/sim/05-load.sh
      run_stage 1 "sim verify" bash scripts/sim/06-verify.sh
      ;;
    2)
      RUN_ID="import-stage2-$RUN_ID" run_stage 2 "import XML/FIXED_WIDTH" bash scripts/sim/08-import-stage2.sh
      ;;
    2b)
      RUN_ID="import-stage2b-$RUN_ID" run_stage 2b "import UPSERT/LOAD failure/partition guard" bash scripts/sim/11-import-stage2b.sh
      ;;
    2c)
      RUN_ID="import-stage2c-$RUN_ID" run_stage 2c "import APPEND/UPSERT/partition replace matrix" bash scripts/sim/17-import-stage2c.sh
      ;;
    2d)
      RUN_ID="import-stage2d-$RUN_ID" run_stage 2d "import bad-record skip threshold" bash scripts/sim/23-import-stage2d.sh
      ;;
    3)
      RUN_ID="export-stage3-$RUN_ID" run_stage 3 "export JSON/FIXED_WIDTH/EXCEL" bash scripts/sim/09-export-stage3.sh
      ;;
    3b)
      RUN_ID="export-stage3b-$RUN_ID" run_stage 3b "export keyset 4-shard" bash scripts/sim/12-export-stage3b.sh
      ;;
    3c)
      RUN_ID="export-stage3c-$RUN_ID" run_stage 3c "export 8-shard/dedup/multi-tenant" bash scripts/sim/18-export-stage3c.sh
      ;;
    4)
      RUN_ID="process-stage4-$RUN_ID" run_stage 4 "process JSONB/DIRECT/validation/empty" bash scripts/sim/10-process-stage4.sh
      ;;
    4b)
      RUN_ID="process-stage4b-$RUN_ID" run_stage 4b "process idempotent rerun/recovery" bash scripts/sim/13-process-stage4b.sh
      ;;
    4c)
      RUN_ID="process-stage4c-$RUN_ID" run_stage 4c "process sharded/cancel semantics" bash scripts/sim/19-process-stage4c.sh
      ;;
    5)
      RUN_ID="dispatch-atomic-stage5b-$RUN_ID" run_stage 5 "dispatch failure + atomic success" bash -c 'bash scripts/sim/14-dispatch-stage5b.sh && bash scripts/sim/16-atomic-stage5b.sh'
      ;;
    5c)
      RUN_ID="dispatch-atomic-stage5c-$RUN_ID" run_stage 5c "dispatch channels + atomic http/timeout/cancel" bash -c 'bash scripts/sim/20-dispatch-stage5c.sh && bash scripts/sim/21-atomic-stage5c.sh'
      ;;
    6)
      RUN_ID="trigger-stage6b-$RUN_ID" run_stage 6 "trigger dedup/storm" bash scripts/sim/15-trigger-stage6b.sh
      ;;
    6c)
      RUN_ID="trigger-stage6c-$RUN_ID" run_stage 6c "trigger schedule/misfire/replay/storm" bash scripts/sim/22-trigger-stage6c.sh
      ;;
    *)
      echo "unknown stage: $stage" >&2
      exit 2
      ;;
  esac
done

{
  echo
  echo "- finished_at: $(date -Iseconds)"
  echo "- log: \`$log\`"
} >> "$summary"

echo "==> summary: $summary"
