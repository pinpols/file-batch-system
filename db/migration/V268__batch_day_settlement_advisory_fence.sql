-- 为批量日结算提供按租户、日历和业务日隔离的事务级并发栅栏。

CREATE OR REPLACE FUNCTION batch.acquire_batch_day_settlement_fence(
    p_tenant_id VARCHAR,
    p_calendar_code VARCHAR,
    p_biz_date DATE
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
AS $function$
DECLARE
    lock_key BIGINT;
BEGIN
    IF p_tenant_id IS NULL OR p_calendar_code IS NULL OR p_biz_date IS NULL THEN
        RAISE EXCEPTION 'batch-day settlement fence requires tenant, calendar and business date';
    END IF;

    lock_key := hashtextextended(
        jsonb_build_array(
            p_tenant_id,
            p_calendar_code,
            to_char(p_biz_date, 'YYYY-MM-DD')
        )::text,
        26820261014
    );
    PERFORM pg_advisory_xact_lock(lock_key);
    RETURN lock_key;
END;
$function$;

COMMENT ON FUNCTION batch.acquire_batch_day_settlement_fence(VARCHAR, VARCHAR, DATE) IS
    '由批次日结算以及相同租户、日历和业务日期的所有工作项/预期项写入方共享的事务级围栏。';
