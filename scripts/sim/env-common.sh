#!/usr/bin/env bash
# sim 脚本公共变量。调用方可先设置 SIM_STAGE_NAME 覆盖 run 前缀。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/sim/env-common.sh must be sourced, not executed" >&2
  exit 2
fi

SIM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/env-common.sh
source "$SIM_ROOT/scripts/lib/env-common.sh"

SIM_STAGE_NAME="${SIM_STAGE_NAME:-$(basename "$0" .sh)}"
SIM_STAGE_NAME="${SIM_STAGE_NAME#[0-9][0-9]-}"

export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-${SIM_STAGE_NAME}-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-${SIM_STAGE_NAME}-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
export PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
export PG_PLATFORM_DB="${PG_PLATFORM_DB:-$POSTGRES_DB}"
export PG_PLATFORM_USER="${PG_PLATFORM_USER:-${POSTGRES_USER:-batch_user}}"
export PG_BUSINESS_DB="${PG_BUSINESS_DB:-$BUSINESS_DB_NAME}"
# SIM 在本机同时运行基础设施、多个 Java 服务和故障注入进程。所有由阶段脚本触发的
# restart.sh 都继承这个受限堆；调用方显式传入 JAVA_OPTS 时保持原样。
export SIM_JAVA_OPTS="${SIM_JAVA_OPTS:--Xmx384m -XX:MaxMetaspaceSize=256m}"
export JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS}"
mkdir -p "$REPORT_DIR"
batch_require_python

# 应用服务运行在 Compose 网络时，夹具中的外部依赖必须使用服务名；宿主机
# 直接运行 JVM 时才使用端口映射。统一在这里判定，避免每个 sim 脚本各写一套。
sim_container_running() {
  local container_name="$1"
  [[ "$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null || true)" == "true" ]]
}

sim_container_stack_active() {
  sim_container_running batch-console-api
}

sim_mockserver_base_url() {
  if sim_container_stack_active; then
    printf '%s\n' "http://mockserver:1080"
  else
    printf '%s\n' "http://localhost:${MOCKSERVER_HOST_PORT:-11080}"
  fi
}

sim_sftp_endpoint() {
  if sim_container_stack_active; then
    printf '%s\n' "sftp 22"
  else
    printf '%s\n' "${SFTP_HOST:-127.0.0.1} ${SFTP_PORT:-12222}"
  fi
}

# 按起始 BIZ_DATE 计算第 N 轮的业务日期(base + N-1 天),供多轮 sim 错开 bizDate、
# 避免同 job+bizDate 被幂等去重撞掉。
biz_date_for_round() {
  "$PYTHON_BIN" - "$BIZ_DATE" "$1" <<'PY'
import datetime as dt
import sys

base = dt.date.fromisoformat(sys.argv[1])
print((base + dt.timedelta(days=int(sys.argv[2]) - 1)).isoformat())
PY
}
