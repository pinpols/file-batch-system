-- 导出束清单使用独立的文件类别作为触发记录；先以未验证约束兼容新写入，再移除旧约束。
-- 冷表与热表保持相同类别约束；全表验证放在下一次迁移事务，缩短此处持锁时间。
ALTER TABLE batch.file_record
    ADD CONSTRAINT ck_file_record_category_v2
    CHECK (file_category IN ('INPUT', 'OUTPUT', 'INTERMEDIATE', 'ARCHIVE', 'EXPORT_TRIGGER')) NOT VALID;
ALTER TABLE batch.file_record DROP CONSTRAINT ck_file_record_category;

ALTER TABLE archive.file_record_archive
    ADD CONSTRAINT ck_file_record_category_v2
    CHECK (file_category IN ('INPUT', 'OUTPUT', 'INTERMEDIATE', 'ARCHIVE', 'EXPORT_TRIGGER')) NOT VALID;
ALTER TABLE archive.file_record_archive DROP CONSTRAINT ck_file_record_category;
