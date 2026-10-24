-- 平台旧模型退役；仅支持使用当前应用版本的全新部署。
-- 历史迁移保持原校验和，最终结构由本增量收敛；不保留旧定义或旧API兼容入口。

-- 仅空库部署：若旧表已有业务记录则拒绝退役，不尝试搬迁或兼容旧数据。
DO $retirement_scope$
BEGIN
    IF EXISTS (SELECT 1 FROM batch.compensation_checkpoint)
       OR EXISTS (SELECT 1 FROM batch.trigger_runtime_state)
       OR EXISTS (SELECT 1 FROM batch.process_staging)
       OR EXISTS (SELECT 1 FROM batch.pipeline_definition)
       OR EXISTS (SELECT 1 FROM batch.pipeline_step_definition)
       OR EXISTS (SELECT 1 FROM archive.atomic_task_config_archive)
       OR EXISTS (SELECT 1 FROM archive.custom_task_type_registry_archive)
       OR EXISTS (SELECT 1 FROM archive.data_quality_rule_archive)
       OR EXISTS (SELECT 1 FROM archive.workflow_definition_version_archive) THEN
        RAISE EXCEPTION 'V347 requires empty retired tables; deploy to a fresh database, no legacy migration is supported'
            USING ERRCODE = '55000';
    END IF;
END;
$retirement_scope$;

-- 当前补跑身份已经由V267固定到Workflow，移除旧时间轮外键与身份列。
ALTER TABLE batch.trigger_misfire_pending
    -- squawk-ignore ban-drop-column
    DROP COLUMN trigger_runtime_state_id;

ALTER TABLE batch.pipeline_instance
    -- squawk-ignore ban-drop-column
    DROP COLUMN pipeline_definition_id;
ALTER TABLE archive.pipeline_instance_archive
    -- squawk-ignore ban-drop-column
    DROP COLUMN pipeline_definition_id;

-- 修订配置围栏触发器函数，避免保留已删除定义表的间接引用。
CREATE OR REPLACE FUNCTION batch.bump_tenant_configuration_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = batch, pg_temp
AS $$
DECLARE
    old_tenant_id TEXT;
    new_tenant_id TEXT;
    old_row JSONB;
    new_row JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_row := to_jsonb(OLD);
        old_tenant_id := old_row ->> 'tenant_id';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_row := to_jsonb(NEW);
        new_tenant_id := new_row ->> 'tenant_id';
    END IF;

    IF old_tenant_id IS NOT NULL THEN
        INSERT INTO batch.tenant_configuration_revision (tenant_id, revision, updated_at)
        VALUES (old_tenant_id, 1, clock_timestamp())
        ON CONFLICT (tenant_id) DO UPDATE
        SET revision = batch.tenant_configuration_revision.revision + 1,
            updated_at = clock_timestamp();
    END IF;

    IF new_tenant_id IS NOT NULL AND new_tenant_id IS DISTINCT FROM old_tenant_id THEN
        INSERT INTO batch.tenant_configuration_revision (tenant_id, revision, updated_at)
        VALUES (new_tenant_id, 1, clock_timestamp())
        ON CONFLICT (tenant_id) DO UPDATE
        SET revision = batch.tenant_configuration_revision.revision + 1,
            updated_at = clock_timestamp();
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

-- 子表先于父表；禁止CASCADE，以免误删仍在使用的运行与审批模型。
-- 步骤来自SystemPipelineCatalog及Job参数，旧租户步骤表已无配置职责。
-- squawk-ignore ban-drop-table
DROP TABLE batch.pipeline_step_definition;
-- 新运行按Job类型选择系统Pipeline，租户编辑/查询API与回退一并退役。
-- squawk-ignore ban-drop-table
DROP TABLE batch.pipeline_definition;
-- 时间轮模型已由Quartz、Workflow occurrence及当前misfire身份替代。
-- squawk-ignore ban-drop-table
DROP TABLE batch.trigger_runtime_state;
-- 预留分步补偿表从未接通执行写入；实际补偿命令状态仍保留。
-- squawk-ignore ban-drop-table
DROP TABLE batch.compensation_checkpoint;
-- 仅平台库：业务库同名表及generation围栏继续保留。
-- squawk-ignore ban-drop-table
DROP TABLE batch.process_staging;

-- 配置与定义历史由现有热表/版本快照承担，不建立无消费者的独立冷镜像。
-- DQ检查是执行证据，data_quality_check_archive保留并随Job运行树原子归档。
-- 原子配置是在线复用配置，现有热表负责编辑/查询，无独立冷存消费者。
-- squawk-ignore ban-drop-table
DROP TABLE archive.atomic_task_config_archive;
-- SDK任务类型在线注册目录由热表负责，运行配置快照承担执行合同。
-- squawk-ignore ban-drop-table
DROP TABLE archive.custom_task_type_registry_archive;
-- 规则是在线定义；检查结果自带规则标识与判定事实，单独镜像不构成规则版本历史。
-- squawk-ignore ban-drop-table
DROP TABLE archive.data_quality_rule_archive;
-- 热表本身已是不可变版本快照历史，不再建立第二份无人搬运/读取的版本镜像。
-- squawk-ignore ban-drop-table
DROP TABLE archive.workflow_definition_version_archive;

-- 冷证据回查按租户和实例定位；空库初始化时创建普通索引。
CREATE INDEX idx_data_quality_check_archive_tenant_instance
    ON archive.data_quality_check_archive (tenant_id, job_instance_id, id);
