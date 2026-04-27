#!/usr/bin/env bash
# =============================================================
# run-tests.sh — 本地一键测试入口
#
# 用法：
#   bash scripts/local/run-tests.sh               # 默认：单元 + 集成（跳过 E2E）
#   bash scripts/local/run-tests.sh --unit        # 仅单元测试（秒级，无容器）
#   bash scripts/local/run-tests.sh --it          # 仅集成测试（需 Docker）
#   bash scripts/local/run-tests.sh --e2e         # 仅 E2E 测试（需 Docker）
#   bash scripts/local/run-tests.sh --all         # 单元 + 集成 + E2E（串行）
#   bash scripts/local/run-tests.sh --build-only  # 仅构建（clean install -DskipTests），不跑任何测试
#   bash scripts/local/run-tests.sh --unit --skip-build  # 跳过构建，直接跑测试（并行场景专用）
#   bash scripts/local/run-tests.sh -- -pl batch-orchestrator -am  # 透传 Maven 参数
#
# 并行执行三类测试（推荐用 make test-parallel）：
#   bash scripts/local/run-tests.sh --build-only           # 第一步：构建一次
#   bash scripts/local/run-tests.sh --unit --skip-build &  # 第二步：并发执行
#   bash scripts/local/run-tests.sh --it   --skip-build &
#   bash scripts/local/run-tests.sh --e2e  --skip-build &
#   wait
#
# --skip-build 会同时跳过 cleanup_test_reports，避免并发时互删报告目录。
#
# 环境变量（可覆盖）：
#   TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE  Docker socket 路径（macOS 默认已设置）
#   DOCKER_API_VERSION                     Docker API 版本（默认 1.44）
#   MAVEN_THREADS                          Maven Reactor 并发度；含 Testcontainers 时默认 1
# =============================================================

set -uo pipefail

SKIP_BUILD=false

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$HOME/.docker/run/docker.sock}"
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

LOG_DIR="$ROOT_DIR/logs/test"
mkdir -p "$LOG_DIR"

LOG_UNIT="$LOG_DIR/test-unit.log"
LOG_IT="$LOG_DIR/test-integration.log"
LOG_E2E="$LOG_DIR/test-e2e.log"
LOG_DEFAULT="$LOG_DIR/test-default.log"
LOG_ALL_UNIT_IT="$LOG_DIR/test-all-unit-integration.log"
LOG_ALL_E2E="$LOG_DIR/test-all-e2e.log"

LOG_UNIT_PASSED="$LOG_DIR/test-unit-passed.log"
LOG_UNIT_FAILED="$LOG_DIR/test-unit-failed.log"
LOG_IT_PASSED="$LOG_DIR/test-integration-passed.log"
LOG_IT_FAILED="$LOG_DIR/test-integration-failed.log"
LOG_E2E_PASSED="$LOG_DIR/test-e2e-passed.log"
LOG_E2E_FAILED="$LOG_DIR/test-e2e-failed.log"
LOG_DEFAULT_PASSED="$LOG_DIR/test-default-passed.log"
LOG_DEFAULT_FAILED="$LOG_DIR/test-default-failed.log"
LOG_ALL_UNIT_IT_PASSED="$LOG_DIR/test-all-unit-integration-passed.log"
LOG_ALL_UNIT_IT_FAILED="$LOG_DIR/test-all-unit-integration-failed.log"
LOG_ALL_E2E_PASSED="$LOG_DIR/test-all-e2e-passed.log"
LOG_ALL_E2E_FAILED="$LOG_DIR/test-all-e2e-failed.log"

TEST_RESULT_LINES=()
TEST_FAILED=0
TEST_PASSED=0

MODE="default"
declare -a EXTRA_MVN_ARGS=()
declare -a CORE_TEST_MODULES=(
  batch-common
  batch-trigger
  batch-orchestrator
  batch-worker-core
  batch-worker-import
  batch-worker-export
  batch-worker-process
  batch-worker-dispatch
  batch-console-api
)

usage() {
  cat <<'EOF'
用法：
  bash scripts/local/run-tests.sh [mode] [--skip-build] [-- <extra maven args>]

模式：
  --default     单元测试 + 集成测试（跳过 E2E）
  --unit        仅单元测试
  --it          仅集成测试
  --e2e         仅 E2E 测试
  --all         单元 + 集成 + E2E（串行）
  --build-only  仅构建核心模块，不跑任何测试

选项：
  --skip-build  跳过 clean install 和 cleanup_test_reports（并行场景专用）

示例：
  bash scripts/local/run-tests.sh
  bash scripts/local/run-tests.sh --unit
  bash scripts/local/run-tests.sh --it
  bash scripts/local/run-tests.sh --e2e
  bash scripts/local/run-tests.sh --all
  bash scripts/local/run-tests.sh --build-only
  bash scripts/local/run-tests.sh --unit --skip-build
  bash scripts/local/run-tests.sh -- -pl batch-orchestrator -am

并行执行（推荐 make test-parallel）：
  bash scripts/local/run-tests.sh --build-only
  bash scripts/local/run-tests.sh --unit --skip-build &
  bash scripts/local/run-tests.sh --it   --skip-build &
  bash scripts/local/run-tests.sh --e2e  --skip-build &
  wait
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --default)     MODE="default"; shift ;;
    --unit)        MODE="unit"; shift ;;
    --it)          MODE="it"; shift ;;
    --e2e)         MODE="e2e"; shift ;;
    --all)         MODE="all"; shift ;;
    --build-only)  MODE="build-only"; shift ;;
    --skip-build)  SKIP_BUILD=true; shift ;;
    --help|-h) usage; exit 0 ;;
    --)        shift; EXTRA_MVN_ARGS=("$@"); break ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ "$MODE" = "unit" ]; then
  MAVEN_THREADS="${MAVEN_THREADS:-1}"
else
  MAVEN_THREADS="${MAVEN_THREADS:-1}"
fi

banner() {
  printf '\n%s\n' "$(printf '=%.0s' {1..64})"
  printf '== %s\n' "$1"
  printf '%s\n\n' "$(printf '=%.0s' {1..64})"
}

run_mvn() {
  local mvn_bin
  local _mvnd_bin="${HOME}/.local/bin/mvnd"
  if [[ -x "$_mvnd_bin" ]]; then
    export MVND_HOME="${HOME}/.local/share/maven-mvnd-1.0.5-darwin-aarch64"
    mvn_bin="$_mvnd_bin"
  else
    mvn_bin=$(command -v mvnd 2>/dev/null || command -v mvn)
  fi
  local -a cmd=("$mvn_bin" -T "$MAVEN_THREADS" --no-transfer-progress "$@")
  if (( ${#EXTRA_MVN_ARGS[@]} > 0 )); then
    cmd+=("${EXTRA_MVN_ARGS[@]}")
  fi
  printf -- '> %s\n\n' "${cmd[*]}"
  "${cmd[@]}"
  local ret=$?
  return $ret
}

run_module_tests() {
  local test_selector=${1-}
  shift || true

  local overall=0
  local module
  for module in "$@"; do
    printf -- '\n------------------------------------------------------------\n'
    printf -- '== 测试模块: %s\n' "$module"
    printf -- '------------------------------------------------------------\n\n'

    local -a args=(test -pl "$module" -Dsurefire.failIfNoSpecifiedTests=false)
    if [[ -n "$test_selector" ]]; then
      args+=("-Dtest=$test_selector")
    fi
    # --skip-build 时各 mode 并行运行，跳过编译避免多进程同时写 target/test-classes/
    if $SKIP_BUILD; then
      args+=("-Dmaven.compiler.skip=true")
    fi

    if ! run_mvn "${args[@]}"; then
      overall=1
    fi
  done

  return $overall
}

record_test_result() {
  local module=$1
  local status=$2
  TEST_RESULT_LINES+=("$module=$status")
  if [ "$status" = "PASSED" ]; then
    TEST_PASSED=$((TEST_PASSED + 1))
  else
    TEST_FAILED=$((TEST_FAILED + 1))
  fi
}

print_test_summary() {
  banner "测试执行总结"
  printf -- '%-40s %s\n' "模块" "状态"
  printf -- '%s\n' "$(printf '=%.0s' {1..50})"
  for entry in "${TEST_RESULT_LINES[@]}"; do
    local module="${entry%%=*}"
    local status="${entry#*=}"
    printf -- '%-40s %s\n' "$module" "$status"
  done
  printf '%s\n' "$(printf '=%.0s' {1..50})"
  printf 'PASSED: %d  |  FAILED: %d\n' "$TEST_PASSED" "$TEST_FAILED"
}

print_log_paths() {
  banner "日志文件位置 [mode=${MODE}]"
  printf '%-40s %s\n' "测试详情" "日志路径"
  printf '%s\n' "$(printf '=%.0s' {1..80})"
  case "$MODE" in
    unit)
      printf '%-40s %s\n' "原始日志" "$LOG_UNIT"
      printf '%-40s %s\n' "✅ 通过" "$LOG_UNIT_PASSED"
      printf '%-40s %s\n' "❌ 失败" "$LOG_UNIT_FAILED"
      ;;
    it)
      printf '%-40s %s\n' "原始日志" "$LOG_IT"
      printf '%-40s %s\n' "✅ 通过" "$LOG_IT_PASSED"
      printf '%-40s %s\n' "❌ 失败" "$LOG_IT_FAILED"
      ;;
    e2e)
      printf '%-40s %s\n' "原始日志" "$LOG_E2E"
      printf '%-40s %s\n' "✅ 通过" "$LOG_E2E_PASSED"
      printf '%-40s %s\n' "❌ 失败" "$LOG_E2E_FAILED"
      ;;
    default)
      printf '%-40s %s\n' "原始日志" "$LOG_DEFAULT"
      printf '%-40s %s\n' "✅ 通过" "$LOG_DEFAULT_PASSED"
      printf '%-40s %s\n' "❌ 失败" "$LOG_DEFAULT_FAILED"
      ;;
    all)
      printf '%-40s %s\n' "原始日志（单元+集成）" "$LOG_ALL_UNIT_IT"
      printf '%-40s %s\n' "✅ 通过（单元+集成）" "$LOG_ALL_UNIT_IT_PASSED"
      printf '%-40s %s\n' "❌ 失败（单元+集成）" "$LOG_ALL_UNIT_IT_FAILED"
      printf '%-40s %s\n' "原始日志（E2E）" "$LOG_ALL_E2E"
      printf '%-40s %s\n' "✅ 通过（E2E）" "$LOG_ALL_E2E_PASSED"
      printf '%-40s %s\n' "❌ 失败（E2E）" "$LOG_ALL_E2E_FAILED"
      ;;
  esac
  printf '%s\n' "$(printf '=%.0s' {1..80})"
}

truncate_logs() {
  for f in "$@"; do
    printf '' > "$f"
  done
}

cleanup_test_reports() {
  find "$ROOT_DIR" -type d \( -path "*/target/surefire-reports" -o -path "*/target/failsafe-reports" \) -prune -exec rm -rf {} + 2>/dev/null || true
}

# extract_test_results <log> <passed> <failed> [scan_dir...]
# scan_dir 默认为 ROOT_DIR；并行执行时传入各 mode 对应的模块目录，避免扫到其他模式的报告
extract_test_results() {
  local log_file=$1
  local passed_file=$2
  local failed_file=$3
  shift 3
  local -a scan_dirs=("$@")
  if (( ${#scan_dirs[@]} == 0 )); then
    scan_dirs=("$ROOT_DIR")
  fi

  printf '' > "$passed_file"
  printf '' > "$failed_file"

  echo "=== ✅ 通过的测试类 ===" >> "$passed_file"
  echo "=== ❌ 失败的测试类 ===" >> "$failed_file"

  local found_any=0

  while IFS= read -r xml; do
    found_any=1

    local class_name
    local failures
    local errors

    class_name=$(basename "$xml" .xml)
    class_name=${class_name#TEST-}

    failures=$(grep -o 'failures="[0-9]*"' "$xml" | head -1 | sed 's/[^0-9]//g')
    errors=$(grep -o 'errors="[0-9]*"' "$xml" | head -1 | sed 's/[^0-9]//g')

    failures=${failures:-0}
    errors=${errors:-0}

    if [ "$failures" -eq 0 ] && [ "$errors" -eq 0 ]; then
      echo "✅ $class_name" >> "$passed_file"
    else
      echo "❌ $class_name" >> "$failed_file"
    fi
  done < <(find "${scan_dirs[@]}" \( -path "*/target/surefire-reports/TEST-*.xml" -o -path "*/target/failsafe-reports/TEST-*.xml" \) | sort)

  if [ "$found_any" -eq 0 ]; then
    echo "未找到测试报告文件" >> "$failed_file"
  fi

  echo "" >> "$failed_file"
  echo "=== 错误详情 ===" >> "$failed_file"
  grep -E -A 10 "\[ERROR\]|\[FAILURE\]" "$log_file" 2>/dev/null >> "$failed_file" || true
}

# 在调用处内联：
#   _core_dirs=(); for _m in "${CORE_TEST_MODULES[@]}"; do _core_dirs+=("$ROOT_DIR/$_m"); done
# 不封装为函数——bash 3.2 的 eval 在函数内只创建局部变量，无法写入调用方作用域

case "$MODE" in
  unit)       truncate_logs "$LOG_UNIT"         "$LOG_UNIT_PASSED"         "$LOG_UNIT_FAILED" ;;
  it)         truncate_logs "$LOG_IT"           "$LOG_IT_PASSED"           "$LOG_IT_FAILED" ;;
  e2e)        truncate_logs "$LOG_E2E"          "$LOG_E2E_PASSED"          "$LOG_E2E_FAILED" ;;
  default)    truncate_logs "$LOG_DEFAULT"      "$LOG_DEFAULT_PASSED"      "$LOG_DEFAULT_FAILED" ;;
  build-only) ;;
  all)        truncate_logs "$LOG_ALL_UNIT_IT"  "$LOG_ALL_UNIT_IT_PASSED"  "$LOG_ALL_UNIT_IT_FAILED" \
                            "$LOG_ALL_E2E"      "$LOG_ALL_E2E_PASSED"      "$LOG_ALL_E2E_FAILED" ;;
esac

if ! $SKIP_BUILD; then
  cleanup_test_reports
fi

# -------------------------------------------------------------
# mvnd / 多模块测试说明
# -------------------------------------------------------------
# 在单次 reactor 中直接跑所有核心模块的 test，历史上出现过：
#   - testCompile 读取到不一致的跨模块产物
#   - 某一个模块失败后，后续模块被连带污染
#   - 单独执行能通过，但聚合执行时误报失败
#
# 当前策略：
#   1. 先对核心模块执行一次 clean install -DskipTests
#   2. 再按模块逐个执行 test，避免把所有模块绑在同一个 test reactor 里
#
# 这样做的目标不是“掩盖真实失败”，而是把模块隔离开，确保失败定位到
# 真实的模块自身，而不是 Maven / mvnd 的聚合执行副作用。
# -------------------------------------------------------------

build_core_modules() {
  run_mvn clean install \
    -pl "$(IFS=,; echo "${CORE_TEST_MODULES[*]}")" \
    -DskipTests
}

# 仅在未设置 --skip-build 时执行构建；已构建时直接返回 0
maybe_build() {
  if $SKIP_BUILD; then
    return 0
  fi
  build_core_modules
}

case "$MODE" in
  build-only)
    banner "构建所有核心模块（跳过测试）"
    build_core_modules
    exit $?
    ;;

  unit)
    banner "单元测试"
    {
      maybe_build && \
      run_module_tests '!*IntegrationTest,!*IT,!PartitionLeaseReclaimSchedulerTest' \
        "${CORE_TEST_MODULES[@]}"
    } 2>&1 | tee "$LOG_UNIT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "UNIT_TESTS" "PASSED"
    else
      record_test_result "UNIT_TESTS" "FAILED"
    fi
    _core_dirs=(); for _m in "${CORE_TEST_MODULES[@]}"; do _core_dirs+=("$ROOT_DIR/$_m"); done
    extract_test_results "$LOG_UNIT" "$LOG_UNIT_PASSED" "$LOG_UNIT_FAILED" "${_core_dirs[@]}"
    ;;

  it)
    banner "集成测试（*IntegrationTest / *IT）"
    {
      maybe_build && \
      run_module_tests '*IntegrationTest,*IT' \
        "${CORE_TEST_MODULES[@]}"
    } 2>&1 | tee "$LOG_IT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "INTEGRATION_TESTS" "PASSED"
    else
      record_test_result "INTEGRATION_TESTS" "FAILED"
    fi
    _core_dirs=(); for _m in "${CORE_TEST_MODULES[@]}"; do _core_dirs+=("$ROOT_DIR/$_m"); done
    extract_test_results "$LOG_IT" "$LOG_IT_PASSED" "$LOG_IT_FAILED" "${_core_dirs[@]}"
    ;;

  e2e)
    banner "E2E 测试（*E2eIT）"
    {
      maybe_build && \
      run_mvn test -pl batch-e2e-tests \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_E2E"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "E2E_TESTS" "PASSED"
    else
      record_test_result "E2E_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_E2E" "$LOG_E2E_PASSED" "$LOG_E2E_FAILED" "$ROOT_DIR/batch-e2e-tests"
    ;;

  default)
    banner "单元 + 集成测试（跳过 E2E）"
    {
      maybe_build && \
      run_module_tests "" "${CORE_TEST_MODULES[@]}"
    } 2>&1 | tee "$LOG_DEFAULT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "DEFAULT_TESTS" "PASSED"
    else
      record_test_result "DEFAULT_TESTS" "FAILED"
    fi
    _core_dirs=(); for _m in "${CORE_TEST_MODULES[@]}"; do _core_dirs+=("$ROOT_DIR/$_m"); done
    extract_test_results "$LOG_DEFAULT" "$LOG_DEFAULT_PASSED" "$LOG_DEFAULT_FAILED" "${_core_dirs[@]}"
    ;;

  all)
    banner "全量测试：单元 + 集成 + E2E"

    {
      maybe_build && \
      run_module_tests "" "${CORE_TEST_MODULES[@]}"
    } 2>&1 | tee "$LOG_ALL_UNIT_IT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "UNIT_INTEGRATION_TESTS" "PASSED"
    else
      record_test_result "UNIT_INTEGRATION_TESTS" "FAILED"
    fi
    _core_dirs=(); for _m in "${CORE_TEST_MODULES[@]}"; do _core_dirs+=("$ROOT_DIR/$_m"); done
    extract_test_results "$LOG_ALL_UNIT_IT" "$LOG_ALL_UNIT_IT_PASSED" "$LOG_ALL_UNIT_IT_FAILED" "${_core_dirs[@]}"

    banner "E2E 测试（*E2eIT）"
    {
      run_mvn test -pl batch-e2e-tests \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_ALL_E2E"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "E2E_TESTS" "PASSED"
    else
      record_test_result "E2E_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_ALL_E2E" "$LOG_ALL_E2E_PASSED" "$LOG_ALL_E2E_FAILED" "$ROOT_DIR/batch-e2e-tests"
    ;;
esac

print_test_summary
print_log_paths

printf '\n%s\n' "$(printf '=%.0s' {1..64})"
if [ $TEST_FAILED -eq 0 ]; then
  printf '== ✅ ALL TESTS PASSED [mode=%s]\n' "$MODE"
else
  printf '== ❌ TESTS FAILED: %d failed, %d passed [mode=%s]\n' "$TEST_FAILED" "$TEST_PASSED" "$MODE"
fi
printf '%s\n' "$(printf '=%.0s' {1..64})"

exit $([ $TEST_FAILED -eq 0 ] && echo 0 || echo 1)
