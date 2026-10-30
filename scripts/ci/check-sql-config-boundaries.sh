#!/usr/bin/env bash
# 用途：SQL 与配置边界。
# 用法：在仓库根目录执行 bash scripts/ci/check-sql-config-boundaries.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec python3 "$ROOT/scripts/ci/check-sql-config-boundaries.py" "$@"
