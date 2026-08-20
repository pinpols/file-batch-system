#!/usr/bin/env bash
# Shared Python runtime selector for shell scripts.

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/lib/python-runtime.sh must be sourced, not executed" >&2
  exit 2
fi

batch_configure_python_runtime() {
  if [[ -n "${PYTHON_BIN:-}" ]]; then
    export PYTHON_BIN
    return 0
  fi
  if [[ -n "${PYTHON:-}" ]]; then
    export PYTHON_BIN="$PYTHON"
  elif command -v python3 >/dev/null 2>&1; then
    export PYTHON_BIN="python3"
  elif command -v python >/dev/null 2>&1; then
    export PYTHON_BIN="python"
  else
    export PYTHON_BIN="python3"
  fi
}

batch_require_python() {
  batch_configure_python_runtime
  if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "Python 3 is required; set PYTHON_BIN=/path/to/python3 or install python3" >&2
    return 2
  fi
}

batch_configure_python_runtime
