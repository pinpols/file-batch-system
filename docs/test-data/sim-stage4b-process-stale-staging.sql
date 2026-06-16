-- Stage 4b Process 陈旧 staging 模拟。
-- 必需的 psql 变量：batch_key

DELETE FROM batch.process_staging
WHERE tenant_id = 'ta'
  AND batch_key = :'batch_key'
  AND target_schema = 'biz'
  AND target_table = 'process_stage4_target';

INSERT INTO batch.process_staging (
    batch_key, row_seq, tenant_id, target_schema, target_table, payload, staged_at
)
VALUES (
    :'batch_key',
    1,
    'ta',
    'biz',
    'process_stage4_target',
    jsonb_build_object(
      'tenant_id', 'ta',
      'scenario', 'JSONB',
      'account_id', 'A999',
      'biz_date', CURRENT_DATE,
      'total_amount', 9999.99,
      'event_count', 99,
      'high_water_mark', 999999
    ),
    now() - interval '1 hour'
);
