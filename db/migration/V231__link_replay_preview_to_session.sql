-- 将已消费的预览凭证关联到原会话，支持客户端在提交响应丢失后安全重试并取回结果。
ALTER TABLE batch.batch_day_replay_preview_token
    ADD COLUMN session_id BIGINT;

CREATE INDEX idx_replay_preview_token_consumed_at
    ON batch.batch_day_replay_preview_token (consumed_at)
    WHERE consumed_at IS NOT NULL;

COMMENT ON COLUMN batch.batch_day_replay_preview_token.session_id IS
    '成功消费该预览凭证创建的重放会话；同一请求重试时返回该会话。';
