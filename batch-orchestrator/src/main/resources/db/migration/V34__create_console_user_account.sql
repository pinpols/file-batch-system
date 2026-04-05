CREATE TABLE IF NOT EXISTS batch.console_user_account (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    username        VARCHAR(128) NOT NULL,
    display_name    VARCHAR(256),
    password_hash   VARCHAR(512) NOT NULL,
    authorities_csv VARCHAR(512) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_console_user_account_tenant_username UNIQUE (tenant_id, username)
);

INSERT INTO batch.console_user_account (tenant_id, username, display_name, password_hash, authorities_csv, enabled)
VALUES
    ('default-tenant', 'admin', 'Console Admin', 'pbkdf2_sha256$120000$ABEiM0RVZneImaq7zN3u/w==$SDdcSBs/sQioqO6CmSkLP+TzSWRrT5585nSe9kXNV2A=', 'ROLE_ADMIN,ROLE_AUDITOR,ROLE_CONFIG_ADMIN', TRUE),
    ('default-tenant', 'auditor', 'Console Auditor', 'pbkdf2_sha256$120000$ECEyQ1RldoeYqbq8vdzu/w==$w7P8/MNBTsIeMZQHUZHqX8x06ZZvZ6WFzt1NNjtP5g8=', 'ROLE_AUDITOR', TRUE),
    ('default-tenant', 'config-admin', 'Console Config Admin', 'pbkdf2_sha256$120000$IDFCU2R1hpeoucrb7P0OHw==$TyfC0ySDFVla+v3MeGte52rb+kY/PAD6XnSD9qbPj80=', 'ROLE_CONFIG_ADMIN', TRUE)
ON CONFLICT (tenant_id, username) DO UPDATE
SET display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    authorities_csv = EXCLUDED.authorities_csv,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
