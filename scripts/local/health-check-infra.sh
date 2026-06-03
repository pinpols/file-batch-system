#!/usr/bin/env bash
# =========================================================
# health-check-infra.sh
#
# 基建健康检查:PG primary/replica / Kafka / Redis / MinIO。
# env-var 驱动 + 协议层探测(无 docker 依赖),换环境只改环境变量。
#
# 用法:
#   bash scripts/local/health-check-infra.sh             # 默认本地(读 .env / 默认值)
#   bash scripts/local/health-check-infra.sh --quiet     # 只 exit code,不输出表
#   bash scripts/local/health-check-infra.sh --no-replica  # 跳过 PG replica 检查
#   bash scripts/local/health-check-infra.sh --no-kafka    # 跳过 Kafka 检查
#
# 在其它环境(staging / CI / 别人机器):
#   PG_PRIMARY_HOST=postgres.staging.svc \
#   PG_PRIMARY_PORT=5432 \
#   POSTGRES_USER=batch_user POSTGRES_PASSWORD=xxx \
#   REDIS_HOST=redis.staging.svc REDIS_PORT=6379 \
#   KAFKA_BOOTSTRAP=kafka.staging.svc:9092 \
#   MINIO_URL=http://minio.staging.svc:9000 \
#   bash scripts/local/health-check-infra.sh
#
# 返回:
#   0  全部 healthy
#   1  至少 1 项不健康
#
# 配套:start-all.sh / restart.sh 启动前 fail-fast 调用,避免基建未起就跑 BE。
# =========================================================

set -uo pipefail

# 加载 .env(若存在),让本机默认值生效;命令行 env var 优先级最高
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
[[ -f "$ROOT_DIR/.env" ]] && set -a && source "$ROOT_DIR/.env" 2>/dev/null && set +a

# ── 配置(env var 优先,默认本地 docker compose 暴露端口) ───────────────
PG_PRIMARY_HOST="${PG_PRIMARY_HOST:-localhost}"
PG_PRIMARY_PORT="${PG_PRIMARY_PORT:-${POSTGRES_PORT:-15432}}"
PG_REPLICA_HOST="${PG_REPLICA_HOST:-localhost}"
PG_REPLICA_PORT="${PG_REPLICA_PORT:-${POSTGRES_REPLICA_PORT:-15433}}"
PG_USER="${PGUSER:-${POSTGRES_USER:-batch_user}}"
PG_PASSWORD="${PGPASSWORD:-${POSTGRES_PASSWORD:-batch_secret_dev_only}}"
PG_DB="${POSTGRES_DB:-batch_platform}"
PG_REPLICA_LAG_THRESHOLD_SEC="${PG_REPLICA_LAG_THRESHOLD_SEC:-300}"  # 5min 默认

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-16379}"

KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:${KAFKA_HOST_PORT:-19092}}"

MINIO_URL="${MINIO_URL:-http://localhost:${MINIO_API_PORT:-19000}}"
MINIO_BUCKET="${MINIO_BUCKET:-batch-dev}"

QUIET=0
SKIP_REPLICA=0
SKIP_KAFKA=0
SKIP_MINIO=0
for arg in "$@"; do
  case "$arg" in
    --quiet) QUIET=1 ;;
    --no-replica) SKIP_REPLICA=1 ;;
    --no-kafka) SKIP_KAFKA=1 ;;
    --no-minio) SKIP_MINIO=1 ;;
    --help|-h)
      grep -E "^# " "$0" | head -25 | sed 's/^# //'
      exit 0
      ;;
  esac
done

GREEN='\033[32m' RED='\033[31m' YELLOW='\033[33m' DIM='\033[2m' RST='\033[0m'
PASS=0 FAIL=0 SKIP=0
RESULTS=()

ok()   { (( PASS++ )); [[ $QUIET == 1 ]] || printf "  ${GREEN}✓${RST} %-20s %s\n" "$1" "$2"; RESULTS+=("OK|$1|$2"); }
ng()   { (( FAIL++ )); [[ $QUIET == 1 ]] || printf "  ${RED}✗${RST} %-20s %s\n" "$1" "$2"; RESULTS+=("FAIL|$1|$2"); }
warn() { [[ $QUIET == 1 ]] || printf "  ${YELLOW}⚠${RST} %-20s %s\n" "$1" "$2"; RESULTS+=("WARN|$1|$2"); }
skip() { (( SKIP++ )); [[ $QUIET == 1 ]] || printf "  ${DIM}-${RST} %-20s %s\n" "$1" "$2"; RESULTS+=("SKIP|$1|$2"); }

# 通用 TCP 端口探测(无 nc 依赖,用 /dev/tcp)
# 兼容 macOS(无 GNU timeout)/ Linux(有 timeout):优先用 timeout 命令,否则后台 + kill。
tcp_probe() {
  local host="$1" port="$2" timeout_sec="${3:-3}"
  if command -v timeout >/dev/null 2>&1; then
    timeout "$timeout_sec" bash -c "echo > /dev/tcp/$host/$port" 2>/dev/null
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$timeout_sec" bash -c "echo > /dev/tcp/$host/$port" 2>/dev/null
  else
    # 无 timeout 命令(macOS 默认):用 perl 的 alarm(macOS / Linux 自带)
    if command -v perl >/dev/null 2>&1; then
      perl -e "
        \$SIG{ALRM} = sub { exit 1 };
        alarm $timeout_sec;
        use IO::Socket::INET;
        my \$s = IO::Socket::INET->new(PeerHost=>'$host', PeerPort=>$port, Proto=>'tcp', Timeout=>$timeout_sec)
          or exit 1;
        close \$s;
        exit 0;
      " 2>/dev/null
    else
      # 最后 fallback:直接探,无超时(系统层 connect 自带 ~75s 超时)
      bash -c "echo > /dev/tcp/$host/$port" 2>/dev/null
    fi
  fi
}

# ── PG primary ────────────────────────────────────────────
check_pg_primary() {
  if ! tcp_probe "$PG_PRIMARY_HOST" "$PG_PRIMARY_PORT" 3; then
    ng "PG primary" "$PG_PRIMARY_HOST:$PG_PRIMARY_PORT 端口不通"
    return
  fi
  if ! command -v psql >/dev/null 2>&1; then
    warn "PG primary" "端口通,但本机无 psql,跳过深度检查"
    (( PASS++ ))
    return
  fi
  local out
  out=$(PGPASSWORD="$PG_PASSWORD" psql -h "$PG_PRIMARY_HOST" -p "$PG_PRIMARY_PORT" \
        -U "$PG_USER" -d "$PG_DB" -tAc "select 1, current_setting('server_version_num')::int / 10000" 2>&1)
  if [[ $? -ne 0 ]]; then
    ng "PG primary" "psql 连接失败:$out"
    return
  fi
  local pg_ver; pg_ver=$(echo "$out" | awk -F'|' '{print $2}')
  local tbl_count
  tbl_count=$(PGPASSWORD="$PG_PASSWORD" psql -h "$PG_PRIMARY_HOST" -p "$PG_PRIMARY_PORT" \
              -U "$PG_USER" -d "$PG_DB" -tAc "select count(*) from pg_tables where schemaname='batch'" 2>/dev/null)
  ok "PG primary" "PG ${pg_ver} on $PG_PRIMARY_HOST:$PG_PRIMARY_PORT,batch schema $tbl_count 表"
}

# ── PG replica(可跳过) ────────────────────────────────────
check_pg_replica() {
  if [[ $SKIP_REPLICA == 1 ]]; then
    skip "PG replica" "--no-replica 跳过"
    return
  fi
  if ! tcp_probe "$PG_REPLICA_HOST" "$PG_REPLICA_PORT" 3; then
    warn "PG replica" "$PG_REPLICA_HOST:$PG_REPLICA_PORT 不通(无 replica 或未部署),跳过"
    return
  fi
  if ! command -v psql >/dev/null 2>&1; then
    warn "PG replica" "端口通,但本机无 psql,跳过深度检查"
    return
  fi
  local recovery lag_sec
  recovery=$(PGPASSWORD="$PG_PASSWORD" psql -h "$PG_REPLICA_HOST" -p "$PG_REPLICA_PORT" \
             -U "$PG_USER" -d "$PG_DB" -tAc "select pg_is_in_recovery()" 2>&1)
  if [[ "$recovery" != "t" ]]; then
    ng "PG replica" "pg_is_in_recovery=$recovery(应为 t)"
    return
  fi
  lag_sec=$(PGPASSWORD="$PG_PASSWORD" psql -h "$PG_REPLICA_HOST" -p "$PG_REPLICA_PORT" \
            -U "$PG_USER" -d "$PG_DB" -tAc \
            "select coalesce(extract(epoch from now() - pg_last_xact_replay_timestamp())::int, 0)" 2>/dev/null)
  lag_sec=${lag_sec:-0}
  if (( lag_sec > PG_REPLICA_LAG_THRESHOLD_SEC )); then
    warn "PG replica" "in recovery,但 lag ${lag_sec}s > 阈值 ${PG_REPLICA_LAG_THRESHOLD_SEC}s"
    (( PASS++ ))  # warning 但仍算 PASS(不 fail-fast)
  else
    ok "PG replica" "in recovery,lag ${lag_sec}s"
  fi
}

# ── Redis ────────────────────────────────────────────────
check_redis() {
  if ! tcp_probe "$REDIS_HOST" "$REDIS_PORT" 3; then
    ng "Redis" "$REDIS_HOST:$REDIS_PORT 端口不通"
    return
  fi
  # 用 RESP 协议直发 PING(免装 redis-cli)
  local resp
  resp=$(printf 'PING\r\nQUIT\r\n' | timeout 3 bash -c "cat > /dev/tcp/$REDIS_HOST/$REDIS_PORT" 2>&1
         printf 'PING\r\nQUIT\r\n' | timeout 3 bash -c "exec 3<>/dev/tcp/$REDIS_HOST/$REDIS_PORT; cat >&3; cat <&3" 2>/dev/null | head -1)
  if echo "$resp" | grep -q "PONG"; then
    ok "Redis" "$REDIS_HOST:$REDIS_PORT PONG"
  elif command -v redis-cli >/dev/null 2>&1; then
    if redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
      ok "Redis" "$REDIS_HOST:$REDIS_PORT PONG"
    else
      ng "Redis" "端口通但 PING 失败"
    fi
  else
    # TCP 通就当 OK(没 redis-cli 也没法做更多)
    ok "Redis" "$REDIS_HOST:$REDIS_PORT 端口通(深度检查需 redis-cli)"
  fi
}

# ── Kafka ────────────────────────────────────────────────
check_kafka() {
  if [[ $SKIP_KAFKA == 1 ]]; then
    skip "Kafka" "--no-kafka 跳过"
    return
  fi
  local host="${KAFKA_BOOTSTRAP%:*}" port="${KAFKA_BOOTSTRAP##*:}"
  if ! tcp_probe "$host" "$port" 3; then
    ng "Kafka" "$KAFKA_BOOTSTRAP 端口不通"
    return
  fi
  # Kafka 协议探测较重,端口通 + 后续 BE 启动 metadata 拉取成功就够
  ok "Kafka" "$KAFKA_BOOTSTRAP 端口通"
}

# ── MinIO ────────────────────────────────────────────────
check_minio() {
  if [[ $SKIP_MINIO == 1 ]]; then
    skip "MinIO" "--no-minio 跳过"
    return
  fi
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$MINIO_URL/minio/health/live" 2>&1)
  if [[ "$code" == "200" ]]; then
    ok "MinIO" "$MINIO_URL/minio/health/live → 200"
  elif [[ "$code" == "000" ]]; then
    ng "MinIO" "$MINIO_URL 不通(超时 / 拒绝连接)"
  else
    ng "MinIO" "$MINIO_URL/minio/health/live → HTTP $code"
  fi
}

# ── main ─────────────────────────────────────────────────
[[ $QUIET == 1 ]] || echo "── 基建健康检查 ──"
check_pg_primary
check_pg_replica
check_redis
check_kafka
check_minio
[[ $QUIET == 1 ]] || echo
[[ $QUIET == 1 ]] || printf "结果:PASS=%d  FAIL=%d  SKIP=%d\n" "$PASS" "$FAIL" "$SKIP"

if (( FAIL > 0 )); then
  [[ $QUIET == 1 ]] || echo
  [[ $QUIET == 1 ]] || echo "建议:docker compose up -d  或检查上述失败项的 host/port env var"
  exit 1
fi
exit 0
