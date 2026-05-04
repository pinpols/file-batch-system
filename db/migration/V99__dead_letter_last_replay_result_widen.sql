-- V99: dead_letter_task.last_replay_result — 容纳完整 SQLException / 堆栈摘要（此前 VARCHAR(32) 会在自动重放失败回写时报错）
ALTER TABLE batch.dead_letter_task
    ALTER COLUMN last_replay_result TYPE VARCHAR(512);

COMMENT ON COLUMN batch.dead_letter_task.last_replay_result IS
    '最近一次自动/手动重放结果摘要或错误信息（截断写入，最长 512）';
