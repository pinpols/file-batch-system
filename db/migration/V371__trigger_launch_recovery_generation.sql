ALTER TABLE batch.trigger_launch_recovery
    ADD COLUMN recovery_generation BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN batch.trigger_launch_recovery.recovery_generation IS
    '死信重新入队时递增，隔离旧恢复调用的迟到失败回执。';
