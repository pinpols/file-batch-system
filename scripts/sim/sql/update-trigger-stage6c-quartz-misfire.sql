UPDATE quartz.qrtz_triggers
SET next_fire_time = (extract(epoch FROM now() - interval '120 seconds') * 1000)::bigint,
    trigger_state = 'WAITING'
WHERE trigger_group = 'batch-trigger' AND trigger_name = 'ta:TA_TRIGGER_STAGE6C_MISFIRE';
