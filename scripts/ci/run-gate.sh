#!/usr/bin/env bash
# 用途：按统一职责分组和模式执行门禁并传播结果。
# 用法：bash scripts/ci/run-gate.sh [--group 分组] [--mode enforce|report|ignore] <CODE> <名称> -- <命令及参数>。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
source "$ROOT/scripts/lib/gate-result.sh"

group="${BATCH_GATE_GROUP:-general}"
mode="${BATCH_GATE_MODE:-enforce}"
while (($# > 0)); do
  case "$1" in
    --group)
      [[ $# -ge 2 ]] || { gate_control_error "--group 缺少值"; exit 2; }
      group="$2"
      shift 2
      ;;
    --mode)
      [[ $# -ge 2 ]] || { gate_control_error "--mode 缺少值"; exit 2; }
      mode="$2"
      shift 2
      ;;
    *) break ;;
  esac
done

if (($# < 4)) || [[ "$3" != -- ]]; then
  printf '❌ 不通过 | code=GATE_RUNNER_USAGE | gate=run-gate | group=governance | mode=enforce | exit_code=2 | action=fix_and_retry\n' >&2
  exit 2
fi

code="$1"
name="$2"
shift 3
BATCH_GATE_GROUP="$group" BATCH_GATE_MODE="$mode" gate_run "$code" "$name" "$@"
