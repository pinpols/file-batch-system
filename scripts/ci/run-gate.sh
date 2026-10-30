#!/usr/bin/env bash
# 用途：按统一职责分组和模式执行门禁并传播结果。
# 用法：bash scripts/ci/run-gate.sh [--group 分组] [--mode enforce|report|ignore] <CODE> <名称> -- <命令及参数>。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
# shellcheck source=../lib/gate-result.sh
source "$ROOT/scripts/lib/gate-result.sh"

runner_usage_error() {
  gate_result FAIL GATE_RUNNER_USAGE "run-gate" 2 governance enforce "$1"
  exit 2
}

group="${BATCH_GATE_GROUP:-general}"
mode="${BATCH_GATE_MODE:-enforce}"
while (($# > 0)); do
  case "$1" in
    --group)
      [[ $# -ge 2 ]] || runner_usage_error '--group 缺少值'
      group="$2"; shift 2
      ;;
    --mode)
      [[ $# -ge 2 ]] || runner_usage_error '--mode 缺少值'
      mode="$2"; shift 2
      ;;
    *) break ;;
  esac
done

if (($# < 4)) || [[ "$3" != -- ]]; then
  runner_usage_error '用法：run-gate.sh [--group 分组] [--mode enforce|report|ignore] <CODE> <名称> -- <命令及参数>'
fi
code="$1"; name="$2"; shift 3
BATCH_GATE_GROUP="$group" BATCH_GATE_MODE="$mode" gate_run "$code" "$name" "$@"
