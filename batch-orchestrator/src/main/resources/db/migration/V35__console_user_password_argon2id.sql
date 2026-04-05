-- 与 batch-orchestrator/db/migration 中 V34 控制台种子同属一套；本脚本仅升级「已执行旧版 V34（PBKDF2）」的库。
-- 新库由 V34 直接写入 Argon2id，本脚本 WHERE 不匹配则跳过（幂等）。
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
