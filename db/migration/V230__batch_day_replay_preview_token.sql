-- 短时预览凭证用于把批次日重放提交绑定到用户确认过的候选与影响快照。
-- 本表仅保存短时、可清理的幂等凭证，不属于冷存运行事实表，无需 archive 镜像。
CREATE TABLE batch.batch_day_replay_preview_token (
    tenant_id       VARCHAR(64)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    snapshot_hash   VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, token_hash),
    CONSTRAINT ck_replay_preview_token_hashes
        CHECK (token_hash ~ '^[0-9a-f]{64}$'
           AND request_hash ~ '^[0-9a-f]{64}$'
           AND snapshot_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_replay_preview_token_expires_at
    ON batch.batch_day_replay_preview_token (expires_at);

COMMENT ON TABLE batch.batch_day_replay_preview_token IS
    '批次日重放短时预览凭证；保存租户绑定的请求与候选影响快照哈希，提交时原子单次消费。';
COMMENT ON COLUMN batch.batch_day_replay_preview_token.token_hash IS
    '一次性预览凭证的 SHA-256，不保存客户端持有的原始凭证。';
COMMENT ON COLUMN batch.batch_day_replay_preview_token.request_hash IS
    '规范化提交参数的 SHA-256；参数变化后必须重新预览。';
COMMENT ON COLUMN batch.batch_day_replay_preview_token.snapshot_hash IS
    '预览候选及影响明细的 SHA-256；候选或影响变化后必须重新预览。';
COMMENT ON COLUMN batch.batch_day_replay_preview_token.expires_at IS
    '预览凭证失效时间；过期凭证不能提交。';
COMMENT ON COLUMN batch.batch_day_replay_preview_token.consumed_at IS
    '成功创建重放会话时的消费时间；非空凭证不可再次提交。';
