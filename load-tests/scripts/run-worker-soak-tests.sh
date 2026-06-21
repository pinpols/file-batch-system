#!/usr/bin/env bash
# =========================================================
# run-worker-soak-tests.sh — 24h+ 持续 soak/longevity 压测
#
# Plan: docs/plans/r3-3-soak-tests.md
#
# 与 run-worker-stress-tests.sh 区别:
#   - stress = 几分钟 burst,验吞吐 / 延迟
#   - soak   = 24h+ 持续,验连接池泄漏、静态缓存增长、跨日 bizDate、
#              Hikari conn leak、Kafka consumer 累计 lag、GC 长期趋势
#
# 设计要点:
#   - 持续 SOAK_HOURS 小时(默认 24,可覆盖)
#   - 持续 SOAK_RPS RPS(默认 200,可覆盖)
#   - 4 类 worker 混合流量:IMPORT 30% / EXPORT 20% / DISPATCH 30% / WORKFLOW 20%
#   - 启动 start-soak.sh 注入 JFR 启动参数 + clock offset(跨日时间偏移)
#   - 后台 monitor-soak.sh 周期 jcmd heap_dump + 健康检查 + 5 项退出条件评估
#   - 收尾 analyze-soak.sh:jfr summary + jq → markdown 报告(6 维度)
#
# 用法:
#   SOAK_HOURS=24 SOAK_RPS=200 bash load-tests/scripts/run-worker-soak-tests.sh
#   # 5 分钟 dry-run 验证:
#   SOAK_HOURS=0 SOAK_MINUTES=5 SOAK_RPS=20 SKIP_START=1 SKIP_MONITOR=0 \
#     bash load-tests/scripts/run-worker-soak-tests.sh
# =========================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"
LOCAL_SCRIPTS="$ROOT_DIR/scripts/local"
SOAK_LOG_DIR="$ROOT_DIR/logs/soak"
mkdir -p "$SOAK_LOG_DIR"

SOAK_HOURS="${SOAK_HOURS:-24}"
SOAK_MINUTES="${SOAK_MINUTES:-0}"
SOAK_RPS="${SOAK_RPS:-200}"
SOAK_RUN_ID="${SOAK_RUN_ID:-soak-$(date +%Y%m%d%H%M%S)}"

# 混合流量百分比(对应 plan)
PCT_IMPORT="${PCT_IMPORT:-30}"
PCT_EXPORT="${PCT_EXPORT:-20}"
PCT_DISPATCH="${PCT_DISPATCH:-30}"
PCT_WORKFLOW="${PCT_WORKFLOW:-20}"

# 跨日时间偏移:启动 JVM clock offset(BatchDateTimeSupport 当前未支持该属性,
# 启动脚本仅尝试注入 -Dbatch.testing.clock-offset;真正生效需后续在 Clock bean
# 上实现注入。详见 docs/plans/r3-3-soak-tests.md TODO 段)。
CLOCK_OFFSET="${CLOCK_OFFSET:-+12h}"

# 调试/跳过开关
SKIP_START="${SKIP_START:-0}"
SKIP_MONITOR="${SKIP_MONITOR:-0}"
SKIP_ANALYZE="${SKIP_ANALYZE:-0}"
SKIP_PRECHECK="${SKIP_PRECHECK:-0}"

# 退出条件阈值(monitor-soak.sh 也读这些)
export SOAK_LOG_DIR SOAK_RUN_ID
export HEAP_USAGE_THRESHOLD_PCT="${HEAP_USAGE_THRESHOLD_PCT:-85}"
export HEAP_USAGE_BREACH_SECONDS="${HEAP_USAGE_BREACH_SECONDS:-300}"
export HIKARI_FULL_BREACH_SECONDS="${HIKARI_FULL_BREACH_SECONDS:-60}"
export KAFKA_LAG_THRESHOLD="${KAFKA_LAG_THRESHOLD:-100000}"
export KAFKA_LAG_BREACH_SECONDS="${KAFKA_LAG_BREACH_SECONDS:-30}"
export ERROR_RATE_THRESHOLD_PCT="${ERROR_RATE_THRESHOLD_PCT:-1.0}"
export ERROR_RATE_BREACH_SECONDS="${ERROR_RATE_BREACH_SECONDS:-300}"
export DISK_USAGE_THRESHOLD_PCT="${DISK_USAGE_THRESHOLD_PCT:-90}"

# === Pre-check: 磁盘 + RAM ===
precheck() {
  echo "==> Soak pre-check"
  # 磁盘:24h JFR + 多次 heap dump ≈ 20GB,要求 ≥ 30GB 可用
  local avail_gb
  if [[ "$(uname)" == "Darwin" ]]; then
    avail_gb="$(df -g "$ROOT_DIR" | awk 'NR==2 {print $4}')"
  else
    avail_gb="$(df -BG "$ROOT_DIR" | awk 'NR==2 {gsub("G","",$4); print $4}')"
  fi
  echo "    disk avail: ${avail_gb} GB (require >= 30 GB for 24h run)"
  if [[ -n "$avail_gb" && "$avail_gb" -lt 30 && "$SOAK_HOURS" -ge 12 ]]; then
    echo "    ERROR: 磁盘不足 30 GB,24h soak 会写满 logs/soak/;短跑请显式设 SOAK_HOURS<12" >&2
    return 1
  fi
  # RAM
  local ram_gb
  if [[ "$(uname)" == "Darwin" ]]; then
    ram_gb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
  else
    ram_gb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))
  fi
  echo "    RAM total : ${ram_gb} GB (require >= 16 GB)"
  if [[ -n "$ram_gb" && "$ram_gb" -lt 16 && "$SOAK_HOURS" -ge 12 ]]; then
    echo "    ERROR: RAM 不足 16 GB,跑不动 5 个 JVM + JFR" >&2
    return 1
  fi
  return 0
}

if [[ "$SKIP_PRECHECK" != "1" ]]; then
  precheck || exit 2
fi

# 计算持续秒数
TOTAL_SECONDS=$(( SOAK_HOURS * 3600 + SOAK_MINUTES * 60 ))
if [[ "$TOTAL_SECONDS" -le 0 ]]; then
  echo "ERROR: SOAK_HOURS + SOAK_MINUTES 必须 > 0" >&2
  exit 2
fi

echo "==> Soak run: id=${SOAK_RUN_ID} duration=${TOTAL_SECONDS}s rps=${SOAK_RPS}"
echo "    mix: IMPORT=${PCT_IMPORT}% EXPORT=${PCT_EXPORT}% DISPATCH=${PCT_DISPATCH}% WORKFLOW=${PCT_WORKFLOW}%"
echo "    log dir: ${SOAK_LOG_DIR}"

# === 启动 console-api + 4 worker(JFR + clock offset)===
if [[ "$SKIP_START" != "1" ]]; then
  CLOCK_OFFSET="$CLOCK_OFFSET" SOAK_RUN_ID="$SOAK_RUN_ID" \
    bash "$LOAD_DIR/scripts/start-soak.sh" || {
      echo "ERROR: start-soak.sh 失败" >&2; exit 3; }
fi

# === 启动后台监控 + 退出条件评估 ===
MONITOR_PID=""
MONITOR_STOP_FLAG="$SOAK_LOG_DIR/${SOAK_RUN_ID}.stop"
rm -f "$MONITOR_STOP_FLAG"

if [[ "$SKIP_MONITOR" != "1" ]]; then
  SOAK_STOP_FLAG="$MONITOR_STOP_FLAG" \
    bash "$LOAD_DIR/scripts/monitor-soak.sh" > "$SOAK_LOG_DIR/${SOAK_RUN_ID}.monitor.log" 2>&1 &
  MONITOR_PID=$!
  echo "==> monitor-soak.sh started pid=${MONITOR_PID}"
fi

# === 流量发生器:简单循环触发 4 类 job,按百分比抽样 ===
# 出于 dry-run 友好性,这里用轻量 curl/psql 触发,而不是 Gatling(避免 24h 跑 mvn JVM)
BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"

# 4 类 job code(与 run-worker-stress-tests.sh 对齐)
JOB_IMPORT="${JOB_IMPORT:-import_customer_job}"
JOB_EXPORT="${JOB_EXPORT:-export_settlement_job}"
JOB_DISPATCH="${JOB_DISPATCH:-lt_dispatch_local_job}"
JOB_WORKFLOW="${JOB_WORKFLOW:-lt_process_sql_job}"

pick_job() {
  local r=$(( RANDOM % 100 ))
  local cum=$PCT_IMPORT
  if (( r < cum )); then echo "$JOB_IMPORT"; return; fi
  cum=$(( cum + PCT_EXPORT ))
  if (( r < cum )); then echo "$JOB_EXPORT"; return; fi
  cum=$(( cum + PCT_DISPATCH ))
  if (( r < cum )); then echo "$JOB_DISPATCH"; return; fi
  echo "$JOB_WORKFLOW"
}

# 每秒请求间隔(微秒)
SLEEP_PER_REQ_US=$(( 1000000 / SOAK_RPS ))

START_TS=$(date +%s)
END_TS=$(( START_TS + TOTAL_SECONDS ))
COUNT=0
ERRORS=0
TRAFFIC_LOG="$SOAK_LOG_DIR/${SOAK_RUN_ID}.traffic.log"
: > "$TRAFFIC_LOG"

echo "==> traffic loop start, until=$(date -r $END_TS '+%F %T' 2>/dev/null || echo $END_TS)"

cleanup() {
  echo "==> cleanup: stopping monitor + analyzing"
  # 给 monitor 发停车信号;若 monitor 已写入退出原因(非空),不覆盖,保留现场。
  if [[ ! -s "$MONITOR_STOP_FLAG" ]]; then
    : > "$MONITOR_STOP_FLAG"
  fi
  if [[ -n "$MONITOR_PID" ]]; then
    wait "$MONITOR_PID" 2>/dev/null || true
  fi
  if [[ "$SKIP_ANALYZE" != "1" ]]; then
    SOAK_RUN_ID="$SOAK_RUN_ID" bash "$LOAD_DIR/scripts/analyze-soak.sh" || \
      echo "WARN: analyze-soak.sh 失败,可手动重跑" >&2
  fi
}
trap cleanup EXIT INT TERM

while (( $(date +%s) < END_TS )); do
  # 退出条件触发:monitor 写 stop flag
  if [[ -f "$MONITOR_STOP_FLAG" ]]; then
    echo "==> EXIT CONDITION TRIGGERED by monitor-soak.sh,停流量留现场" >&2
    cat "$MONITOR_STOP_FLAG" >&2 || true
    break
  fi

  job="$(pick_job)"
  # 用 curl 异步触发 trigger(失败不退出,只计数)
  if ! curl -fsS -o /dev/null -m 5 \
      -X POST "$TRIGGER_BASE_URL/internal/trigger/fire" \
      -H "Content-Type: application/json" \
      -H "X-Internal-Secret: $INTERNAL_SECRET" \
      -d "{\"tenantId\":\"$LOAD_TEST_TENANT_ID\",\"jobCode\":\"$job\",\"bizDate\":\"$BIZ_DATE\",\"params\":{\"soakRunId\":\"$SOAK_RUN_ID\"}}" \
      2>>"$TRAFFIC_LOG"; then
    ERRORS=$(( ERRORS + 1 ))
  fi
  COUNT=$(( COUNT + 1 ))

  # 每 60s 打印一行
  if (( COUNT % (SOAK_RPS * 60) == 0 )); then
    elapsed=$(( $(date +%s) - START_TS ))
    echo "    progress: elapsed=${elapsed}s sent=${COUNT} errors=${ERRORS}"
  fi

  # 节流(macOS / GNU sleep 都支持小数秒)
  sleep_s="$(awk -v us="$SLEEP_PER_REQ_US" 'BEGIN{printf "%.4f", us/1000000}')"
  sleep "$sleep_s"
done

echo "==> traffic loop done: sent=${COUNT} errors=${ERRORS}"
# trap 接管 cleanup
exit 0
