INSERT INTO batch.api_key (
    tenant_id, key_name, key_prefix, key_hash, key_hash_algo,
    scopes, enabled, created_at
) VALUES (
    :'tenant_id', :'key_name', :'key_prefix', :'key_hash', 'sha256',
    '*', true, now()
)
ON CONFLICT DO NOTHING;
