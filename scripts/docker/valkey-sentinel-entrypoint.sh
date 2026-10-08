#!/usr/bin/env sh
set -eu

: "${SENTINEL_MASTER_NAME:?SENTINEL_MASTER_NAME is required}"
: "${SENTINEL_MASTER_HOST:?SENTINEL_MASTER_HOST is required}"
: "${SENTINEL_MASTER_PORT:?SENTINEL_MASTER_PORT is required}"
: "${SENTINEL_QUORUM:?SENTINEL_QUORUM is required}"

case "$SENTINEL_MASTER_NAME" in
  *[!A-Za-z0-9_-]*) echo "invalid Sentinel master name" >&2; exit 2 ;;
esac
case "$SENTINEL_MASTER_HOST" in
  *[!A-Za-z0-9.-]*) echo "invalid Sentinel master host" >&2; exit 2 ;;
esac
case "$SENTINEL_MASTER_PORT:$SENTINEL_QUORUM" in
  *[!0-9:]*) echo "Sentinel port and quorum must be numeric" >&2; exit 2 ;;
esac

config="${TMPDIR:-/tmp}/sentinel.conf"
cat >"$config" <<EOF
port 26379
bind 0.0.0.0
protected-mode no
dir /tmp
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel monitor ${SENTINEL_MASTER_NAME} ${SENTINEL_MASTER_HOST} ${SENTINEL_MASTER_PORT} ${SENTINEL_QUORUM}
sentinel down-after-milliseconds ${SENTINEL_MASTER_NAME} 3000
sentinel failover-timeout ${SENTINEL_MASTER_NAME} 15000
sentinel parallel-syncs ${SENTINEL_MASTER_NAME} 1
EOF

exec valkey-server "$config" --sentinel
