-- Stage 5b 专属故障注入配置退场；按命名所有权定位，保留运行、分发与健康历史。
-- 全新数据库在 Flyway 初始化前运行 SIM cleanup 时尚无 batch.file_channel_config，安全空操作。
DO $sim_cleanup$
BEGIN
    IF to_regclass('batch.file_channel_config') IS NOT NULL THEN
        UPDATE batch.file_channel_config
        SET enabled = false,
            updated_at = current_timestamp
        WHERE tenant_id = 'tb'
          AND channel_code = 'tb_api_fail'
          AND channel_name = 'TB API fail channel [SIM:dispatch-stage5b]'
          AND channel_type = 'API';
    END IF;
END
$sim_cleanup$;
