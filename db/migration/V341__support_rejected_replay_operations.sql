-- 明确区分审批被拒绝与操作员取消。

ALTER TABLE batch.replay_operation
    DROP CONSTRAINT ck_replay_operation_status;

ALTER TABLE batch.replay_operation
    ADD CONSTRAINT ck_replay_operation_status
        CHECK (status IN (
            'PENDING_APPROVAL', 'REJECTED', 'RUNNING', 'SUCCEEDED',
            'PARTIAL_FAILED', 'FAILED', 'CANCELLED'
        )) NOT VALID;

ALTER TABLE batch.replay_operation
    VALIDATE CONSTRAINT ck_replay_operation_status;

COMMENT ON CONSTRAINT ck_replay_operation_status ON batch.replay_operation IS
    'Replay Operation 允许的生命周期状态；明确区分审批拒绝与取消。';
