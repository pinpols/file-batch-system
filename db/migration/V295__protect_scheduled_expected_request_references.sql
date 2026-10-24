-- Scheduled projections and expected-work obligations retain the originating
-- request identity.  A request cannot be deleted while either ledger row is
-- present; terminal ledger retention is handled by the explicit cleanup
-- protocol rather than by cascading the parent request.
ALTER TABLE batch.expected_work_occurrence
    ADD CONSTRAINT fk_expected_work_trigger_request
        FOREIGN KEY (tenant_id, trigger_request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id) NOT VALID;
ALTER TABLE batch.expected_work_occurrence
    VALIDATE CONSTRAINT fk_expected_work_trigger_request;

ALTER TABLE batch.scheduled_occurrence
    ADD CONSTRAINT fk_scheduled_occurrence_trigger_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id) NOT VALID;
ALTER TABLE batch.scheduled_occurrence
    VALIDATE CONSTRAINT fk_scheduled_occurrence_trigger_request;

COMMENT ON CONSTRAINT fk_expected_work_trigger_request
    ON batch.expected_work_occurrence IS
    'Tenant-scoped originating request; NO ACTION preserves expected-work evidence until explicit lifecycle cleanup';
COMMENT ON CONSTRAINT fk_scheduled_occurrence_trigger_request
    ON batch.scheduled_occurrence IS
    'Tenant-scoped originating request; NO ACTION preserves scheduled-fire provenance until explicit lifecycle cleanup';
