-- AI 图片是私有加密对象；数据库只保存元数据和持久化清理意图。
ALTER TABLE batch.console_ai_turn ADD COLUMN client_turn_id UUID;
CREATE UNIQUE INDEX uk_console_ai_turn_client_id
    ON batch.console_ai_turn (tenant_id, client_turn_id) WHERE client_turn_id IS NOT NULL;

CREATE TABLE batch.console_ai_attachment (
    tenant_id VARCHAR(64) NOT NULL,
    id UUID NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    client_attachment_id UUID NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(256) NOT NULL,
    media_type VARCHAR(32),
    byte_size BIGINT,
    width INTEGER,
    height INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    conversation_id VARCHAR(128),
    turn_no BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_console_ai_attachment PRIMARY KEY (tenant_id, id),
    CONSTRAINT uk_console_ai_attachment_client UNIQUE (tenant_id, owner_user_id, client_attachment_id),
    CONSTRAINT uk_console_ai_attachment_object UNIQUE (object_key),
    CONSTRAINT fk_console_ai_attachment_turn FOREIGN KEY (tenant_id, conversation_id, turn_no)
        REFERENCES batch.console_ai_turn (tenant_id, conversation_id, turn_no) ON DELETE CASCADE,
    CONSTRAINT ck_console_ai_attachment_status CHECK (status IN ('UPLOADING', 'DRAFT', 'BOUND')),
    CONSTRAINT ck_console_ai_attachment_binding CHECK (
        (status = 'BOUND' AND conversation_id IS NOT NULL AND turn_no IS NOT NULL)
        OR (status <> 'BOUND' AND conversation_id IS NULL AND turn_no IS NULL)),
    CONSTRAINT ck_console_ai_attachment_size CHECK (
        byte_size > 0 AND (status = 'UPLOADING' OR
            (width > 0 AND height > 0 AND media_type IN ('image/png', 'image/jpeg'))))
);

CREATE INDEX idx_console_ai_attachment_expiry ON batch.console_ai_attachment (tenant_id, expires_at);
CREATE INDEX idx_console_ai_attachment_turn ON batch.console_ai_attachment (tenant_id, conversation_id, turn_no);

CREATE TABLE batch.console_ai_object_cleanup (
    tenant_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_console_ai_object_cleanup PRIMARY KEY (tenant_id, object_key)
);

ALTER TABLE batch.console_ai_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.console_ai_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY console_ai_attachment_tenant_isolation ON batch.console_ai_attachment
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE batch.console_ai_object_cleanup ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.console_ai_object_cleanup FORCE ROW LEVEL SECURITY;
CREATE POLICY console_ai_object_cleanup_tenant_isolation ON batch.console_ai_object_cleanup
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
