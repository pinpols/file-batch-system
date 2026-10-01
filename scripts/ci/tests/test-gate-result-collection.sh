#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
# shellcheck source=../../lib/gate-result.sh
source "$ROOT/scripts/lib/gate-result.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

export BATCH_GATE_COLLECT=1
export BATCH_GATE_FAILURE_FILE="$tmp_dir/failures.tsv"

gate_reset_collected
gate_run TEST_PASS "通过样例" true
gate_run TEST_FAIL "失败样例" bash -c 'exit 7'
grep -F $'TEST_FAIL\t7\t失败样例' "$BATCH_GATE_FAILURE_FILE" >/dev/null

if gate_assert_collected; then
  echo "聚合断言未拦截已记录的失败" >&2
  exit 1
fi

gate_reset_collected
gate_assert_collected
echo "门禁聚合模式测试通过"
