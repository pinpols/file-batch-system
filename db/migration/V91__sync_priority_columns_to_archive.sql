-- V91: 补 V88 漏掉的 archive 镜像 (CLAUDE.md "archive 冷表对齐")
--
-- V88 给 batch.job_task / batch.outbox_event 加了 priority 列，但 archive 镜像表未同步。
-- ArchiveSchemaDriftCheck.checkOnStartup() 启动期 diff 14 张归档对照表，差异即 fail-fast。
-- 不补的话 orchestrator 下次重启 → IllegalStateException → 整个进程起不来。
--
-- 注意: archive 表只是冷库, 不需要 CHECK / 索引 / NOT NULL — 数据从 batch.* INSERT-SELECT-* 进来,
-- 列定义存在即可让 SELECT * 列对齐。
ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS priority INTEGER;

ALTER TABLE archive.outbox_event_archive
    ADD COLUMN IF NOT EXISTS priority INTEGER;

COMMENT ON COLUMN archive.job_task_archive.priority IS
    'V88 镜像列, 默认从 batch.job_task 拷, 用于历史 task 的优先级回溯';
COMMENT ON COLUMN archive.outbox_event_archive.priority IS
    'V88 镜像列, 默认从 batch.outbox_event 拷, 用于历史投递事件的优先级回溯';
