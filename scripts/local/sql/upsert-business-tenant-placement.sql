INSERT INTO batch.business_tenant_placement (
    tenant_id,
    placement_key,
    updated_by
)
VALUES (
    :'tenant_id',
    :'placement_key',
    :'updated_by'
)
ON CONFLICT (tenant_id) DO UPDATE
SET placement_key = EXCLUDED.placement_key,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;
