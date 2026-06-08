SELECT
  (SELECT count(*) FROM batch.tenant)||'/'||
  (SELECT count(*) FROM batch.job_definition)||'/'||
  (SELECT count(*) FROM batch.pipeline_definition)||'/'||
  (SELECT count(*) FROM batch.file_template_config)||'/'||
  (SELECT count(*) FROM batch.file_channel_config)||'/'||
  (SELECT count(*) FROM batch.workflow_definition);
