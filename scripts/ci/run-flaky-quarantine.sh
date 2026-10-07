#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

python3 scripts/ci/check-flaky-test-governance.py

if ! rg -q '@FlakyTest\s*\(' --glob '**/src/test/**/*.java'; then
  echo "flaky 隔离执行跳过：当前没有隔离测试"
  exit 0
fi

./mvnw -B -Pflaky-quarantine test -DfailIfNoTests=false
