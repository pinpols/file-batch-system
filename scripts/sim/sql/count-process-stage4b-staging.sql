SELECT count(*)
FROM batch.process_staging
WHERE tenant_id = :'tenant_id'
  AND batch_key = :'batch_key'
  AND target_schema = 'biz'
  AND target_table = 'process_stage4_target';
