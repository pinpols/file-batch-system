#!/usr/bin/env bash
# ADR-sim 4day · Citus 专用:把 API/API_PUSH 渠道的 target_endpoint 从 docker 内网名
# (mockserver:1080)改写成宿主可达地址(localhost:11080)。
#
# 背景:Citus sim 的 8 个 worker 是「宿主机 JVM」(start-all.sh 直接起,非 docker),
# 无法解析 docker compose 内部 hostname `mockserver`,API_PUSH dispatch 会
# 「dispatch blocked by channel health backoff / DISPATCH_COMPENSATED」。
# 把端点指向 mockserver 的宿主映射端口 11080(`docker port mockserver` => 1080→11080)即通。
#
# 幂等:逐渠道字面量 UPDATE(Citus 分布式表禁止 SET 里用引用列的 STABLE 函数,故不用
# replace()/jsonb_set(),改为整列字面量赋值)。config_json 只保留 method,端点权威源走列。
# 00-clean.sh 保留 file_channel_config,故本脚本跑一次即长期生效;重新 seed 后需再跑。
#
# 用法:source env-citus 后 `bash scripts/sim-4day/05-fix-channel-endpoints-citus.sh`
set -uo pipefail

PG_PLAT="${PG_PLATFORM_CONTAINER:-citus-coord}"
PGU_PLAT="${PG_PLATFORM_USER:-postgres}"
PG_DB_PLAT="${PG_PLATFORM_DB:-batch_platform}"
HOST_MOCK="${MOCKSERVER_HOST_BASE:-http://localhost:11080}"

echo "==> 改写 API 渠道端点 → ${HOST_MOCK}(平台库 ${PG_PLAT}/${PG_DB_PLAT})"
docker exec -i "$PG_PLAT" psql -U "$PGU_PLAT" -d "$PG_DB_PLAT" -v ON_ERROR_STOP=1 <<SQL
UPDATE batch.file_channel_config SET target_endpoint='${HOST_MOCK}/tb/callback', config_json='{"method":"POST"}'::jsonb WHERE channel_code='tb_api_push';
UPDATE batch.file_channel_config SET target_endpoint='${HOST_MOCK}/tc/ingest',   config_json='{"method":"POST"}'::jsonb WHERE channel_code='tc_api_risk_push';
UPDATE batch.file_channel_config SET target_endpoint='${HOST_MOCK}/tb/ingest',    config_json='{"method":"POST"}'::jsonb WHERE channel_code='tb_api_ingest';
-- 重置渠道健康熔断退避(此前指向不可达地址累积的 backoff 会挡住新分发)
TRUNCATE batch.file_channel_health;

-- 自愈:修复存量集群里 DISPATCH pipeline 缺失的 stage 路由提示(append_if_new 只插不改,
-- 2026-06-06 之前 seed 的环境 TB_DISPATCH_SETTLE 等会留空 {} → ACK 成功后顺延落 COMPENSATE
-- 把已成功记录冲正 → COMPLETE 报 state_conflict。幂等:仅把空 {} 补成与 seed 一致的提示)。
UPDATE batch.pipeline_step_definition sd SET step_params='{"onSuccessNextStageCode": "COMPLETE"}'::jsonb
  FROM batch.pipeline_definition pd
  WHERE pd.id=sd.pipeline_definition_id AND pd.pipeline_type='DISPATCH'
    AND sd.stage_code='ACK' AND coalesce(sd.step_params::text,'{}')='{}';
UPDATE batch.pipeline_step_definition sd SET step_params='{"onFailureNextStageCode": "COMPENSATE"}'::jsonb
  FROM batch.pipeline_definition pd
  WHERE pd.id=sd.pipeline_definition_id AND pd.pipeline_type='DISPATCH'
    AND sd.stage_code='RETRY' AND coalesce(sd.step_params::text,'{}')='{}';
UPDATE batch.pipeline_step_definition sd SET step_params='{"terminalOnSuccess": true}'::jsonb
  FROM batch.pipeline_definition pd
  WHERE pd.id=sd.pipeline_definition_id AND pd.pipeline_type='DISPATCH'
    AND sd.stage_code IN ('COMPENSATE','COMPLETE') AND coalesce(sd.step_params::text,'{}')='{}';

SELECT channel_code, target_endpoint FROM batch.file_channel_config
  WHERE channel_code IN ('tb_api_push','tc_api_risk_push','tb_api_ingest') ORDER BY channel_code;
SQL
rc=$?
[[ $rc -eq 0 ]] && echo "✅ 渠道端点已改写 + 健康熔断已重置" || { echo "❌ 失败 rc=$rc" >&2; exit 1; }
