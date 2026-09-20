-- Serialize release/secret version allocation and enforce approval/current-version invariants.

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, secret_ref
               ORDER BY version_no DESC, id DESC
           ) AS row_no
      FROM batch.secret_version
     WHERE current_version = true
)
UPDATE batch.secret_version AS secret
   SET current_version = false,
       updated_at = CURRENT_TIMESTAMP
  FROM ranked
 WHERE secret.id = ranked.id
   AND ranked.row_no > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_secret_version_current
    ON batch.secret_version (tenant_id, secret_ref)
    WHERE current_version = true;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, release_id
               ORDER BY id DESC
           ) AS row_no
      FROM batch.config_approval
     WHERE approval_status = 'PENDING'
)
UPDATE batch.config_approval AS approval
   SET approval_status = 'EXPIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM ranked
 WHERE approval.id = ranked.id
   AND ranked.row_no > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_config_approval_pending_release
    ON batch.config_approval (tenant_id, release_id)
    WHERE approval_status = 'PENDING';

ALTER TABLE batch.secret_version
    ADD CONSTRAINT ck_secret_version_payload_protected
    CHECK (
        secret_payload IS NULL
        OR (
            jsonb_typeof(secret_payload) = 'object'
            AND secret_payload ->> 'format' = 'BATCHENC_BASE64_V1'
            AND length(secret_payload ->> 'ciphertext') >= 32
        )
    ) NOT VALID;

ALTER TABLE batch.secret_version
    VALIDATE CONSTRAINT ck_secret_version_payload_protected;
