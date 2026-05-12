ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS description VARCHAR(512);

COMMENT ON COLUMN batch.business_calendar.description IS
    'Business calendar description used by Console Excel export/import and config package import.';
