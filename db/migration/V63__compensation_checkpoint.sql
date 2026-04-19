-- =========================================================
-- V63 · compensation_checkpoint 表
--
-- 背景（v3 A-3.1 a）：
-- DefaultCompensationService 的每个 handler 内部可能执行多步操作
-- （如 JOB handler 生成新 job_instance → 回填 result summary → 通知上游）。
-- 任一中间步骤失败时，handler 目前只把状态标 FAILED，已执行部分（如新生成的
-- job_instance）**无撤销机制**，残留 DB 成为"孤立已启动任务"，人工清理困难。
--
-- 本表用于在 handler 执行每一步后写一条 checkpoint，便于：
--   #1  补偿失败时运维能按 runbook 逆向操作（见 docs/runbook/compensation-cleanup.md）
--   #2  后续做 saga 反向链（v3 A-3.1 c）时有数据支撑；表结构不需要再改
--
-- 设计要点：
--   - compensation_id 对应 DefaultCompensationService 调用语境的唯一 ID
--     （与 compensation_command.id 对齐，如果后续改 saga 可沿用）
--   - step_order 单调递增，handler 内按操作顺序写
--   - status {PENDING, COMMITTED, ROLLED_BACK, FAILED}：目前只落
--     PENDING / COMMITTED / FAILED；ROLLED_BACK 保留给 saga
--   - payload_json 存该步的上下文（e.g., 新 job_instance_id）方便逆向时定位
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.compensation_checkpoint (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    compensation_id VARCHAR(128) NOT NULL,
    handler_code    VARCHAR(64)  NOT NULL,  -- JOB / STEP / PARTITION / ...
    step_order      INTEGER      NOT NULL,
    step_name       VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    payload_json    TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_compensation_checkpoint_status
        CHECK (status IN ('PENDING', 'COMMITTED', 'ROLLED_BACK', 'FAILED')),
    CONSTRAINT ck_compensation_checkpoint_step_order CHECK (step_order >= 1)
);

CREATE INDEX IF NOT EXISTS idx_compensation_checkpoint_tenant_comp
    ON batch.compensation_checkpoint (tenant_id, compensation_id, step_order);

CREATE INDEX IF NOT EXISTS idx_compensation_checkpoint_status_created
    ON batch.compensation_checkpoint (status, created_at);

COMMENT ON TABLE batch.compensation_checkpoint IS
    'Compensation 多步操作的执行检查点；失败时运维可按此表逆向清理残留状态。';
COMMENT ON COLUMN batch.compensation_checkpoint.compensation_id IS
    '对应 compensation_command.id 或调用者传入的 correlationId';
COMMENT ON COLUMN batch.compensation_checkpoint.status IS
    'PENDING=进入；COMMITTED=成功；FAILED=本步失败；ROLLED_BACK=后续 saga 反向成功';
COMMENT ON COLUMN batch.compensation_checkpoint.payload_json IS
    '该步的上下文快照（e.g., 新生成的 job_instance_id / partition_ids），逆向定位使用';
