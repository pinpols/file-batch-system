-- Platform Pipeline archive rows have no tenant definition row.
ALTER TABLE archive.pipeline_instance_archive
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN pipeline_definition_id DROP NOT NULL;

COMMENT ON COLUMN archive.pipeline_instance_archive.pipeline_definition_id IS
    'Legacy tenant Pipeline definition identity retained for historical runs; system Pipeline runs may omit it.';
