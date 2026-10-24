-- 全新数据库部署会在应用流量或业务数据出现前执行迁移。
-- 在已消费的令牌提交关联之前，session_id 按设计允许为空。

ALTER TABLE batch.batch_day_replay_preview_token
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_replay_preview_token_session_tenant
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES batch.batch_day_replay_session (tenant_id, id)
        MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION;
