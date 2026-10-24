-- 平台 Pipeline 的归档记录不对应租户定义行。

ALTER TABLE archive.pipeline_instance_archive
    -- 历史归档保留租户 Pipeline 定义 ID；平台 Pipeline 运行允许该字段为空。
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN pipeline_definition_id DROP NOT NULL;

COMMENT ON COLUMN archive.pipeline_instance_archive.pipeline_definition_id IS
    '历史运行保留的租户自定义 Pipeline 定义标识；系统 Pipeline 运行可为空。';
