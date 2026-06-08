UPDATE batch.batch_day_instance
SET day_status = 'SETTLED',
    settled_at = now(),
    frozen = false,
    operated_by = 'sim-4day',
    operation_reason = 'manual cutover 日切(direct;operate API 有 audit bug)',
    operated_at = now(),
    updated_at = now(),
    version = version + 1
WHERE tenant_id = :'tenant'
  AND biz_date = :'biz_date'::date
  AND day_status NOT IN ('SETTLED','SKIPPED','MANUAL_RELEASED','FAILED');
