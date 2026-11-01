-- 仅清理指定租户、截止时刻前且所属批次日已 SETTLED 的实际触发观察记录。
-- 先 action=preview 审核，再经运维授权以 action=delete 执行；每次最多 10000 条。
-- psql -X -v ON_ERROR_STOP=1 -v tenant_id=租户 -v cutoff=时间 -v batch_size=1000 -v action=preview -f scripts/db/cleanup-scheduled-occurrence-fire-retention.sql
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
  DO $$ BEGIN RAISE EXCEPTION '必须指定 action=preview 或 delete'; END $$;
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
  DELETE FROM batch.scheduled_occurrence_fire fire
  USING cleanup_scheduled_fire_candidates candidate
  WHERE fire.id = candidate.id AND fire.tenant_id = :'tenant_id';
\endif
COMMIT;
