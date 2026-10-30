#!/usr/bin/env bash
# 用途：执行已登记的隔离测试并记录其真实结果。
# 用法：在仓库根目录执行 bash scripts/ci/run-flaky-quarantine.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

python3 scripts/ci/check-flaky-test-governance.py

if ! rg -q '@FlakyTest\s*\(' --glob '**/src/test/**/*.java'; then
  echo "flaky 隔离执行跳过：当前没有隔离测试"
  exit 0
fi

./mvnw -B -Pflaky-quarantine test -DfailIfNoTests=false
