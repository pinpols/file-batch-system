#!/usr/bin/env bash
# =========================================================
# 01-init-biz.sh:模拟器初始化(幂等)
#   1. 在 batch_business 库应用 scripts/db/business/create_biz_tables.sql(biz.* 业务表)
#   2. MinIO 创建 ta/tb/tc 各自的 prefix 路径(共用 batch-dev bucket,文件 prefix 隔离)
#
# 跑前:主 compose 必须已 up(PG / MinIO healthy)
# 跑后:可继续 02-start-sim.sh 起 sftp + mockserver
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
PG_USER="${POSTGRES_USER:-batch_user}"
MINIO_CONTAINER="${MINIO_CONTAINER:-batch-minio}"
MINIO_AK="${MINIO_ROOT_USER:-minioadmin}"
MINIO_SK="${MINIO_ROOT_PASSWORD:-minioadmin123}"
MINIO_BUCKET="${MINIO_BUCKET:-batch-dev}"

echo "==> 1/2 应用 biz.* 业务表(batch_business 库)"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d batch_business \
  < scripts/db/business/create_biz_tables.sql > /tmp/init-biz-tables.log 2>&1
applied=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d batch_business -tAc \
  "select count(*) from pg_tables where schemaname='biz'")
echo "  biz schema 现有 $applied 张表"

echo "==> 2/2 MinIO bucket / prefix 准备"
docker exec "$MINIO_CONTAINER" mc alias set local http://localhost:9000 "$MINIO_AK" "$MINIO_SK" >/dev/null 2>&1
docker exec "$MINIO_CONTAINER" mc mb -p "local/$MINIO_BUCKET" 2>&1 | grep -v "already" || true
# 用占位文件提前建好 ta/tb/tc 输出 prefix(channel_config 里的 oss_object_prefix)
for p in "ta/outbound/report" "tb/outbound/statement" "tc/outbound/risk-alert"; do
  echo "init-$(date +%s)" | docker exec -i "$MINIO_CONTAINER" \
    mc pipe "local/$MINIO_BUCKET/$p/.keep" >/dev/null 2>&1 || true
done
docker exec "$MINIO_CONTAINER" mc ls --recursive "local/$MINIO_BUCKET" 2>&1 | grep "\.keep" | head -6

echo "==> ✅ 初始化完成"
echo "    biz tables: $applied"
echo "    MinIO bucket: $MINIO_BUCKET(ta/tb/tc prefix 已建)"
