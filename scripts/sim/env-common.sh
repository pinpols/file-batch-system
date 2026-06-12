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

# ---------------------------------------------------------------------------
# platform / business 双容器路由(双栈:单机 = 同容器异库;Citus = platform 走
# 协调器、business 走原 PG)。所有默认值回退到现有单机配置,不传环境变量时行为不变。
#   单机:  PG_PLATFORM_CONTAINER=PG_BUSINESS_CONTAINER=batch-postgres-primary
#   Citus:  source scripts/sim/env-citus.sh 后 platform→citus-coord/postgres,
#           business→batch-postgres-primary/batch_business_part
# ---------------------------------------------------------------------------
export PG_PLATFORM_CONTAINER="${PG_PLATFORM_CONTAINER:-$PG_CONTAINER}"
export PG_PLATFORM_DB="${PG_PLATFORM_DB:-$POSTGRES_DB}"
export PG_PLATFORM_USER="${PG_PLATFORM_USER:-$POSTGRES_USER}"
export PG_BUSINESS_CONTAINER="${PG_BUSINESS_CONTAINER:-$PG_CONTAINER}"
export PG_BUSINESS_DB="${PG_BUSINESS_DB:-$BUSINESS_DB_NAME}"
export PG_BUSINESS_USER="${PG_BUSINESS_USER:-$POSTGRES_USER}"

# psql helper:platform / business 各自路由,sim 脚本统一用这两个函数,不再内联容器名
pg_platform() {
  docker exec -i "$PG_PLATFORM_CONTAINER" psql -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" "$@"
}
pg_business() {
  docker exec -i "$PG_BUSINESS_CONTAINER" psql -U "$PG_BUSINESS_USER" -d "$PG_BUSINESS_DB" "$@"
}
export -f pg_platform pg_business 2>/dev/null || true

mkdir -p "$REPORT_DIR"
