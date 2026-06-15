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
mkdir -p "$REPORT_DIR"
