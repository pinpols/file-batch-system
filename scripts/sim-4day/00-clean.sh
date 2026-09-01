#!/usr/bin/env bash
# ADR-sim 4day · P0 清空异常数据(保留所有 config)
# - 平台库 batch.* 运行时表:显式 allowlist + CASCADE(config 永远是 FK 父表,不会被截断)
# - 业务库 batch_business 的 biz.* 数据表
# - MinIO batch-dev/ 全清后重建 outbound prefix
# - mockserver 仅清请求日志(保留 expectations)
# 跑前/跑后断言 config 行数不变,防误删。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
PG="${PG_CONTAINER:-batch-postgres-primary}"
PGU="${POSTGRES_USER:-batch_user}"
MINIO="${MINIO_CONTAINER:-batch-minio}"
MC_ALIAS="${MC_ALIAS:-local}"
BUCKET="${BATCH_S3_BUCKET:-batch-dev}"
SQL_DIR="$HERE/sql"
KAFKA="${KAFKA_CONTAINER:-batch-kafka}"
KAFKA_BOOTSTRAP="${KAFKA_CONTAINER_BOOTSTRAP:-kafka:29092}"

psql_plat() { docker exec -i "$PG" psql -U "$PGU" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"; }
psql_biz()  { docker exec -i "$PG" psql -U "$PGU" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"; }

clean_kafka_runtime() {
  echo "==> 清理 Kafka runtime topic 历史消息"
  docker exec -i "$KAFKA" sh -s "$KAFKA_BOOTSTRAP" <<'EOF'
set -eu
bootstrap="$1"
topics_bin=/opt/kafka/bin/kafka-topics.sh
offsets_bin=/opt/kafka/bin/kafka-get-offsets.sh
delete_bin=/opt/kafka/bin/kafka-delete-records.sh
topics="$($topics_bin --bootstrap-server "$bootstrap" --list | awk '/^batch\./ {print}')"
for topic in $topics; do
  offset_file="$(mktemp)"
  printf '%s\n' '{"partitions":[' >"$offset_file"
  first=1
  while IFS=: read -r topic_name partition offset; do
    [ -n "${partition:-}" ] || continue
    if [ "$first" -eq 0 ]; then printf ',' >>"$offset_file"; fi
    printf '{"topic":"%s","partition":%s,"offset":%s}' "$topic_name" "$partition" "$offset" >>"$offset_file"
    first=0
  done <<TOPIC_OFFSETS
$($offsets_bin --bootstrap-server "$bootstrap" --time -1 --topic "$topic")
TOPIC_OFFSETS
  printf '],"version":1}\n' >>"$offset_file"
  if [ "$first" -eq 0 ]; then
    "$delete_bin" --bootstrap-server "$bootstrap" --offset-json-file "$offset_file" >/dev/null
  fi
  rm -f "$offset_file"
done
echo "    Kafka batch.* runtime topic 历史消息已清理"
EOF
}

echo "==> 0/5 config 基线快照(截断后须不变)"
CFG_BEFORE=$(psql_plat -tA -f /dev/stdin < "$SQL_DIR/config-baseline.sql")
echo "    tenant/job/pipeline/template/channel/workflow = $CFG_BEFORE"

echo "==> 1/5 截断平台运行时表(batch.*,CASCADE)"
psql_plat -f /dev/stdin < "$SQL_DIR/clean-platform-runtime.sql"
echo "    平台运行时表已截断"

echo "==> 2/5 截断业务库 biz.* 数据表"
psql_biz -f /dev/stdin < "$SQL_DIR/clean-business-runtime.sql"
echo "    biz.* 已截断"

echo "==> 3/5 清 Kafka runtime topic 历史消息"
clean_kafka_runtime

echo "==> 4/5 清 MinIO bucket + 重建 outbound prefix"
docker exec "$MINIO" mc alias set "$MC_ALIAS" http://localhost:9000 "$BATCH_S3_ACCESS_KEY" "$BATCH_S3_SECRET_KEY" >/dev/null 2>&1 || true
docker exec "$MINIO" mc rm --recursive --force "$MC_ALIAS/$BUCKET/" >/dev/null 2>&1 || true
for p in ingress ta/outbound/report tb/outbound/statement tc/outbound/risk-alert; do
  echo "init" | docker exec -i "$MINIO" mc pipe "$MC_ALIAS/$BUCKET/$p/.keep" >/dev/null 2>&1 || true
done
echo "    MinIO 已清空并重建 prefix"

echo "==> 5/5 mockserver 清请求日志(保留 expectations)"
curl -s -X PUT "http://localhost:11080/mockserver/clear?type=LOG" -H 'content-type: application/json' -d '{}' >/dev/null 2>&1 \
  && echo "    mockserver 日志已清" || echo "    mockserver 未响应(跳过,P4 前会重载 stub)"

echo "==> 校验:config 不变 + 运行时归零"
CFG_AFTER=$(psql_plat -tA -f /dev/stdin < "$SQL_DIR/config-baseline.sql")
echo "    config after = $CFG_AFTER"
[ "$CFG_BEFORE" = "$CFG_AFTER" ] && echo "    ✅ config 保留完好" || { echo "    ❌ config 行数变了!BEFORE=$CFG_BEFORE AFTER=$CFG_AFTER"; exit 1; }
psql_plat -tA -f /dev/stdin < "$SQL_DIR/runtime-residue.sql"
echo "==> P0 清空完成"
