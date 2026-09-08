BEGIN;

INSERT INTO batch.job_definition (
    tenant_id,
    job_code,
    job_name,
    job_type,
    biz_type,
    schedule_type,
    timezone,
    trigger_mode,
    queue_code,
    worker_group,
    window_code,
    priority,
    enabled,
    created_at,
    updated_at
)
VALUES (
    :'tenant_id',
    :'job_code',
    'seedval dispatch probe',
    'DISPATCH',
    'TEST',
    'MANUAL',
    'Asia/Shanghai',
    'SCHEDULED',
    'dispatch_queue',
    'DISPATCH',
    'always_open',
    5,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.file_record (
    tenant_id,
    file_code,
    biz_type,
    file_category,
    file_name,
    original_file_name,
    file_format_type,
    charset,
    file_size_bytes,
    checksum_type,
    checksum_value,
    storage_type,
    storage_path,
    storage_bucket,
    file_status,
    biz_date,
    source_type,
    source_ref
)
VALUES (
    :'tenant_id',
    :'file_code',
    'TEST',
    'OUTPUT',
    :'probe_tag' || '-probe.txt',
    :'probe_tag' || '-probe.txt',
    'JSON',
    'UTF-8',
    12,
    'NONE',
    'noop',
    'LOCAL',
    '/tmp/batch/' || :'probe_tag' || '-probe.txt',
    'batch-dev',
    'GENERATED',
    CURRENT_DATE,
    'GENERATED',
    :'probe_tag'
)
ON CONFLICT (tenant_id, checksum_value, storage_path)
WHERE checksum_value IS NOT NULL
DO UPDATE SET file_code = EXCLUDED.file_code,
              file_status = EXCLUDED.file_status,
              source_ref = EXCLUDED.source_ref,
              updated_at = CURRENT_TIMESTAMP
RETURNING id;

COMMIT;
