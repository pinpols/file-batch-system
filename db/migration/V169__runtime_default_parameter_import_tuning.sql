-- Align audited runtime defaults with 2026-06 import tuning.

INSERT INTO batch.batch_runtime_default_parameter
    (module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description)
VALUES
    ('WORKER_IMPORT', 'file_processing.max_chunk_size', '10000', 'INTEGER', 'rows',
     'batch.worker.import.file-processing.max-chunk-size', 'BATCH_WORKER_IMPORT_MAX_CHUNK_SIZE',
     'Upper bound for template chunk_size; protects JVM heap on COPY / wide rows'),
    ('WORKER_IMPORT', 'jdbc_mapped.strict_idempotency', 'true', 'BOOLEAN', NULL,
     'batch.worker.import.jdbc-mapped.strict-idempotency',
     'BATCH_WORKER_IMPORT_JDBC_MAPPED_STRICT_IDEMPOTENCY',
     'Require conflict_columns for jdbc_mapped_import templates so checkpoint retry is idempotent')
ON CONFLICT (module, parameter_key)
DO UPDATE SET
    default_value = EXCLUDED.default_value,
    value_type = EXCLUDED.value_type,
    unit = EXCLUDED.unit,
    yaml_path = EXCLUDED.yaml_path,
    env_var = EXCLUDED.env_var,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

UPDATE batch.batch_runtime_default_parameter
SET default_value = '2000',
    description = 'Load batch chunk; overridden by file_template_config.chunk_size up to max_chunk_size',
    updated_at = CURRENT_TIMESTAMP
WHERE module = 'WORKER_IMPORT'
  AND parameter_key = 'file_processing.chunk_size';
