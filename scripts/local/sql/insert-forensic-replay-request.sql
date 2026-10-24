INSERT INTO :"replay_schema".forensic_replay_requests (tenant_id, request_id)
VALUES (:'tenant_id', :'request_id')
ON CONFLICT (tenant_id, request_id) DO NOTHING;
