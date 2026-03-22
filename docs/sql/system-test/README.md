# System Test Seed Pack

This directory contains repeatable seed SQL for system testing.
For the recommended testing split, see [docs/testing/test-strategy.md](/Users/dengchao/Downloads/file-batch-system/docs/testing/test-strategy.md).

## Included

- `platform_seed.sql`
  - Seeds `batch_platform.batch` with queues, quotas, jobs, workflows, pipelines, files, approvals, alerts, retries, outbox, and governance state.
- `platform_edge_cases.sql`
  - Adds boundary coverage for enum states, error tables, outbox delivery logs, retry logs, and file error records.
- `business_seed.sql`
  - Seeds `batch_business.biz` with import and export source data.
- `business_edge_cases.sql`
  - Adds additional settlement batch states and settlement detail terminal states.

## Load

Prefer the helper script:

```bash
scripts/local/load-system-test-data.sh
```

Or run the SQL directly:

```bash
psql -U batch_user -d batch_platform -f docs/sql/system-test/platform_seed.sql
psql -U batch_user -d batch_platform -f docs/sql/system-test/platform_edge_cases.sql
psql -U batch_user -d batch_business -f docs/sql/system-test/business_seed.sql
psql -U batch_user -d batch_business -f docs/sql/system-test/business_edge_cases.sql
```

The base seed files are idempotent for a dedicated test database: they truncate the covered tables first, then insert a known dataset. The edge-case packs add extra coverage on top of the base seeds.
