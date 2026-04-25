#!/usr/bin/env bash
# P2-4: 创建流复制用户 replicator + 放行 replica 容器在 batch-network 子网的连接。
# 主库容器首次启动（数据目录空）时由 postgres 官方 entrypoint 自动调用本脚本。
#
# REPLICATION_PASSWORD 优先从环境读取（POSTGRES_REPLICATION_PASSWORD），未设时回退默认值
# repl_pass_dev_only。生产强制覆盖。

set -euo pipefail

REPL_USER="${POSTGRES_REPLICATION_USER:-replicator}"
REPL_PASS="${POSTGRES_REPLICATION_PASSWORD:-repl_pass_dev_only}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${REPL_USER}') THEN
      CREATE ROLE ${REPL_USER} WITH REPLICATION LOGIN PASSWORD '${REPL_PASS}';
    END IF;
  END\$\$;
EOSQL

# pg_hba 放行 replica：postgres 镜像默认 pg_hba.conf 不接受外部 replication 连接，需追加。
# trust 仅在 docker 内部网络（batch-network 内的 172.x 段）；生产使用 md5/scram。
PGHBA="${PGDATA}/pg_hba.conf"
if ! grep -q "host[[:space:]]\+replication[[:space:]]\+${REPL_USER}" "$PGHBA"; then
  cat >>"$PGHBA" <<EOF

# P2-4: streaming replication from postgres-replica container
host replication ${REPL_USER} 0.0.0.0/0 scram-sha-256
EOF
  pg_ctl reload -D "$PGDATA" >/dev/null
fi
