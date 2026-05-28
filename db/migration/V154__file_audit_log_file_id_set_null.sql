-- V154: file_audit_log.file_id FK ON DELETE SET NULL + 列改 NULLable
--
-- 背景:V138 已把 file_dispatch_record.file_id 改 CASCADE(父表 file_record 物理删/归档时
-- 子表同步消失)。但 file_audit_log 对同一父表的 FK 仍是默认 NO ACTION,等同 RESTRICT —
-- 任何 cleanup / 归档路径删 file_record 时会被 file_audit_log FK 拦截抛
-- DataIntegrityViolationException,孤儿 file_record 行积累(同 V119 修复 job_execution_log
-- 的问题语义)。
--
-- 决策对齐 V128(job_execution_log 同类决策):审计行属"留存证据",父表删后不应被级联抹掉 —
-- 应 SET NULL 保审计、孤儿 audit 行运维路径单独清。

ALTER TABLE batch.file_audit_log
    ALTER COLUMN file_id DROP NOT NULL;

ALTER TABLE batch.file_audit_log
    DROP CONSTRAINT IF EXISTS file_audit_log_file_id_fkey;

ALTER TABLE batch.file_audit_log
    ADD CONSTRAINT file_audit_log_file_id_fkey
        FOREIGN KEY (file_id) REFERENCES batch.file_record(id) ON DELETE SET NULL;
