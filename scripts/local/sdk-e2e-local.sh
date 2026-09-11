#!/usr/bin/env bash
# =============================================================================
# sdk-e2e-local.sh — BYO SDK 样例 worker × 真 orchestrator 本地全链路验证
#
# 用**已在本地跑着的**真栈(orchestrator+trigger+postgres+kafka)驱动一个
# examples/self-hosted-sdk/sample-tenant-worker-<lang>,走真链路逐阶段断言:
# register → dispatch → claim → execute → report → terminal。
#
# 共享逻辑全在 scripts/lib/sdk-e2e-common.sh(CI 入口 run-sdk-orchestrator-e2e.sh
# 复用同一套);本脚本只负责"栈已起"的本地入口。覆盖矩阵 + 已知 wire bug 见
# docs/sdk/local-e2e-coverage.md。
#
# 用法:
#   bash scripts/local/sdk-e2e-local.sh go
#   bash scripts/local/sdk-e2e-local.sh python
#   KEEP=1 bash scripts/local/sdk-e2e-local.sh go     # 不清理探针(调试)
# =============================================================================
set -uo pipefail

LANG_ID="${1:?usage: sdk-e2e-local.sh <go|python|typescript>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/sdk-e2e-common.sh
source "$ROOT/scripts/lib/sdk-e2e-common.sh"

WC="sdk-e2e-${LANG_ID}-$$"
WORKER_LOG="/tmp/sdk-e2e-${LANG_ID}.log"
WORKER_PID=""

cleanup() {
  [[ -n "$WORKER_PID" ]] && kill "$WORKER_PID" 2>/dev/null
  pkill -f "$WC" 2>/dev/null
  [[ "${KEEP:-0}" == "1" ]] || sdk_e2e_cleanup "$WC"
}
trap cleanup EXIT

sdk_e2e_say "0. preconditions (local stack must be running)"
sdk_e2e_check_stack || { echo "  → start the stack first (host jars or scripts/docker/up-apps.sh)"; exit 1; }

sdk_e2e_say "1. seed api-key + echo job"
RAW="$(sdk_e2e_seed_api_key "$WC")"
sdk_e2e_ensure_echo_job || { sdk_e2e_fail "could not create ${SDK_E2E_JOB_CODE} (run scripts/data/load-system-test-data.sh for atomic_shell_demo seed)"; exit 1; }
sdk_e2e_pass "api-key + ${SDK_E2E_JOB_CODE} ready"

sdk_e2e_say "2. pre-create node-direct dispatch topic"
sdk_e2e_precreate_topic "$WC"; sdk_e2e_pass "topic batch.task.dispatch.atomic.node.${WC} ready"

sdk_e2e_say "3. start ${LANG_ID} sample worker (code=${WC})"
WORKER_PID="$(sdk_e2e_start_worker "$LANG_ID" "$WC" "$RAW" "$WORKER_LOG")" || exit 2

sdk_e2e_say "4a. register (real API-key auth → worker_registry)"
STAGE_REGISTER=0
sdk_e2e_assert_register "$WC" "$WORKER_PID" "$WORKER_LOG" && { STAGE_REGISTER=1; sdk_e2e_pass "registered"; } || { sdk_e2e_fail "did not register"; exit 1; }

sdk_e2e_say "4b. launch + dispatch + claim + execute + report + terminal"
sdk_e2e_run_chain "$RAW" "$WORKER_LOG"

sdk_e2e_say "summary (${LANG_ID})"
printf 'register=%s dispatch=%s execute=%s report=%s terminal=%s\n' \
  "$STAGE_REGISTER" "${STAGE_DISPATCH:-0}" "${STAGE_EXECUTE:-0}" "${STAGE_REPORT:-0}" "${STAGE_TERMINAL:-0}"
echo "worker log: $WORKER_LOG"
[[ "${STAGE_TERMINAL:-0}" == "1" ]]
