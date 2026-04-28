-- =========================================================
-- V78 - i18n 持久化错误三元组 Phase 2:剩余 8 表加 error_key + error_args
-- =========================================================
-- 承接 V77 的 3 表落地 (job_task / workflow_node_run / event_delivery_log),
-- 把剩余的运行态 / 派发 / 通知 / 补偿表全部纳入 i18n 持久化体系。
--
-- Phase 2 表清单(8 张):
--   1) batch.pipeline_step_run            - PROCESS / IMPORT / EXPORT 各 stage 失败
--   2) batch.job_step_instance            - 子 step 失败(IMPORT 6 阶段)
--   3) batch.compensation_command         - 补偿命令失败
--   4) batch.compensation_checkpoint      - 补偿断点失败
--   5) batch.file_dispatch_record         - 文件派发失败(SFTP / OSS / 邮件)
--   6) batch.file_error_record            - 单条文件解析 / 校验失败
--   7) batch.retry_schedule               - 重试调度上一次失败原因
--   8) batch.notification_delivery_log    - 通知投递失败
--
-- 字段语义同 V77:
--   error_key   VARCHAR(128)  - i18n message key (BizException.of)
--   error_args  JSONB         - 占位符参数 JSON 数组
--   error_message(已存在)   - 写入时 Locale 渲染的字符串(fallback / 运维直读)
--
-- retry_schedule 用 last_error_key / last_error_args(与已有 last_error_message 对齐)。

ALTER TABLE batch.pipeline_step_run
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.job_step_instance
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.compensation_command
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.compensation_checkpoint
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.file_dispatch_record
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.file_error_record
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.retry_schedule
    ADD COLUMN IF NOT EXISTS last_error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_error_args JSONB;

ALTER TABLE batch.notification_delivery_log
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

COMMENT ON COLUMN batch.pipeline_step_run.error_key            IS 'i18n message key;读路径按当前 Locale 渲染替代 error_message';
COMMENT ON COLUMN batch.pipeline_step_run.error_args           IS 'i18n key 的占位符参数 JSON 数组';
COMMENT ON COLUMN batch.job_step_instance.error_key            IS '同 pipeline_step_run.error_key';
COMMENT ON COLUMN batch.job_step_instance.error_args           IS '同 pipeline_step_run.error_args';
COMMENT ON COLUMN batch.compensation_command.error_key         IS '同 pipeline_step_run.error_key';
COMMENT ON COLUMN batch.compensation_command.error_args        IS '同 pipeline_step_run.error_args';
COMMENT ON COLUMN batch.compensation_checkpoint.error_key      IS '同 pipeline_step_run.error_key';
COMMENT ON COLUMN batch.compensation_checkpoint.error_args     IS '同 pipeline_step_run.error_args';
COMMENT ON COLUMN batch.file_dispatch_record.error_key         IS '同 pipeline_step_run.error_key';
COMMENT ON COLUMN batch.file_dispatch_record.error_args        IS '同 pipeline_step_run.error_args';
COMMENT ON COLUMN batch.file_error_record.error_key            IS '同 pipeline_step_run.error_key';
COMMENT ON COLUMN batch.file_error_record.error_args           IS '同 pipeline_step_run.error_args';
COMMENT ON COLUMN batch.retry_schedule.last_error_key          IS '同 pipeline_step_run.error_key (重试调度的最近一次)';
COMMENT ON COLUMN batch.retry_schedule.last_error_args         IS '同 pipeline_step_run.error_args';
COMMENT ON COLUMN batch.notification_delivery_log.error_key    IS '同 pipeline_step_run.error_key';
COMMENT ON COLUMN batch.notification_delivery_log.error_args   IS '同 pipeline_step_run.error_args';
