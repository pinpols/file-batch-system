#!/usr/bin/env bash
# load-tests 公共变量。只能被 source。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "load-tests/scripts/env.sh must be sourced, not executed" >&2
  exit 2
fi

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
LOAD_DIR="${LOAD_DIR:-$ROOT_DIR/load-tests}"
# shellcheck source=../../scripts/lib/env-common.sh
source "$ROOT_DIR/scripts/lib/env-common.sh"

export BIZ_DATE="${BIZ_DATE:-2026-05-05}"
export LOAD_TEST_TENANT_ID="${LOAD_TEST_TENANT_ID:-${BATCH_DEFAULT_TENANT_ID:-default-tenant}}"
export MAX_ERROR_PCT="${MAX_ERROR_PCT:-20.0}"
export PIPELINE_POLL_INTERVAL_SEC="${PIPELINE_POLL_INTERVAL_SEC:-2}"
export SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
export CONSOLE_ACCESS_TOKEN="${CONSOLE_ACCESS_TOKEN:-load-test-token}"

# worker 夹具会给 RUN_ID 追加最长的 "-SETTLEMENT" 后缀并写入 varchar(64)。在产生文件或写库前
# 统一校验，避免长时间容量画像最终才以数据库长度错误失败。
LOAD_TEST_RUN_ID_MAX_LENGTH=53

validate_load_test_run_id() {
  local run_id="${1:-}"
  local label="${2:-RUN_ID}"
  if [[ -z "$run_id" ]]; then
    echo "$label must not be empty" >&2
    return 2
  fi
  if (( ${#run_id} > LOAD_TEST_RUN_ID_MAX_LENGTH )); then
    echo "$label is too long: ${#run_id} characters; maximum is ${LOAD_TEST_RUN_ID_MAX_LENGTH} because load-test fixtures append -SETTLEMENT into varchar(64)" >&2
    return 2
  fi
}

load_test_available_kib() {
  local path="${1:-$ROOT_DIR}"
  df -Pk "$path" | awk 'NR == 2 {print $4}'
}

require_load_test_disk_headroom() {
  local label="${1:-load-test}"
  local min_free_gib="${2:-${LOAD_TEST_MIN_FREE_GIB:-5}}"
  if ! [[ "$min_free_gib" =~ ^[1-9][0-9]*$ ]]; then
    echo "LOAD_TEST_MIN_FREE_GIB must be a positive integer, got: ${min_free_gib}" >&2
    return 2
  fi

  local available_kib required_kib
  available_kib="$(load_test_available_kib "$ROOT_DIR")"
  required_kib="$((min_free_gib * 1024 * 1024))"
  if [[ -z "$available_kib" || "$available_kib" -lt "$required_kib" ]]; then
    echo "${label} requires at least ${min_free_gib}GiB free disk; available=$((available_kib / 1024 / 1024))GiB at ${ROOT_DIR}" >&2
    echo "Run: bash load-tests/scripts/cleanup-load-test-environment.sh --diagnose" >&2
    echo "For local-only cleanup after review: bash load-tests/scripts/cleanup-load-test-environment.sh --apply --all" >&2
    return 2
  fi
}
