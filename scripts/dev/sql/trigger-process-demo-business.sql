DROP TABLE IF EXISTS biz.process_demo_source;
DROP TABLE IF EXISTS biz.process_demo_target;

CREATE TABLE biz.process_demo_source (
  id BIGSERIAL PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  account_no TEXT NOT NULL,
  amount NUMERIC(18,2) NOT NULL,
  biz_date DATE NOT NULL
);

CREATE TABLE biz.process_demo_target (
  account_no TEXT PRIMARY KEY,
  total_amount NUMERIC(18,2) NOT NULL,
  txn_count BIGINT NOT NULL,
  computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO biz.process_demo_source (tenant_id, account_no, amount, biz_date) VALUES
  ('default-tenant','A001',100.00,'2026-04-28'),
  ('default-tenant','A001',250.50,'2026-04-28'),
  ('default-tenant','A002', 80.00,'2026-04-28'),
  ('default-tenant','A002',120.00,'2026-04-28'),
  ('default-tenant','A003',999.99,'2026-04-28');
