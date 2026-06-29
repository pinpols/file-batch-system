#!/usr/bin/env bash
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
cd "$ROOT"
SIM_STAGE_NAME="reset-runtime"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

PG_PLAT_C="${PG_PLATFORM_CONTAINER:-batch-postgres-primary}"
PG_PLAT_U="${PG_PLATFORM_USER:-batch_user}"
PG_PLAT_D="${PG_PLATFORM_DB:-batch_platform}"
PG_BIZ_C="${PG_BUSINESS_CONTAINER:-batch-postgres-primary}"
PG_BIZ_U="${PG_BUSINESS_USER:-batch_user}"
PG_BIZ_D="${PG_BUSINESS_DB:-batch_business}"

# 平台运行态(分区父表 TRUNCATE 自动级联子分区;Citus 下 sequential 防分片死锁;CASCADE 兜 FK 依赖)
PLAT_TABLES="batch.job_instance, batch.job_instance_dedup_key, batch.job_task, batch.job_partition, batch.job_step_instance,
  batch.job_execution_log, batch.pipeline_instance, batch.pipeline_progress, batch.pipeline_step_run,
  batch.trigger_request, batch.trigger_outbox_event, batch.trigger_misfire_pending, batch.trigger_runtime_state,
  batch.outbox_event, batch.outbox_event_dedup_key, batch.event_outbox_retry, batch.event_delivery_log,
  batch.file_record, batch.file_dispatch_record, batch.file_error_record, batch.file_channel_health, batch.file_audit_log,
  batch.custom_task_type_registry, batch.worker_registry, batch.worker_report_outbox,
  batch.dead_letter_task, batch.process_staging,
  batch.console_operation_audit, batch.console_ai_audit_log, batch.batch_day_operation_audit, batch.console_push_job_notification"

BIZ_TABLES="biz.customer_account, biz.transaction, biz.risk_score, biz.risk_alert,
  biz.settlement_batch, biz.settlement_detail, biz.process_account_summary, biz.process_event_copy,
  biz.process_order_event, biz.process_stage4_source, biz.process_stage4_target, biz.import_stage2c_customer"

echo "==> reset 平台运行态(${PG_PLAT_C}/${PG_PLAT_D})"
docker exec -i "$PG_PLAT_C" psql -U "$PG_PLAT_U" -d "$PG_PLAT_D" -v ON_ERROR_STOP=1 <<SQL
SET citus.multi_shard_modify_mode TO 'sequential';
TRUNCATE ${PLAT_TABLES} CASCADE;
SQL
plat_rc=$?

echo "==> reset biz 业务数据(${PG_BIZ_C}/${PG_BIZ_D})"
docker exec -i "$PG_BIZ_C" psql -U "$PG_BIZ_U" -d "$PG_BIZ_D" -v ON_ERROR_STOP=1 <<SQL
TRUNCATE ${BIZ_TABLES} CASCADE;
SQL
biz_rc=$?

if [[ $plat_rc -eq 0 && $biz_rc -eq 0 ]]; then
  echo "✅ runtime 重置完成(运行态 + biz 数据已清空,config/definition/fixture 保留)"
else
  echo "❌ 重置失败 plat_rc=$plat_rc biz_rc=$biz_rc" >&2
  exit 1
fi
