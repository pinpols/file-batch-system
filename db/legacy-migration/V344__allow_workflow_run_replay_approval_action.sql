-- 允许整 Workflow Replay 使用独立审批动作。
-- 保留已支持的审批动作，并纳入 Workflow Replay，避免审批创建被 CHECK 约束拒绝。

ALTER TABLE batch.approval_command
    DROP CONSTRAINT ck_approval_command_action;

ALTER TABLE batch.approval_command
    ADD CONSTRAINT ck_approval_command_action CHECK (
        action_type IN (
            'CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD',
            'RETRY', 'RERUN', 'WORKFLOW_RUN_REPLAY'
        )
    ) NOT VALID;

ALTER TABLE batch.approval_command
    VALIDATE CONSTRAINT ck_approval_command_action;

COMMENT ON CONSTRAINT ck_approval_command_action ON batch.approval_command IS
    '限制审批命令可执行的业务动作类型。';
