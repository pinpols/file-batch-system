-- DANGER: 删除 workflow node probe 测试夹具。仅限隔离本地测试库，并核对 probe ID。
DELETE FROM batch.workflow_node
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id = :'workflow_definition_id'::bigint
  AND node_code = 'SEEDVAL_PROBE';
-- DANGER: 删除 workflow node probe 测试夹具。仅限隔离本地测试库，并核对 probe ID。
