#!/usr/bin/env bash
# 用途：仅判断 Console API 是否已响应 Actuator JSON；依赖 DOWN 时聚合健康端点可返回 HTTP 503。
# 用法：由调用脚本 source scripts/lib/console-api-readiness.sh 后使用公共变量或函数。

# 仅判断 Console API 是否已响应 Actuator JSON；依赖 DOWN 时聚合健康端点可返回 HTTP 503。
console_api_is_ready() {
  local health_url="$1"
  local python_bin="$2"
  local health_body

  health_body=$(curl -sS --max-time 5 --connect-timeout 2 "$health_url" 2>/dev/null) || return 1
  printf '%s' "$health_body" | "$python_bin" -c '
import json
import sys

try:
    payload = json.load(sys.stdin)
except (json.JSONDecodeError, UnicodeDecodeError):
    sys.exit(1)

sys.exit(0 if isinstance(payload, dict) and isinstance(payload.get("status"), str) else 1)
' >/dev/null 2>&1
}
