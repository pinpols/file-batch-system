INSERT INTO :"replay_schema".forensic_day_audits(id, tenant_id, snapshot)
VALUES (NULLIF(:'audit_id', '')::bigint, :'tenant_id', :'snapshot'::jsonb);
