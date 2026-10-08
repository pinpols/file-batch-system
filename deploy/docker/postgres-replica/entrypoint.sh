#!/usr/bin/env bash
# P2-4: postgres-replica 容器入口脚本。
#
# 行为：
#   1. 若 PGDATA 为空 → pg_basebackup 从主库拉一份完整数据
#      （-R 自动写 standby.signal + primary_conninfo 到 postgresql.auto.conf）
#   2. exec postgres 进入待机模式（standby.signal 触发 recovery，持续应用主库 WAL）
#
# 容错：
#   - 主库未就绪时 pg_basebackup 失败 → 脚本 exit 1，docker restart 策略会重启重试
#   - 已有数据目录直接 exec，不会覆盖；要重新引导需手动清 volume
#
# 权限：容器直接以 postgres 用户运行；数据卷必须由 postgres 拥有。

set -euo pipefail

PRIMARY_HOST="${POSTGRES_PRIMARY_HOST:-postgres-primary}"
PRIMARY_PORT="${POSTGRES_PRIMARY_PORT:-5432}"
: "${POSTGRES_REPLICATION_USER:?POSTGRES_REPLICATION_USER is required}"
: "${POSTGRES_REPLICATION_PASSWORD:?POSTGRES_REPLICATION_PASSWORD is required}"
REPL_USER="$POSTGRES_REPLICATION_USER"
REPL_PASS="$POSTGRES_REPLICATION_PASSWORD"

# PGDATA 取值优先环境变量；未设时用官方镜像默认 /var/lib/postgresql/data
: "${PGDATA:=/var/lib/postgresql/data/pgdata}"
export PGDATA

# 确保 PGDATA 可由当前非 root 用户写入，权限 0700。
mkdir -p "$PGDATA"
if [ "$(stat -c %u "$PGDATA")" != "$(id -u)" ]; then
  echo "[postgres-replica] PGDATA must be owned by postgres: $PGDATA" >&2
  exit 1
fi
chmod 0700 "$PGDATA"

if [ -z "$(ls -A "$PGDATA" 2>/dev/null)" ]; then
  echo "[postgres-replica] PGDATA 为空，从主库 pg_basebackup 引导..."
  echo "[postgres-replica] primary=${PRIMARY_HOST}:${PRIMARY_PORT}, user=${REPL_USER}"

  # 等主库就绪（最多 60s）
  for i in $(seq 1 30); do
    if PGPASSWORD="$REPL_PASS" pg_isready \
        -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" \
        -U "$REPL_USER" -d postgres -t 2 >/dev/null 2>&1; then
      break
    fi
    echo "[postgres-replica] 主库未就绪，等待... ($i/30)"
    sleep 2
  done

  PGPASSWORD="$REPL_PASS" pg_basebackup \
    -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$REPL_USER" \
    -D "$PGDATA" -Fp -Xs -P -R

  chmod 0700 "$PGDATA"
  echo "[postgres-replica] pg_basebackup 完成；进入 standby 模式"
else
  echo "[postgres-replica] PGDATA 已存在，跳过引导，直接启动"
fi

if [[ "${1:-}" == "postgres" ]]; then
  shift
fi

exec postgres "$@"
