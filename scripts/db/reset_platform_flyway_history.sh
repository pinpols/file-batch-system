#!/usr/bin/env bash
# 用途：重置明确指定的本地平台数据库 Flyway 历史记录。
# 用法：在仓库根目录执行 bash scripts/db/reset_platform_flyway_history.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

# 清空 batch.flyway_schema_history，重启 batch-orchestrator 或 batch-trigger 后会重新执行迁移并回填历史。
# 默认连接与 batch-orchestrator application-local.yml 一致；可通过环境变量覆盖。

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
SQL="${ROOT}/scripts/db/reset_platform_flyway_history.sql"
PGDATABASE="${PGDATABASE:-$PLATFORM_DB}"

if [[ ! -f "${SQL}" ]]; then
  echo "missing ${SQL}" >&2
  exit 1
fi

echo "Truncating batch.flyway_schema_history on ${PGUSER}@${PGHOST}:${PGPORT}/${PGDATABASE} ..."
psql -v ON_ERROR_STOP=1 -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" -f "${SQL}"
echo "Done. Start orchestrator (and/or trigger) to re-run Flyway migrations."
