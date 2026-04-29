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

# 历史上引用的 docs/sql/system-test/ 不存在，真 seed 一直在 scripts/db/test-seed/；
# business_seed 里 INSERT 前要求 biz.* 表已建，必须先跑 create_biz_tables.sql，否则报 relation not found。
SEED_DIR="${ROOT_DIR}/scripts/db/test-seed"
BIZ_DDL="${ROOT_DIR}/scripts/db/business/create_biz_tables.sql"

echo "Creating business DDL (biz.customer_account / settlement_batch / settlement_detail)..."
psql_business -f "${BIZ_DDL}"

echo "Loading platform seed..."
psql_platform -f "${SEED_DIR}/platform_seed.sql"
echo "Loading platform edge cases..."
psql_platform -f "${SEED_DIR}/platform_edge_cases.sql"

echo "Loading business seed..."
psql_business -f "${SEED_DIR}/business_seed.sql"
echo "Loading business edge cases..."
psql_business -f "${SEED_DIR}/business_edge_cases.sql"

# platform_seed.sql 末尾的 setval DO BLOCK 只在自身 INSERT 后推进序列；
# platform_edge_cases.sql 也用 hardcoded id 继续插入，但没再跑 setval，
# 导致 batch.*_id_seq 落后于实际 max(id) → orchestrator 新建实例时撞 PK 冲突
# (Detail: Key (id)=(4006/4007) already exists)。
# 统一在所有 seed 加载完成后再跑一次，覆盖 batch schema 全部 *_id_seq。
echo "Aligning batch.*_id_seq with max(id) (seed 用 hardcoded id 后必须同步序列)..."
psql_platform -c "
DO \$\$
DECLARE
    rec RECORD;
    seq_name text;
BEGIN
    FOR rec IN
        SELECT c.relname AS tbl
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'batch' AND c.relkind = 'r'
    LOOP
        -- pg_get_serial_sequence 对不存在的列会抛 undefined_column(例如 shedlock 表 PK 是 name 不是 id)。
        -- 先查 information_schema 确认 id 列存在,再去拿 sequence,避免整段 DO inline 被拒。
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'batch' AND table_name = rec.tbl AND column_name = 'id'
        ) THEN
            CONTINUE;
        END IF;
        seq_name := pg_get_serial_sequence('batch.' || rec.tbl, 'id');
        IF seq_name IS NOT NULL THEN
            EXECUTE format(
                'SELECT setval(%L, COALESCE((SELECT MAX(id) FROM batch.%I), 1), true)',
                seq_name, rec.tbl);
        END IF;
    END LOOP;
END \$\$;
"

# seed 注入了大量 in-progress runtime 行（job_instance/partition/task/step_instance/workflow_run
# 处于 RUNNING/READY/WAITING/CREATED/RETRYING）。这些行的存在会让真实 launch 撞两类冲突：
#   1) quota：占住 max_running_jobs / max_partitions_per_tenant 名额；
#   2) CLAIM：worker 对同 partition_id 发 CLAIM 时 step_instance 已是 RUNNING/READY → 抛
#      `job step instance claim conflict`，新 task 永远 stuck READY。
# seed 的设计意图是「展示运行中的样例数据给前端看」，但跑真实任务时这些行会阻塞链路。
# 加载完成后强制把所有非终态 runtime 行收尾到 TERMINATED，保留行数据但释放约束。
echo "Closing seed-injected in-progress runtime rows to TERMINATED (避免 CLAIM/quota 冲突)..."
psql_platform -c "
UPDATE batch.job_instance SET instance_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE instance_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.job_partition SET partition_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE partition_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.job_task SET task_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE task_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.job_step_instance SET step_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE step_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.pipeline_instance SET run_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE run_status IN ('CREATED','RUNNING','COMPENSATING');
UPDATE batch.workflow_run SET run_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE run_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
-- workflow_node_run.node_status 的 CHECK 约束(ck_workflow_node_run_status)只允许
-- READY/RUNNING/SUCCESS/FAILED/SKIPPED,不接受 TERMINATED(其它 batch.* 表的命名)。
-- 用 FAILED 收尾"未跑完"的 in-progress 节点行,与上下游收尾语义对齐。
UPDATE batch.workflow_node_run SET node_status='FAILED', finished_at=COALESCE(finished_at, now())
  WHERE node_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
"

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
