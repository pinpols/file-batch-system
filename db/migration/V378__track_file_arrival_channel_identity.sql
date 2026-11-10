-- 迁移前已有记录保留旧来源匹配；事务提交后新增记录即使由旧版本写入也不能冒用历史回退。
-- 两次 ALTER 在同一迁移事务内持有表锁，避免按业务时间或序列推测提交先后。
ALTER TABLE batch.file_record
    ADD COLUMN arrival_channel_code VARCHAR(128),
    ADD COLUMN legacy_channel_fallback BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE batch.file_record ALTER COLUMN legacy_channel_fallback SET DEFAULT false;

-- 冷表保持结构镜像；新版本归档时显式复制两个字段。
ALTER TABLE archive.file_record_archive
    ADD COLUMN arrival_channel_code VARCHAR(128),
    ADD COLUMN legacy_channel_fallback BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE archive.file_record_archive ALTER COLUMN legacy_channel_fallback SET DEFAULT false;

COMMENT ON COLUMN batch.file_record.arrival_channel_code IS '由可信入口登记的文件到达渠道编码；未建立身份时为空。';
COMMENT ON COLUMN batch.file_record.legacy_channel_fallback IS '迁移前已存在的历史记录允许沿用来源类型匹配。';
COMMENT ON COLUMN archive.file_record_archive.arrival_channel_code IS '归档时保留可信文件到达渠道编码。';
COMMENT ON COLUMN archive.file_record_archive.legacy_channel_fallback IS '归档时保留历史来源匹配资格。';
