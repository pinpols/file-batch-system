-- Give runtime Pipeline instances a stable code/version independent of tenant-owned definitions.
-- Fresh deployments use platform Pipelines without tenant-owned definitions.
-- System Pipeline runs have no tenant definition row.
ALTER TABLE batch.pipeline_instance
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN pipeline_definition_id DROP NOT NULL,
    -- squawk-ignore ban-drop-constraint
    DROP CONSTRAINT IF EXISTS pipeline_instance_pipeline_definition_id_fkey,
    ADD COLUMN IF NOT EXISTS system_pipeline_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS system_pipeline_version INTEGER;

UPDATE batch.pipeline_instance
SET system_pipeline_code = pipeline_type,
    system_pipeline_version = 1
WHERE system_pipeline_code IS NULL;

ALTER TABLE batch.pipeline_instance
    ALTER COLUMN system_pipeline_version SET DEFAULT 1,
    ADD CONSTRAINT ck_pipeline_instance_system_code_present CHECK (system_pipeline_code IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_pipeline_instance_system_version CHECK (system_pipeline_version IS NOT NULL AND system_pipeline_version > 0) NOT VALID;

ALTER TABLE batch.pipeline_instance
    VALIDATE CONSTRAINT ck_pipeline_instance_system_code_present,
    VALIDATE CONSTRAINT ck_pipeline_instance_system_version;

COMMENT ON COLUMN batch.pipeline_instance.pipeline_definition_id IS
    'Legacy tenant-defined pipeline identity; nullable for system pipeline runs and retained for history only.';
COMMENT ON COLUMN batch.pipeline_instance.system_pipeline_code IS
    'Immutable platform Pipeline code selected from the Job type.';
COMMENT ON COLUMN batch.pipeline_instance.system_pipeline_version IS
    'Semantic version of the system Pipeline contract used by this run.';

ALTER TABLE archive.pipeline_instance_archive
    ADD COLUMN IF NOT EXISTS system_pipeline_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS system_pipeline_version INTEGER;

UPDATE archive.pipeline_instance_archive
SET system_pipeline_code = pipeline_type,
    system_pipeline_version = 1
WHERE system_pipeline_code IS NULL;

ALTER TABLE archive.pipeline_instance_archive
    ALTER COLUMN system_pipeline_version SET DEFAULT 1,
    ADD CONSTRAINT ck_pipeline_instance_archive_system_code_present CHECK (system_pipeline_code IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_pipeline_instance_archive_system_version CHECK (system_pipeline_version IS NOT NULL AND system_pipeline_version > 0) NOT VALID;

ALTER TABLE archive.pipeline_instance_archive
    VALIDATE CONSTRAINT ck_pipeline_instance_archive_system_code_present,
    VALIDATE CONSTRAINT ck_pipeline_instance_archive_system_version;
