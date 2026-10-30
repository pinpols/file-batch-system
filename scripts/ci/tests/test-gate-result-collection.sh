#!/usr/bin/env bash
# 用途：门禁失败汇总自测。
# 用法：在仓库根目录执行 bash scripts/ci/tests/test-gate-result-collection.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
# shellcheck source=../../lib/gate-result.sh
source "$ROOT/scripts/lib/gate-result.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# 自测固定自己的控制输入，不能依赖外层CI分组或本地模式。
unset BATCH_GATE_MODE BATCH_GATE_MODES BATCH_GATE_GROUP_MODES BATCH_GATE_ENABLED BATCH_GATE_GROUP
export BATCH_GATE_COLLECT=1
export BATCH_GATE_FAILURE_FILE="$tmp_dir/failures.tsv"
# 负例只写测试专有摘要，不能污染调用方的真实CI摘要。
export GITHUB_STEP_SUMMARY="$tmp_dir/summary.md"

gate_reset_collected
gate_run TEST_PASS "通过样例" true >"$tmp_dir/initial-pass.out"
gate_run TEST_FAIL "失败样例" bash -c 'exit 7' >"$tmp_dir/initial-fail.out" 2>&1
grep -F $'TEST_FAIL\t7\t失败样例' "$BATCH_GATE_FAILURE_FILE" >/dev/null

if gate_assert_collected >"$tmp_dir/initial-summary.out" 2>&1; then
  echo "聚合断言未拦截已记录的失败" >&2
  exit 1
fi

gate_reset_collected
gate_assert_collected >"$tmp_dir/initial-empty.out"
bash "$ROOT/scripts/ci/tests/test-gate-control.sh"
echo "门禁聚合模式测试通过"


# 前后端独立运行同一组结果契约，期望值不从实现生成。
contract_failures=0
contract_cases=0
assert_contract() {
  local actual="$1" expected="$2" label="$3"
  ((contract_cases += 1))
  if [[ "$actual" != *"$expected"* ]]; then
    printf '结果契约不匹配: %s\n预期: %s\n实际: %s\n' "$label" "$expected" "$actual" >&2
    ((contract_failures += 1))
  fi
}

result="$(gate_result SKIP TEST_SKIP '条件检查' '没有相关变更' testing)" || true
assert_contract "$result" '⏭️ 跳过 | code=TEST_SKIP | gate=条件检查 | group=testing | mode=conditional | exit_code=0 | action=none | reason=没有相关变更' SKIP
result="$(gate_result FAIL TEST_REASON '失败检查' 7 quality enforce '首个有效错误' 2>&1)"
assert_contract "$result" 'exit_code=7 | action=fix_and_retry | reason=首个有效错误' FAIL_REASON
result="$(gate_result REPORT TEST_REASON '报告检查' 9 quality report '需人工查看')"
assert_contract "$result" 'exit_code=9 | action=review_report | reason=需人工查看' REPORT_REASON
result="$(gate_result IGNORE TEST_IGNORE '忽略检查')"
assert_contract "$result" 'mode=ignore | exit_code=0 | action=none | reason=统一控制配置未执行该门禁' IGNORE_REASON
result="$(gate_result PASS TEST_ESCAPE $'名称|含换行\n第二行' 0 quality enforce $'100%\t明细\r\n后续')"
assert_contract "$result" 'gate=名称%7C含换行%0A第二行 | group=quality' SINGLE_LINE_NAME
assert_contract "$result" 'reason=100%25%09明细%0D%0A后续' SINGLE_LINE_REASON
((contract_cases += 1))
[[ "$result" != *$'\n'* ]] || { echo '结果行包含未转义换行' >&2; ((contract_failures += 1)); }

invalid_status=0
result="$(gate_result UNKNOWN TEST_UNKNOWN '未知状态' 0 2>&1)" || invalid_status=$?
assert_contract "$invalid_status" 2 UNKNOWN_EXIT
assert_contract "$result" '❌ 不通过 | code=GATE_RESULT_INVALID_STATUS | gate=未知状态 | group=general | mode=enforce | exit_code=2 | action=fix_and_retry | reason=' UNKNOWN_RESULT

enforce_status=0
result="$(BATCH_GATE_COLLECT=0 gate_run TEST_ENFORCE_EXIT '阻断退出码' bash -c 'exit 7' 2>&1)" || enforce_status=$?
assert_contract "$enforce_status" 7 ENFORCE_EXIT
assert_contract "$result" 'exit_code=7 | action=fix_and_retry' ENFORCE_RESULT

missing_status=0
result="$(BATCH_GATE_COLLECT=0 gate_run TEST_NO_COMMAND '缺少命令' 2>&1)" || missing_status=$?
assert_contract "$missing_status" 2 MISSING_COMMAND_EXIT
assert_contract "$result" 'reason=TEST_NO_COMMAND 缺少执行命令' MISSING_COMMAND_REASON
invalid_group=0
result="$(BATCH_GATE_GROUP='bad group' gate_run TEST_GROUP '非法分组' true 2>&1)" || invalid_group=$?
assert_contract "$invalid_group" 2 INVALID_GROUP_EXIT
assert_contract "$result" 'reason=无效门禁分组: bad group' INVALID_GROUP_REASON

usage_status=0
result="$(bash "$ROOT/scripts/ci/run-gate.sh" --group 2>&1)" || usage_status=$?
assert_contract "$usage_status" 2 RUNNER_USAGE_EXIT
assert_contract "$result" 'group=governance | mode=enforce | exit_code=2 | action=fix_and_retry | reason=--group 缺少值' RUNNER_USAGE_RESULT

result="$(gate_run TEST_DIAGNOSTIC '保留诊断' bash -c 'printf "❌ 不通过 | code=OLD_FAILURE | gate=旧检查 | reason=首个有效错误\n" >&2; exit 7' 2>&1)"
assert_contract "$result" '诊断 | ❌ 不通过 | code=OLD_FAILURE | gate=旧检查 | reason=首个有效错误' LEGACY_DIAGNOSTIC
gate_reset_collected
gate_run TEST_SUMMARY '汇总检查' bash -c 'exit 7' >/dev/null 2>&1
summary_status=0
result="$(gate_assert_collected 2>&1)" || summary_status=$?
assert_contract "$summary_status" 1 COLLECTION_EXIT
assert_contract "$result" '❌ 不通过 | code=TEST_SUMMARY | gate=汇总检查 | group=general | mode=enforce | exit_code=7 | action=fix_and_retry' COLLECTION_ITEM
assert_contract "$result" 'code=GATE_COLLECTION_SUMMARY | gate=门禁失败汇总 | group=general | mode=enforce | exit_code=1 | action=fix_and_retry | reason=1 项门禁失败' COLLECTION_RESULT

gate_reset_collected
gate_run TEST_MULTILINE $'名称|第一行\n第二行' bash -c 'exit 7' >/dev/null 2>&1
result="$(gate_assert_collected 2>&1)" || true
assert_contract "$result" 'gate=名称%7C第一行%0A第二行 | group=general | mode=enforce | exit_code=7' COLLECTION_SINGLE_LINE
assert_contract "$result" 'reason=1 项门禁失败' COLLECTION_EXACT_COUNT

export GITHUB_STEP_SUMMARY="$tmp_dir/summary.md"
: >"$GITHUB_STEP_SUMMARY"
result="$(gate_result PASS TEST_SUMMARY_FILE '摘要检查' 0 testing)"
assert_contract "$(cat "$GITHUB_STEP_SUMMARY")" "- $result" SUMMARY_FORMAT
unset GITHUB_STEP_SUMMARY
gate_reset_collected
printf '结果格式契约: %d 项, %d 失败\n' "$contract_cases" "$contract_failures"
((contract_failures == 0))
