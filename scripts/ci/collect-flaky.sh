#!/usr/bin/env bash
# =========================================================
# collect-flaky.sh - surefire/failsafe flaky 汇总薄封装
#
# 调用 collect-flaky.py 扫 `**/target/{surefire,failsafe}-reports/*.xml`,
# 输出人读 summary + (GH Actions 下) Markdown step summary。
#
# 永远以 0 退出 — flaky 用例本来就允许 pass,不阻断 CI,治理走
# `docs/runbook/ci.md` 「flaky 治理」。
#
# Usage:
#   bash scripts/ci/collect-flaky.sh [-- <extra args passed to collect-flaky.py>]
#
# 常用透传:
#   --json <path>            写机读 JSON
#   --warn-threshold <N>     stderr WARN 阈值(默认 5)
# =========================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 "$ROOT_DIR/scripts/ci/collect-flaky.py" --root "$ROOT_DIR" "$@"
status=$?

# 显式 0:即使 python 脚本自身异常(IO 之类),也不该阻断本来已经绿的 build。
# 真出问题日志已经走 stderr,运维巡检 reports artifact 能直接看 XML。
if [[ "$status" -ne 0 ]]; then
  echo "WARN: collect-flaky.py exited with status $status; not failing build." >&2
fi
exit 0
