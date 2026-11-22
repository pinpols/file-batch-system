-- =========================================================
-- V29 - 为 file_template_config 增加插件引用字段
-- 说明：
-- 1) 为导出和导入模板增加显式插件引用。
-- 2) 将已有模板回填为当前默认插件标识。
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS export_data_ref VARCHAR(128),
    ADD COLUMN IF NOT EXISTS load_target_ref VARCHAR(128);

COMMENT ON COLUMN batch.file_template_config.export_data_ref IS
    'GenerateStep 使用的 ExportDataPlugin 标识；EXPORT 模板必须设置。'
    '支持 sql_template_export（需要 default_query_sql），'
    '以及 jdbc_mapped_export（需要 query_param_schema.jdbcMappedExport）。';

COMMENT ON COLUMN batch.file_template_config.load_target_ref IS
    'LoadStep 使用的 ImportLoadPlugin 标识；IMPORT 模板必须设置。'
    '支持 jdbc_mapped（需要 query_param_schema.jdbcMappedImport）。';

-- 将已有 EXPORT 模板的 default_query_sql 迁移为 sql_template_export。

UPDATE batch.file_template_config
SET    export_data_ref = 'sql_template_export'
WHERE  template_type   = 'EXPORT'
  AND  default_query_sql IS NOT NULL
  AND  export_data_ref IS NULL;

-- 将已有 IMPORT 模板迁移为 jdbc_mapped。
-- （每个模板都必须单独配置 query_param_schema.jdbcMappedImport）

UPDATE batch.file_template_config
SET    load_target_ref = 'jdbc_mapped'
WHERE  template_type  = 'IMPORT'
  AND  load_target_ref IS NULL;
