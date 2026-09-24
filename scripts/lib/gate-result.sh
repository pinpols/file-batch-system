#!/usr/bin/env bash
# Shared result format for local hooks and CI gate commands.

gate_result() {
  local status="$1"
  local code="$2"
  local name="$3"
  local exit_code="${4:-0}"

  local line
  case "$status" in
    PASS) line="✅ 通过 | code=${code} | gate=${name}" ;;
    FAIL)
      line="❌ 不通过 | code=${code} | gate=${name} | exit_code=${exit_code}"
      ;;
    SKIP) line="⏭️ 跳过 | code=${code} | gate=${name} | reason=${exit_code}" ;;
    *)
      line="❌ 不通过 | code=GATE_RESULT_INVALID_STATUS | gate=${name} | exit_code=2"
      printf '%s\n' "$line" >&2
      return 2
      ;;
  esac

  if [[ "$status" == FAIL ]]; then
    printf '%s\n' "$line" >&2
  else
    printf '%s\n' "$line"
  fi
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf -- '- %s\n' "$line" >>"$GITHUB_STEP_SUMMARY"
  fi
}

gate_run() {
  local code="$1"
  local name="$2"
  shift 2

  if "$@"; then
    gate_result PASS "$code" "$name"
  else
    local exit_code=$?
    gate_result FAIL "$code" "$name" "$exit_code"
    return "$exit_code"
  fi
}

gate_skip() {
  gate_result SKIP "$1" "$2" "$3"
}

gate_report_on_exit() {
  GATE_RESULT_CODE="$1"
  GATE_RESULT_NAME="$2"
  trap 'gate_report_exit "$?"' EXIT
}

gate_report_exit() {
  local exit_code="$1"
  trap - EXIT
  if ((exit_code == 0)); then
    gate_result PASS "$GATE_RESULT_CODE" "$GATE_RESULT_NAME"
  else
    gate_result FAIL "$GATE_RESULT_CODE" "$GATE_RESULT_NAME" "$exit_code"
  fi
  exit "$exit_code"
}
