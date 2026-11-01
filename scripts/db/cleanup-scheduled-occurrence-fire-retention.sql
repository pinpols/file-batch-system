-- 用途：预览或清理指定租户、截止时刻前且所属批次日已 SETTLED 的实际触发观察记录。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v tenant_id=租户 -v cutoff=时间 -v batch_size=1000 -f scripts/db/cleanup-scheduled-occurrence-fire-retention.sql
-- 破坏性操作：仅对经授权的目标平台库执行；实际删除后不能由脚本撤销。
-- 删除前必须核对预览范围及已验证备份；误删时按 scripts/db/README.md 的恢复流程处理。
-- 默认 preview；删除须显式确认数据库、租户、截止时间、批次大小及已验证备份。每次最多 10000 条。
\set ON_ERROR_STOP on
\if :{?tenant_id}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 tenant_id'; END $$;
\endif
\if :{?cutoff}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 cutoff'; END $$;
\endif
\if :{?batch_size}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 batch_size'; END $$;
\endif
\if :{?action}
\else
  \set action preview
\endif
SELECT length(trim(:'tenant_id')) > 0 AND :'cutoff'::timestamptz < now()
   AND :'batch_size'::integer BETWEEN 1 AND 10000
   AND :'action' IN ('preview', 'delete') AS options_valid \gset
\if :options_valid
\else
  DO $$ BEGIN RAISE EXCEPTION '租户、截止时间、批次大小或 action 无效'; END $$;
\endif

BEGIN;
SELECT set_config('app.tenant_id', :'tenant_id', true);
CREATE TEMP TABLE cleanup_scheduled_fire_candidates ON COMMIT DROP AS
SELECT fire.id, fire.actual_fire_time
FROM batch.scheduled_occurrence_fire fire
JOIN batch.scheduled_occurrence occurrence
  ON occurrence.tenant_id = fire.tenant_id
 AND occurrence.workflow_definition_id = fire.workflow_definition_id
 AND occurrence.scheduled_fire_time = fire.scheduled_fire_time
JOIN batch.batch_day_instance day
  ON day.tenant_id = occurrence.tenant_id
 AND day.calendar_code = occurrence.calendar_code
 AND day.biz_date = occurrence.biz_date
WHERE fire.tenant_id = :'tenant_id'
  AND fire.actual_fire_time < :'cutoff'::timestamptz
  AND day.day_status = 'SETTLED'
ORDER BY fire.actual_fire_time, fire.id
LIMIT :'batch_size'::integer
FOR UPDATE OF fire SKIP LOCKED FOR SHARE OF day SKIP LOCKED;

SELECT count(*) AS candidate_count, min(actual_fire_time) AS earliest,
       max(actual_fire_time) AS latest FROM cleanup_scheduled_fire_candidates;
SELECT (:'action' = 'delete') AS should_delete \gset
\if :should_delete
  \if :{?confirm_database}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认 confirm_database'; END $$;
  \endif
  \if :{?confirm_tenant}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认 confirm_tenant'; END $$;
  \endif
  \if :{?confirm_cutoff}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认 confirm_cutoff'; END $$;
  \endif
  \if :{?confirm_batch_size}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认 confirm_batch_size'; END $$;
  \endif
  \if :{?confirm_backup}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认已验证备份 confirm_backup=verified'; END $$;
  \endif
  SELECT current_database() = :'confirm_database' AND :'tenant_id' = :'confirm_tenant'
     AND :'cutoff' = :'confirm_cutoff' AND :'batch_size' = :'confirm_batch_size'
     AND :'confirm_backup' = 'verified' AS confirmed \gset
  \if :confirmed
  \else
    DO $$ BEGIN RAISE EXCEPTION '数据库、租户、截止时间或备份确认不匹配'; END $$;
  \endif
  DELETE FROM batch.scheduled_occurrence_fire fire
  USING cleanup_scheduled_fire_candidates candidate
  WHERE fire.id = candidate.id AND fire.tenant_id = :'tenant_id';
\endif
COMMIT;
