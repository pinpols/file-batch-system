INSERT INTO batch.api_key (
    tenant_id,
    key_name,
    key_prefix,
    key_hash,
    key_hash_algo,
    scopes,
    enabled,
    created_at
)
VALUES (
    :'tenant_id',
    :'key_name',
    :'key_prefix',
    :'key_hash',
    'sha256',
    '*',
    TRUE,
    CURRENT_TIMESTAMP
)
ON CONFLICT (tenant_id, key_name) DO UPDATE
SET key_prefix = EXCLUDED.key_prefix,
    key_hash = EXCLUDED.key_hash,
    key_hash_algo = EXCLUDED.key_hash_algo,
    scopes = EXCLUDED.scopes,
    enabled = TRUE,
    expires_at = NULL,
    revoked_by = NULL,
    revoked_at = NULL;
