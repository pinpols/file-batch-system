-- 为跨日历依赖等待记录持久化人工释放或跳过的操作信息。

ALTER TABLE batch.calendar_dependency_wait
    ADD COLUMN manual_action VARCHAR(16),
    ADD COLUMN manual_operator_id VARCHAR(128),
    ADD COLUMN manual_reason VARCHAR(1024),
    ADD COLUMN manual_requested_at TIMESTAMPTZ;

ALTER TABLE batch.calendar_dependency_wait
    ADD CONSTRAINT ck_calendar_dependency_wait_manual_action
        CHECK (manual_action IS NULL OR manual_action IN ('RELEASE', 'SKIP')) NOT VALID,
    ADD CONSTRAINT ck_calendar_dependency_wait_manual_fields
        CHECK ((manual_action IS NULL AND manual_operator_id IS NULL
                AND manual_reason IS NULL AND manual_requested_at IS NULL)
            OR (manual_action IS NOT NULL AND manual_operator_id IS NOT NULL
                AND manual_reason IS NOT NULL AND manual_requested_at IS NOT NULL)) NOT VALID;

ALTER TABLE batch.calendar_dependency_wait
    VALIDATE CONSTRAINT ck_calendar_dependency_wait_manual_action;

ALTER TABLE batch.calendar_dependency_wait
    VALIDATE CONSTRAINT ck_calendar_dependency_wait_manual_fields;

COMMENT ON COLUMN batch.calendar_dependency_wait.manual_action IS
    '管理员请求对该等待日执行 RELEASE（跳过依赖闸门）或 SKIP；由调度器在租户事务内落实';
COMMENT ON COLUMN batch.calendar_dependency_wait.manual_operator_id IS
    '请求人工依赖处置的 Console 操作人';
COMMENT ON COLUMN batch.calendar_dependency_wait.manual_reason IS
    '人工释放或跳过的必填原因';
