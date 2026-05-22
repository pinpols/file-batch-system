#!/usr/bin/env bash
# =========================================================
# security-scan.sh - 本地 / CI 安全扫描一键入口
# Notes:
# 1) 先打包 security-scan 独立 Java 模块，再运行其 jar。
# 2) 该脚本只做编排，不实现扫描逻辑。
# 3) 脚本参数会透传给 Java 模块，支持 --mode / --root / --target-url 等。
# =========================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BUILD_MODULE=true
SKIP_TESTS=false
declare -a JAVA_ARGS=()

usage() {
  cat <<'EOF'
Usage:
  bash scripts/ci/security-scan.sh [options] [-- <security-scan args>]

Options:
  --skip-build       Skip mvn package and run the existing jar directly
  --skip-tests       Pass -DskipTests to mvn package (security-scan 子模块单测
                     纯内部 orchestrator/options 逻辑,与扫描业务正确性无关;
                     Java 25 + mockito javaagent 偶发 fork JVM crash 会让 PR 误挂)
  --help             Show this message

Examples:
  bash scripts/ci/security-scan.sh
  bash scripts/ci/security-scan.sh --mode=secret
  bash scripts/ci/security-scan.sh --mode=deps --root=.
  bash scripts/ci/security-scan.sh --mode=all --target-url=http://localhost:8080
  bash scripts/ci/security-scan.sh --skip-build -- --mode=dast --target-url=http://localhost:8080
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-build)
        BUILD_MODULE=false
        shift
        ;;
      --skip-tests)
        SKIP_TESTS=true
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      --)
        shift
        while [[ $# -gt 0 ]]; do
          JAVA_ARGS+=("$1")
          shift
        done
        ;;
      *)
        JAVA_ARGS+=("$1")
        shift
        ;;
    esac
  done
}

parse_args "$@"

mkdir -p "$ROOT_DIR/target/security-scan-report"

if [[ "$BUILD_MODULE" == "true" ]]; then
  mvn_skip_flag=()
  [[ "$SKIP_TESTS" == "true" ]] && mvn_skip_flag+=("-DskipTests")
  mvn -f "$ROOT_DIR/security-scan/pom.xml" package "${mvn_skip_flag[@]}"
fi

# 通过 glob 解析实际产物，避开硬编码版本号；${revision} 改动或 release 升版后无需同步此处。
JAR_PATH="$(ls -1 "$ROOT_DIR/security-scan/target/security-scan-"*.jar 2>/dev/null \
  | grep -Ev 'sources|javadoc|original' | head -n 1 || true)"

if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  printf 'security-scan jar not found under %s\n' "$ROOT_DIR/security-scan/target/" >&2
  printf 'Run without --skip-build first, or build the module with mvn -f security-scan/pom.xml package.\n' >&2
  exit 1
fi

java -jar "$JAR_PATH" "${JAVA_ARGS[@]}"
