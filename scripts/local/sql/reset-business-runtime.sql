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
