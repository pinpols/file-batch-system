#!/usr/bin/env bash
# =========================================================
# heal-zombie-pipelines.sh - 自愈:把超期 stale 的 pipeline_instance 转 FAILED
# 说明：
# 1) 配合 FileGovernanceProperties.processingDelayMaxAgeSeconds(默认 7 天)使用 —
#    scheduler 已不再 WARN 这种 zombie, 但行还留在 RUNNING, 需要本脚本主动转终态.
# 2) 选定 run_status='RUNNING' 且 started_at < now() - maxAgeSeconds 的行,
#    UPDATE 为 FAILED + 写 finished_at + 标 trace 备注.
# 3) 默认 dry-run; 实际执行需显式 BATCH_HEAL_DRY_RUN=false.
# =========================================================
# 使用方法:
#   # dry-run(默认), 仅打印将处置的行
#   bash scripts/ops/heal-zombie-pipelines.sh
#
#   # 实际执行
#   BATCH_HEAL_DRY_RUN=false bash scripts/ops/heal-zombie-pipelines.sh
#
#   # 自定义 maxAgeSeconds(覆盖默认 7 天 = 604800)
#   BATCH_HEAL_ZOMBIE_MAX_AGE_SECONDS=86400 \
#     BATCH_HEAL_DRY_RUN=false bash scripts/ops/heal-zombie-pipelines.sh
#
# 变量:
#   BATCH_HEAL_DRY_RUN                  true(默认) / false
#   BATCH_HEAL_ZOMBIE_MAX_AGE_SECONDS  超过该值仍 RUNNING 视为 zombie(默认 604800 = 7d)
#   BATCH_HEAL_ZOMBIE_TENANT           只处置指定租户(留空 = 全部)
#   PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE  PostgreSQL 连接信息
#                                                       默认: localhost:15432
#                                                            batch_user/batch_pass_123
#                                                            batch_platform

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

# ── 配置 ─────────────────────────────────────────────────────────────
DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"
MAX_AGE="${BATCH_HEAL_ZOMBIE_MAX_AGE_SECONDS:-604800}"
TENANT="${BATCH_HEAL_ZOMBIE_TENANT:-}"

psql_file() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
       -X -A -t -F '|' -v ON_ERROR_STOP=1 \
       -v max_age_seconds="$MAX_AGE" \
       -v tenant="$TENANT" "$@"
}

# ── 巡检 ───────────────────────────────────────────────────────────────────
echo "==> heal-zombie-pipelines.sh"
echo "    DRY_RUN  = $DRY_RUN"
echo "    MAX_AGE  = ${MAX_AGE}s ($((MAX_AGE / 86400)) days)"
echo "    TENANT   = ${TENANT:-<all>}"
echo "    PG       = ${PGHOST}:${PGPORT}/${PGDATABASE} (user=${PGUSER})"
echo

echo "==> 待处置 zombie pipeline_instance 列表:"
echo "    id|tenant_id|job_code|pipeline_type|started_at|age_seconds"
psql_file -f "$OPS_SQL_DIR/heal-zombie-pipelines-list.sql" | sed 's/^/    /'

COUNT=$(psql_file -f "$OPS_SQL_DIR/heal-zombie-pipelines-count.sql")
COUNT=$(echo "$COUNT" | tr -d '[:space:]')
echo
echo "==> 共 $COUNT 行 zombie 待处置"

if [ "$COUNT" = "0" ]; then
  echo "✓ 无 zombie, 退出"
  exit 0
fi

# ── 执行 ───────────────────────────────────────────────────────────────────
if [ "$DRY_RUN" = "true" ]; then
  echo
  echo "[DRY-RUN] 不执行 UPDATE; 设 BATCH_HEAL_DRY_RUN=false 实际执行"
  exit 0
fi

echo
echo "==> 执行 UPDATE → FAILED..."
UPDATED=$(psql_file -f "$OPS_SQL_DIR/heal-zombie-pipelines-update.sql" | wc -l | tr -d '[:space:]')
echo "✓ 已转 FAILED: $UPDATED 行"
