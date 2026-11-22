-- 入口归属由管理员在上游写入对象前声明；扫描协调状态不承担配置事实来源。
CREATE TABLE batch.import_ingress_ownership (
    scope_id VARCHAR(64) PRIMARY KEY CHECK (scope_id ~ '^[0-9a-f]{64}$'),
    storage_id VARCHAR(64) NOT NULL CHECK (storage_id ~ '^[0-9a-f]{64}$'),
    bucket_name TEXT NOT NULL CHECK (length(bucket_name) > 0),
    object_prefix TEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RETIRED' CHECK (state IN ('ACTIVE', 'RETIRED', 'RELEASED')),
    updated_by TEXT NOT NULL DEFAULT current_user,
    change_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (storage_id, object_prefix)
);

CREATE TABLE batch.import_ingress_ownership_binding (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL REFERENCES batch.import_ingress_ownership (scope_id),
    binding_type VARCHAR(16) NOT NULL CHECK (binding_type IN ('MANIFEST', 'CHANNEL')),
    tenant_id VARCHAR(128) NOT NULL CHECK (length(tenant_id) > 0),
    object_prefix TEXT NOT NULL CHECK (length(object_prefix) > 0 AND right(object_prefix, 1) = '/'),
    file_group_code VARCHAR(128),
    manifest_key_template TEXT,
    channel_code VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_ingress_ownership_binding_fields CHECK (
      (binding_type = 'MANIFEST' AND file_group_code IS NOT NULL AND manifest_key_template IS NOT NULL
       AND channel_code IS NULL)
      OR (binding_type = 'CHANNEL' AND channel_code IS NOT NULL
          AND file_group_code IS NULL AND manifest_key_template IS NULL)
    )
);

CREATE UNIQUE INDEX uk_import_ingress_ownership_binding_enabled
    ON batch.import_ingress_ownership_binding (scope_id, binding_type, tenant_id, object_prefix)
    WHERE enabled;
CREATE INDEX idx_import_ingress_ownership_binding_scope
    ON batch.import_ingress_ownership_binding (scope_id);

COMMENT ON TABLE batch.import_ingress_ownership IS '入站扫描范围的跨组件权威声明，退休后仍保护，明确释放后才交还普通对象对账。';
COMMENT ON COLUMN batch.import_ingress_ownership.scope_id IS '存储身份与完整扫描前缀的摘要，与扫描协调范围一致。';
COMMENT ON COLUMN batch.import_ingress_ownership.storage_id IS '物理存储、桶和部署命名空间的摘要。';
COMMENT ON COLUMN batch.import_ingress_ownership.bucket_name IS '声明所属对象存储桶。';
COMMENT ON COLUMN batch.import_ingress_ownership.object_prefix IS '完整 scanner 数据前缀；允许空前缀表示整个桶。';
COMMENT ON COLUMN batch.import_ingress_ownership.state IS 'ACTIVE 允许扫描并保护；RETIRED 停止扫描但仍保护；RELEASED 显式归还普通对账。';
COMMENT ON COLUMN batch.import_ingress_ownership.updated_by IS '最近一次声明或状态变更的数据库账号。';
COMMENT ON COLUMN batch.import_ingress_ownership.change_reason IS '最近一次状态变更原因；释放范围时必须提供。';
COMMENT ON COLUMN batch.import_ingress_ownership.created_at IS '首次声明时间。';
COMMENT ON COLUMN batch.import_ingress_ownership.updated_at IS '声明状态最近变化时间。';
COMMENT ON TABLE batch.import_ingress_ownership_binding IS '入口范围下按租户和目录声明的清单或渠道绑定。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.id IS '绑定版本主键；停用后保留历史声明。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.scope_id IS '所属扫描范围。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.binding_type IS 'MANIFEST 为权威清单绑定，CHANNEL 为可信渠道绑定。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.tenant_id IS '绑定所属租户。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.object_prefix IS '租户数据目录，匹配时采用最长前缀。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.file_group_code IS '清单绑定的文件组编码。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.manifest_key_template IS '包含单个业务日占位符的权威清单完整对象键模板。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.channel_code IS '可信文件到达渠道编码。';
COMMENT ON COLUMN batch.import_ingress_ownership_binding.enabled IS '是否供扫描器使用；停用的历史清单仍受归属保护。';
ALTER TABLE batch.import_ingress_ownership ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_ownership FORCE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_ownership_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.import_ingress_ownership_binding FORCE ROW LEVEL SECURITY;
REVOKE ALL ON batch.import_ingress_ownership, batch.import_ingress_ownership_binding FROM PUBLIC;
REVOKE ALL ON SEQUENCE batch.import_ingress_ownership_binding_id_seq FROM PUBLIC;

-- 跨租户范围仅允许迁移/管理账号维护；运行时读取权限由专用脚本显式授予。
DO $$
BEGIN
    EXECUTE format('CREATE POLICY import_ingress_ownership_owner ON batch.import_ingress_ownership TO %I USING (true) WITH CHECK (true)', current_user);
    EXECUTE format('CREATE POLICY import_ingress_ownership_binding_owner ON batch.import_ingress_ownership_binding TO %I USING (true) WITH CHECK (true)', current_user);
END;
$$;
