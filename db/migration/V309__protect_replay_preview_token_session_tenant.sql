-- Fresh-database deployment runs migrations before application traffic or business data.
-- session_id is intentionally nullable until a consumed token is linked during submit.
ALTER TABLE batch.batch_day_replay_preview_token
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_replay_preview_token_session_tenant
        FOREIGN KEY (tenant_id, session_id)
        REFERENCES batch.batch_day_replay_session (tenant_id, id)
        MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION;
