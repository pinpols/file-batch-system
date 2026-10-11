-- SecretVersion 运行时撤销标记。保留既有生命周期状态，撤销是独立的紧急阻断信号。
ALTER TABLE batch.secret_version
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoked_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS revoke_reason VARCHAR(512);

ALTER TABLE batch.config_change_log
    DROP CONSTRAINT IF EXISTS ck_config_change_log_action;

ALTER TABLE batch.config_change_log
    ADD CONSTRAINT ck_config_change_log_action
    CHECK (change_action IN (
        'CREATE', 'PUBLISH', 'GRAY', 'ROLLBACK', 'ROTATE',
        'SUBMIT_APPROVAL', 'APPROVE', 'REJECT', 'REVOKE'
    )) NOT VALID;

ALTER TABLE batch.config_change_log
    VALIDATE CONSTRAINT ck_config_change_log_action;

COMMENT ON COLUMN batch.secret_version.revoked_at IS '紧急撤销时点；Dispatch 每次外发前检查，覆盖实例固定版本。';
COMMENT ON COLUMN batch.secret_version.revoked_by IS '执行密钥撤销的操作者。';
COMMENT ON COLUMN batch.secret_version.revoke_reason IS '密钥撤销原因；不得包含密钥材料。';
