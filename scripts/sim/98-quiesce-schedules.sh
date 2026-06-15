#!/usr/bin/env bash
# =========================================================
# 98-quiesce-schedules.sh — sim 收尾:把"自动 fire 的定时触发"静默,清掉已解决的死信历史,
# 让系统回到「无 cron 残留 / 无新写入」的干净基线。
#
# 背景(为什么需要):
#   sim 的 seed(append-tenant-coverage.py 等)把多个 job 种成 schedule_type=CRON/FIXED_RATE
#   + enabled,跑完不关就一直自动 fire(STAGE6C 每 2s、TC_IMPORT_RISK_SCORE 每 5min …),
#   反复撞 sim 的负向用例(missing_col / BLOCKED / 畸形文件)→ 不停刷 dead_letter。
#   00-clean.sh 的语义是「保留所有 config」,定时定义属于 config,所以它不会关这些 →
#   churn 永远自我延续。这个脚本就是 sim 的「收尾静默」一步。
#
# 做什么(都幂等):
#   1. 所有 schedule_type ∈ (CRON, FIXED_RATE) 的 job_definition → schedule_type='MANUAL'。
#      - 保留 schedule_expr(只改类型),enabled 不动 → job 仍可经 launch API 触发(sim 主链走 API,
#        不受影响;4 天驱动 / 各 stage 照常)。
#      - 真正需要 cron 自动 fire 的阶段(STAGE6C)在自己的 fixtures 重 seed 里用
#        `ON CONFLICT (tenant_id, job_code) DO UPDATE SET schedule_type=EXCLUDED.schedule_type`
#        把它的两个 job 改回 SCHEDULED,自我重置——所以本脚本静默后它下次跑仍正常。
#   2. 清空 dead_letter_task(sim 跑出来的全是负向用例残留:已解决的 SUCCESS 历史 +
#      确定性失败如 missing_col 在 auto-retry 里反复再失败、不断新刷死信的 NEW/FAILED)。
#      只静默 schedule 不够 —— 存量 open 死信里的「永不可能成功」项会被 retry governance
#      一直重试再失败,churn 停不下来。本脚本是 sim 收尾,清空与 00-reset-runtime 的
#      TRUNCATE 语义一致(都只在 sim 上下文跑)。⚠️ 非 sim 环境勿用(会抹掉真待处理失败)。
#
# 何时跑:任一 sim 会话结束后(尤其在跑 strict-verify 真实数据验证之前 —— 静默后
#   job_instance 不再有并发写,翻页一致性校验才能拿到稳定快照,不会假失败)。
#
# 用法:source env-citus 后 `bash scripts/sim/98-quiesce-schedules.sh`;不 source 走单机(双栈安全)。
# =========================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh" 2>/dev/null || true

PG_C="${PG_PLATFORM_CONTAINER:-batch-postgres-primary}"
PG_U="${PG_PLATFORM_USER:-batch_user}"
PG_D="${PG_PLATFORM_DB:-batch_platform}"

echo "==> 静默自动 fire 的定时触发 + 清已解决死信(${PG_C}/${PG_D})"
docker exec -i "$PG_C" psql -U "$PG_U" -d "$PG_D" -v ON_ERROR_STOP=1 <<'SQL'
\echo '-- 1/2 CRON/FIXED_RATE → MANUAL(保留 expr / enabled;stage6c 等自我重置不受影响)'
WITH q AS (
  UPDATE batch.job_definition
     SET schedule_type = 'MANUAL'
   WHERE schedule_type IN ('CRON', 'FIXED_RATE')
  RETURNING 1)
SELECT count(*) AS quiesced_schedules FROM q;

\echo '-- 2/2 清空 dead_letter_task(sim 负向用例残留:SUCCESS 历史 + 确定性失败的重试 churn)'
WITH d AS (DELETE FROM batch.dead_letter_task RETURNING 1)
SELECT count(*) AS purged_dead_letters FROM d;

\echo '-- 残留核对:仍自动 fire 的定时 应为 0;死信 应为 0'
SELECT 'still_auto_fire' AS check, count(*) AS n
  FROM batch.job_definition WHERE schedule_type IN ('CRON', 'FIXED_RATE')
UNION ALL
SELECT 'remaining_dead_letters', count(*) FROM batch.dead_letter_task;
SQL
rc=$?

if [[ $rc -eq 0 ]]; then
  echo "✅ 已回到干净基线:定时不再自动 fire(job 仍可经 API 触发),死信历史已清。"
  echo "   现在可安全跑 strict-verify(scripts/local/strict-verify.sh):无并发写,翻页校验稳定。"
else
  echo "❌ 静默失败 rc=$rc" >&2
  exit 1
fi
