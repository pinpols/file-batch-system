-- ADR-028 Sensor WAIT 节点 S3：扩展 workflow_node_run 支持 WAIT 类型 + sensor 探测状态跟踪。
--
-- 改动：
--   1. 放宽 ck_workflow_node_run_type 允许 WAIT/JOB（V5 原约束只有 TASK/GATEWAY/FILE_STEP/START/END）
--   2. 增 4 列：sensor 探测状态（next_probe_at / last_probe_at / error_count / probe_count）
--   3. archive.workflow_node_run_archive 同步对齐（ArchiveSchemaDriftCheck 守护）
--
-- 兼容性：旧行 sensor_* 列保持 NULL；非 WAIT 节点永远不读这些列。

-- 1) 放宽 node_type CHECK 约束（旧约束已无 WAIT/JOB，sensor + ADR-016 job node 同步开放）
ALTER TABLE batch.workflow_node_run
    DROP CONSTRAINT IF EXISTS ck_workflow_node_run_type;
ALTER TABLE batch.workflow_node_run
    ADD CONSTRAINT ck_workflow_node_run_type
    CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END', 'WAIT', 'JOB'));

-- 2) sensor 探测状态列
ALTER TABLE batch.workflow_node_run
    ADD COLUMN IF NOT EXISTS sensor_next_probe_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sensor_last_probe_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sensor_probe_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sensor_error_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_workflow_node_run_sensor_next_probe
    ON batch.workflow_node_run (sensor_next_probe_at)
    WHERE node_type = 'WAIT' AND node_status = 'RUNNING';

COMMENT ON COLUMN batch.workflow_node_run.sensor_next_probe_at IS
    'ADR-028 WAIT 节点下次轮询时间；NULL 表示 RUNNING 后首次探测立刻执行';
COMMENT ON COLUMN batch.workflow_node_run.sensor_last_probe_at IS
    'ADR-028 上次探测时间（成功/失败均更新）';
COMMENT ON COLUMN batch.workflow_node_run.sensor_probe_count IS
    'ADR-028 累计探测次数';
COMMENT ON COLUMN batch.workflow_node_run.sensor_error_count IS
    'ADR-028 连续 ERROR 次数；达 3 次推进 FAILED';

-- 3) archive 镜像同步（ArchiveSchemaDriftCheck 启动期对齐校验）
ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN IF NOT EXISTS sensor_next_probe_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sensor_last_probe_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sensor_probe_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sensor_error_count INTEGER NOT NULL DEFAULT 0;
