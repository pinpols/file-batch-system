-- docs/analysis/2026-06-03-deep-scan-be-security.md P1-1:
-- api_key 由裸 SHA-256 升级为 PBKDF2-HMAC-SHA256(600k iter)+ per-key salt。
-- 老 key 标 algo='sha256' legacy,验证仍走原路径并在首次命中时按 KDF 重算升级。
ALTER TABLE batch.api_key
    ADD COLUMN IF NOT EXISTS salt          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS key_hash_algo VARCHAR(16) NOT NULL DEFAULT 'sha256';

-- key_hash 列宽放宽以容纳 base64(32B) ≈ 44 字符 的 KDF 输出。
ALTER TABLE batch.api_key
    ALTER COLUMN key_hash TYPE VARCHAR(256);

COMMENT ON COLUMN batch.api_key.salt IS 'base64(16B) random salt; null for legacy sha256 rows';
COMMENT ON COLUMN batch.api_key.key_hash_algo IS 'sha256(legacy,无盐) | pbkdf2(P1-1 新默认)';

-- 注:api_key 不在 ArchiveSchemaDriftCheck.ARCHIVED_TABLES 范围(无 archive 冷表镜像),
-- 故无需同步 archive 列。
