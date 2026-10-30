#!/usr/bin/env bash
# 用途：验证门禁分组、执行模式与结果控制。
# 用法：在仓库根目录执行 bash scripts/ci/tests/test-gate-control.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
# shellcheck source=../../lib/gate-result.sh
source "$ROOT/scripts/lib/gate-result.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

assert_contains() {
  local text="$1"
  local expected="$2"
  if [[ "$text" != *"$expected"* ]]; then
    printf '未找到预期文本: %s\n实际输出: %s\n' "$expected" "$text" >&2
    exit 1
  fi
}

enforce_output="$(BATCH_GATE_GROUP=quality gate_run TEST_ENFORCE "阻断模式样例" true)"
assert_contains "$enforce_output" "✅ 通过 | code=TEST_ENFORCE | gate=阻断模式样例 | group=quality | mode=enforce | exit_code=0 | action=none"

report_output="$(BATCH_GATE_GROUP=security BATCH_GATE_MODE=report gate_run TEST_REPORT "报告模式样例" bash -c 'exit 7' 2>&1)"
assert_contains "$report_output" "⚠️ 报告 | code=TEST_REPORT | gate=报告模式样例 | group=security | mode=report | exit_code=7 | action=review_report"

marker="$tmp_dir/ignored-command-ran"
ignore_output="$(BATCH_GATE_GROUP=docs BATCH_GATE_MODE=ignore gate_run TEST_IGNORE "忽略模式样例" touch "$marker")"
if [[ -e "$marker" ]]; then
  echo "忽略模式仍执行了命令" >&2
  exit 1
fi
assert_contains "$ignore_output" "⏭️ 忽略 | code=TEST_IGNORE | gate=忽略模式样例 | group=docs | mode=ignore | exit_code=0 | action=none"

override_output="$(
  BATCH_GATE_GROUP=security \
  BATCH_GATE_GROUP_MODES='security=report' \
  BATCH_GATE_MODES='TEST_OVERRIDE=ignore' \
    gate_run TEST_OVERRIDE "门禁覆盖样例" false
)"
assert_contains "$override_output" "group=security | mode=ignore"

local_hook_output="$(
  BATCH_GATE_GROUP=local-pre-commit \
  BATCH_GATE_GROUP_MODES='quality=report' \
    gate_run PRE_COMMIT_JAVA_READABILITY "本地提交分组样例" true
)"
assert_contains "$local_hook_output" "group=quality | mode=report"

filtered_output="$(
  BATCH_GATE_GROUP=quality gate_run TEST_FILTER "统一输出格式样例" bash -c \
    'printf "诊断详情\n✅ 通过 | code=LEGACY_CHILD | gate=旧状态\n✅ 通过 | code=NESTED | gate=嵌套门禁 | group=testing | mode=enforce | exit_code=0 | action=none\n"'
)"
assert_contains "$filtered_output" "诊断详情"
assert_contains "$filtered_output" "code=NESTED | gate=嵌套门禁 | group=testing | mode=enforce"
assert_contains "$filtered_output" "group=quality | mode=enforce"
if [[ "$filtered_output" == *"LEGACY_CHILD"* ]]; then
  echo "旧格式子检查状态没有被统一 runner 收敛" >&2
  exit 1
fi

disabled_output="$(BATCH_GATE_ENABLED=0 BATCH_GATE_GROUP=build gate_run TEST_DISABLED "总开关样例" false)"
assert_contains "$disabled_output" "group=build | mode=ignore"

if BATCH_GATE_MODE=unexpected gate_run TEST_INVALID "非法模式样例" true >"$tmp_dir/invalid.out" 2>&1; then
  echo "非法模式未被拒绝" >&2
  exit 1
fi
assert_contains "$(<"$tmp_dir/invalid.out")" "code=GATE_MODE_INVALID"

if BATCH_GATE_GROUP='bad group' gate_run TEST_INVALID_GROUP "非法分组样例" true >"$tmp_dir/invalid-group.out" 2>&1; then
  echo "非法分组未被拒绝" >&2
  exit 1
fi
assert_contains "$(<"$tmp_dir/invalid-group.out")" "reason=无效门禁分组: bad group"

if BATCH_GATE_GROUP_MODES='quality =report' gate_run TEST_INVALID_MAP "非法映射样例" true >"$tmp_dir/invalid-map.out" 2>&1; then
  echo "非法映射未被拒绝" >&2
  exit 1
fi
assert_contains "$(<"$tmp_dir/invalid-map.out")" "reason=无效控制项: quality =report"

echo "门禁分组与模式控制测试通过"
