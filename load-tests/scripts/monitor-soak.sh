#!/usr/bin/env bash
# =========================================================
# monitor-soak.sh — 后台周期 jcmd heap dump + 健康检查 + 退出条件评估
#
# 由 run-worker-soak-tests.sh 在后台启动。每 30s 一次轮询:
#   - 健康:health-check-infra.sh(PG/Kafka/Redis/MinIO)
#   - 每 4h 一次 jcmd $pid GC.heap_dump
#   - 退出条件 5 项(plan 表):任一触发就 touch $SOAK_STOP_FLAG
#       1) Heap usage > HEAP_USAGE_THRESHOLD_PCT 持续 HEAP_USAGE_BREACH_SECONDS
#       2) Hikari active == max 持续 HIKARI_FULL_BREACH_SECONDS
#       3) Kafka lag > KAFKA_LAG_THRESHOLD 持续 KAFKA_LAG_BREACH_SECONDS
#       4) Error rate > ERROR_RATE_THRESHOLD_PCT 持续 ERROR_RATE_BREACH_SECONDS
#       5) Disk usage > DISK_USAGE_THRESHOLD_PCT 立即
# =========================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOAK_LOG_DIR="${SOAK_LOG_DIR:-$ROOT_DIR/logs/soak}"
SOAK_RUN_ID="${SOAK_RUN_ID:-soak-unknown}"
SOAK_STOP_FLAG="${SOAK_STOP_FLAG:-$SOAK_LOG_DIR/${SOAK_RUN_ID}.stop}"
PIDS_FILE="$SOAK_LOG_DIR/${SOAK_RUN_ID}.pids"

POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-30}"
HEAP_DUMP_INTERVAL_SEC="${HEAP_DUMP_INTERVAL_SEC:-14400}"  # 4h

# 阈值(已 export 自 run-worker-soak-tests.sh)
HEAP_USAGE_THRESHOLD_PCT="${HEAP_USAGE_THRESHOLD_PCT:-85}"
HEAP_USAGE_BREACH_SECONDS="${HEAP_USAGE_BREACH_SECONDS:-300}"
HIKARI_FULL_BREACH_SECONDS="${HIKARI_FULL_BREACH_SECONDS:-60}"
KAFKA_LAG_THRESHOLD="${KAFKA_LAG_THRESHOLD:-100000}"
KAFKA_LAG_BREACH_SECONDS="${KAFKA_LAG_BREACH_SECONDS:-30}"
ERROR_RATE_THRESHOLD_PCT="${ERROR_RATE_THRESHOLD_PCT:-1.0}"
ERROR_RATE_BREACH_SECONDS="${ERROR_RATE_BREACH_SECONDS:-300}"
DISK_USAGE_THRESHOLD_PCT="${DISK_USAGE_THRESHOLD_PCT:-90}"

# Actuator base(每个模块独立端口),只取 console-api 作主样本
CONSOLE_BASE_URL="${CONSOLE_BASE_URL:-http://localhost:18080}"

# 各退出条件累计起始时间(0 = 未在违规)
heap_breach_since=0
hikari_breach_since=0
kafka_breach_since=0
error_breach_since=0
last_heap_dump=0

trigger_stop() {
  local reason="$1"
  echo "$(date -u +%FT%TZ) STOP: $reason" | tee -a "$SOAK_STOP_FLAG" >&2
}

heap_dump_all() {
  if [[ ! -f "$PIDS_FILE" ]]; then return 0; fi
  local stamp
  stamp="$(date +%Y%m%d-%H%M)"
  while IFS=$'\t' read -r name pid; do
    [[ -n "${pid:-}" ]] || continue
    if kill -0 "$pid" 2>/dev/null; then
      local out="$SOAK_LOG_DIR/heap-${name}-${stamp}.hprof"
      echo "    jcmd $pid GC.heap_dump $out"
      jcmd "$pid" GC.heap_dump "$out" 2>&1 | head -3 || true
    fi
  done < "$PIDS_FILE"
}

# 用 actuator/metrics 取数值;失败返回空
metric_value() {
  local name="$1" tag="${2:-}"
  local url="$CONSOLE_BASE_URL/actuator/metrics/$name"
  [[ -n "$tag" ]] && url="$url?tag=$tag"
  curl -fsS -m 3 "$url" 2>/dev/null | sed -n 's/.*"VALUE","value":\([0-9.]*\).*/\1/p' | head -1
}

# 1) Heap usage %
check_heap() {
  local used max pct
  used="$(metric_value jvm.memory.used area:heap)"
  max="$(metric_value jvm.memory.max area:heap)"
  if [[ -z "$used" || -z "$max" || "$max" == "0" ]]; then return; fi
  pct="$(awk -v u="$used" -v m="$max" 'BEGIN{printf "%.0f", u*100/m}')"
  if (( pct > HEAP_USAGE_THRESHOLD_PCT )); then
    if (( heap_breach_since == 0 )); then heap_breach_since=$(date +%s); fi
    local elapsed=$(( $(date +%s) - heap_breach_since ))
    if (( elapsed >= HEAP_USAGE_BREACH_SECONDS )); then
      trigger_stop "heap usage ${pct}% > ${HEAP_USAGE_THRESHOLD_PCT}% for ${elapsed}s"
    fi
  else
    heap_breach_since=0
  fi
}

# 2) Hikari active / max
check_hikari() {
  local active max
  active="$(metric_value hikaricp.connections.active)"
  max="$(metric_value hikaricp.connections.max)"
  if [[ -z "$active" || -z "$max" || "$max" == "0" ]]; then return; fi
  if awk -v a="$active" -v m="$max" 'BEGIN{exit !(a>=m)}'; then
    if (( hikari_breach_since == 0 )); then hikari_breach_since=$(date +%s); fi
    local elapsed=$(( $(date +%s) - hikari_breach_since ))
    if (( elapsed >= HIKARI_FULL_BREACH_SECONDS )); then
      trigger_stop "hikari active(${active}) == max(${max}) for ${elapsed}s"
    fi
  else
    hikari_breach_since=0
  fi
}

# 3) Kafka lag(本地用 docker exec kafka-consumer-groups,简化:汇总最大 lag)
check_kafka_lag() {
  local lag
  lag="$(docker exec -i $(docker ps --format '{{.Names}}' | grep -E 'kafka$|kafka-1' | head -1) \
        kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null \
        | awk 'NR>1 && $6 ~ /^[0-9]+$/ {if($6>m) m=$6} END{print m+0}')"
  if [[ -z "$lag" ]]; then return; fi
  if (( lag > KAFKA_LAG_THRESHOLD )); then
    if (( kafka_breach_since == 0 )); then kafka_breach_since=$(date +%s); fi
    local elapsed=$(( $(date +%s) - kafka_breach_since ))
    if (( elapsed >= KAFKA_LAG_BREACH_SECONDS )); then
      trigger_stop "kafka max lag ${lag} > ${KAFKA_LAG_THRESHOLD} for ${elapsed}s"
    fi
  else
    kafka_breach_since=0
  fi
}

# 4) Error rate(http.server.requests.errors / total)
check_error_rate() {
  local total err pct
  total="$(metric_value http.server.requests)"
  err="$(metric_value http.server.requests status:5xx)"
  if [[ -z "$total" || "$total" == "0" ]]; then return; fi
  err="${err:-0}"
  pct="$(awk -v e="$err" -v t="$total" 'BEGIN{printf "%.2f", e*100/t}')"
  if awk -v p="$pct" -v th="$ERROR_RATE_THRESHOLD_PCT" 'BEGIN{exit !(p>th)}'; then
    if (( error_breach_since == 0 )); then error_breach_since=$(date +%s); fi
    local elapsed=$(( $(date +%s) - error_breach_since ))
    if (( elapsed >= ERROR_RATE_BREACH_SECONDS )); then
      trigger_stop "error rate ${pct}% > ${ERROR_RATE_THRESHOLD_PCT}% for ${elapsed}s"
    fi
  else
    error_breach_since=0
  fi
}

# 5) Disk usage(immediate)
check_disk() {
  local pct
  pct="$(df -P "$ROOT_DIR" | awk 'NR==2 {gsub("%","",$5); print $5}')"
  if [[ -n "$pct" ]] && (( pct > DISK_USAGE_THRESHOLD_PCT )); then
    trigger_stop "disk usage ${pct}% > ${DISK_USAGE_THRESHOLD_PCT}% (immediate)"
  fi
}

echo "==> monitor-soak: 启动 run_id=$SOAK_RUN_ID poll=${POLL_INTERVAL_SEC}s"

while true; do
  if [[ -f "$SOAK_STOP_FLAG" ]]; then
    echo "==> monitor-soak: stop flag detected, 退出"
    break
  fi

  # 健康检查 — 基建挂直接 stop
  if ! bash "$ROOT_DIR/scripts/local/health-check-infra.sh" --quiet 2>/dev/null; then
    trigger_stop "infra health-check failed"
  fi

  check_disk
  check_heap
  check_hikari
  check_kafka_lag
  check_error_rate

  # 周期 heap dump
  now=$(date +%s)
  if (( now - last_heap_dump >= HEAP_DUMP_INTERVAL_SEC )); then
    echo "$(date -u +%FT%TZ) heap_dump cycle"
    heap_dump_all
    last_heap_dump=$now
  fi

  sleep "$POLL_INTERVAL_SEC"
done

echo "==> monitor-soak: 结束"
