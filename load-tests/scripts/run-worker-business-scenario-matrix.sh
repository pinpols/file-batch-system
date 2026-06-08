#!/usr/bin/env bash
# =========================================================
# run-worker-business-scenario-matrix.sh
#
# 本地真实上下游 worker 业务场景矩阵统一入口。
# 默认只运行已稳定的小矩阵 Stage 2/3/4；Stage 1 基线、Stage 5 故障分支、
# Stage 6 trigger 去重需显式选择。
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
  STAGES=2,3,4 bash load-tests/scripts/run-worker-business-scenario-matrix.sh
  STAGES=1,2,3,4 bash load-tests/scripts/run-worker-business-scenario-matrix.sh

Profiles:
  smoke  Stage 2/3/4: import/export/process 小矩阵
  stage1 Stage 1: sim 主链路 baseline
  full   Stage 1/2/3/4; Stage 5/6 暂不默认纳入

Notes:
  Stage 5 含外部失败 / retry / SSRF 安全闸语义，后续沉淀为 failure profile。
  Stage 6 trigger 去重为单条手工矩阵，保留在报告中，不默认重复触发。
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

case "$PROFILE" in
  smoke)
    DEFAULT_STAGES="2,3,4"
    ;;
  stage1)
    DEFAULT_STAGES="1"
    ;;
  full)
    DEFAULT_STAGES="1,2,3,4"
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
    3)
      RUN_ID="export-stage3-$RUN_ID" run_stage 3 "export JSON/FIXED_WIDTH/EXCEL" bash scripts/sim/09-export-stage3.sh
      ;;
    4)
      RUN_ID="process-stage4-$RUN_ID" run_stage 4 "process JSONB/DIRECT/validation/empty" bash scripts/sim/10-process-stage4.sh
      ;;
    5)
      echo "Stage 5 is intentionally not automated yet: failure profile is still being formalized." | tee -a "$log"
      echo >> "$summary"
      echo "## Stage 5 - skipped" >> "$summary"
      echo "- status: SKIPPED" >> "$summary"
      echo "- reason: 外部失败 / retry / SSRF 安全闸语义需单独 failure profile，不纳入默认 smoke" >> "$summary"
      ;;
    6)
      echo "Stage 6 trigger dedup is documented as a manual one-shot matrix." | tee -a "$log"
      echo >> "$summary"
      echo "## Stage 6 - skipped" >> "$summary"
      echo "- status: SKIPPED" >> "$summary"
      echo "- reason: 当前为手工 one-shot 去重验证，后续再沉淀脚本" >> "$summary"
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
