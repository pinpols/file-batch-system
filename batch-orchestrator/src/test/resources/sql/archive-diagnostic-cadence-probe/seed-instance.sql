-- 父实例必须已经在相同租户和当前批次中插入；不禁用正常引用约束。
insert into batch.job_instance(
    tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, priority, dedup_key, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, finished_at, dry_run, parent_instance_id
) values (
    :tenantId, :definitionId, 'ARCHIVE_PROBE', :identity, current_date - 3, 'MANUAL',
    'SUCCESS', 5, :identity, 1, 1, 0, :identity, now() - interval '3 days', false, :parentId
) returning id
