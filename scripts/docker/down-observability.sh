#!/usr/bin/env bash
# 用途：统一停止本地观测栈。
# 用法：在仓库根目录执行 bash scripts/docker/down-observability.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# down-observability.sh - 统一停止本地观测栈
# 说明：
# 1) 透传到底层 observability/down.sh。
# 2) 默认使用 .env.local。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

exec "$ROOT/scripts/docker/observability/down.sh" "$@"
