#!/usr/bin/env bash
# 用途：可重复运行 ta/tb/tc 覆盖初始化、真实执行与限定清理。
# 用法：bash scripts/local/coverage-harness.sh all --operator admin --allow-local-bypass
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
batch_configure_local_jvm_database_env
batch_require_python
exec "$PYTHON_BIN" "$ROOT/scripts/local/coverage-harness.py" "$@"
