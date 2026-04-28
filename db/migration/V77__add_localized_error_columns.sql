-- =========================================================
-- V77 - i18n 持久化错误三元组:为高价值表加 error_key + error_args
-- =========================================================
-- 设计依据:docs/design/i18n.md §6 "错误消息持久化"。
-- 写路径(BizException → ExceptionHandler / Repository)同步写 error_key + error_args,读路径
-- (console-api 历史日志查询)按当前 Locale 重渲染,而不是按写入时的 locale 永久 frozen。
--
-- 三表覆盖度:
--   1) batch.job_task             - 任务失败(用户最常见)
--   2) batch.workflow_node_run    - workflow 节点失败
--   3) batch.event_delivery_log   - outbox 派发失败(运维巡检)
--
-- Phase 2(后续 PR):pipeline_step_run / job_step_instance / compensation_command /
-- compensation_checkpoint / file_dispatch_record / file_error_record / retry_schedule /
-- notification_delivery_log。
--
-- 列设计:
--   error_key   VARCHAR(128)  - i18n message key,如 error.task.lease_renew_rejected
--   error_args  JSONB         - 占位符参数 JSON 数组,如 ["acme-corp", "42"]
--   error_message(已存在)   - 写入时 Locale 渲染的字符串,作为:
--                                · 无 i18n key 时唯一展示来源(老 literal / 第三方异常)
--                                · key 在新版本被删/改时的 fallback
--                                · 运维 SELECT 直接可读

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.workflow_node_run
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

ALTER TABLE batch.event_delivery_log
    ADD COLUMN IF NOT EXISTS error_key  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_args JSONB;

COMMENT ON COLUMN batch.job_task.error_key             IS 'i18n message key;读路径按当前 Locale 重渲染替代 error_message';
COMMENT ON COLUMN batch.job_task.error_args            IS 'i18n key 的占位符参数 JSON 数组,与 messages.properties {0}/{1}/... 对应';
COMMENT ON COLUMN batch.workflow_node_run.error_key    IS '同 job_task.error_key';
COMMENT ON COLUMN batch.workflow_node_run.error_args   IS '同 job_task.error_args';
COMMENT ON COLUMN batch.event_delivery_log.error_key   IS '同 job_task.error_key';
COMMENT ON COLUMN batch.event_delivery_log.error_args  IS '同 job_task.error_args';
