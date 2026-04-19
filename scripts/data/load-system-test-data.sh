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
#   - MinIO: http://localhost:19000
#
# 使用方法：
#   BATCH_PLATFORM_DB_PASSWORD=... \
#   BATCH_BUSINESS_DB_PASSWORD=... \
#   BATCH_MINIO_ACCESS_KEY=... \
#   BATCH_MINIO_SECRET_KEY=... \
#     bash scripts/data/load-system-test-data.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORM_DB="${BATCH_PLATFORM_DB_NAME:-batch_platform}"
BUSINESS_DB="${BATCH_BUSINESS_DB_NAME:-batch_business}"
PG_USER="${BATCH_PLATFORM_DB_USERNAME:-batch_user}"
PG_HOST="${BATCH_PLATFORM_DB_HOST:-localhost}"
PG_PORT="${BATCH_PLATFORM_DB_PORT:-15432}"
PG_PASSWORD="${BATCH_PLATFORM_DB_PASSWORD:-batch_pass_123}"
MINIO_ALIAS="${BATCH_MINIO_ALIAS:-local}"
MINIO_ENDPOINT="${BATCH_MINIO_ENDPOINT:-http://localhost:19000}"
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

  # ── ta/tb/tc 租户样本：与 test-full-coverage-import-suite 里 Excel channel 配置的
  #    bucket/prefix（ta/, tb/, tc/）对齐；同时与 docker/sftp/data/{tenant}/inbound/ 下
  #    的 SFTP fixture 保持同名，方便前端在 OSS 和 SFTP 两条链路之间切换验证。
  cat > "${tmp_dir}/ta-customer-profile-20260419.csv" <<'EOF'
customerNo,customerName,customerType,level,region,updatedAt
RT-CUST-0001,Acme Retail Co.,ENTERPRISE,GOLD,EAST,2026-04-19T08:00:00Z
RT-CUST-0002,Blue Ocean Mart,ENTERPRISE,SILVER,SOUTH,2026-04-19T08:00:00Z
RT-CUST-0003,North Star Shop,PERSONAL,BRONZE,WEST,2026-04-19T08:00:00Z
RT-CUST-0004,Sunrise Grocery,PERSONAL,BRONZE,NORTH,2026-04-19T08:00:00Z
EOF

  cat > "${tmp_dir}/tb-transaction-20260419.csv" <<'EOF'
txnId,fromAccount,toAccount,amount,currency,occurredAt,riskLabel
TB-TXN-20260419-0001,CUST-TB-001,CUST-TB-099,15000.00,CNY,2026-04-19T09:30:00Z,NORMAL
TB-TXN-20260419-0002,CUST-TB-002,CUST-TB-088,8500.50,CNY,2026-04-19T10:15:00Z,NORMAL
TB-TXN-20260419-0003,CUST-TB-099,CUST-TB-003,500000.00,CNY,2026-04-19T11:00:00Z,ATTENTION
TB-TXN-20260419-0004,CUST-TB-004,CUST-TB-077,2200.00,CNY,2026-04-19T11:45:00Z,NORMAL
EOF

  cat > "${tmp_dir}/tc-risk-score-20260419.json" <<'EOF'
[
  {"customerNo":"TC-CUST-001","riskScore":82,"band":"HIGH","factors":["FREQUENCY","AMOUNT"],"computedAt":"2026-04-19T07:00:00Z"},
  {"customerNo":"TC-CUST-002","riskScore":45,"band":"MEDIUM","factors":["LOCATION"],"computedAt":"2026-04-19T07:00:00Z"},
  {"customerNo":"TC-CUST-003","riskScore":12,"band":"LOW","factors":[],"computedAt":"2026-04-19T07:00:00Z"},
  {"customerNo":"TC-CUST-004","riskScore":91,"band":"HIGH","factors":["DEVICE","AMOUNT","FREQUENCY"],"computedAt":"2026-04-19T07:00:00Z"}
]
EOF

  # 入站样本：上传到每个租户 prefix 下的 inbound/ 目录
  mc cp "${tmp_dir}/ta-customer-profile-20260419.csv" "${MINIO_ALIAS}/${MINIO_BUCKET}/ta/inbound/customer-profile-20260419.csv" >/dev/null
  mc cp "${tmp_dir}/tb-transaction-20260419.csv"      "${MINIO_ALIAS}/${MINIO_BUCKET}/tb/inbound/transaction-20260419.csv" >/dev/null
  mc cp "${tmp_dir}/tc-risk-score-20260419.json"      "${MINIO_ALIAS}/${MINIO_BUCKET}/tc/inbound/risk-score-20260419.json" >/dev/null

  # 出站占位：预建目录占位，避免前端首次打开导出页 bucket 404
  printf '' > "${tmp_dir}/.keep"
  mc cp "${tmp_dir}/.keep" "${MINIO_ALIAS}/${MINIO_BUCKET}/ta/outbound/report/.keep" >/dev/null
  mc cp "${tmp_dir}/.keep" "${MINIO_ALIAS}/${MINIO_BUCKET}/tb/outbound/statement/.keep" >/dev/null

  echo "MinIO seed complete."
else
  echo "mc not found, skip MinIO seed."
fi

echo "System test seed loaded."
