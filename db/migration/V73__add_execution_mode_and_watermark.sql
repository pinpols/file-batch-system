-- =========================================================
-- V73: ExecutionMode 一等公民化
--
-- 来源:docs/design/batch-classification-and-gaps.md §4.1
--
-- 背景:增量批处理目前靠业务在 SQL 模板里手写
-- `where update_time > :last_high_water_mark`,框架层完全无知,
-- 无法统一管理水位、补数窗口、乱序兜底。本次把 ExecutionMode
-- 提升为一等公民,引入持久化的水位字段。
--
-- 字段语义:
--   job_definition.execution_mode    = FULL / INCREMENTAL / CDC,默认 FULL
--   job_definition.watermark_field   = 增量模式下的水位字段名(可空)
--                                      (例:'update_time' / 'id' / 'binlog_offset')
--   job_instance.high_water_mark_in  = 本次执行的水位起点
--                                      (orchestrator 派发时从上次成功实例的
--                                       high_water_mark_out 读出)
--   job_instance.high_water_mark_out = 本次执行结束时回报的新水位
--                                      (worker 完成时通过 task report 上报;
--                                       下次同一 job 的实例从这里继续)
--
-- 兼容性:
--   - 旧 job_definition 默认 execution_mode='FULL',watermark_field IS NULL
--     → 现行 worker 行为不变(没读这两个字段)
--   - 旧 job_instance 没回填 high_water_mark_*,值为 NULL
--     → 第一次切到 INCREMENTAL 时 IN=NULL 表示"从头",由 worker 解释
--   - VARCHAR(64) 足以容纳常见水位:ISO-8601 timestamp / bigint / binlog
--     position(`mysql-bin.000123:456`) / kafka offset 等
--
-- worker 与 console-api 的运行时打通(TaskDispatchMessage 携带 in 值、
-- worker 上报 out 值)在后续 commit 完成,本次只落数据模型。
-- =========================================================

ALTER TABLE batch.job_definition
  ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16) NOT NULL DEFAULT 'FULL',
  ADD COLUMN IF NOT EXISTS watermark_field VARCHAR(64);

COMMENT ON COLUMN batch.job_definition.execution_mode IS 'Execution mode: FULL / INCREMENTAL / CDC';
COMMENT ON COLUMN batch.job_definition.watermark_field IS 'Watermark column name for INCREMENTAL mode (e.g. update_time, id)';

ALTER TABLE batch.job_instance
  ADD COLUMN IF NOT EXISTS high_water_mark_in VARCHAR(64),
  ADD COLUMN IF NOT EXISTS high_water_mark_out VARCHAR(64);

COMMENT ON COLUMN batch.job_instance.high_water_mark_in IS 'Watermark range start carried into this run';
COMMENT ON COLUMN batch.job_instance.high_water_mark_out IS 'Watermark high-bound reported by worker on completion';

-- 同步归档表(ArchiveSchemaDriftCheck 在启动时校验 batch.* 与 archive.*_archive 列对齐;V71 落地)
ALTER TABLE archive.job_instance_archive
  ADD COLUMN IF NOT EXISTS high_water_mark_in VARCHAR(64),
  ADD COLUMN IF NOT EXISTS high_water_mark_out VARCHAR(64);

-- 加 CHECK 约束限定枚举值
ALTER TABLE batch.job_definition
  DROP CONSTRAINT IF EXISTS chk_job_definition_execution_mode;
ALTER TABLE batch.job_definition
  ADD CONSTRAINT chk_job_definition_execution_mode
    CHECK (execution_mode IN ('FULL', 'INCREMENTAL', 'CDC'));
