#!/usr/bin/env bash
# =========================================================
# 03-import-tenants.sh:把 ta/tb/tc 的 4 类 worker 配置导入到 console-api
#   走 /api/console/config/tenant-package/excel/upload → /apply/{token}
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CONSOLE_BASE="${CONSOLE_BASE:-http://localhost:18080}"
FIXTURE_DIR="$ROOT/docs/test-data/test-full-coverage-import-suite"

# console-api bypass-mode=true 时不需要 auth header,否则需要 Cookie
AUTH_HEADER=()
[[ -n "${CONSOLE_TOKEN:-}" ]] && AUTH_HEADER=(-H "Cookie: batch_console_token=$CONSOLE_TOKEN")

import_one() {
  local tenant="$1"
  local file="$FIXTURE_DIR/${tenant}-tenant-config-package-test.xlsx"
  [[ -f "$file" ]] || { echo "  ✗ 文件不存在:$file" >&2; return 1; }

  echo "── tenant $tenant"
  # 1) upload
  local upload_resp
  upload_resp=$(curl -sf -X POST \
    -H "X-Tenant-Id: $tenant" "${AUTH_HEADER[@]}" \
    -F "file=@${file}" \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/upload" 2>&1)
  local token
  token=$(echo "$upload_resp" | python3 -c "import json,sys; print(json.load(sys.stdin).get('data',{}).get('uploadToken',''))" 2>/dev/null)
  if [[ -z "$token" ]]; then
    echo "  ✗ upload 失败: $upload_resp" >&2
    return 1
  fi
  echo "  ✓ upload token=$token"

  # 2) apply
  local apply_resp
  apply_resp=$(curl -sf -X POST \
    -H "X-Tenant-Id: $tenant" "${AUTH_HEADER[@]}" \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/apply/$token" 2>&1)
  local code
  code=$(echo "$apply_resp" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code',''))" 2>/dev/null)
  if [[ "$code" == "SUCCESS" || "$code" == "OK" ]]; then
    echo "  ✓ apply OK"
  else
    echo "  ⚠ apply resp: $apply_resp" >&2
  fi
}

echo "==> console-api health"
curl -sf "$CONSOLE_BASE/actuator/health" -o /dev/null && echo "  ✓ UP" || { echo "  ✗ console-api DOWN"; exit 1; }

echo "==> 导入 3 个租户配置(ta / tb / tc)"
for t in ta tb tc; do
  import_one "$t"
done

echo
echo "==> ✅ 导入完成,验证"
docker exec batch-postgres-primary psql -U batch_user -d batch_platform -c \
  "select tenant_id, count(*) jobs from batch.job_definition where tenant_id in ('ta','tb','tc') group by tenant_id" 2>&1 | head -10
