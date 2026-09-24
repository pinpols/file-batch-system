#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
source "$ROOT/scripts/lib/gate-result.sh"

if (($# < 4)) || [[ "$3" != -- ]]; then
  printf '❌ 不通过 | code=GATE_RUNNER_USAGE | gate=run-gate | exit_code=2\n' >&2
  exit 2
fi

code="$1"
name="$2"
shift 3
gate_run "$code" "$name" "$@"
