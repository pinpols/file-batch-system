-- Fresh-database deployment runs migrations before application traffic or business data.
-- The direct validated constraints below are deliberate; this is not an online upgrade.
ALTER TABLE batch.scheduled_occurrence
    -- squawk-ignore constraint-missing-not-valid, disallowed-unique-constraint
    ADD CONSTRAINT uk_scheduled_occurrence_request_identity
        UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id);

ALTER TABLE batch.scheduled_occurrence_catch_up_audit
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_scheduled_occurrence_catch_up_identity
        FOREIGN KEY (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id)
        REFERENCES batch.scheduled_occurrence
            (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id)
        ON UPDATE NO ACTION
        ON DELETE NO ACTION;
