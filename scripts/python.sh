#!/usr/bin/env bash
# 仓库统一 Python 入口：优先使用 .venv，并校验 Python 3 版本。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/python-runtime.sh
source "$ROOT/scripts/lib/python-runtime.sh"
batch_require_python

exec "$PYTHON_BIN" "$@"
