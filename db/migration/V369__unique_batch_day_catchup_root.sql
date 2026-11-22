-- 仅约束批日补跑根请求；子 Job 可继承同一去重键。
-- 空库基线：不对既有重复请求执行自动裁决。
CREATE UNIQUE INDEX uk_trigger_request_batch_day_catchup_root
    ON batch.trigger_request (tenant_id, dedup_key)
    WHERE trigger_type = 'CATCH_UP'
      AND parent_workflow_run_id IS NULL
      AND left(dedup_key, length(tenant_id) + length(':batch-day-catchup:')) =
          tenant_id || ':batch-day-catchup:';

COMMENT ON INDEX batch.uk_trigger_request_batch_day_catchup_root IS
    '同租户的批日补跑根请求按冻结业务键唯一；子请求不受此约束。';
