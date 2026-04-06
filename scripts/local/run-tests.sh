#!/usr/bin/env bash
# =============================================================
# run-tests.sh — 本地一键测试入口
#
# 用法：
#   bash scripts/local/run-tests.sh            # 默认：单元 + 集成（跳过 E2E）
#   bash scripts/local/run-tests.sh --unit     # 仅单元测试（秒级，无容器）
#   bash scripts/local/run-tests.sh --it       # 仅集成测试（需 Docker）
#   bash scripts/local/run-tests.sh --e2e      # 仅 E2E 测试（需 Docker）
#   bash scripts/local/run-tests.sh --all      # 单元 + 集成 + E2E
#   bash scripts/local/run-tests.sh -- -pl batch-orchestrator -am  # 透传 Maven 参数
#
# 环境变量（可覆盖）：
#   TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE  Docker socket 路径（macOS 默认已设置）
#   DOCKER_API_VERSION                     Docker API 版本（默认 1.44）
#   MAVEN_THREADS                          Maven Reactor 并发度；含 Testcontainers 时默认 1，避免多模块并行拉 Kafka 导致失败（可设 1C 自担风险）
# =============================================================

set -uo pipefail  # 移除 'e' 标志：允许测试失败，但继续执行后续步骤

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# ---------- Docker / Testcontainers 环境变量 ----------
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$HOME/.docker/run/docker.sock}"
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

# ---------- 日志路径固化（6类测试） ----------
LOG_DIR="$ROOT_DIR/logs"
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

# ---------- 测试结果追踪（bash 3.x 兼容，不用 declare -A） ----------
TEST_RESULT_LINES=()
TEST_FAILED=0
TEST_PASSED=0

# ---------- 参数解析 ----------
MODE="default"   # default | unit | it | e2e | all
declare -a EXTRA_MVN_ARGS=()

usage() {
  cat <<'EOF'
用法：
  bash scripts/local/run-tests.sh [mode] [-- <extra maven args>]

模式（四选一，默认 --default）：
  --default   单元测试 + 集成测试（跳过 E2E，速度快，日常开发首选）
  --unit      仅单元测试（无需 Docker，秒级反馈）
  --it        仅集成测试（*IntegrationTest，需要 Docker）
  --e2e       仅 E2E 测试（*E2eIT，需要 Docker，最耗时）
  --all       单元 + 集成 + E2E 全量

示例：
  bash scripts/local/run-tests.sh
  bash scripts/local/run-tests.sh --unit
  bash scripts/local/run-tests.sh --it
  bash scripts/local/run-tests.sh --e2e
  bash scripts/local/run-tests.sh --all
  bash scripts/local/run-tests.sh -- -pl batch-orchestrator -am
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --default) MODE="default"; shift ;;
    --unit)    MODE="unit";    shift ;;
    --it)      MODE="it";      shift ;;
    --e2e)     MODE="e2e";     shift ;;
    --all)     MODE="all";     shift ;;
    --help|-h) usage; exit 0 ;;
    --)        shift; EXTRA_MVN_ARGS=("$@"); break ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# ---------- MAVEN_THREADS：单元测试无容器，可并行；其余默认串行避免端口冲突 ----------
if [ "$MODE" = "unit" ]; then
  MAVEN_THREADS="${MAVEN_THREADS:-1C}"
else
  MAVEN_THREADS="${MAVEN_THREADS:-1}"
fi

# ---------- 工具函数 ----------
banner() {
  printf '\n%s\n' "$(printf '=%.0s' {1..64})"
  printf '== %s\n' "$1"
  printf '%s\n\n' "$(printf '=%.0s' {1..64})"
}

run_mvn() {
  local -a cmd=(mvn -T "$MAVEN_THREADS" --no-transfer-progress "$@")
  if (( ${#EXTRA_MVN_ARGS[@]} > 0 )); then
    cmd+=("${EXTRA_MVN_ARGS[@]}")
  fi
  printf '> %s\n\n' "${cmd[*]}"
  "${cmd[@]}"
  local ret=$?
  return $ret
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
  printf '%-40s %s\n' "模块" "状态"
  printf '%s\n' "$(printf '=%.0s' {1..50})"
  for entry in "${TEST_RESULT_LINES[@]}"; do
    local module="${entry%%=*}"
    local status="${entry#*=}"
    printf '%-40s %s\n' "$module" "$status"
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
      printf '%-40s %s\n' "原始日志"       "$LOG_UNIT"
      printf '%-40s %s\n' "✅ 通过"         "$LOG_UNIT_PASSED"
      printf '%-40s %s\n' "❌ 失败"         "$LOG_UNIT_FAILED"
      ;;
    it)
      printf '%-40s %s\n' "原始日志"       "$LOG_IT"
      printf '%-40s %s\n' "✅ 通过"         "$LOG_IT_PASSED"
      printf '%-40s %s\n' "❌ 失败"         "$LOG_IT_FAILED"
      ;;
    e2e)
      printf '%-40s %s\n' "原始日志"       "$LOG_E2E"
      printf '%-40s %s\n' "✅ 通过"         "$LOG_E2E_PASSED"
      printf '%-40s %s\n' "❌ 失败"         "$LOG_E2E_FAILED"
      ;;
    default)
      printf '%-40s %s\n' "原始日志"       "$LOG_DEFAULT"
      printf '%-40s %s\n' "✅ 通过"         "$LOG_DEFAULT_PASSED"
      printf '%-40s %s\n' "❌ 失败"         "$LOG_DEFAULT_FAILED"
      ;;
    all)
      printf '%-40s %s\n' "原始日志（单元+集成）" "$LOG_ALL_UNIT_IT"
      printf '%-40s %s\n' "✅ 通过（单元+集成）"  "$LOG_ALL_UNIT_IT_PASSED"
      printf '%-40s %s\n' "❌ 失败（单元+集成）"  "$LOG_ALL_UNIT_IT_FAILED"
      printf '%-40s %s\n' "原始日志（E2E）"       "$LOG_ALL_E2E"
      printf '%-40s %s\n' "✅ 通过（E2E）"         "$LOG_ALL_E2E_PASSED"
      printf '%-40s %s\n' "❌ 失败（E2E）"         "$LOG_ALL_E2E_FAILED"
      ;;
  esac
  printf '%s\n' "$(printf '=%.0s' {1..80})"
}

extract_test_results() {
  local log_file=$1
  local passed_file=$2
  local failed_file=$3

  printf '' > "$passed_file"
  printf '' > "$failed_file"

  # 提取成功的测试类：Failures: 0, Errors: 0
  echo "=== ✅ 通过的测试类 ===" >> "$passed_file"
  grep "Running " "$log_file" 2>/dev/null | while IFS= read -r line; do
    test_class=$(echo "$line" | awk '{print $NF}')
    # 找到该测试类对应的测试结果行
    result=$(grep -F -A 5 "Running $test_class" "$log_file" 2>/dev/null | grep "Tests run:" | head -1)
    if echo "$result" | grep -q "Failures: 0.*Errors: 0"; then
      echo "✅ $test_class" >> "$passed_file"
    fi
  done

  # 提取失败的测试类：Failures > 0 或 Errors > 0
  echo "=== ❌ 失败的测试类 ===" >> "$failed_file"
  grep "Running " "$log_file" 2>/dev/null | while IFS= read -r line; do
    test_class=$(echo "$line" | awk '{print $NF}')
    # 找到该测试类对应的测试结果行
    result=$(grep -F -A 5 "Running $test_class" "$log_file" 2>/dev/null | grep "Tests run:" | head -1)
    if echo "$result" | grep -qE "Failures: [1-9]|Errors: [1-9]"; then
      echo "❌ $test_class" >> "$failed_file"
    fi
  done

  # 附加错误堆栈信息
  echo "" >> "$failed_file"
  echo "=== 错误详情 ===" >> "$failed_file"
  grep -E -A 10 "\[ERROR\]|\[FAILURE\]" "$log_file" 2>/dev/null >> "$failed_file" || true
}

# ---------- 清空本次模式相关的所有日志（保证每次覆盖，不残留旧数据） ----------
truncate_logs() {
  for f in "$@"; do
    printf '' > "$f"
  done
}

case "$MODE" in
  unit)    truncate_logs "$LOG_UNIT"         "$LOG_UNIT_PASSED"         "$LOG_UNIT_FAILED" ;;
  it)      truncate_logs "$LOG_IT"           "$LOG_IT_PASSED"           "$LOG_IT_FAILED" ;;
  e2e)     truncate_logs "$LOG_E2E"          "$LOG_E2E_PASSED"          "$LOG_E2E_FAILED" ;;
  default) truncate_logs "$LOG_DEFAULT"      "$LOG_DEFAULT_PASSED"      "$LOG_DEFAULT_FAILED" ;;
  all)     truncate_logs "$LOG_ALL_UNIT_IT"  "$LOG_ALL_UNIT_IT_PASSED"  "$LOG_ALL_UNIT_IT_FAILED" \
                         "$LOG_ALL_E2E"      "$LOG_ALL_E2E_PASSED"      "$LOG_ALL_E2E_FAILED" ;;
esac

# ---------- 执行入口 ----------
case "$MODE" in

  unit)
    banner "单元测试"
    {
      run_mvn test \
        -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
        -am \
        -Dtest='!*IntegrationTest,!*IT' \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_UNIT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "UNIT_TESTS" "PASSED"
    else
      record_test_result "UNIT_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_UNIT" "$LOG_UNIT_PASSED" "$LOG_UNIT_FAILED"
    ;;

  it)
    banner "集成测试（*IntegrationTest）"
    {
      run_mvn test \
        -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
        -am \
        -Dtest='*IntegrationTest,*IT' \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_IT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "INTEGRATION_TESTS" "PASSED"
    else
      record_test_result "INTEGRATION_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_IT" "$LOG_IT_PASSED" "$LOG_IT_FAILED"
    ;;

  e2e)
    banner "E2E 测试（*E2eIT）"
    {
      # 先编译依赖（跳过测试），再单独只跑 batch-e2e-tests 避免依赖模块测试混入
      run_mvn install -pl batch-e2e-tests -am -DskipTests --no-transfer-progress && \
      run_mvn test    -pl batch-e2e-tests \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_E2E"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "E2E_TESTS" "PASSED"
    else
      record_test_result "E2E_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_E2E" "$LOG_E2E_PASSED" "$LOG_E2E_FAILED"
    ;;

  default)
    banner "单元 + 集成测试（跳过 E2E）"
    {
      run_mvn test \
        -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
        -am \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_DEFAULT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "DEFAULT_TESTS" "PASSED"
    else
      record_test_result "DEFAULT_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_DEFAULT" "$LOG_DEFAULT_PASSED" "$LOG_DEFAULT_FAILED"
    ;;

  all)
    banner "全量测试：单元 + 集成 + E2E"
    # Step 1：单元 + 集成（排除 E2E 模块）
    {
      run_mvn test \
        -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
        -am \
        -Dsurefire.failIfNoSpecifiedTests=false
    } 2>&1 | tee "$LOG_ALL_UNIT_IT"
    if [ ${PIPESTATUS[0]} -eq 0 ]; then
      record_test_result "UNIT_INTEGRATION_TESTS" "PASSED"
    else
      record_test_result "UNIT_INTEGRATION_TESTS" "FAILED"
    fi
    extract_test_results "$LOG_ALL_UNIT_IT" "$LOG_ALL_UNIT_IT_PASSED" "$LOG_ALL_UNIT_IT_FAILED"

    # Step 2：E2E（独立运行，依赖已在 Step 1 install，直接只测 batch-e2e-tests）
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
    extract_test_results "$LOG_ALL_E2E" "$LOG_ALL_E2E_PASSED" "$LOG_ALL_E2E_FAILED"
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

