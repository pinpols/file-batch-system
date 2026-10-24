-- 每次回放使用独占 namespace；若意外重名则失败，绝不覆盖先前保留的现场。
CREATE SCHEMA :"replay_schema";

CREATE TABLE :"replay_schema".forensic_job_instances (
  instance_no  TEXT PRIMARY KEY,
  tenant_id    TEXT NOT NULL,
  job_code     TEXT NOT NULL,
  biz_date     DATE,
  snapshot     JSONB NOT NULL
);

CREATE INDEX ON :"replay_schema".forensic_job_instances (tenant_id, job_code, biz_date);

CREATE TABLE :"replay_schema".forensic_replay_requests (
  tenant_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  PRIMARY KEY (tenant_id, request_id)
);

CREATE TABLE :"replay_schema".forensic_day_audits (
  id        BIGINT,
  tenant_id TEXT,
  snapshot  JSONB NOT NULL
);
