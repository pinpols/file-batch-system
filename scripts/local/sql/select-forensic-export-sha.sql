SELECT sha256
FROM batch.forensic_export_log
WHERE tenant_id = :'tenant_id'
  AND export_id = :'export_id';
