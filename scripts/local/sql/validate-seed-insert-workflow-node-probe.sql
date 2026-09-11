INSERT INTO batch.workflow_node (
    tenant_id,
    workflow_definition_id,
    node_code,
    node_name,
    node_type,
    node_order,
    retry_policy,
    retry_max_count,
    timeout_seconds,
    enabled
)
VALUES (
    :'tenant_id',
    :'workflow_definition_id'::bigint,
    'SEEDVAL_PROBE',
    'probe',
    'TASK',
    99,
    'NONE',
    0,
    0,
    TRUE
)
ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING
RETURNING tenant_id;
