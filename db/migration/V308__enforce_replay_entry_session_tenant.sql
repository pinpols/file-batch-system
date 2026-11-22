-- 全新数据库部署会在应用流量或业务数据出现前执行迁移。
-- 以下直接创建已验证约束是有意设计；这不是在线升级。

ALTER TABLE batch.batch_day_replay_session
    -- squawk-ignore constraint-missing-not-valid, disallowed-unique-constraint
    ADD CONSTRAINT uk_batch_day_replay_session_tenant_id
        UNIQUE (tenant_id, id);

ALTER TABLE batch.batch_day_replay_entry
    DROP CONSTRAINT batch_day_replay_entry_session_id_fkey;

ALTER TABLE batch.batch_day_replay_entry
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_batch_day_replay_entry_session_tenant
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES batch.batch_day_replay_session (tenant_id, id)
        ON UPDATE NO ACTION
        ON DELETE NO ACTION;
