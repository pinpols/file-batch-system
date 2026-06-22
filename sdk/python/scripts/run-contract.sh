#!/usr/bin/env bash
# 运行 SDK 契约 fixture 测试套件(硬校验)。
#
# 从 docs/api/sdk-contract-fixtures/ 发现 fixture,对 python 决策核做真实断言。
# 已转硬(2026-06-22):任一 fixture 不符 → 真实失败。pyproject 设 xfail_strict=true,
# 残留 xfail 标记若实际通过(xpass)也判失败,杜绝静默回归。
#
# 退出码与 pytest 一致:0 = 全过,非 0 = 失败。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${SDK_DIR}"

echo "[contract] running pytest on tests/contract/ (enforcing)"
exec python -m pytest tests/contract/ -v -rxX --tb=short "$@"
