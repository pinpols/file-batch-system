#!/usr/bin/env bash
# =========================================================
# 03-import-tenants.sh:把 ta/tb/tc 的 4 类 worker 配置导入到 console-api
#   走 /api/console/config/tenant-package/excel/upload → /apply/{token}
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"

FIXTURE_DIR="$ROOT/docs/test-data/test-full-coverage-import-suite"

# console-api bypass-mode=true 时不需要 auth header,否则需要 Cookie
AUTH_HEADER=()
[[ -n "${CONSOLE_TOKEN:-}" ]] && AUTH_HEADER=(-H "Cookie: batch_console_token=$CONSOLE_TOKEN")
COOKIE_JAR=""
CURL_AUTH_ARGS=("${AUTH_HEADER[@]}")

ensure_console_auth() {
  if [[ -n "${CONSOLE_TOKEN:-}" ]]; then
    return
  fi
  local probe_code
  probe_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 --connect-timeout 5 \
    -H "X-Tenant-Id: ta" "$CONSOLE_BASE/api/console/auth/check" || true)
  if [[ "$probe_code" == "204" || "$probe_code" == "200" ]]; then
    return
  fi
  COOKIE_JAR="$(mktemp -t sim-console-cookie.XXXXXX)"
  local user="${CONSOLE_USER:-admin}"
  local pass="${CONSOLE_PASS:-admin123}"
  local code
  code=$(curl -sS -o /tmp/sim-console-login.json -w "%{http_code}" \
    -c "$COOKIE_JAR" \
    -H "Content-Type: application/json" \
    -X POST "$CONSOLE_BASE/api/console/auth/login" \
    --data-raw "{\"username\":\"$user\",\"password\":\"$pass\"}" || true)
  if [[ "$code" != "200" ]]; then
    echo "  ✗ console login failed HTTP=$code: $(head -c 300 /tmp/sim-console-login.json 2>/dev/null)" >&2
    exit 1
  fi
  CURL_AUTH_ARGS=(-b "$COOKIE_JAR")
}

import_one() {
  local tenant="$1"
  local file="$FIXTURE_DIR/${tenant}-tenant-config-package-test.xlsx"
  [[ -f "$file" ]] || { echo "  ✗ 文件不存在:$file" >&2; return 1; }

  echo "── tenant $tenant"
  # 1) upload
  local upload_resp upload_body upload_code
  upload_body="$(mktemp -t sim-tenant-upload.XXXXXX)"
  local upload_key="sim-${tenant}-tenant-package-upload-$(date +%s%N)"
  upload_code=$(curl -sS --max-time 90 --connect-timeout 5 -o "$upload_body" -w "%{http_code}" -X POST \
    -H "X-Tenant-Id: $tenant" "${CURL_AUTH_ARGS[@]}" \
    -H "Idempotency-Key: $upload_key" \
    -H "X-Request-Id: $upload_key" \
    -F "file=@${file}" \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/upload?tenantId=$tenant" 2>&1 || true)
  upload_resp="$(cat "$upload_body" 2>/dev/null || true)"
  rm -f "$upload_body"
  if [[ ! "$upload_code" =~ ^2 ]]; then
    echo "  ✗ upload 失败 HTTP=$upload_code: $upload_resp" >&2
    return 1
  fi
  local token
  token=$(echo "$upload_resp" | python3 -c "import json,sys; print(json.load(sys.stdin).get('data',{}).get('uploadToken',''))" 2>/dev/null)
  if [[ -z "$token" ]]; then
    echo "  ✗ upload 失败: $upload_resp" >&2
    return 1
  fi
  echo "  ✓ upload token=$token"

  # 2) apply
  local apply_resp apply_body apply_code
  apply_body="$(mktemp -t sim-tenant-apply.XXXXXX)"
  local apply_key="sim-${tenant}-tenant-package-apply-$(date +%s%N)"
  apply_code=$(curl -sS --max-time 180 --connect-timeout 5 -o "$apply_body" -w "%{http_code}" -X POST \
    -H "X-Tenant-Id: $tenant" "${CURL_AUTH_ARGS[@]}" \
    -H "Idempotency-Key: $apply_key" \
    -H "X-Request-Id: $apply_key" \
    -H "Content-Type: application/json" \
    -d '{}' \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/apply/$token" 2>&1 || true)
  apply_resp="$(cat "$apply_body" 2>/dev/null || true)"
  rm -f "$apply_body"
  if [[ ! "$apply_code" =~ ^2 ]]; then
    echo "  ✗ apply 失败 HTTP=$apply_code: $apply_resp" >&2
    return 1
  fi
  local code
  code=$(echo "$apply_resp" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code',''))" 2>/dev/null)
  if [[ "$code" == "SUCCESS" || "$code" == "OK" ]]; then
    echo "  ✓ apply OK"
  else
    echo "  ✗ apply 失败: $apply_resp" >&2
    return 1
  fi
}

echo "==> console-api health"
curl -sf --max-time 30 --connect-timeout 5 "$CONSOLE_BASE/actuator/health" -o /dev/null && echo "  ✓ UP" || { echo "  ✗ console-api DOWN"; exit 1; }
ensure_console_auth

echo "==> 导入 3 个租户配置(ta / tb / tc)"
for t in ta tb tc; do
  import_one "$t"
done

echo
echo "==> 应用 sim-e2e bootstrap(runtime config refresh)"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -v ON_ERROR_STOP=1 \
  -f /dev/stdin < "$ROOT/docs/test-data/sim-e2e-bootstrap.sql" >/dev/null
echo "  ✓ bootstrap OK"

echo
echo "==> ✅ 导入完成,验证"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -f /dev/stdin < "$ROOT/docs/test-data/sim-import-tenants-verify.sql" 2>&1 | head -10
