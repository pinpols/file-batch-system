-- 登录按 lower(username) 查找，约束必须采用相同口径。历史冲突会使迁移失败，需先人工处置。
CREATE UNIQUE INDEX uk_console_user_account_username_ci
    ON batch.console_user_account (lower(username));

CREATE TABLE batch.console_user_batch_operation (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    actor_username VARCHAR(128) NOT NULL,
    source_digest VARCHAR(64) NOT NULL,
    account_count INTEGER NOT NULL CHECK (account_count BETWEEN 1 AND 500),
    tenant_ids TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_console_user_batch_actor_request UNIQUE (actor_username, request_id)
);

CREATE INDEX idx_console_user_batch_actor_created
    ON batch.console_user_batch_operation (actor_username, created_at DESC);
