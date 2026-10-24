-- 关联的运行数据属于完整归档事务，其中包括冷存储中的 Workflow 血缘。
-- 危险：删除已过期的终态请求/等待记录。只能在同一事务中内部调用这两个保留期
-- 数据库归属。仍有活跃或可恢复请求，或存在保留的运行时/台账引用时，均不符合清理条件。
\set ON_ERROR_STOP on

\if :{?request_retention_cutoff}
\else
  DO $retention_options$ BEGIN
    RAISE EXCEPTION 'request_retention_cutoff is required by the retention include' USING ERRCODE='22023';
  END $retention_options$;
\endif
\if :{?request_cleanup_mode}
\else
  DO $retention_options$ BEGIN
    RAISE EXCEPTION 'request_cleanup_mode is required by the retention include' USING ERRCODE='22023';
  END $retention_options$;
\endif
\if :{?trigger_request_cleanup_batch_size}
\else
  \set trigger_request_cleanup_batch_size 1000
\endif
SELECT :'trigger_request_cleanup_batch_size'::integer BETWEEN 1 AND 10000
       AND :'request_retention_cutoff'::timestamptz < now()
       AND :'request_cleanup_mode' IN ('success','failure') AS _request_retention_options_valid \gset
\if :_request_retention_options_valid
\else
  DO $retention_options$ BEGIN
    RAISE EXCEPTION 'invalid request retention cutoff, mode or batch size (expected 1..10000)' USING ERRCODE='22023';
  END $retention_options$;
\endif

-- 脚本；不得对共享测试夹具运行，也不得跳过审查。
CREATE TEMP TABLE cleanup_trigger_request_candidates (
    id bigint PRIMARY KEY, tenant_id varchar(64) NOT NULL, request_id varchar(128) NOT NULL
) ON COMMIT DROP;

-- ON COMMIT DROP 也能确保误将脚本单独运行时，在删除任何业务表记录前就失败。
INSERT INTO cleanup_trigger_request_candidates(id,tenant_id,request_id)
SELECT request.id,request.tenant_id,request.request_id
FROM batch.trigger_request request
WHERE ((:'request_cleanup_mode'='success' AND request.request_status='LAUNCHED')
    OR (:'request_cleanup_mode'='failure' AND request.request_status IN ('REJECTED','GIVE_UP','DUPLICATE','LAUNCHED')))
  AND request.created_at < :'request_retention_cutoff'::timestamptz
  AND request.updated_at < :'request_retention_cutoff'::timestamptz
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance job
                  WHERE job.trigger_request_id=request.id OR job.id=request.related_job_instance_id)
  AND NOT EXISTS (SELECT 1 FROM archive.job_instance_archive job
                  WHERE job.trigger_request_id=request.id OR job.id=request.related_job_instance_id)
  AND NOT EXISTS (SELECT 1 FROM batch.workflow_run run
                  WHERE run.trigger_request_id=request.id OR run.id=request.parent_workflow_run_id)
  AND NOT EXISTS (SELECT 1 FROM archive.workflow_run_archive run
                  WHERE run.trigger_request_id=request.id OR run.id=request.parent_workflow_run_id)
  AND NOT EXISTS (SELECT 1 FROM batch.job_dependency_waiting_launch waiting
                  WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id
                    AND (waiting.wait_status NOT IN ('RELEASED','CANCELLED')
                         OR waiting.updated_at >= :'request_retention_cutoff'::timestamptz))
  AND NOT EXISTS (SELECT 1 FROM batch.workflow_launch_wait waiting
                  WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id
                    AND (waiting.wait_status NOT IN ('RESUMED','REJECTED')
                         OR waiting.updated_at >= :'request_retention_cutoff'::timestamptz))
  AND NOT EXISTS (SELECT 1 FROM batch.batch_day_waiting_launch waiting
                  WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id
                    AND (waiting.wait_status NOT IN ('RELEASED','REJECTED','CANCELLED')
                         OR waiting.updated_at >= :'request_retention_cutoff'::timestamptz))
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_outbox_event event
                  WHERE event.tenant_id=request.tenant_id AND event.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM archive.trigger_outbox_event_archive event
                  WHERE event.tenant_id=request.tenant_id AND event.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.scheduled_occurrence occurrence
                  WHERE occurrence.tenant_id=request.tenant_id AND occurrence.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.scheduled_occurrence_supersession_history history
                  WHERE history.tenant_id=request.tenant_id AND history.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.scheduled_occurrence_catch_up_audit audit
                  WHERE audit.tenant_id=request.tenant_id AND audit.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.expected_work_occurrence occurrence
                  WHERE occurrence.tenant_id=request.tenant_id AND occurrence.trigger_request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_misfire_pending pending
                  WHERE pending.tenant_id=request.tenant_id AND pending.catch_up_request_id=request.id)
ORDER BY request.created_at,request.id
LIMIT :'trigger_request_cleanup_batch_size'::integer
FOR UPDATE OF request SKIP LOCKED;

DELETE FROM batch.job_dependency_waiting_launch waiting
USING cleanup_trigger_request_candidates candidate
WHERE waiting.tenant_id=candidate.tenant_id AND waiting.request_id=candidate.request_id
  AND waiting.wait_status IN ('RELEASED','CANCELLED')
  AND waiting.updated_at < :'request_retention_cutoff'::timestamptz;

DELETE FROM batch.workflow_launch_wait waiting
USING cleanup_trigger_request_candidates candidate
WHERE waiting.tenant_id=candidate.tenant_id AND waiting.request_id=candidate.request_id
  AND waiting.wait_status IN ('RESUMED','REJECTED')
  AND waiting.updated_at < :'request_retention_cutoff'::timestamptz;

DELETE FROM batch.batch_day_waiting_launch waiting
USING cleanup_trigger_request_candidates candidate
WHERE waiting.tenant_id=candidate.tenant_id AND waiting.request_id=candidate.request_id
  AND waiting.wait_status IN ('RELEASED','REJECTED','CANCELLED')
  AND waiting.updated_at < :'request_retention_cutoff'::timestamptz;

DELETE FROM batch.trigger_request request
USING cleanup_trigger_request_candidates candidate
WHERE request.id=candidate.id AND request.tenant_id=candidate.tenant_id AND request.request_id=candidate.request_id
  AND NOT EXISTS (SELECT 1 FROM batch.job_dependency_waiting_launch waiting
                  WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.workflow_launch_wait waiting
                  WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id)
  AND NOT EXISTS (SELECT 1 FROM batch.batch_day_waiting_launch waiting
                  WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id);
