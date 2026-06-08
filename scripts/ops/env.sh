#!/usr/bin/env bash
# ops 脚本公共变量。默认只读本地 env,生产/远端请显式传 PG* / URL / TOKEN。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/ops/env.sh must be sourced, not executed" >&2
  exit 2
fi

OPS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/env-common.sh
source "$OPS_ROOT/scripts/lib/env-common.sh"

OPS_SQL_DIR="${OPS_SQL_DIR:-$OPS_ROOT/scripts/ops/sql}"
BATCH_SCHEMA="${BATCH_SCHEMA:-batch}"
export OPS_SQL_DIR BATCH_SCHEMA
