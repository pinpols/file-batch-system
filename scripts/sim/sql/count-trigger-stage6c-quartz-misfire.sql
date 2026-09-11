SELECT count(*) FROM quartz.qrtz_triggers
WHERE trigger_group = 'batch-trigger' AND trigger_name = 'ta:TA_TRIGGER_STAGE6C_MISFIRE';
