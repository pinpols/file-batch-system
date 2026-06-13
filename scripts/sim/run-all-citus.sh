#!/usr/bin/env bash
# =========================================================
# run-all-citus.sh:在 Citus 上一次性、可重复地连跑 sim 全场景。
#
# 先 00-reset-runtime 清运行态(保证 COUNT 类断言不被跨 stage / 跨重复运行的累积污染),
# 再顺序跑 08→25 + 07/05/06-sdk,逐 stage 记 PASS/FAIL,末尾汇总。可反复执行,每次结果一致。
#
# 前置:8 服务已起并连 Citus(平台→25432 simple-mode,业务→15432 _part 库);
#       console 以 bypass-mode=true 起(06-sdk 走 cookie 鉴权需免 CSRF);
#       worker-import 带 skip 阈值 env(23 需)、worker-dispatch 带 allow-private(14/20 需)。
#       —— 这些由 env-citus.sh 的 citus_restart_worker 统一注入,见各 stage 头注释。
#
# 用法:
#   unset BATCH_ENV_LOADED COMPOSE_ENV_FILE BATCH_ENV_COMMON_ROOT CITUS_PLATFORM_JDBC_URL
#   export BATCH_ENV_COMMON_ROOT="$(pwd)"
#   source scripts/lib/env-common.sh && batch_load_default_env
#   source scripts/sim/env-citus.sh
#   export BATCH_SECURITY_BYPASS_MODE=true
#   bash scripts/sim/run-all-citus.sh
# =========================================================
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

LOGDIR="${SIM_RUN_LOGDIR:-/tmp/citus-sim-run}"
mkdir -p "$LOGDIR"

STAGES=(08-import-stage2 09-export-stage3 10-process-stage4 \
        11-import-stage2b 12-export-stage3b 13-process-stage4b \
        14-dispatch-stage5b 15-trigger-stage6b 16-atomic-stage5b \
        17-import-stage2c 18-export-stage3c 19-process-stage4c \
        20-dispatch-stage5c 21-atomic-stage5c 22-trigger-stage6c \
        23-import-stage2d 24-trigger-stage6d 25-import-stage2e-checkpoint-crash \
        07-atomic-load 05-load 06-sdk-worker-verify)

echo "######## 00-reset-runtime(清运行态,保证可重复)########"
bash scripts/sim/00-reset-runtime.sh || { echo "❌ reset 失败,中止"; exit 1; }

declare -a RESULTS
pass=0; fail=0
for st in "${STAGES[@]}"; do
  echo ""
  echo "════════════════════ RUN $st ════════════════════ $(date +%H:%M:%S)"
  log="$LOGDIR/$st.log"
  bash "scripts/sim/$st.sh" > "$log" 2>&1
  rc=$?
  tail=$(grep -E "PASS|FAIL|全部通过|assertion|完成:" "$log" | tail -1 | cut -c1-100)
  if [[ $rc -eq 0 ]]; then
    RESULTS+=("✅ $st  | $tail"); pass=$((pass+1)); echo "RESULT: ✅ $st"
  else
    RESULTS+=("❌ $st rc=$rc | $tail"); fail=$((fail+1)); echo "RESULT: ❌ $st rc=$rc"
  fi
done

echo ""
echo "######## 汇总  PASS=$pass FAIL=$fail ########"
for r in "${RESULTS[@]}"; do echo "$r"; done
echo "DONE_ALL pass=$pass fail=$fail"
[[ $fail -eq 0 ]]
