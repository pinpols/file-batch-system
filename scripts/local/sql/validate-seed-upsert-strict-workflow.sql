BEGIN;

INSERT INTO batch.workflow_definition (
    tenant_id,
    workflow_code,
    workflow_name,
    workflow_type,
    version,
    enabled,
    description,
    created_by,
    updated_by,
    created_at,
    updated_at
)
VALUES (
    :'tenant_id',
    :'job_code',
    'seedval pipeline workflow',
    'PIPELINE',
    1,
    TRUE,
    'strict seed workflow probe',
    :'probe_tag',
    :'probe_tag',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (tenant_id, workflow_code, version) DO UPDATE
SET workflow_name = EXCLUDED.workflow_name,
    workflow_type = EXCLUDED.workflow_type,
    enabled = TRUE,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.workflow_node (
    tenant_id,
    workflow_definition_id,
    node_code,
    node_name,
    node_type,
    related_job_code,
    node_order,
    retry_policy,
    retry_max_count,
    timeout_seconds,
    enabled,
    node_params,
    created_at,
    updated_at
)
SELECT definition.tenant_id,
       definition.id,
       node.node_code,
       node.node_name,
       node.node_type,
       node.related_job_code,
       node.node_order,
       'NONE',
       0,
       0,
       TRUE,
       node.node_params::jsonb,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM batch.workflow_definition definition
CROSS JOIN (VALUES
    ('START', 'Start', 'START', NULL::text, 0, '{"entry":true}'),
    ('EXPORT_STEP', 'Export Step', 'TASK', 'export_settlement_job', 1, '{"step":"export"}'),
    ('END', 'End', 'END', NULL::text, 2, '{"entry":false}')
) AS node(node_code, node_name, node_type, related_job_code, node_order, node_params)
WHERE definition.tenant_id = :'tenant_id'
  AND definition.workflow_code = :'job_code'
  AND definition.version = 1
ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO UPDATE
SET node_name = EXCLUDED.node_name,
    node_type = EXCLUDED.node_type,
    related_job_code = EXCLUDED.related_job_code,
    node_order = EXCLUDED.node_order,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    timeout_seconds = EXCLUDED.timeout_seconds,
    enabled = TRUE,
    node_params = EXCLUDED.node_params,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.workflow_edge (
    tenant_id,
    workflow_definition_id,
    from_node_code,
    to_node_code,
    edge_type,
    enabled,
    created_at,
    updated_at
)
SELECT definition.tenant_id,
       definition.id,
       edge.from_node_code,
       edge.to_node_code,
       edge.edge_type,
       TRUE,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM batch.workflow_definition definition
CROSS JOIN (VALUES
    ('START', 'EXPORT_STEP', 'ALWAYS'),
    ('EXPORT_STEP', 'END', 'ALWAYS')
) AS edge(from_node_code, to_node_code, edge_type)
WHERE definition.tenant_id = :'tenant_id'
  AND definition.workflow_code = :'job_code'
  AND definition.version = 1
ON CONFLICT (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type) DO UPDATE
SET enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO batch.job_definition (
    tenant_id,
    job_code,
    job_name,
    job_type,
    schedule_type,
    timezone,
    trigger_mode,
    queue_code,
    worker_group,
    window_code,
    priority,
    enabled,
    created_by,
    updated_by,
    created_at,
    updated_at
)
VALUES (
    :'tenant_id',
    :'job_code',
    'seedval pipeline workflow',
    'WORKFLOW',
    'MANUAL',
    'Asia/Shanghai',
    'SCHEDULED',
    'export_queue',
    'EXPORT',
    'always_open',
    5,
    TRUE,
    :'probe_tag',
    :'probe_tag',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    schedule_type = EXCLUDED.schedule_type,
    timezone = EXCLUDED.timezone,
    trigger_mode = EXCLUDED.trigger_mode,
    queue_code = EXCLUDED.queue_code,
    worker_group = EXCLUDED.worker_group,
    window_code = EXCLUDED.window_code,
    priority = EXCLUDED.priority,
    enabled = TRUE,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;
