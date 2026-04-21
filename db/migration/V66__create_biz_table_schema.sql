-- biz_table_schema：worker-import 启动时扫业务库 information_schema 并上报。
-- console-api 上传校验时用它拦住 Excel 里填的 targetColumn / jdbcMappedImport.columnMappings[*].to /
-- sql_template_export.columns[*] 指向不存在的业务表或列（以前这类错只有 LoadStep 真往 biz 表写才抛）。
-- 快照覆盖策略：每次 worker 启动先删自己模块的快照再 INSERT，已删表自动消失。
CREATE TABLE IF NOT EXISTS batch.biz_table_schema (
  id            BIGSERIAL PRIMARY KEY,
  schema_name   VARCHAR(64)  NOT NULL,
  table_name    VARCHAR(128) NOT NULL,
  columns       JSONB        NOT NULL,
  registered_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_biz_table_schema UNIQUE (schema_name, table_name)
);

CREATE INDEX IF NOT EXISTS idx_biz_table_schema ON batch.biz_table_schema (schema_name);

COMMENT ON TABLE  batch.biz_table_schema IS 'worker 启动时上报业务库 schema；Excel 上传按此校验 targetColumn/columnMappings';
COMMENT ON COLUMN batch.biz_table_schema.columns IS 'jsonb 数组：[{"name":"customer_no","type":"varchar","nullable":false}, ...]';
