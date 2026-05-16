-- ============================================================================
-- V128: job_execution_log.job_partition_id FK 改 SET NULL（保审计行）
-- ----------------------------------------------------------------------------
-- R7 DB 审计 P1-4：V119 把 job_execution_log.job_partition_id FK 改 ON DELETE
-- CASCADE，原意是兜底 cleanup 序列遗漏不卡调度。但 CASCADE 把整条 audit log
-- 跟着 partition 一起物理删除——job_execution_log 是排障 / 合规追溯的最后凭据
-- （含 COMPENSATION_REJECTED / lease 抢占冲突 / DLQ 投递记录等），按域定位它该
-- 比 partition 长寿。
--
-- 处置：FK 改 ON DELETE SET NULL：
--   - partition 删除时 audit 行留存，仅 job_partition_id 解除引用
--   - 通过 job_instance_id（同表已有 FK，没在 V119 改 CASCADE）仍可关联到主体
--   - cleanup 序列原本就该先删 log 再删 partition（DefaultArchiveService 已实施），
--     CASCADE 是冗余防御；改 SET NULL 后冗余防御变成"保审计"防御
--
-- 不动 job_step_instance.job_partition_id：step 实例与 partition 是父子运行时
-- 关系，partition 删除时 step 跟着删除是正确语义（不是审计数据）。
--
-- 风险：无。partition_id 列原本就是 nullable（V7 创建时无 NOT NULL）。
-- ============================================================================

ALTER TABLE batch.job_execution_log
    DROP CONSTRAINT IF EXISTS job_execution_log_job_partition_id_fkey,
    ADD CONSTRAINT job_execution_log_job_partition_id_fkey
        FOREIGN KEY (job_partition_id) REFERENCES batch.job_partition(id) ON DELETE SET NULL;
