#!/usr/bin/env bash
# 运行 SDK 契约 fixture 测试套件。
#
# Phase 0：调用桩 runner，从 docs/api/sdk-contract-fixtures/ 发现 fixture
#   并将其全部标记为 xfail。
#
# Phase 1+：入口相同，内部是真实断言。
#
# 退出码与 pytest 一致：0 = 通过（允许 xfail），非 0 = 真实失败。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${SDK_DIR}"

echo "[contract] running pytest on tests/contract/ (Phase 0 stub: all xfail)"
exec python -m pytest tests/contract/ -v -rxX --tb=short "$@"
