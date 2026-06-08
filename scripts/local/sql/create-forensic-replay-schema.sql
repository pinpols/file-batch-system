DROP SCHEMA IF EXISTS :"replay_schema" CASCADE;
CREATE SCHEMA :"replay_schema";

CREATE TABLE :"replay_schema".forensic_job_instances (
  instance_no  TEXT PRIMARY KEY,
  tenant_id    TEXT NOT NULL,
  job_code     TEXT NOT NULL,
  biz_date     DATE,
  snapshot     JSONB NOT NULL
);

CREATE INDEX ON :"replay_schema".forensic_job_instances (tenant_id, job_code, biz_date);

CREATE TABLE :"replay_schema".forensic_day_audits (
  id        BIGINT,
  tenant_id TEXT,
  snapshot  JSONB NOT NULL
);
