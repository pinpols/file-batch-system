-- 资源标签：支持对 job_definition / workflow_definition / file_channel / file_template 打标签分组。
CREATE TABLE IF NOT EXISTS batch.resource_tag (
    id              BIGSERIAL       PRIMARY KEY,
    tenant_id       VARCHAR(64)     NOT NULL,
    resource_type   VARCHAR(32)     NOT NULL,          -- JOB, WORKFLOW, FILE_CHANNEL, FILE_TEMPLATE
    resource_code   VARCHAR(128)    NOT NULL,          -- jobCode / workflowCode / channelCode / templateCode
    tag_key         VARCHAR(64)     NOT NULL,
    tag_value       VARCHAR(256)    NOT NULL DEFAULT '',
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_resource_tag UNIQUE (tenant_id, resource_type, resource_code, tag_key),
    CONSTRAINT ck_resource_tag_type CHECK (resource_type IN ('JOB','WORKFLOW','FILE_CHANNEL','FILE_TEMPLATE'))
);

CREATE INDEX IF NOT EXISTS idx_resource_tag_tenant_type ON batch.resource_tag (tenant_id, resource_type);
CREATE INDEX IF NOT EXISTS idx_resource_tag_tenant_key  ON batch.resource_tag (tenant_id, tag_key);
