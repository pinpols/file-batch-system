#!/usr/bin/env bash
# =========================================================
# inspect.sh - 复用本地巡检脚本检查观测栈健康、指标和系统 exporter
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

bash "$ROOT/scripts/local/inspect-observability.sh" "$@"
