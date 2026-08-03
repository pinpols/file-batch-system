#!/usr/bin/env bash
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
