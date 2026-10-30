-- 只恢复本场景写入的慢速值；并发写入其它值时不覆盖，由调用方报告冲突。
UPDATE batch.file_template_config
SET chunk_size = :'chunk_size'::integer,
    updated_at = current_timestamp
WHERE tenant_id = :'tenant_id'
  AND template_code = :'template_code'
  AND is_deleted = false
  AND chunk_size = 1;

SELECT chunk_size
FROM batch.file_template_config
WHERE tenant_id = :'tenant_id'
  AND template_code = :'template_code'
  AND is_deleted = false;
