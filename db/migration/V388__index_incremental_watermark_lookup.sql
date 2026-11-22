-- 增量水位启动查询（JobInstanceMapper.selectLatestEffectiveHighWaterMarkOut）:
-- 同一 (tenant_id, job_definition_id) 下取最近一次完整成功且报告了非空 OUT 的实例。
-- 成功实例默认 30 天后由归档服务移入 archive.job_instance_archive 并从热表删除，
-- 因此热冷两支各建一条同样形状的部分索引，保证查询量级与归档无关。
-- job_instance 是分区表，沿用既有普通索引写法（同 V384）。

CREATE INDEX IF NOT EXISTS idx_job_instance_effective_watermark
    ON batch.job_instance (tenant_id, job_definition_id, finished_at DESC NULLS LAST, id DESC)
    WHERE instance_status = 'SUCCESS'
      AND replay_session_id IS NULL
      AND rerun_flag = false
      AND high_water_mark_out IS NOT NULL
      AND high_water_mark_out <> '';

CREATE INDEX IF NOT EXISTS idx_job_instance_archive_effective_watermark
    ON archive.job_instance_archive (tenant_id, job_definition_id, finished_at DESC NULLS LAST, id DESC)
    WHERE instance_status = 'SUCCESS'
      AND replay_session_id IS NULL
      AND rerun_flag = false
      AND high_water_mark_out IS NOT NULL
      AND high_water_mark_out <> '';
