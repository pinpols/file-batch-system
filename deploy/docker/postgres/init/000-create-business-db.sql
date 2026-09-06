SELECT 'CREATE DATABASE batch_business'
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = 'batch_business'
)\gexec

\connect batch_business

CREATE SCHEMA IF NOT EXISTS biz;
COMMENT ON SCHEMA biz IS 'Business data schema for import and export tables.';
