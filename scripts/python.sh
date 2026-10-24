#!/usr/bin/env bash
# 用途：仓库统一 Python 入口：优先使用 .venv，并校验 Python 3 版本。
# 用法：bash scripts/python.sh <Python 文件或解释器参数>；自动选择仓库 Python 环境。
# 仓库统一 Python 入口：优先使用 .venv，并校验 Python 3 版本。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/python-runtime.sh
source "$ROOT/scripts/lib/python-runtime.sh"
batch_require_python

exec "$PYTHON_BIN" "$@"
