ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS current_load INTEGER NOT NULL DEFAULT 0;
