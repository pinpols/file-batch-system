-- V185 - Widen job_definition.depends_on_job_code to match job_code length.
-- V177 introduced depends_on_job_code as VARCHAR(64), but it references job_code VARCHAR(128).
-- Keep validation/OpenAPI/Excel at 128 and widen DB to remove the 64/128 mismatch.
-- job_definition is a config table and has no archive.* mirror, matching V177.

ALTER TABLE batch.job_definition
    ALTER COLUMN depends_on_job_code TYPE VARCHAR(128);

COMMENT ON COLUMN batch.job_definition.depends_on_job_code IS
    'ADR-043: 上游 job code;非空时本触发器 fire 前要求该 job 同 bizDate 已 SUCCESS,否则跳过本次';
