-- =========================================================
-- V175 - file_error_record 增加 Excel 物理定位列(行号 + 列名)
-- =========================================================
-- 背景:ExcelFormatParser 走 POI SAX 流式解析,endRow(rowNum) 拿得到 0-based 物理行号
-- 却被丢弃,坏行只记逻辑 record_no。用户拿到坏记录后无法回原表(.xlsx)精确定位是第几行 /
-- 哪一列出的错。本迁移把物理行号 + 出错列(表头名)透传进坏行治理并落库。
--
-- 列设计:
--   source_row_num  BIGINT        - 源文件 1-based 物理行号(Excel 行 / 文本行)。SAX 的 rowNum
--                                    为 0-based,落库统一 +1 对齐用户在 Excel 里看到的行号。
--                                    其他格式(CSV/JSON/...)暂不回填,保持 NULL 向后兼容。
--   source_column   VARCHAR(256)  - 出错列的表头名(如 "客户编号");无法定位到具体列时 NULL。
--
-- 向后兼容:两列均可空,不配 / 旧写路径不回填 = NULL,现有查询与写入不受影响。
-- archive 镜像:file_error_record 不在 ArchiveSchemaDriftCheck.ARCHIVED_TABLES 内
--              (无 archive.file_error_record_archive 冷表),故无需补归档表迁移。

ALTER TABLE batch.file_error_record
    ADD COLUMN IF NOT EXISTS source_row_num BIGINT,
    ADD COLUMN IF NOT EXISTS source_column  VARCHAR(256);

COMMENT ON COLUMN batch.file_error_record.source_row_num IS '源文件 1-based 物理行号(Excel 行 / 文本行);用于坏行回原表定位,非 Excel 路径可空';
COMMENT ON COLUMN batch.file_error_record.source_column  IS '出错列的表头名;无法定位到具体列时为空';
