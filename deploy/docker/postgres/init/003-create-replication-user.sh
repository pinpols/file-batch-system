#!/usr/bin/env bash
# P2-4: 创建流复制用户 replicator + 放行 replica 容器在 batch-network 子网的连接。
# 主库容器首次启动（数据目录空）时由 postgres 官方 entrypoint 自动调用本脚本。
#
# 复制账号凭据由 Compose 注入；默认值仅在 docker-compose.yml 维护，生产必须覆盖。

set -euo pipefail

: "${POSTGRES_REPLICATION_USER:?POSTGRES_REPLICATION_USER is required}"
: "${POSTGRES_REPLICATION_PASSWORD:?POSTGRES_REPLICATION_PASSWORD is required}"
if [[ ! "$POSTGRES_REPLICATION_USER" =~ ^[A-Za-z_][A-Za-z0-9_]*$ || ${#POSTGRES_REPLICATION_USER} -gt 63 ]]; then
  echo "POSTGRES_REPLICATION_USER must be a valid unquoted PostgreSQL role name" >&2
  exit 2
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -f "$SCRIPT_DIR/sql/create-replication-user.sql"

# pg_hba 放行 replica：postgres 镜像默认 pg_hba.conf 不接受外部 replication 连接，需追加。
# trust 仅在 docker 内部网络（batch-network 内的 172.x 段）；生产使用 md5/scram。
PGHBA="${PGDATA}/pg_hba.conf"
if ! awk -v user="$POSTGRES_REPLICATION_USER" \
  '$1 == "host" && $2 == "replication" && $3 == user { found = 1 } END { exit !found }' "$PGHBA"; then
  cat >>"$PGHBA" <<EOF

# P2-4: streaming replication from postgres-replica container
host replication ${POSTGRES_REPLICATION_USER} 0.0.0.0/0 scram-sha-256
EOF
  pg_ctl reload -D "$PGDATA" >/dev/null
fi
