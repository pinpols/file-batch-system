SELECT request.request_id,
       error.record_no,
       error.error_code,
       error.error_stage,
       error.is_skipped,
       error.skip_action
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.id = request.related_job_instance_id
JOIN batch.pipeline_instance pipeline
  ON pipeline.tenant_id = instance.tenant_id
 AND pipeline.related_job_instance_id = instance.id
JOIN batch.file_record file
  ON file.tenant_id = pipeline.tenant_id
 AND file.id = pipeline.file_id
JOIN batch.file_error_record error
  ON error.tenant_id = file.tenant_id
 AND error.file_id = file.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY request.created_at, error.record_no, error.id;
