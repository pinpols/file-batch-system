SELECT tenant_id, target_table, batch_key, count(*) AS rows
FROM batch.process_staging
WHERE tenant_id = :'tenant_id'
  AND batch_key LIKE :'batch_no' || '%'
GROUP BY tenant_id, target_table, batch_key
ORDER BY batch_key;
