-- Stage 4b Process 旧 generation staging 隔离夹具。
-- 必需的 psql 变量：batch_key

DELETE FROM batch.process_staging
WHERE tenant_id = 'ta'
  AND batch_key = :'batch_key'
  AND target_schema = 'biz'
  AND target_table = 'process_stage4_target';

INSERT INTO batch.process_staging_generation (
    generation_id, tenant_id, job_task_id, batch_key, generation_status
)
VALUES (gen_random_uuid(), 'ta', NULL, :'batch_key', 'OPEN')
RETURNING generation_id AS sim_stale_generation_id \gset

INSERT INTO batch.process_staging (
    batch_key, staging_generation_id, row_seq, tenant_id,
    target_schema, target_table, payload, staged_at
)
VALUES (
    :'batch_key',
    :'sim_stale_generation_id'::uuid,
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
