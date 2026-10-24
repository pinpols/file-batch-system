-- DANGER: 删除 SIM 业务库内指定 tenant_id + batch_key 的 OPEN、未绑定 job_task 的
-- process_staging_generation 及其匹配 staging 行；不可恢复源数据，只能重新运行 Stage 4b fixture。
-- 仅由 scripts/sim/13-process-stage4b.sh 在隔离 SIM 环境调用；执行前必须确认本轮场景已完成，
-- tenant_id 和 batch_key 精确指向本次 SIM fixture。禁止用于生产或含真实业务数据的数据库。
DELETE FROM batch.process_staging staging
USING batch.process_staging_generation generation
WHERE staging.tenant_id = generation.tenant_id
  AND staging.staging_generation_id = generation.generation_id
  AND staging.batch_key = generation.batch_key
  AND generation.tenant_id = :'tenant_id'
  AND generation.batch_key = :'batch_key'
  AND generation.job_task_id IS NULL
  AND generation.generation_status = 'OPEN'
  AND staging.payload->>'account_id' = 'A999';

DELETE FROM batch.process_staging_generation
WHERE tenant_id = :'tenant_id'
  AND batch_key = :'batch_key'
  AND job_task_id IS NULL
  AND generation_status = 'OPEN';
