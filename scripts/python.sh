#!/usr/bin/env bash
# Repository Python entrypoint. Selects .venv first and validates Python 3.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/python-runtime.sh
source "$ROOT/scripts/lib/python-runtime.sh"
batch_require_python

exec "$PYTHON_BIN" "$@"
