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
#   MAVEN_THREADS                          Maven 并发线程数（默认 1C）
# =============================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# ---------- Docker / Testcontainers 环境变量 ----------
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$HOME/.docker/run/docker.sock}"
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"
MAVEN_THREADS="${MAVEN_THREADS:-1C}"

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
}

# ---------- 执行入口 ----------
case "$MODE" in

  unit)
    banner "单元测试"
    run_mvn test \
      -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
      -am \
      -Dtest='!*IntegrationTest' \
      -Dsurefire.failIfNoSpecifiedTests=false
    ;;

  it)
    banner "集成测试（*IntegrationTest）"
    run_mvn test \
      -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
      -am \
      -Dtest='*IntegrationTest' \
      -Dsurefire.failIfNoSpecifiedTests=false
    ;;

  e2e)
    banner "E2E 测试（*E2eIT）"
    run_mvn test \
      -pl batch-e2e-tests -am \
      -Dsurefire.failIfNoSpecifiedTests=false
    ;;

  default)
    banner "单元 + 集成测试（跳过 E2E）"
    run_mvn test \
      -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
      -am \
      -Dsurefire.failIfNoSpecifiedTests=false
    ;;

  all)
    banner "全量测试：单元 + 集成 + E2E"
    # Step 1：单元 + 集成（排除 E2E 模块）
    run_mvn test \
      -pl batch-common,batch-trigger,batch-orchestrator,batch-worker-core,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
      -am \
      -Dsurefire.failIfNoSpecifiedTests=false
    # Step 2：E2E（独立运行，避免与其他模块共享 Testcontainers 端口）
    banner "E2E 测试（*E2eIT）"
    run_mvn test \
      -pl batch-e2e-tests -am \
      -Dsurefire.failIfNoSpecifiedTests=false
    ;;

esac

printf '\n%s\n' "$(printf '=%.0s' {1..64})"
printf '== ALL TESTS PASSED  [mode=%s]\n' "$MODE"
printf '%s\n' "$(printf '=%.0s' {1..64})"
