#!/usr/bin/env bash
# 用途：本地提交检查与 CI 门禁共用的分组、模式控制和结果输出格式。
# 用法：由调用脚本 source scripts/lib/gate-result.sh 后使用公共变量或函数。
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
  gate_result FAIL GATE_MODE_INVALID "门禁模式控制" 2 governance enforce "$1"
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

gate_escape_field() {
  local value="${1:-}"
  value="${value//%/%25}"
  value="${value//|/%7C}"
  value="${value//$'\r'/%0D}"
  value="${value//$'\n'/%0A}"
  value="${value//$'\t'/%09}"
  printf '%s' "$value"
}

gate_decode_field() {
  local value="$2"
  value="${value//%09/$'\t'}"
  value="${value//%0D/$'\r'}"
  value="${value//%0A/$'\n'}"
  value="${value//%7C/|}"
  # 百分号最后还原，保留原文字面量%0A而不进行二次解码。
  value="${value//%25/%}"
  printf -v "$1" '%s' "$value"
}

gate_result() {
  local status="$1" code="$2" name="$3" detail="${4:-0}"
  local group="${5:-${BATCH_GATE_GROUP:-general}}"
  local mode="${6:-${BATCH_GATE_MODE:-enforce}}" reason="${7:-}"
  local label action exit_code=0 result_exit=0 line
  case "$status" in
    PASS) label='✅ 通过'; action=none ;;
    FAIL) label='❌ 不通过'; action=fix_and_retry; exit_code="$detail" ;;
    REPORT) label='⚠️ 报告'; action=review_report; mode=report; exit_code="$detail" ;;
    SKIP)
      label='⏭️ 跳过'; action=none; mode=conditional
      reason="$detail"
      [[ "$reason" != 0 ]] || reason='未满足执行条件'
      ;;
    IGNORE)
      label='⏭️ 忽略'; action=none; mode=ignore
      if [[ -z "$reason" ]]; then
        if [[ "$detail" != 0 ]]; then reason="$detail"; else reason='统一控制配置未执行该门禁'; fi
      fi
      ;;
    *)
      status=FAIL; label='❌ 不通过'; action=fix_and_retry
      code=GATE_RESULT_INVALID_STATUS; mode=enforce; exit_code=2; result_exit=2
      reason='使用 PASS、FAIL、SKIP、REPORT 或 IGNORE 状态'
      ;;
  esac
  # 字段保持单行，不能让诊断中的管道或换行伪造第二条结果。
  line="${label} | code=$(gate_escape_field "$code") | gate=$(gate_escape_field "$name") | group=$(gate_escape_field "$group") | mode=$(gate_escape_field "$mode") | exit_code=$(gate_escape_field "$exit_code") | action=${action}"
  [[ -z "$reason" ]] || line+=" | reason=$(gate_escape_field "$reason")"
  if [[ "$status" == FAIL ]]; then printf '%s\n' "$line" >&2; else printf '%s\n' "$line"; fi
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then printf -- '- %s\n' "$line" >>"$GITHUB_STEP_SUMMARY"; fi
  return "$result_exit"
}

gate_filter_legacy_status_lines() {
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      '✅ 通过 | code='*|'❌ 不通过 | code='*|'⚠️ 报告 | code='*|'⏭️ 忽略 | code='*|'⏭️ 跳过 | code='*)
        if [[ "$line" != *' | group='* || "$line" != *' | mode='* ]]; then
          # 旧失败、报告和跳过仍可能携带唯一原因，作为诊断保留而非权威结果。
          [[ "$line" == '✅ 通过 | code='* ]] || printf '诊断 | %s\n' "$line"
          continue
        fi
        ;;
    esac
    printf '%s\n' "$line"
  done
}

gate_run() {
  if (($# < 2)); then gate_control_error 'gate_run 缺少 CODE 或名称'; return 2; fi
  local code="$1"
  local name="$2"
  shift 2
  local group="${BATCH_GATE_GROUP:-general}"
  local mode
  if (($# > 0)) && [[ "$1" == --group ]]; then
    if (($# < 2)); then gate_control_error '--group 缺少值'; return 2; fi
    group="$2"; shift 2
  fi
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
  local failure_file encoded_code encoded_name encoded_group encoded_mode
  failure_file="$(gate_failure_file)"
  encoded_code="$(gate_escape_field "$1")"
  encoded_name="$(gate_escape_field "$2")"
  encoded_group="$(gate_escape_field "${4:-${BATCH_GATE_GROUP:-general}}")"
  encoded_mode="$(gate_escape_field "${5:-enforce}")"
  mkdir -p "$(dirname "$failure_file")"
  printf '%s\t%s\t%s\t%s\t%s\n' "$encoded_code" "$3" "$encoded_name" "$encoded_group" "$encoded_mode" >>"$failure_file"
}

gate_reset_collected() {
  rm -f "$(gate_failure_file)"
}

gate_assert_collected() {
  local failure_file
  failure_file="$(gate_failure_file)"
  if [[ ! -s "$failure_file" ]]; then
    gate_result PASS GATE_COLLECTION_SUMMARY "门禁失败汇总" 0 "${BATCH_GATE_GROUP:-general}"
    return 0
  fi
  local count=0 code exit_code name group mode
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then printf '### 门禁失败汇总\n\n' >>"$GITHUB_STEP_SUMMARY"; fi
  while IFS=$'\t' read -r code exit_code name group mode; do
    gate_decode_field code "$code"
    gate_decode_field name "$name"
    gate_decode_field group "$group"
    gate_decode_field mode "$mode"
    ((count += 1))
    gate_result FAIL "$code" "$name" "$exit_code" "${group:-general}" "${mode:-enforce}"
  done <"$failure_file"
  gate_result FAIL GATE_COLLECTION_SUMMARY "门禁失败汇总" 1 "${BATCH_GATE_GROUP:-general}" enforce "${count} 项门禁失败"
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
