SELECT
  (
    SELECT count(*)
    FROM batch.job_instance ji
    WHERE coalesce(ji.params_snapshot #>> '{requestParams,metadata,runId}', ji.params_snapshot #>> '{effectiveParams,metadata,runId}', ji.params_snapshot #>> '{metadata,runId}', ji.params_snapshot #>> '{runId}') = :'run_id'
       OR ji.id IN (
         SELECT tr.related_job_instance_id
         FROM batch.trigger_request tr
         WHERE tr.related_job_instance_id IS NOT NULL
           AND (
             tr.request_id LIKE (:'run_id' || '%')
             OR tr.dedup_key LIKE (:'run_id' || '%')
             OR tr.trace_id LIKE (:'run_id' || '%')
           )
       )
  )
  + (
    SELECT count(*)
    FROM batch.trigger_request tr
    WHERE tr.request_id LIKE (:'run_id' || '%')
       OR tr.dedup_key LIKE (:'run_id' || '%')
       OR tr.trace_id LIKE (:'run_id' || '%')
  ) AS remaining_profile_rows;
