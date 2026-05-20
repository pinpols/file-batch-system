-- =========================================================
-- V135: file_record.storage_path VARCHAR(1024) → TEXT
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §4.1 / Quick wins §8
--
-- 背景:
--   V6 创建 batch.file_record 时 storage_path VARCHAR(1024)。
--   S3 / OSS 带 query 参数(预签名 URL、版本 ID)的对象 URI 常常超 1KB,
--   原宽度在 OUTPUT / DISPATCHED 场景下偶有 truncate 风险。
--   PG 中 TEXT 与 VARCHAR(n) 存储/索引开销一致,放宽到 TEXT 无副作用。
--
-- 影响:
--   纯放宽,不会丢数据。已有索引 / 约束不变。
--   file_record 无 archive 镜像表(不在 ARCHIVED_TABLES 清单内),无需同步归档。
-- =========================================================

ALTER TABLE batch.file_record
    ALTER COLUMN storage_path TYPE TEXT;
