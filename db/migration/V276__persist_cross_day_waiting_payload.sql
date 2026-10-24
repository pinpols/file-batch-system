-- Preserve the exact launch context while a cross-day dependency is waiting.
-- The application stores an encrypted envelope; the database column never contains plaintext.
alter table batch.workflow_node_run
    add column source_payload_protected jsonb;

alter table archive.workflow_node_run_archive
    add column source_payload_protected jsonb;

comment on column batch.workflow_node_run.source_payload_protected is
    'KMS-protected source payload snapshot used to resume cross-day dependency waits';
comment on column archive.workflow_node_run_archive.source_payload_protected is
    'KMS-protected source payload snapshot used to resume cross-day dependency waits';
