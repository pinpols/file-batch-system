#!/usr/bin/env bash
# 用途：reset-runtime.sh:把 sim 运行态数据清回干净基线,让整套 08→25 + 05/06/07 可重复跑。
# 用法：在仓库根目录执行 bash scripts/sim/00-reset-runtime.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 00-reset-runtime.sh:把 sim 运行态数据清回干净基线,让整套 08→25 + 05/06/07 可重复跑。
#
# 背景:多数 stage 按唯一 batchNo/requestId 断言,天然可重复;但少数 stage 的 COUNT 断言
# 依赖共享表的累积量(如 12-export 读 biz.customer_account 全部 ta ACTIVE 行、24-trigger
# 的 outbox 重试实例计数),跨 stage / 跨重复运行会累积污染。跑前 reset 一次即全套可重复。
#
# 清:运行态(job_/pipeline_/trigger_/outbox/file_/task/registry/audit/process_staging)
#     + biz.* 业务数据表。
# 保:所有 *_definition / *_config / *_template / *_schedule(编排定义)、tenant、console_user、
#     api_key、4 张系统表(batch_runtime_default_parameter/step_registry/shedlock/biz_table_schema)。
#
# 用法:source env-citus 后 `bash scripts/sim/00-reset-runtime.sh`;不 source 时回落单机(双栈安全)。
# =========================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1
# shellcheck disable=SC2034 # env-common.sh 读取该阶段名。
SIM_STAGE_NAME="reset-runtime"
# shellcheck source=env-common.sh
# shellcheck disable=SC1091 # 运行时从仓库绝对路径加载。
source "$ROOT/scripts/sim/env-common.sh"
# shellcheck source=../lib/destructive-ops.sh
source "$ROOT/scripts/lib/destructive-ops.sh"
SQL_DIR="$ROOT/scripts/sim/sql"

PG_PLAT_C="${PG_PLATFORM_CONTAINER:-$PG_CONTAINER}"
PG_PLAT_U="${PG_PLATFORM_USER:-$POSTGRES_USER}"
PG_PLAT_D="${PG_PLATFORM_DB:-$PLATFORM_DB}"
PG_BIZ_C="${PG_BUSINESS_CONTAINER:-$PG_CONTAINER}"
PG_BIZ_U="${PG_BUSINESS_USER:-$POSTGRES_USER}"
PG_BIZ_D="${PG_BUSINESS_DB:-$BUSINESS_DB}"

# 与 sim-harness 共用平台清理清单，避免新阶段仅在其中一个入口清理。
PLATFORM_RESET_SQL="$ROOT/scripts/local/sql/reset-platform-runtime.sql"

BIZ_TABLES="biz.customer_account, biz.transaction, biz.risk_score, biz.risk_alert,
  biz.settlement_batch, biz.settlement_detail, biz.process_account_summary, biz.process_event_copy,
  biz.process_order_event, biz.process_stage4_source, biz.process_stage4_target, biz.import_stage2c_customer"

batch_require_compose_container "$PG_PLAT_C" || exit $?
if [[ "$PG_BIZ_C" != "$PG_PLAT_C" ]]; then
  batch_require_compose_container "$PG_BIZ_C" || exit $?
fi

echo "==> reset 平台运行态(${PG_PLAT_C}/${PG_PLAT_D})"
docker exec -e PGOPTIONS='-c batch.destructive_ops=sim-reset' -i "$PG_PLAT_C" psql -U "$PG_PLAT_U" -d "$PG_PLAT_D" -v ON_ERROR_STOP=1 \
  -f /dev/stdin < "$PLATFORM_RESET_SQL"
plat_rc=$?

echo "==> reset biz 业务数据(${PG_BIZ_C}/${PG_BIZ_D})"
docker exec -e PGOPTIONS='-c batch.destructive_ops=sim-reset' -i "$PG_BIZ_C" psql -U "$PG_BIZ_U" -d "$PG_BIZ_D" -v ON_ERROR_STOP=1 \
  -v reset_tables="$BIZ_TABLES" -f /dev/stdin < "$SQL_DIR/reset-business-runtime.sql"
biz_rc=$?

if [[ $plat_rc -eq 0 && $biz_rc -eq 0 ]]; then
  echo "✅ runtime 重置完成(运行态 + biz 数据已清空,config/definition/fixture 保留)"
else
  echo "❌ 重置失败 plat_rc=$plat_rc biz_rc=$biz_rc" >&2
  exit 1
fi
