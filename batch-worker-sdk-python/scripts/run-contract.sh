#!/usr/bin/env bash
# Run the SDK contract-fixture suite.
#
# Phase 0: invokes the stub runner that discovers fixtures from
#   docs/api/sdk-contract-fixtures/ and xfails them all.
#
# Phase 1+: same entrypoint, real assertions inside.
#
# Exit codes mirror pytest: 0 = ok (xfails allowed), non-zero = real failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${SDK_DIR}"

echo "[contract] running pytest on tests/contract/ (Phase 0 stub: all xfail)"
exec python -m pytest tests/contract/ -v -rxX --tb=short "$@"
