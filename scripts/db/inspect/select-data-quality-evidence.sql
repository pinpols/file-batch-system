-- 用途：按精确租户和Job实例回查冷热DQ执行证据，不执行恢复写入或删除。
-- 用法：psql ... -v tenant_id=ta -v job_instance_id=123 -f scripts/db/inspect/select-data-quality-evidence.sql
SELECT id, tenant_id, job_instance_id, rule_id, rule_code, rule_type, severity, status, metrics_json, failure_sample, error_message, checked_at, created_at, updated_at
FROM (
    SELECT id, tenant_id, job_instance_id, rule_id, rule_code, rule_type, severity, status, metrics_json, failure_sample, error_message, checked_at, created_at, updated_at FROM batch.data_quality_check
    WHERE tenant_id = :'tenant_id' AND job_instance_id = :'job_instance_id'::bigint
    UNION ALL
    SELECT cold.id, cold.tenant_id, cold.job_instance_id, cold.rule_id, cold.rule_code, cold.rule_type, cold.severity, cold.status, cold.metrics_json, cold.failure_sample, cold.error_message, cold.checked_at, cold.created_at, cold.updated_at FROM archive.data_quality_check_archive cold
    WHERE cold.tenant_id = :'tenant_id' AND cold.job_instance_id = :'job_instance_id'::bigint
      AND NOT EXISTS (SELECT 1 FROM batch.data_quality_check hot WHERE hot.id = cold.id)
) evidence
ORDER BY id;
