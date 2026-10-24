DO $$
DECLARE
    tenant text;
BEGIN
    FOREACH tenant IN ARRAY ARRAY['ta', 'tb', 'tc'] LOOP
        IF NOT EXISTS (SELECT 1 FROM batch.job_definition WHERE tenant_id = tenant) THEN
            RAISE EXCEPTION 'SIM tenant fixture missing job definitions for %', tenant;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM batch.workflow_definition WHERE tenant_id = tenant) THEN
            RAISE EXCEPTION 'SIM tenant fixture missing workflow definitions for %', tenant;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM batch.file_channel_config WHERE tenant_id = tenant) THEN
            RAISE EXCEPTION 'SIM tenant fixture missing file channels for %', tenant;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM batch.file_template_config WHERE tenant_id = tenant) THEN
            RAISE EXCEPTION 'SIM tenant fixture missing file templates for %', tenant;
        END IF;
    END LOOP;
END
$$;
