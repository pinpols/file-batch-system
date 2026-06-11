-- V179: 8 条"父id域内唯一"型 UNIQUE 补 tenant_id 前缀(Citus distributed 最后漏网)。
-- 背景:W1-W6 复合化只动了 PK,这批"UNIQUE(parent_id, seq)"型索引不含 tenant_id,
-- create_distributed_table 实测拒绝("cannot create constraint",Citus 要求所有
-- UNIQUE 含分布列)。加 tenant_id 前缀语义零弱化(parent_id 已隐含租户)。
-- 名称沿用原名(约束名是隐性契约纪律)。

-- job_partition(constraint 形态)
ALTER TABLE batch.job_partition DROP CONSTRAINT uk_job_partition_instance_no;
ALTER TABLE batch.job_partition ADD CONSTRAINT uk_job_partition_instance_no
    UNIQUE (tenant_id, job_instance_id, partition_no);

-- job_task(两条 partial unique index 形态,constraint 不支持 WHERE,保持索引形态)
DROP INDEX batch.uk_job_task_no_partition_seq;
CREATE UNIQUE INDEX uk_job_task_no_partition_seq ON batch.job_task
    USING btree (tenant_id, job_instance_id, task_seq) WHERE (job_partition_id IS NULL);
DROP INDEX batch.uk_job_task_partition_seq;
CREATE UNIQUE INDEX uk_job_task_partition_seq ON batch.job_task
    USING btree (tenant_id, job_partition_id, task_seq) WHERE (job_partition_id IS NOT NULL);

-- pipeline_instance(partial unique index 形态)
DROP INDEX batch.uk_pipeline_instance_job_instance;
CREATE UNIQUE INDEX uk_pipeline_instance_job_instance ON batch.pipeline_instance
    USING btree (tenant_id, related_job_instance_id) WHERE (related_job_instance_id IS NOT NULL);

-- pipeline_step_run(constraint 形态;tenant_id 列 V176 已补)
ALTER TABLE batch.pipeline_step_run DROP CONSTRAINT uk_pipeline_step_run;
ALTER TABLE batch.pipeline_step_run ADD CONSTRAINT uk_pipeline_step_run
    UNIQUE (tenant_id, pipeline_instance_id, step_code, run_seq);

-- trigger_misfire_pending(constraint 形态)
ALTER TABLE batch.trigger_misfire_pending DROP CONSTRAINT uk_trigger_misfire_pending;
ALTER TABLE batch.trigger_misfire_pending ADD CONSTRAINT uk_trigger_misfire_pending
    UNIQUE (tenant_id, trigger_runtime_state_id, scheduled_fire_time);

-- trigger_runtime_state(constraint 形态;"每 job_definition 一条运行态"语义收紧为
-- "每租户每 job_definition 一条"——本就是业务事实,job_definition 是租户内对象)
ALTER TABLE batch.trigger_runtime_state DROP CONSTRAINT uk_trigger_runtime_state_job_def;
ALTER TABLE batch.trigger_runtime_state ADD CONSTRAINT uk_trigger_runtime_state_job_def
    UNIQUE (tenant_id, job_definition_id);

-- workflow_node_run(constraint 形态;tenant_id 列 V177 已补)
ALTER TABLE batch.workflow_node_run DROP CONSTRAINT uk_workflow_node_run;
ALTER TABLE batch.workflow_node_run ADD CONSTRAINT uk_workflow_node_run
    UNIQUE (tenant_id, workflow_run_id, node_code, run_seq);
