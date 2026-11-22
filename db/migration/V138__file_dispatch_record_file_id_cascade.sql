-- =========================================================
-- V138: file_dispatch_record.file_id FK 改为 ON DELETE CASCADE
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.6 / Quick wins §8
--
-- 背景:
--   V6 创建 batch.file_dispatch_record 时 file_id REFERENCES batch.file_record(id)
--   未显式 ON DELETE 行为,PG 默认 NO ACTION (= RESTRICT)。
--   归档/清理脚本删除 file_record 时,若顺序漏删 file_dispatch_record 子行,
--   会被 FK 阻塞,清理事务回滚整组失败。
--
-- 设计:
--   file_dispatch_record 是 file_record 的"派发投递记录",生命周期完全依附父记录,
--   父表归档/物理删除时子表应同步消失。改为 ON DELETE CASCADE。
--
-- 影响:
--   ・新策略生效后,DELETE batch.file_record 会级联删除关联 file_dispatch_record。
--   ・现有清理脚本(scripts/db/cleanup-*.sql)若已按"先子后父"顺序删除,
--     行为不变;若漏删子表,会从"事务失败"变为"自动清理",更安全。
--
-- archive 镜像表:
--   archive.file_dispatch_record_archive 由 LIKE INCLUDING CONSTRAINTS 创建,
--   PG LIKE 子句**不复制 FK**,因此 archive 表本就无 file_id FK,无需修改。
-- =========================================================

ALTER TABLE batch.file_dispatch_record
    DROP CONSTRAINT IF EXISTS file_dispatch_record_file_id_fkey;

ALTER TABLE batch.file_dispatch_record
    ADD CONSTRAINT file_dispatch_record_file_id_fkey
    FOREIGN KEY (file_id) REFERENCES batch.file_record(id) ON DELETE CASCADE;
