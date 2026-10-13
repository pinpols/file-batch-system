#!/usr/bin/env bash
# 本地提交检查与 CI 门禁共用的分组、模式控制和结果输出格式。

gate_mode_valid() {
  case "$1" in
    enforce|report|ignore) return 0 ;;
    *) return 1 ;;
  esac
}

gate_group_valid() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]*$ ]]
}

gate_local_group_for_code() {
  case "$1" in
    PRE_COMMIT_DIFF_CHECK|PRE_COMMIT_UTF8_ENCODING*|PRE_COMMIT_COMMENT_LANGUAGE)
      printf 'hygiene'
      ;;
    PRE_COMMIT_*DOC*)
      printf 'docs'
      ;;
    PRE_COMMIT_*CONFIG*|PRE_COMMIT_*ENV*|PRE_COMMIT_*HELM*|PRE_COMMIT_FEATURE_SWITCH*|PRE_COMMIT_INFRASTRUCTURE_UTF8|PRE_COMMIT_PRODUCTION_OVERLAY)
      printf 'config'
      ;;
    PRE_COMMIT_*SBOM*|PRE_COMMIT_DEPENDENCY_*)
      printf 'supply-chain'
      ;;
    PRE_COMMIT_*JAVA*|PRE_COMMIT_*SHELL*|PRE_COMMIT_*MYBATIS*|PRE_COMMIT_MAPOF*|PRE_COMMIT_PIPELINE*|PRE_COMMIT_TEST_CONVENTIONS*)
      printf 'quality'
      ;;
    PRE_COMMIT_*|GATE_COLLECTION_SUMMARY)
      printf 'governance'
      ;;
    *) printf '%s' "${BATCH_GATE_GROUP:-general}" ;;
  esac
}

gate_control_error() {
  local detail="$1"
  printf '❌ 不通过 | code=GATE_MODE_INVALID | gate=门禁模式控制 | group=governance | mode=enforce | exit_code=2 | action=fix_and_retry | reason=%s\n' "$detail" >&2
}

gate_mode_from_map() {
  local mapping="$1"
  local expected_key="$2"
  local entry
  local key
  local mode
  local matched=""
  local old_ifs="$IFS"
  IFS=','
  for entry in $mapping; do
    IFS="$old_ifs"
    if [[ "$entry" != *=* ]]; then
      gate_control_error "控制项缺少等号: ${entry}"
      return 2
    fi
    key="${entry%%=*}"
    mode="${entry#*=}"
    if [[ ! "$key" =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]] || ! gate_mode_valid "$mode"; then
      gate_control_error "无效控制项: ${entry}"
      return 2
    fi
    if [[ "$key" == "$expected_key" ]]; then
      matched="$mode"
    fi
    IFS=','
  done
  IFS="$old_ifs"
  printf '%s' "$matched"
}

gate_resolve_mode() {
  local code="$1"
  local group="$2"
  local enabled="${BATCH_GATE_ENABLED:-1}"
  local mode="${BATCH_GATE_MODE:-enforce}"
  local group_mode
  local code_mode

  case "${enabled,,}" in
    0|false|no|off) printf 'ignore'; return 0 ;;
    1|true|yes|on) ;;
    *)
      gate_control_error "BATCH_GATE_ENABLED=${enabled}"
      return 2
      ;;
  esac
  if ! gate_mode_valid "$mode"; then
    gate_control_error "BATCH_GATE_MODE=${mode}"
    return 2
  fi
  group_mode="$(gate_mode_from_map "${BATCH_GATE_GROUP_MODES:-}" "$group")" || return $?
  code_mode="$(gate_mode_from_map "${BATCH_GATE_MODES:-}" "$code")" || return $?
  [[ -z "$group_mode" ]] || mode="$group_mode"
  [[ -z "$code_mode" ]] || mode="$code_mode"
  printf '%s' "$mode"
}

gate_result() {
  local status="$1"
  local code="$2"
  local name="$3"
  local detail="${4:-0}"
  local group="${5:-${BATCH_GATE_GROUP:-general}}"
  local mode="${6:-${BATCH_GATE_MODE:-enforce}}"
  local reason="${7:-}"

  local line
  case "$status" in
    PASS) line="✅ 通过 | code=${code} | gate=${name} | group=${group} | mode=${mode} | exit_code=0 | action=none" ;;
    FAIL)
      line="❌ 不通过 | code=${code} | gate=${name} | group=${group} | mode=${mode} | exit_code=${detail} | action=fix_and_retry"
      ;;
    SKIP)
      mode="conditional"
      line="⏭️ 跳过 | code=${code} | gate=${name} | group=${group} | mode=${mode} | exit_code=0 | action=none | reason=${detail}"
      ;;
    REPORT)
      line="⚠️ 报告 | code=${code} | gate=${name} | group=${group} | mode=report | exit_code=${detail} | action=review_report"
      [[ -z "$reason" ]] || line+=" | reason=${reason}"
      ;;
    IGNORE)
      line="⏭️ 忽略 | code=${code} | gate=${name} | group=${group} | mode=ignore | exit_code=0 | action=none"
      [[ -z "$detail" || "$detail" == 0 ]] || line+=" | reason=${detail}"
      ;;
    *)
      line="❌ 不通过 | code=GATE_RESULT_INVALID_STATUS | gate=${name} | group=${group} | mode=enforce | exit_code=2 | action=fix_and_retry | reason=使用 PASS、FAIL、SKIP、REPORT 或 IGNORE 状态"
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

gate_filter_legacy_status_lines() {
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      '✅ 通过 | code='*|'❌ 不通过 | code='*|'⚠️ 报告 | code='*|'⏭️ 忽略 | code='*|'⏭️ 跳过 | code='*)
        if [[ "$line" != *' | group='* || "$line" != *' | mode='* ]]; then
          continue
        fi
        ;;
    esac
    printf '%s\n' "$line"
  done
}

gate_run() {
  local code="$1"
  local name="$2"
  shift 2
  local group="${BATCH_GATE_GROUP:-general}"
  local mode
  if [[ "$group" == local-pre-commit ]]; then
    group="$(gate_local_group_for_code "$code")"
  fi
  if ! gate_group_valid "$group"; then
    gate_control_error "无效门禁分组: ${group}"
    return 2
  fi
  if (($# == 0)); then
    gate_control_error "${code} 缺少执行命令"
    return 2
  fi
  mode="$(gate_resolve_mode "$code" "$group")" || return $?

  if [[ "$mode" == ignore ]]; then
    gate_result IGNORE "$code" "$name" "统一控制配置未执行该门禁" "$group" "$mode"
    return 0
  fi
  if "$@" > >(gate_filter_legacy_status_lines) 2> >(gate_filter_legacy_status_lines >&2); then
    gate_result PASS "$code" "$name" 0 "$group" "$mode"
  else
    local exit_code=$?
    if [[ "$mode" == report ]]; then
      gate_result REPORT "$code" "$name" "$exit_code" "$group" "$mode"
      return 0
    fi
    gate_result FAIL "$code" "$name" "$exit_code" "$group" "$mode"
    if [[ "${BATCH_GATE_COLLECT:-0}" == "1" ]]; then
      gate_record_failure "$code" "$name" "$exit_code" "$group" "$mode"
      return 0
    fi
    return "$exit_code"
  fi
}

gate_failure_file() {
  printf '%s\n' "${BATCH_GATE_FAILURE_FILE:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/batch-gate-failures.tsv}"
}

gate_record_failure() {
  local failure_file
  failure_file="$(gate_failure_file)"
  mkdir -p "$(dirname "$failure_file")"
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$3" "$2" "${4:-general}" "${5:-enforce}" >>"$failure_file"
}

gate_reset_collected() {
  rm -f "$(gate_failure_file)"
}

gate_assert_collected() {
  local failure_file
  failure_file="$(gate_failure_file)"
  if [[ ! -s "$failure_file" ]]; then
    gate_result PASS GATE_COLLECTION_SUMMARY "门禁失败汇总"
    return 0
  fi

  local count=0
  local code
  local exit_code
  local name
  local group
  local mode
  local summary="## 门禁失败汇总"
  while IFS=$'\t' read -r code exit_code name group mode; do
    ((count += 1))
    printf '❌ %s | code=%s | group=%s | mode=%s | exit_code=%s\n' "$name" "$code" "${group:-general}" "${mode:-enforce}" "$exit_code" >&2
    summary+=$'\n'"- ❌ ${name} (code=${code}, group=${group:-general}, mode=${mode:-enforce}, exit_code=${exit_code})"
  done <"$failure_file"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '%s\n' "$summary" >>"$GITHUB_STEP_SUMMARY"
  fi
  printf '❌ 共 %d 项门禁失败，详见上述各检查日志。\n' "$count" >&2
  gate_result FAIL GATE_COLLECTION_SUMMARY "门禁失败汇总" 1 "${BATCH_GATE_GROUP:-general}" enforce
  return 1
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
