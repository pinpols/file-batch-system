DO $$
DECLARE
    rec RECORD;
    seq_name text;
BEGIN
    FOR rec IN
        SELECT c.relname AS tbl
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'batch' AND c.relkind = 'r'
    LOOP
        -- pg_get_serial_sequence 对不存在的列会抛 undefined_column(例如 shedlock 表 PK 是 name 不是 id)。
        -- 先查 information_schema 确认 id 列存在,再去拿 sequence,避免整段 DO inline 被拒。
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'batch' AND table_name = rec.tbl AND column_name = 'id'
        ) THEN
            CONTINUE;
        END IF;
        seq_name := pg_get_serial_sequence('batch.' || rec.tbl, 'id');
        IF seq_name IS NOT NULL THEN
            EXECUTE format(
                'SELECT setval(%L, COALESCE((SELECT MAX(id) FROM batch.%I), 1), true)',
                seq_name, rec.tbl);
        END IF;
    END LOOP;
END $$;
