#!/usr/bin/env bash
# 用途：报告模块之间的限界上下文依赖。
# 用法：在仓库根目录执行 bash scripts/ci/report-bounded-context-dependencies.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPORT_FILE="${1:-${ROOT_DIR}/target/bounded-context/dependencies.tsv}"

mkdir -p "$(dirname "${REPORT_FILE}")"

"${ROOT_DIR}/mvnw" \
  -pl batch-console-api \
  -Dtest=BoundedContextMigrationProgressTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DboundedContext.report="${REPORT_FILE}" \
  test

printf 'Bounded-context inventory: %s\n' "${REPORT_FILE}"
