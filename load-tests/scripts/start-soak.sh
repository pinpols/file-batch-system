#!/usr/bin/env bash
# =========================================================
# start-soak.sh — 启动 console-api + 4 worker(JFR + clock offset)
#
# 复用 scripts/local/start-all.sh,通过环境变量注入额外 JVM 参数:
#   1) JFR:启动期 -XX:StartFlightRecording=name=soak,duration=${SOAK_HOURS}h,...
#   2) Heap dump on OOM:-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...
#   3) 跨日时间偏移:-Dbatch.testing.clock-offset=${CLOCK_OFFSET}
#       注意:BatchDateTimeSupport 当前使用注入的 Clock bean,不读该属性。
#       本脚本仍透传该 -D,等后续 Clock bean 实现 offset 支持后无需改脚本。
#       详见 plan TODO 段。
#
# 用法:
#   SOAK_RUN_ID=soak-... CLOCK_OFFSET=+12h bash load-tests/scripts/start-soak.sh
# =========================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOAK_LOG_DIR="${SOAK_LOG_DIR:-$ROOT_DIR/logs/soak}"
SOAK_RUN_ID="${SOAK_RUN_ID:-soak-$(date +%Y%m%d%H%M%S)}"
SOAK_HOURS="${SOAK_HOURS:-24}"
CLOCK_OFFSET="${CLOCK_OFFSET:-+12h}"
mkdir -p "$SOAK_LOG_DIR"

# JFR + heap dump + clock offset
# 注意:JFR filename 含 %p(PID 占位符),JVM 自动展开。
JFR_OPTS="-XX:StartFlightRecording=name=soak-${SOAK_RUN_ID},duration=${SOAK_HOURS}h,filename=${SOAK_LOG_DIR}/jvm-${SOAK_RUN_ID}-%p.jfr,settings=profile,dumponexit=true"
OOM_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${SOAK_LOG_DIR}/"
CLOCK_OPTS="-Dbatch.testing.clock-offset=${CLOCK_OFFSET}"

# 透传给 start-all.sh 的 JAVA_OPTS(会被追加到每个模块的启动参数)
export JAVA_OPTS="${JAVA_OPTS:-} ${JFR_OPTS} ${OOM_OPTS} ${CLOCK_OPTS}"

echo "==> start-soak: JAVA_OPTS=${JAVA_OPTS}"
echo "==> start-soak: 调用 scripts/local/start-all.sh"

bash "$ROOT_DIR/scripts/local/start-all.sh"

# 记录每个 java 进程的 pid → 文件,monitor-soak.sh / analyze-soak.sh 用
PIDS_FILE="$SOAK_LOG_DIR/${SOAK_RUN_ID}.pids"
: > "$PIDS_FILE"
for jar in "$ROOT_DIR"/build/runtime-jars/*.jar; do
  [[ -f "$jar" ]] || continue
  name="$(basename "$jar" .jar)"
  # 从 start-all.pids 提取
  pid="$(awk -v n="$name" '$1==n {print $2}' "$ROOT_DIR/logs/start-all.pids" 2>/dev/null || true)"
  if [[ -n "${pid:-}" ]]; then
    printf '%s\t%s\n' "$name" "$pid" >> "$PIDS_FILE"
  fi
done

echo "==> start-soak: 已记录 pid 到 $PIDS_FILE"
cat "$PIDS_FILE" || true
