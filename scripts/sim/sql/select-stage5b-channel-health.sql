SELECT h.health_status
FROM batch.file_channel_health h
JOIN batch.file_channel_config c
  ON c.tenant_id = h.tenant_id AND c.channel_code = h.channel_code
WHERE c.tenant_id = 'tb'
  AND c.channel_code = 'tb_api_fail'
  AND c.channel_name = 'TB API fail channel [SIM:dispatch-stage5b]'
  AND c.enabled = true AND c.is_deleted = false;
