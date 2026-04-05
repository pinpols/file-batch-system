-- 控制台默认账号密码哈希统一为 Argon2id（开发种子明文均为 admin123，与 ConsolePasswordHasher 一致）。
-- 将 V34 写入的 PBKDF2 存量升级为 Argon2；已为本格式的行不修改（幂等）。
UPDATE batch.console_user_account
SET password_hash = '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default-tenant'
  AND username IN ('admin', 'auditor', 'config-admin')
  AND password_hash IN (
    'pbkdf2_sha256$120000$ABEiM0RVZneImaq7zN3u/w==$SDdcSBs/sQioqO6CmSkLP+TzSWRrT5585nSe9kXNV2A=',
    'pbkdf2_sha256$120000$ECEyQ1RldoeYqbq8vdzu/w==$w7P8/MNBTsIeMZQHUZHqX8x06ZZvZ6WFzt1NNjtP5g8=',
    'pbkdf2_sha256$120000$IDFCU2R1hpeoucrb7P0OHw==$TyfC0ySDFVla+v3MeGte52rb+kY/PAD6XnSD9qbPj80='
  );
