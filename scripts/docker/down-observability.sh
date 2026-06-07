#!/usr/bin/env bash
# =========================================================
# down-observability.sh - 一键停止本地观测栈
# 说明：
# 1) 透传到底层 observability/down.sh。
# 2) 默认使用 .env.local。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

exec "$ROOT/scripts/docker/observability/down.sh" "$@"

