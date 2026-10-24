#!/usr/bin/env bash
# 审计当前应用日志中的 WARN：预期本地告警需登记 owner/到期日，
# 运行状态异常保留 ACTION_REQUIRED，未登记告警视为新回归。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
POLICY_FILE="${RUNTIME_WARNING_POLICY_FILE:-$ROOT/config/runtime-warning-policy.tsv}"
LOG_DIR="${RUNTIME_WARNING_LOG_DIR:-$ROOT/logs/current/app}"
TODAY="$(date +%F)"

[[ -f "$POLICY_FILE" ]] || { echo "FAIL RUNTIME_WARNINGS: 缺少策略 $POLICY_FILE" >&2; exit 2; }
[[ -d "$LOG_DIR" ]] || { echo "FAIL RUNTIME_WARNINGS: 无当前应用日志 $LOG_DIR" >&2; exit 2; }

warnings_file="$(mktemp)"
audit_temporary_files=("$warnings_file")
trap 'rm -f -- "${audit_temporary_files[@]}"' EXIT
log_files="$(mktemp)"
audit_temporary_files+=("$log_files")
if ! find "$LOG_DIR" -maxdepth 1 -type f -name '*.log' ! -name '*.screen.log' \
    ! -name '*.manual*.log' ! -name '*.debug*.log' -print0 >"$log_files"; then
  echo "FAIL RUNTIME_WARNINGS: 应用日志盘点失败 $LOG_DIR" >&2
  exit 2
fi
if [[ ! -s "$log_files" ]]; then
  echo "FAIL RUNTIME_WARNINGS: 没有可审计的应用日志 $LOG_DIR" >&2
  exit 2
fi

total=0
expected=0
action_required=0
unknown=0
expired=0
while IFS= read -r -d '' log_file; do
  collection_status=0
  grep ' WARN ' "$log_file" >"$warnings_file" || collection_status=$?
  if (( collection_status > 1 )); then
    echo "FAIL RUNTIME_WARNINGS: 应用日志读取失败 $log_file" >&2
    exit 2
  fi
  while IFS= read -r warning || [[ -n "$warning" ]]; do
    [[ -z "$warning" ]] && continue
    total=$((total + 1))
    matched=0
    while IFS=$'\t' read -r code disposition owner expires regex required_context; do
      [[ -z "$code" || "$code" == \#* ]] && continue
      if [[ "$warning" =~ $regex ]]; then
        matched=1
        if [[ "$expires" < "$TODAY" ]]; then
          echo "EXPIRED $code owner=$owner expires=$expires"
          expired=$((expired + 1))
        elif [[ "$disposition" == "EXPECTED" && -n "$required_context" ]] \
            && ! grep -Eq -- "$required_context" "$log_file"; then
          echo "ACTION $code owner=$owner reason=context_missing: $warning"
          action_required=$((action_required + 1))
        elif [[ "$disposition" == "EXPECTED" ]]; then
          expected=$((expected + 1))
        else
          echo "ACTION $code owner=$owner: $warning"
          action_required=$((action_required + 1))
        fi
        break
      fi
    done <"$POLICY_FILE"
    if (( matched == 0 )); then
      if (( unknown < 20 )); then
        echo "UNKNOWN: $log_file:$warning"
      fi
      unknown=$((unknown + 1))
    fi
  done <"$warnings_file"
done <"$log_files"

if (( unknown > 0 || expired > 0 )); then
  echo "FAIL RUNTIME_WARNINGS: total=$total expected=$expected action_required=$action_required unknown=$unknown expired=$expired" >&2
  exit 1
fi
if (( action_required > 0 )); then
  echo "WARN RUNTIME_WARNINGS: total=$total expected=$expected action_required=$action_required"
  exit 3
fi
echo "PASS RUNTIME_WARNINGS: total=$total expected=$expected"
