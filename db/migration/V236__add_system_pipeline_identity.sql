-- 为运行时 Pipeline 实例赋予稳定的代码和版本，使其不依赖租户自有定义。
-- 全新部署使用平台级 Pipeline，不创建租户自有定义。
-- 系统 Pipeline 运行不对应租户定义记录。

ALTER TABLE batch.pipeline_instance
    -- 系统 Pipeline 运行没有租户定义记录，因此允许定义 ID 为空。
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN pipeline_definition_id DROP NOT NULL,
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
    '历史租户自定义 Pipeline 定义标识；系统 Pipeline 运行可为空，仅用于保留历史关联。';
COMMENT ON COLUMN batch.pipeline_instance.system_pipeline_code IS
    '按 Job 类型选定的平台 Pipeline 编码；运行期间不可变。';
COMMENT ON COLUMN batch.pipeline_instance.system_pipeline_version IS
    '本次运行使用的系统 Pipeline 合同语义版本。';

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
