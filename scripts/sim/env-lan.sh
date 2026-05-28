#!/usr/bin/env bash
# =========================================================
# env-lan.sh:LAN(局域网)访问时 source 这个,让 sim 脚本走 LAN IP。
#
# 用法:
#   source scripts/sim/env-lan.sh
#   bash scripts/sim/03-import-tenants.sh
#   bash scripts/sim/05-load.sh
#   bash scripts/sim/06-verify.sh
#
# 也支持显式传 LAN_HOST:
#   LAN_HOST=192.168.1.20 source scripts/sim/env-lan.sh
# =========================================================

# 1) 自动探测本机 LAN IP(macOS / Linux 通用),允许 LAN_HOST 显式覆盖
if [[ -z "${LAN_HOST:-}" ]]; then
  # macOS 优先 en0(以太网)→ en1;Linux 走默认路由的接口
  if command -v ipconfig >/dev/null 2>&1; then
    LAN_HOST=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)
  fi
  if [[ -z "${LAN_HOST:-}" ]] && command -v ip >/dev/null 2>&1; then
    LAN_HOST=$(ip route get 1.1.1.1 2>/dev/null | awk '/src/ {print $7; exit}')
  fi
  LAN_HOST="${LAN_HOST:-localhost}"
fi

# 2) BE / FE / 模拟器 host 端口(对齐 .env.local 默认值)
export LAN_HOST
export CONSOLE_BASE="http://${LAN_HOST}:${CONSOLE_PORT:-18080}"
export TRIGGER_BASE="http://${LAN_HOST}:${TRIGGER_PORT:-18081}"
export ORCH_BASE="http://${LAN_HOST}:${ORCHESTRATOR_PORT:-18082}"
export MOCK_BASE="http://${LAN_HOST}:${MOCKSERVER_HOST_PORT:-11080}"

# Infra(脚本里 docker exec 不依赖这些,但客户端 / DataGrip 用得着)
export PG_PRIMARY_HOST="$LAN_HOST"; export PG_PRIMARY_PORT="${POSTGRES_PORT:-15432}"
export REDIS_HOST="$LAN_HOST";      export REDIS_PORT="${REDIS_PORT:-16379}"
export KAFKA_BOOTSTRAP="${LAN_HOST}:${KAFKA_HOST_PORT:-19092}"
export MINIO_URL="http://${LAN_HOST}:${MINIO_API_PORT:-19000}"
export SFTP_HOST="$LAN_HOST"
export SFTP_PORT="${SFTP_HOST_PORT:-12222}"

# 打印
cat <<EOF
══════ LAN 环境变量已 export ══════
  LAN_HOST           = $LAN_HOST
  CONSOLE_BASE       = $CONSOLE_BASE
  TRIGGER_BASE       = $TRIGGER_BASE
  ORCH_BASE          = $ORCH_BASE
  MOCK_BASE          = $MOCK_BASE
  PG_PRIMARY_HOST:PORT = $PG_PRIMARY_HOST:$PG_PRIMARY_PORT
  REDIS_HOST:PORT    = $REDIS_HOST:$REDIS_PORT
  KAFKA_BOOTSTRAP    = $KAFKA_BOOTSTRAP
  MINIO_URL          = $MINIO_URL
  SFTP_HOST:PORT     = $SFTP_HOST:$SFTP_PORT

LAN 访问从手机 / 别人电脑也用同款 URL(host 同网段即可)。
EOF
