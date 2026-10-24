-- A reclaimed DISPATCH invocation can repeat the completion stage after the receiver has
-- idempotently accepted the same request. Keep one completion audit per logical request,
-- file, and channel while retaining audits for later requests and other channels.
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_audit_dispatch_completion_request
    ON batch.file_audit_log (
        tenant_id,
        file_id,
        evidence_ref,
        (detail_summary ->> 'channelCode')
    )
    WHERE operation_type = 'DISPATCH_COMPLETE'
      AND evidence_ref IS NOT NULL
      AND detail_summary ->> 'channelCode' IS NOT NULL;
