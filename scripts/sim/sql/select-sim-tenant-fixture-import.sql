SELECT fixture_sha256
FROM batch.sim_tenant_fixture_import_state
WHERE tenant_id = :'tenant_id';
