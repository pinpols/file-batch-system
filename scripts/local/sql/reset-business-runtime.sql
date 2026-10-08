DO $$
BEGIN
    IF current_setting('batch.destructive_ops', true) IS DISTINCT FROM 'sim-reset' THEN
        RAISE EXCEPTION '拒绝重置业务运行态:需通过本地 sim reset 入口显式授权';
    END IF;
END
$$;

DO $reset$
DECLARE
    relation record;
BEGIN
    FOR relation IN
        SELECT cls.relname
        FROM pg_class cls
        JOIN pg_namespace namespace ON namespace.oid = cls.relnamespace
        WHERE namespace.nspname = 'biz'
          AND cls.relkind = 'p'
    LOOP
        EXECUTE format('TRUNCATE TABLE biz.%I CASCADE', relation.relname);
    END LOOP;

    FOR relation IN
        SELECT cls.relname
        FROM pg_class cls
        JOIN pg_namespace namespace ON namespace.oid = cls.relnamespace
        WHERE namespace.nspname = 'biz'
          AND cls.relkind = 'r'
          AND cls.relispartition = FALSE
    LOOP
        EXECUTE format('TRUNCATE TABLE biz.%I CASCADE', relation.relname);
    END LOOP;
END
$reset$;
