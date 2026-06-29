SELECT 'runtime残留 file_record='||count(*) FROM batch.file_record
UNION ALL
SELECT 'pipeline_instance='||count(*) FROM batch.pipeline_instance
UNION ALL
SELECT 'job_instance='||count(*) FROM batch.job_instance
UNION ALL
SELECT 'job_instance_dedup_key='||count(*) FROM batch.job_instance_dedup_key
UNION ALL
SELECT 'outbox_event='||count(*) FROM batch.outbox_event
UNION ALL
SELECT 'outbox_event_dedup_key='||count(*) FROM batch.outbox_event_dedup_key
UNION ALL
SELECT 'pipeline_step_run='||count(*) FROM batch.pipeline_step_run;
