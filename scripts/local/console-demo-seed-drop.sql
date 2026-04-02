-- ============================================================
-- Console Demo Seed — 清理脚本
-- 删除 console-demo-seed.sql 写入的所有演示数据（按 FK 依赖倒序）
-- 运行态表：按 tenant_id = 'default-tenant' 删除（覆盖旧残留数据）
-- 配置表：  按 id 范围删除（避免误删其他租户配置）
-- 幂等执行：可安全重复执行
-- 用法：psql -f console-demo-seed-drop.sql
-- ============================================================
BEGIN;

-- ── 无 tenant_id 的叶子表（先按关联 id 范围删除）───────────────

-- event_delivery_log → outbox_event (outbox_event_id)
DELETE FROM batch.event_delivery_log
    WHERE outbox_event_id IN (
        SELECT id FROM batch.outbox_event WHERE tenant_id = 'default-tenant'
    );

-- event_outbox_retry → outbox_event (outbox_event_id)
DELETE FROM batch.event_outbox_retry
    WHERE outbox_event_id IN (
        SELECT id FROM batch.outbox_event WHERE tenant_id = 'default-tenant'
    );

-- workflow_node_run → workflow_run (workflow_run_id)
DELETE FROM batch.workflow_node_run
    WHERE workflow_run_id IN (
        SELECT id FROM batch.workflow_run WHERE tenant_id = 'default-tenant'
    );

-- pipeline_step_run → pipeline_instance (pipeline_instance_id)
DELETE FROM batch.pipeline_step_run
    WHERE pipeline_instance_id IN (
        SELECT id FROM batch.pipeline_instance WHERE tenant_id = 'default-tenant'
    );

-- ── 有 tenant_id 的运行态表（倒序：子表先于父表）──────────────

DELETE FROM batch.console_ai_audit_log   WHERE tenant_id = 'default-tenant';
DELETE FROM batch.alert_event            WHERE tenant_id = 'default-tenant';
DELETE FROM batch.batch_day_instance     WHERE tenant_id = 'default-tenant';
DELETE FROM batch.tenant_scheduler_snapshot WHERE tenant_id = 'default-tenant';
DELETE FROM batch.quota_runtime_state    WHERE tenant_id = 'default-tenant';
DELETE FROM batch.file_channel_health    WHERE tenant_id = 'default-tenant';
DELETE FROM batch.outbox_event           WHERE tenant_id = 'default-tenant';
DELETE FROM batch.approval_command       WHERE tenant_id = 'default-tenant';
DELETE FROM batch.compensation_command   WHERE tenant_id = 'default-tenant';
DELETE FROM batch.dead_letter_task       WHERE tenant_id = 'default-tenant';
DELETE FROM batch.retry_schedule         WHERE tenant_id = 'default-tenant';
DELETE FROM batch.job_execution_log      WHERE tenant_id = 'default-tenant';
DELETE FROM batch.file_audit_log         WHERE tenant_id = 'default-tenant';
DELETE FROM batch.file_error_record      WHERE tenant_id = 'default-tenant';
DELETE FROM batch.file_dispatch_record   WHERE tenant_id = 'default-tenant';
DELETE FROM batch.pipeline_instance      WHERE tenant_id = 'default-tenant';
DELETE FROM batch.workflow_run           WHERE tenant_id = 'default-tenant';
DELETE FROM batch.job_step_instance      WHERE tenant_id = 'default-tenant';
DELETE FROM batch.job_task               WHERE tenant_id = 'default-tenant';
DELETE FROM batch.job_partition          WHERE tenant_id = 'default-tenant';
DELETE FROM batch.job_instance           WHERE tenant_id = 'default-tenant';
DELETE FROM batch.trigger_request        WHERE tenant_id = 'default-tenant';
DELETE FROM batch.file_record            WHERE tenant_id = 'default-tenant';

-- ── 静态配置（按 id 范围，无 tenant_id 的配置先删子后删父）──────

-- 密钥 / 变更日志 / 发布
DELETE FROM batch.secret_version         WHERE id BETWEEN 25001 AND 25005;
DELETE FROM batch.config_change_log      WHERE id BETWEEN 26001 AND 26015;
DELETE FROM batch.config_release         WHERE id BETWEEN 24001 AND 24008;

-- 渠道 / 模板配置
DELETE FROM batch.file_channel_config    WHERE id BETWEEN 51001 AND 51007;
DELETE FROM batch.file_template_config   WHERE id BETWEEN 50001 AND 50010;

-- Pipeline 定义（步骤先于定义）
DELETE FROM batch.pipeline_step_definition WHERE id BETWEEN 47001 AND 47024;
DELETE FROM batch.pipeline_definition    WHERE id BETWEEN 46001 AND 46008;

-- 工作流定义（边 → 节点 → 定义）
DELETE FROM batch.workflow_edge          WHERE id BETWEEN 23001 AND 23013;
DELETE FROM batch.workflow_node          WHERE id BETWEEN 22001 AND 22017;
DELETE FROM batch.workflow_definition    WHERE id BETWEEN 21001 AND 21010;

-- Job 定义
DELETE FROM batch.job_definition         WHERE id BETWEEN 20001 AND 20025;

-- Worker 注册表
DELETE FROM batch.worker_registry        WHERE id BETWEEN 10501 AND 10512;

-- 日历（节假日先于日历）
DELETE FROM batch.calendar_holiday       WHERE id BETWEEN 10401 AND 10405;
DELETE FROM batch.business_calendar      WHERE id BETWEEN 10301 AND 10302;

-- 基础参考表
DELETE FROM batch.batch_window           WHERE id BETWEEN 10201 AND 10203;
DELETE FROM batch.tenant_quota_policy    WHERE id = 10101;
DELETE FROM batch.resource_queue         WHERE id BETWEEN 10001 AND 10005;

COMMIT;
