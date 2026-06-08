#!/usr/bin/env bash
# ADR-sim 4day · P0 清空脏数据(保留所有 config)
# - 平台库 batch.* 运行时表:显式 allowlist + CASCADE(config 永远是 FK 父表,不会被截断)
# - 业务库 batch_business 的 biz.* 数据表
# - MinIO batch-dev/ 全清后重建 outbound prefix
# - mockserver 仅清请求日志(保留 expectations)
# 跑前/跑后断言 config 行数不变,防误删。
set -euo pipefail

PG=batch-postgres-primary
PGU=batch_user
MINIO=batch-minio
MC_ALIAS=local
BUCKET=batch-dev
HERE="$(cd "$(dirname "$0")" && pwd)"
SQL_DIR="$HERE/sql"

psql_plat() { docker exec -i "$PG" psql -U "$PGU" -d batch_platform -v ON_ERROR_STOP=1 "$@"; }
psql_biz()  { docker exec -i "$PG" psql -U "$PGU" -d batch_business -v ON_ERROR_STOP=1 "$@"; }

echo "==> 0/4 config 基线快照(截断后须不变)"
CFG_BEFORE=$(psql_plat -tA -f /dev/stdin < "$SQL_DIR/config-baseline.sql")
echo "    tenant/job/pipeline/template/channel/workflow = $CFG_BEFORE"

echo "==> 1/4 截断平台运行时表(batch.*,CASCADE)"
psql_plat -f /dev/stdin < "$SQL_DIR/clean-platform-runtime.sql"
echo "    平台运行时表已截断"

echo "==> 2/4 截断业务库 biz.* 数据表"
psql_biz -f /dev/stdin < "$SQL_DIR/clean-business-runtime.sql"
echo "    biz.* 已截断"

echo "==> 3/4 清 MinIO bucket + 重建 outbound prefix"
docker exec "$MINIO" mc alias set "$MC_ALIAS" http://localhost:9000 minioadmin minioadmin123 >/dev/null 2>&1 || true
docker exec "$MINIO" mc rm --recursive --force "$MC_ALIAS/$BUCKET/" >/dev/null 2>&1 || true
for p in ingress ta/outbound/report tb/outbound/statement tc/outbound/risk-alert; do
  echo "init" | docker exec -i "$MINIO" mc pipe "$MC_ALIAS/$BUCKET/$p/.keep" >/dev/null 2>&1 || true
done
echo "    MinIO 已清空并重建 prefix"

echo "==> 4/4 mockserver 清请求日志(保留 expectations)"
curl -s -X PUT "http://localhost:11080/mockserver/clear?type=LOG" -H 'content-type: application/json' -d '{}' >/dev/null 2>&1 \
  && echo "    mockserver 日志已清" || echo "    mockserver 未响应(跳过,P4 前会重载 stub)"

echo "==> 校验:config 不变 + 运行时归零"
CFG_AFTER=$(psql_plat -tA -f /dev/stdin < "$SQL_DIR/config-baseline.sql")
echo "    config after = $CFG_AFTER"
[ "$CFG_BEFORE" = "$CFG_AFTER" ] && echo "    ✅ config 保留完好" || { echo "    ❌ config 行数变了!BEFORE=$CFG_BEFORE AFTER=$CFG_AFTER"; exit 1; }
psql_plat -tA -f /dev/stdin < "$SQL_DIR/runtime-residue.sql"
echo "==> P0 清空完成"
