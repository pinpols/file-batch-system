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
declare -a JAVA_ARGS=()

usage() {
  cat <<'EOF'
Usage:
  bash scripts/ci/security-scan.sh [options] [-- <security-scan args>]

Options:
  --skip-build       Skip mvn package and run the existing jar directly
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

JAR_PATH="$ROOT_DIR/security-scan/target/security-scan-1.0.0-SNAPSHOT.jar"

if [[ "$BUILD_MODULE" == "true" ]]; then
  mvn -f "$ROOT_DIR/security-scan/pom.xml" package
fi

if [[ ! -f "$JAR_PATH" ]]; then
  printf 'security-scan jar not found: %s\n' "$JAR_PATH" >&2
  printf 'Run without --skip-build first, or build the module with mvn -f security-scan/pom.xml package.\n' >&2
  exit 1
fi

java -jar "$JAR_PATH" "${JAVA_ARGS[@]}"
