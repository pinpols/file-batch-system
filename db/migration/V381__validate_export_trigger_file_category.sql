-- 在独立事务验证文件热表和冷表的扩展类别约束，避免与前一迁移的结构锁合并。
ALTER TABLE batch.file_record VALIDATE CONSTRAINT ck_file_record_category_v2;
ALTER TABLE archive.file_record_archive VALIDATE CONSTRAINT ck_file_record_category_v2;
