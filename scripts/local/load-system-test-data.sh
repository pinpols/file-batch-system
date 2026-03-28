#!/usr/bin/env bash
# =========================================================
# load-system-test-data.sh - 装载系统测试数据
# Notes:
# 1) 写入平台库、业务库和 MinIO 的系统测试种子。
# 2) 用于本地联调、巡检和 E2E 前准备。
# =========================================================
# 默认依赖：
#   - PostgreSQL: localhost:15432
#   - 平台库: batch_platform
#   - 业务库: batch_business
#   - MinIO: http://localhost:9000
#
# 使用方法：
#   BATCH_PLATFORM_DB_PASSWORD=... \
#   BATCH_BUSINESS_DB_PASSWORD=... \
#   BATCH_MINIO_ACCESS_KEY=... \
#   BATCH_MINIO_SECRET_KEY=... \
#     bash scripts/local/load-system-test-data.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORM_DB="${BATCH_PLATFORM_DB_NAME:-batch_platform}"
BUSINESS_DB="${BATCH_BUSINESS_DB_NAME:-batch_business}"
PG_USER="${BATCH_PLATFORM_DB_USERNAME:-batch_user}"
PG_HOST="${BATCH_PLATFORM_DB_HOST:-localhost}"
PG_PORT="${BATCH_PLATFORM_DB_PORT:-15432}"
PG_PASSWORD="${BATCH_PLATFORM_DB_PASSWORD:-batch_pass_123}"
MINIO_ALIAS="${BATCH_MINIO_ALIAS:-local}"
MINIO_ENDPOINT="${BATCH_MINIO_ENDPOINT:-http://localhost:9000}"
MINIO_ACCESS_KEY="${BATCH_MINIO_ACCESS_KEY:-minioadmin}"
MINIO_SECRET_KEY="${BATCH_MINIO_SECRET_KEY:-minioadmin123}"
MINIO_BUCKET="${BATCH_MINIO_BUCKET:-batch-dev}"

export PGPASSWORD="${PG_PASSWORD}"

psql_platform() {
  psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${PLATFORM_DB}" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${BUSINESS_DB}" -v ON_ERROR_STOP=1 "$@"
}

echo "Loading platform seed..."
psql_platform -f "${ROOT_DIR}/docs/sql/system-test/platform_seed.sql"
echo "Loading platform edge cases..."
psql_platform -f "${ROOT_DIR}/docs/sql/system-test/platform_edge_cases.sql"

echo "Loading business seed..."
psql_business -f "${ROOT_DIR}/docs/sql/system-test/business_seed.sql"
echo "Loading business edge cases..."
psql_business -f "${ROOT_DIR}/docs/sql/system-test/business_edge_cases.sql"

if command -v mc >/dev/null 2>&1; then
  echo "Seeding MinIO objects..."
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf "${tmp_dir}"' EXIT

  cat > "${tmp_dir}/customer-account-20260322.csv" <<'EOF'
customerNo,customerName,customerType,certificateNo,mobileNo,email,status
CUST0001,Acme Retail Co., Ltd.,ENTERPRISE,91310000000000001X,13800000001,ops@acme.example,ACTIVE
CUST0002,Blue Ocean Tech Ltd.,ENTERPRISE,91310000000000002X,13800000002,finance@blueocean.example,ACTIVE
CUST0003,North Star Trading,ENTERPRISE,91310000000000003X,13800000003,contact@northstar.example,FROZEN
EOF

  cat > "${tmp_dir}/customer-account-20260322.json" <<'EOF'
[
  {"customerNo":"CUST9001","customerName":"Finance Holdings Ltd.","customerType":"ENTERPRISE","certificateNo":"91310000000009001X","status":"ACTIVE"},
  {"customerNo":"CUST9002","customerName":"Finance Market Services","customerType":"PERSONAL","certificateNo":"91310000000009002X","status":"INACTIVE"}
]
EOF

  cat > "${tmp_dir}/customer-account-20260322.xml" <<'EOF'
<customers>
  <customer><customerNo>CUST9101</customerNo><customerName>XML Test Customer</customerName><customerType>ENTERPRISE</customerType><status>ACTIVE</status></customer>
</customers>
EOF

  cat > "${tmp_dir}/settlement-20260322.csv" <<'EOF'
settlementNo,customerNo,bizDate,accountingPeriod,grossAmount,feeAmount,netAmount,currency
STL-20260322-0001,CUST0001,2026-03-22,2026-03,3000.00,30.00,2970.00,CNY
STL-20260322-0002,CUST0002,2026-03-22,2026-03,1800.00,18.00,1782.00,CNY
EOF

  cat > "${tmp_dir}/settlement-20260315.csv" <<'EOF'
settlementNo,customerNo,bizDate,accountingPeriod,grossAmount,feeAmount,netAmount,currency
STL-20260315-0001,CUST0001,2026-03-15,2026-03,2000.00,20.00,1980.00,CNY
EOF

  mc alias set "${MINIO_ALIAS}" "${MINIO_ENDPOINT}" "${MINIO_ACCESS_KEY}" "${MINIO_SECRET_KEY}" >/dev/null
  mc mb --ignore-existing "${MINIO_ALIAS}/${MINIO_BUCKET}" >/dev/null
  mc cp "${tmp_dir}/customer-account-20260322.csv" "${MINIO_ALIAS}/${MINIO_BUCKET}/ingress/import/customer-account-20260322.csv" >/dev/null
  mc cp "${tmp_dir}/customer-account-20260322.json" "${MINIO_ALIAS}/${MINIO_BUCKET}/ingress/import/customer-account-20260322.json" >/dev/null
  mc cp "${tmp_dir}/customer-account-20260322.xml" "${MINIO_ALIAS}/${MINIO_BUCKET}/ingress/import/customer-account-20260322.xml" >/dev/null
  mc cp "${tmp_dir}/settlement-20260322.csv" "${MINIO_ALIAS}/${MINIO_BUCKET}/outbound/settlement/settlement-20260322.csv.part" >/dev/null
  mc cp "${tmp_dir}/settlement-20260315.csv" "${MINIO_ALIAS}/${MINIO_BUCKET}/archive/settlement/settlement-20260315.csv" >/dev/null
  printf 'done\n' > "${tmp_dir}/customer-account-20260322.done"
  mc cp "${tmp_dir}/customer-account-20260322.done" "${MINIO_ALIAS}/${MINIO_BUCKET}/ingress/import/customer-account-20260322.done" >/dev/null
  echo "MinIO seed complete."
else
  echo "mc not found, skip MinIO seed."
fi

echo "System test seed loaded."
