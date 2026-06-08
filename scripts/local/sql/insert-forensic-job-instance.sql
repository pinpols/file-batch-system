INSERT INTO :"replay_schema".forensic_job_instances(instance_no, tenant_id, job_code, biz_date, snapshot)
VALUES (:'instance_no', :'tenant_id', :'job_code', NULLIF(:'biz_date', '')::date, :'snapshot'::jsonb)
ON CONFLICT (instance_no) DO NOTHING;
