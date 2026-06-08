#!/usr/bin/env bash
# =========================================================
# apply-pending-flyway-migrations.sh
#
# Dev mode 工具:console-api 启动后,若 orchestrator/trigger 未起(本地常态),
# Flyway 不会跑,新增的 V*.sql 未应用 → console-api 调 mapper 报 column not exist。
#
# 本脚本扫 db/migration/V*.sql,对比 batch.flyway_schema_history 表已应用版本,
# 手动 docker exec psql 跑 pending migration + INSERT history,补齐到最新。
#
# 用法:bash scripts/local/apply-pending-flyway-migrations.sh
# =========================================================
set -uo pipefail

PG="${PG_CONTAINER:-batch-postgres-primary}"
PG_USER="${PG_USER:-batch_user}"
PG_DB="${PG_DB:-batch_platform}"
MIG_DIR="${MIG_DIR:-db/migration}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SQL_DIR="$ROOT_DIR/scripts/local/sql"

[[ -d "$MIG_DIR" ]] || { echo "❌ $MIG_DIR not found"; exit 1; }
docker exec "$PG" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 \
  || { echo "❌ PG container $PG 不可达"; exit 1; }

# 已应用版本
applied=$(docker exec -i "$PG" psql -U "$PG_USER" -d "$PG_DB" -tA -f /dev/stdin \
  < "$SQL_DIR/select-applied-flyway-versions.sql" 2>/dev/null | tr '\n' ' ')

# 文件遍历
applied_count=0; skipped_count=0
for f in $(ls "$MIG_DIR"/V*.sql 2>/dev/null | sort -t V -k 2 -n); do
  ver=$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')
  desc=$(basename "$f" | sed -E 's/^V[0-9]+__//;s/\.sql$//;s/_/ /g')
  if echo " $applied " | grep -q " $ver "; then
    skipped_count=$((skipped_count + 1))
    continue
  fi
  echo "==> 应用 V$ver: $desc"
  if docker exec -i "$PG" psql -U "$PG_USER" -d "$PG_DB" < "$f" > /tmp/mig-V${ver}.log 2>&1; then
    docker exec -i "$PG" psql -U "$PG_USER" -d "$PG_DB" \
      -v version="$ver" \
      -v description="$desc" \
      -v script="$(basename "$f")" \
      -f /dev/stdin < "$SQL_DIR/insert-flyway-history.sql" > /dev/null
    echo "    ✅"
    applied_count=$((applied_count + 1))
  else
    echo "    ❌ 失败 — 看 /tmp/mig-V${ver}.log"
    cat /tmp/mig-V${ver}.log | tail -5
    exit 2
  fi
done

echo
echo "==> 完成 — 应用 $applied_count;跳过(已应用)$skipped_count"
echo "    建议:重启 console-api 让 MyBatis cache 看到新列(bash scripts/local/restart.sh console)"
