-- =========================================================
-- V188 - file_record archive mirror
-- =========================================================
-- 背景:
--   archive_policy 早已把 file_record 列为可归档对象,但 V71 未创建
--   archive.file_record_archive。结果是 job_instance / pipeline / dispatch 冷表
--   已经可追溯时,文件证据仍只能依赖 batch.file_record 热表。
--
-- 范围:
--   只补 BFS 文件证据冷表镜像和查询索引;不改变对象存储生命周期,不删除热表
--   file_record,也不做字段级或记录级企业血缘。
-- =========================================================

CREATE TABLE IF NOT EXISTS archive.file_record_archive
    (LIKE batch.file_record INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'file_record_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.file_record_archive
            ADD CONSTRAINT pk_file_record_archive PRIMARY KEY (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_file_record_archive_biz_date
    ON archive.file_record_archive (tenant_id, biz_date, created_at);

CREATE INDEX IF NOT EXISTS idx_file_record_archive_trace_id
    ON archive.file_record_archive (trace_id);

CREATE INDEX IF NOT EXISTS idx_file_record_archive_status
    ON archive.file_record_archive (tenant_id, file_status, updated_at);

COMMENT ON TABLE archive.file_record_archive IS
    'batch.file_record 的冷表镜像;用于历史文件证据链和归档后 forensic 查询';
