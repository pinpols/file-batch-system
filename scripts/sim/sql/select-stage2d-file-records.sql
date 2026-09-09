SELECT file.id,
       file.file_status,
       file.metadata_json->>'badRecordCount' AS bad_records,
       file.metadata_json->>'skippedCount' AS skipped,
       file.metadata_json->>'validatedCount' AS validated,
       file.metadata_json->>'loadedCount' AS loaded,
       file.metadata_json->>'skipThresholdExceeded' AS threshold_exceeded
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
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY request.created_at, file.id;
