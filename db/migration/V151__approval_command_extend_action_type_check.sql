-- =========================================================
-- V151: approval_command 扩展 approval_type + action_type CHECK
--
-- 背景:
--   V27 创建表时 ck_approval_command_type 仅含 ('CATCH_UP','COMPENSATION','DLQ_REPLAY','DOWNLOAD'),
--   ck_approval_command_action 仅含 ('CATCH_UP','COMPENSATION','DLQ_REPLAY','DOWNLOAD','RETRY')。
--   但后续 console self-service rerun 路径(ConsoleSelfServiceJobService)插入:
--     approval_type='SELF_SERVICE', action_type='RERUN'
--   导致生产 console.log + orchestrator.log 反复抛 ck_approval_command_action 违反。
--
-- 修正:
--   - approval_type 白名单加 'SELF_SERVICE'
--   - action_type 白名单加 'RERUN'
-- =========================================================

ALTER TABLE batch.approval_command
    DROP CONSTRAINT ck_approval_command_type;

ALTER TABLE batch.approval_command
    ADD CONSTRAINT ck_approval_command_type CHECK (
        approval_type IN ('CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD', 'SELF_SERVICE')
    );

ALTER TABLE batch.approval_command
    DROP CONSTRAINT ck_approval_command_action;

ALTER TABLE batch.approval_command
    ADD CONSTRAINT ck_approval_command_action CHECK (
        action_type IN ('CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD', 'RETRY', 'RERUN')
    );
