-- =========================================================
-- V79 - 把 V77/V78 加给 batch.* 的 i18n 列(error_key + error_args)同步到 archive.* cold schema
-- =========================================================
-- 上一轮 V77/V78 给热表加了 i18n 三元组(error_key/error_args/error_message),
-- 但 archive.* 归档表(V71 创建)没同步,启动时 ArchiveSchemaDriftCheck 报 7 张表
-- "hot has extra [error_args, error_key]" 直接拒启 orchestrator。
--
-- archive.* 与 batch.* 字段必须 1:1 镜像,归档迁移 SQL(INSERT INTO archive.X SELECT * FROM batch.X)
-- 才不会因为 column 数量不一致失败。

ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE archive.event_delivery_log_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE archive.pipeline_step_run_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE archive.job_step_instance_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE archive.compensation_command_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE archive.file_dispatch_record_archive
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

COMMENT ON COLUMN archive.job_task_archive.error_key             IS '镜像 batch.job_task.error_key';
COMMENT ON COLUMN archive.job_task_archive.error_args            IS '镜像 batch.job_task.error_args';
COMMENT ON COLUMN archive.workflow_node_run_archive.error_key    IS '镜像 batch.workflow_node_run.error_key';
COMMENT ON COLUMN archive.workflow_node_run_archive.error_args   IS '镜像 batch.workflow_node_run.error_args';
COMMENT ON COLUMN archive.event_delivery_log_archive.error_key   IS '镜像 batch.event_delivery_log.error_key';
COMMENT ON COLUMN archive.event_delivery_log_archive.error_args  IS '镜像 batch.event_delivery_log.error_args';
COMMENT ON COLUMN archive.pipeline_step_run_archive.error_key    IS '镜像 batch.pipeline_step_run.error_key';
COMMENT ON COLUMN archive.pipeline_step_run_archive.error_args   IS '镜像 batch.pipeline_step_run.error_args';
COMMENT ON COLUMN archive.job_step_instance_archive.error_key    IS '镜像 batch.job_step_instance.error_key';
COMMENT ON COLUMN archive.job_step_instance_archive.error_args   IS '镜像 batch.job_step_instance.error_args';
COMMENT ON COLUMN archive.compensation_command_archive.error_key  IS '镜像 batch.compensation_command.error_key';
COMMENT ON COLUMN archive.compensation_command_archive.error_args IS '镜像 batch.compensation_command.error_args';
COMMENT ON COLUMN archive.file_dispatch_record_archive.error_key  IS '镜像 batch.file_dispatch_record.error_key';
COMMENT ON COLUMN archive.file_dispatch_record_archive.error_args IS '镜像 batch.file_dispatch_record.error_args';
