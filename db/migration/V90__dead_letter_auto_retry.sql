-- V90: dead_letter_task 自动重放支持
--
-- next_replay_at      — 下一次自动重放时间 (NULL = 不安排自动重放, 仅人工触发)
-- max_replay_count    — 自动重放上限, 超过后 scheduler 改 GIVE_UP, 人工仍可 /internal/dead-letters/{id}/replay
-- error_class         — BUSINESS (硬错, 不自动重放) | SYSTEM (软错, 自动重放)
--                       BUSINESS 来源: NON_RETRYABLE_ERROR_CODES (file_missing / channel_not_found 等)
ALTER TABLE batch.dead_letter_task
    ADD COLUMN IF NOT EXISTS next_replay_at TIMESTAMPTZ;

ALTER TABLE batch.dead_letter_task
    ADD COLUMN IF NOT EXISTS max_replay_count INTEGER NOT NULL DEFAULT 3;

ALTER TABLE batch.dead_letter_task
    ADD COLUMN IF NOT EXISTS error_class VARCHAR(32) NOT NULL DEFAULT 'SYSTEM';

ALTER TABLE batch.dead_letter_task
    DROP CONSTRAINT IF EXISTS ck_dead_letter_error_class;

ALTER TABLE batch.dead_letter_task
    ADD CONSTRAINT ck_dead_letter_error_class
    CHECK (error_class IN ('BUSINESS', 'SYSTEM'));

-- 自动重放轮询索引: 只对 NEW / FAILED + 已到 next_replay_at 的 SYSTEM 记录有效
CREATE INDEX IF NOT EXISTS idx_dead_letter_auto_retry
    ON batch.dead_letter_task (replay_status, error_class, next_replay_at, replay_count)
    WHERE replay_status IN ('NEW', 'FAILED') AND error_class = 'SYSTEM';

COMMENT ON COLUMN batch.dead_letter_task.next_replay_at IS
    'V90: 下一次自动重放时间, NULL 表示不安排自动重放';
COMMENT ON COLUMN batch.dead_letter_task.max_replay_count IS
    'V90: 自动重放上限, 超过后转 GIVE_UP';
COMMENT ON COLUMN batch.dead_letter_task.error_class IS
    'V90: BUSINESS|SYSTEM 错误分类, BUSINESS 不参与自动重放';
