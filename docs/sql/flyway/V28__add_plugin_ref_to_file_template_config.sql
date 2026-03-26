-- =========================================================
-- V28 - Add plugin reference columns to file_template_config
--
-- export_data_ref: identifies which ExportDataPlugin to use
--   (e.g. 'jdbc_mapped_export', 'sql_template_export')
-- load_target_ref: identifies which ImportLoadPlugin to use
--   (e.g. 'jdbc_mapped')
--
-- Data migration:
--   - Templates with default_query_sql set → sql_template_export
--   - Import templates → jdbc_mapped (new default, requires
--     query_param_schema.jdbcMappedImport to be configured)
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS export_data_ref VARCHAR(128),
    ADD COLUMN IF NOT EXISTS load_target_ref VARCHAR(128);

COMMENT ON COLUMN batch.file_template_config.export_data_ref IS
    'ExportDataPlugin id used by GenerateStep. Must be set for EXPORT templates. '
    'Supported: sql_template_export (requires default_query_sql), '
    'jdbc_mapped_export (requires query_param_schema.jdbcMappedExport).';

COMMENT ON COLUMN batch.file_template_config.load_target_ref IS
    'ImportLoadPlugin id used by LoadStep. Must be set for IMPORT templates. '
    'Supported: jdbc_mapped (requires query_param_schema.jdbcMappedImport).';

-- Migrate existing EXPORT templates that have default_query_sql → sql_template_export
UPDATE batch.file_template_config
SET    export_data_ref = 'sql_template_export'
WHERE  template_type   = 'EXPORT'
  AND  default_query_sql IS NOT NULL
  AND  export_data_ref IS NULL;

-- Migrate existing IMPORT templates → jdbc_mapped
-- (query_param_schema.jdbcMappedImport must be configured separately per template)
UPDATE batch.file_template_config
SET    load_target_ref = 'jdbc_mapped'
WHERE  template_type  = 'IMPORT'
  AND  load_target_ref IS NULL;
