#!/usr/bin/env bash
# =========================================================
# up-observability.sh - 一键启动本地观测栈
# 说明：
# 1) 透传到底层 observability/up.sh。
# 2) 默认使用 .env.local。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

exec "$ROOT/scripts/docker/observability/up.sh" "$@"

