#!/usr/bin/env bash
# 用途：复用本地巡检脚本检查观测栈健康、指标和系统 exporter。
# 用法：在仓库根目录执行 bash scripts/docker/observability/inspect.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# inspect.sh - 复用本地巡检脚本检查观测栈健康、指标和系统 exporter
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

bash "$ROOT/scripts/ops/inspect-observability.sh" "$@"
