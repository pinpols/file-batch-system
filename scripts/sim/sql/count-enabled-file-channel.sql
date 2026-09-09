SELECT count(*)
FROM batch.file_channel_config
WHERE tenant_id = :'tenant_id'
  AND channel_code = :'channel_code'
  AND enabled = true;
