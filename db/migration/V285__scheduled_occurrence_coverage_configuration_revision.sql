ALTER TABLE batch.scheduled_occurrence_coverage
    ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN batch.scheduled_occurrence_coverage.configuration_revision IS
    'Tenant configuration revision fully represented by this occurrence projection watermark.';
