#!/usr/bin/env bash
# 审计当前应用日志中的 WARN：预期本地告警需登记 owner/到期日，
# 运行状态异常保留 ACTION_REQUIRED，未登记告警视为新回归。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
POLICY_FILE="${RUNTIME_WARNING_POLICY_FILE:-$ROOT/config/runtime-warning-policy.tsv}"
LOG_DIR="${RUNTIME_WARNING_LOG_DIR:-$ROOT/logs/current/app}"
TODAY="$(date +%F)"

[[ -f "$POLICY_FILE" ]] || { echo "FAIL RUNTIME_WARNINGS: 缺少策略 $POLICY_FILE" >&2; exit 2; }
[[ -d "$LOG_DIR" ]] || { echo "SKIP RUNTIME_WARNINGS: 无当前应用日志 $LOG_DIR"; exit 0; }

warnings_file="$(mktemp)"
trap 'rm -f "$warnings_file"' EXIT
find "$LOG_DIR" -maxdepth 1 -type f -name '*.log' ! -name '*.screen.log' \
  ! -name '*.manual*.log' ! -name '*.debug*.log' -print0 \
  | xargs -0 grep -H ' WARN ' >"$warnings_file" 2>/dev/null || true

total=0
expected=0
action_required=0
unknown=0
expired=0
while IFS= read -r warning; do
  [[ -z "$warning" ]] && continue
  total=$((total + 1))
  matched=0
  while IFS=$'\t' read -r code disposition owner expires regex; do
    [[ -z "$code" || "$code" == \#* ]] && continue
    if [[ "$warning" =~ $regex ]]; then
      matched=1
      if [[ "$expires" < "$TODAY" ]]; then
        echo "EXPIRED $code owner=$owner expires=$expires"
        expired=$((expired + 1))
      elif [[ "$disposition" == "EXPECTED" ]]; then
        expected=$((expected + 1))
      else
        echo "ACTION $code owner=$owner: ${warning#*:}"
        action_required=$((action_required + 1))
      fi
      break
    fi
  done <"$POLICY_FILE"
  if (( matched == 0 )); then
    if (( unknown < 20 )); then
      echo "UNKNOWN: $warning"
    fi
    unknown=$((unknown + 1))
  fi
done <"$warnings_file"

if (( unknown > 0 || expired > 0 )); then
  echo "FAIL RUNTIME_WARNINGS: total=$total expected=$expected action_required=$action_required unknown=$unknown expired=$expired" >&2
  exit 1
fi
if (( action_required > 0 )); then
  echo "WARN RUNTIME_WARNINGS: total=$total expected=$expected action_required=$action_required"
  exit 3
fi
echo "PASS RUNTIME_WARNINGS: total=$total expected=$expected"
