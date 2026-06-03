#!/usr/bin/env bash
# =============================================================
# seed-test-role-users.sh
#
# 给本地 console-api 种入 FE e2e RBAC matrix 期望的 3 个角色用户。
#
# 用例:rbac-matrix.spec.ts 与 e2e/global-setup.cjs 期望以下角色 storage state
#   - op-tx     ROLE_TENANT_USER  tenant=tx   pw=admin123
#   - tadmin-ta ROLE_TENANT_ADMIN tenant=ta   pw=Admin@123abc
#   - user-tx   ROLE_USER         tenant=tx   pw=admin123
# 缺这 3 个 -> login 401 -> rbac-matrix 50 spec 假阳性失败。
#
# 用法:
#   bash scripts/local/seed-test-role-users.sh
#
# 环境变量:
#   CONSOLE_BASE_URL  默认 http://localhost:18080
#   ADMIN_USERNAME    默认 admin
#   ADMIN_PASSWORD    默认 admin123(V52 seed)
#   TENANT_TX         默认 tx(可改 tc 用 DEV_FIXTURE 白名单避开 NON-PROD prefix guard)
#   TENANT_TA         默认 ta(DEV_FIXTURE 白名单)
#
# 行为:
#   1. 用 admin 走 /api/console/auth/login(明文)拿 HttpOnly cookie
#   2. POST /api/console/tenants 建 TENANT_TX 与 TENANT_TA(已存在 409 -> skip)
#      —— BE NON-PROD ReservedPrefixGuard 要求 tenant_id 在 DEV_FIXTURE
#         (ta/tb/tc/default-tenant)或带 test- prefix;否则 INVALID_ARGUMENT。
#         默认 TENANT_TX=tx 不在白名单,**会失败**;脚本将引导用户切换 tc 或改 guard。
#   3. POST /api/console/users 建 op-tx / tadmin-ta / user-tx(409 -> skip)
#   4. 逐用户验证可 login(明文 path)。任何失败 -> exit 1 + 引导。
# =============================================================
set -euo pipefail

CONSOLE_BASE_URL="${CONSOLE_BASE_URL:-http://localhost:18080}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
TENANT_TX="${TENANT_TX:-tx}"
TENANT_TA="${TENANT_TA:-ta}"

COOKIE_JAR="$(mktemp -t seed-test-role-users.XXXXXX.cookies)"
trap 'rm -f "$COOKIE_JAR"' EXIT

GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; YEL=$'\033[1;33m'; RST=$'\033[0m'
ok()    { printf "%s[OK]%s  %s\n"   "$GREEN" "$RST" "$*"; }
ng()    { printf "%s[NG]%s  %s\n"   "$RED"   "$RST" "$*"; }
note()  { printf "%s[..]%s  %s\n"   "$YEL"   "$RST" "$*"; }

# --- 1) admin 登录 ---
note "admin login -> $CONSOLE_BASE_URL"
login_body=$(printf '{"username":"%s","password":"%s"}' "$ADMIN_USERNAME" "$ADMIN_PASSWORD")
login_resp=$(curl -sS -o /tmp/seed-login.json -w "%{http_code}" \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -X POST "$CONSOLE_BASE_URL/api/console/auth/login" \
  --data-raw "$login_body" || echo "000")
if [[ "$login_resp" != "200" ]]; then
  ng "admin login HTTP=$login_resp (resp: $(cat /tmp/seed-login.json 2>/dev/null | head -c 400))"
  echo "  - 确认 console-api up: curl $CONSOLE_BASE_URL/actuator/health"
  echo "  - 若 admin/admin123 已被 reset,export ADMIN_PASSWORD=<新密码> 再跑"
  echo "  - 若 login-encryption required=true(prod-like),先关此开关或本脚本扩展支持加密路径"
  exit 1
fi
ok "admin login OK (cookie 已存)"

# --- 2) 建 tenant(幂等)---
# CreateTenantRequest 必填 tenantId/tenantName/username/password;借此一步建 tenant + 默认 user。
# 这里默认 user 设为 admin-<tenant>(随便取,后续不依赖,真正 3 个测试 user 在 step 3 单独建)。
create_tenant() {
  local tid="$1"
  note "create tenant $tid"
  local body
  body=$(printf '{"tenantId":"%s","tenantName":"%s","username":"admin-%s","password":"Admin@123abc"}' \
    "$tid" "Test Tenant $tid" "$tid")
  local code
  code=$(curl -sS -o /tmp/seed-tenant.json -w "%{http_code}" \
    -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -X POST "$CONSOLE_BASE_URL/api/console/tenants" \
    --data-raw "$body" || echo "000")
  case "$code" in
    200|201)  ok "tenant $tid 已创建" ;;
    409)      ok "tenant $tid 已存在 -> skip" ;;
    400)
      local detail
      detail=$(cat /tmp/seed-tenant.json 2>/dev/null | head -c 400)
      if echo "$detail" | grep -q "non_prod_require_test_prefix\|reserved_prefix\|reserved_id"; then
        ng "tenant $tid 拒绝(NON-PROD ReservedPrefixGuard)"
        echo "  $detail"
        echo "  解决:"
        echo "    - export TENANT_TX=tc(白名单:ta/tb/tc/default-tenant)再跑;或"
        echo "    - export TENANT_TX=e2e-tx(带 test- prefix);或"
        echo "    - 改 ReservedPrefixGuard.DEV_FIXTURE_TENANT_IDS 加入 tx"
        exit 1
      fi
      ng "tenant $tid HTTP=400: $detail"
      exit 1
      ;;
    *)
      ng "tenant $tid HTTP=$code: $(cat /tmp/seed-tenant.json 2>/dev/null | head -c 400)"
      exit 1
      ;;
  esac
}

create_tenant "$TENANT_TX"
create_tenant "$TENANT_TA"

# --- 3) 建 3 个测试用户(幂等)---
create_user() {
  local tid="$1" username="$2" role="$3" pw="$4"
  note "create user $username (tenant=$tid, role=$role)"
  local body
  body=$(printf '{"tenantId":"%s","username":"%s","password":"%s","displayName":"%s","authoritiesCsv":"%s"}' \
    "$tid" "$username" "$pw" "$username" "$role")
  local code
  code=$(curl -sS -o /tmp/seed-user.json -w "%{http_code}" \
    -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -X POST "$CONSOLE_BASE_URL/api/console/users" \
    --data-raw "$body" || echo "000")
  case "$code" in
    200|201) ok "user $username 已创建" ;;
    409)     ok "user $username 已存在 -> skip" ;;
    *)
      ng "user $username HTTP=$code: $(cat /tmp/seed-user.json 2>/dev/null | head -c 400)"
      exit 1
      ;;
  esac
}

create_user "$TENANT_TX" "op-tx"     "ROLE_TENANT_USER"  "admin123"
create_user "$TENANT_TA" "tadmin-ta" "ROLE_TENANT_ADMIN" "Admin@123abc"
create_user "$TENANT_TX" "user-tx"   "ROLE_USER"         "admin123"

# --- 4) 逐用户验证 login ---
verify_login() {
  local username="$1" pw="$2"
  local code
  code=$(curl -sS -o /tmp/seed-verify.json -w "%{http_code}" \
    -c /dev/null \
    -H 'Content-Type: application/json' \
    -X POST "$CONSOLE_BASE_URL/api/console/auth/login" \
    --data-raw "$(printf '{"username":"%s","password":"%s"}' "$username" "$pw")" || echo "000")
  if [[ "$code" == "200" ]]; then
    ok "login $username -> 200"
  else
    ng "login $username -> $code: $(cat /tmp/seed-verify.json 2>/dev/null | head -c 200)"
    exit 1
  fi
}

verify_login "op-tx"     "admin123"
verify_login "tadmin-ta" "Admin@123abc"
verify_login "user-tx"   "admin123"

ok "RBAC seed 完成:3 用户均可登录"
