#!/usr/bin/env bash
# =========================================================
# 11-import-stage2b.sh:Import LOAD/幂等/分片保护业务分支验证
#
# 覆盖:
#   - BATCH_UPSERT 重跑幂等:同 tenant_id + customer_no 不重复,字段更新
#   - LOAD 目标表配置错误:系统级失败态
#   - PARTITION_REPLACE_COPY + partitionCount>1:fail-fast,防半量写入
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-import。
# 不重启服务;临时验证模板/job/pipeline 均幂等 upsert 到 DB。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
if [[ -z "${BATCH_INTERNAL_SECRET:-}" && -f .env.local ]]; then
  BATCH_INTERNAL_SECRET="$(grep -E '^BATCH_INTERNAL_SECRET=' .env.local | tail -1 | cut -d= -f2- || true)"
fi
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-import-stage2b-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-import-stage2b-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap + stage2b fixtures"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null

docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin >/dev/null <<'SQL'
WITH src AS (
  SELECT *
  FROM batch.file_template_config
  WHERE tenant_id = 'ta'
    AND template_code = 'TA_IMPORT_CUSTOMER_XML_TPL'
    AND is_deleted = false
  ORDER BY version DESC
  LIMIT 1
), templates AS (
  SELECT
    'TA_IMPORT_CUSTOMER_XML_LOAD_BAD_TPL' AS template_code,
    '客户 XML 导入 LOAD 失败模板' AS template_name,
    'CUSTOMER_XML_LOAD_BAD' AS biz_type,
    jsonb_set(src.query_param_schema, '{jdbcMappedImport,table}', '"missing_customer_account"', true)
      AS query_param_schema,
    'Stage 2b import LOAD target failure scenario' AS description
  FROM src
  UNION ALL
  SELECT
    'TA_IMPORT_CUSTOMER_XML_PARTITION_COPY_TPL' AS template_code,
    '客户 XML 导入分区 COPY 模板' AS template_name,
    'CUSTOMER_XML_PARTITION_COPY' AS biz_type,
    jsonb_set(
      jsonb_set(src.query_param_schema, '{jdbcMappedImport,loadStrategy}', '"PARTITION_REPLACE_COPY"', true),
      '{jdbcMappedImport,replacePartitionColumns}',
      '["tenant_id","source_batch_no"]'::jsonb,
      true
    ) AS query_param_schema,
    'Stage 2b import partition replace copy guard scenario' AS description
  FROM src
)
INSERT INTO batch.file_template_config (
    tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset, with_bom, line_separator,
    delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
    header_template, trailer_template, checksum_type, compress_type, encrypt_type,
    naming_rule, field_mappings, validation_rule_set, query_param_schema,
    streaming_enabled, page_size, fetch_size, chunk_size, enabled, version,
    description, created_by, updated_by, preprocess_pipeline,
    preview_masking_enabled, error_line_masking_enabled, log_masking_enabled,
    content_encryption_enabled, download_requires_approval, load_target_ref, is_deleted
)
SELECT
    src.tenant_id, templates.template_code, templates.template_name, src.template_type, templates.biz_type,
    src.file_format_type, src.charset, src.target_charset, src.with_bom, src.line_separator,
    src.delimiter, src.quote_char, src.escape_char, src.record_length, src.header_rows, src.footer_rows,
    src.header_template, src.trailer_template, src.checksum_type, src.compress_type, src.encrypt_type,
    replace(src.naming_rule, 'customer_xml', lower(templates.biz_type)), src.field_mappings,
    src.validation_rule_set, templates.query_param_schema,
    src.streaming_enabled, src.page_size, src.fetch_size, src.chunk_size, true, 1,
    templates.description, 'sim-e2e', 'sim-e2e', src.preprocess_pipeline,
    src.preview_masking_enabled, src.error_line_masking_enabled, src.log_masking_enabled,
    src.content_encryption_enabled, src.download_requires_approval, src.load_target_ref, false
FROM src
JOIN templates ON true
ON CONFLICT (tenant_id, template_code, version) DO UPDATE
SET template_name = EXCLUDED.template_name,
    biz_type = EXCLUDED.biz_type,
    naming_rule = EXCLUDED.naming_rule,
    query_param_schema = EXCLUDED.query_param_schema,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP,
    is_deleted = false;

WITH src AS (
  SELECT *
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_IMPORT_CUSTOMER_XML'
), jobs AS (
  SELECT
    'TA_IMPORT_CUSTOMER_XML_LOAD_BAD' AS job_code,
    '客户 XML 导入 LOAD 失败' AS job_name,
    'CUSTOMER_XML_LOAD_BAD' AS biz_type,
    'TA_IMPORT_CUSTOMER_XML_LOAD_BAD_TPL' AS template_code,
    src.shard_strategy AS shard_strategy,
    'Stage 2b import LOAD target failure scenario' AS description
  FROM src
  UNION ALL
  SELECT
    'TA_IMPORT_CUSTOMER_XML_PARTITION_COPY' AS job_code,
    '客户 XML 导入分区 COPY' AS job_name,
    'CUSTOMER_XML_PARTITION_COPY' AS biz_type,
    'TA_IMPORT_CUSTOMER_XML_PARTITION_COPY_TPL' AS template_code,
    'STATIC' AS shard_strategy,
    'Stage 2b import partition replace copy guard scenario' AS description
  FROM src
)
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT src.tenant_id, jobs.job_code, jobs.job_name, src.job_type, jobs.biz_type,
       'MANUAL', null, src.timezone, src.priority, src.queue_code, src.worker_group,
       src.calendar_code, src.window_code, 'API', false, jobs.shard_strategy,
       'NONE', 0, src.timeout_seconds, src.execution_handler,
       src.param_schema, jsonb_build_object('templateCode', jobs.template_code),
       1, true, jobs.description, 'sim-e2e', 'sim-e2e', 'FULL',
       coalesce(src.previous_day_dependency_scope, 'INHERIT'), src.retry_policy_by_class
FROM src
JOIN jobs ON true
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    trigger_mode = EXCLUDED.trigger_mode,
    shard_strategy = EXCLUDED.shard_strategy,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    default_params = EXCLUDED.default_params,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP,
    execution_mode = 'FULL';

WITH src AS (
  SELECT *
  FROM batch.pipeline_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_IMPORT_CUSTOMER_XML'
    AND version = 1
), pipes AS (
  SELECT 'TA_IMPORT_CUSTOMER_XML_LOAD_BAD' AS job_code,
         '客户 XML 导入 LOAD 失败流水线' AS pipeline_name,
         'CUSTOMER_XML_LOAD_BAD' AS biz_type,
         'Stage 2b import LOAD target failure scenario' AS description
  UNION ALL
  SELECT 'TA_IMPORT_CUSTOMER_XML_PARTITION_COPY',
         '客户 XML 导入分区 COPY 流水线',
         'CUSTOMER_XML_PARTITION_COPY',
         'Stage 2b import partition replace copy guard scenario'
)
INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description
)
SELECT src.tenant_id, pipes.job_code, pipes.pipeline_name, src.pipeline_type, pipes.biz_type,
       src.worker_group, 1, true, pipes.description
FROM src
JOIN pipes ON true
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH source_steps AS (
  SELECT psd.*
  FROM batch.pipeline_definition src_pd
  JOIN batch.pipeline_step_definition psd ON psd.pipeline_definition_id = src_pd.id
  WHERE src_pd.tenant_id = 'ta'
    AND src_pd.job_code = 'TA_IMPORT_CUSTOMER_XML'
    AND src_pd.version = 1
), target_pipelines AS (
  SELECT pd.id AS pipeline_definition_id
  FROM batch.pipeline_definition pd
  WHERE pd.tenant_id = 'ta'
    AND pd.job_code IN ('TA_IMPORT_CUSTOMER_XML_LOAD_BAD', 'TA_IMPORT_CUSTOMER_XML_PARTITION_COPY')
    AND pd.version = 1
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT tp.pipeline_definition_id, ss.step_code, ss.step_name, ss.stage_code, ss.step_order,
       ss.impl_code, coalesce(ss.step_params, '{}'::jsonb), ss.timeout_seconds,
       ss.retry_policy, ss.retry_max_count, ss.enabled
FROM target_pipelines tp
CROSS JOIN source_steps ss
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE
SET step_name = EXCLUDED.step_name,
    stage_code = EXCLUDED.stage_code,
    step_order = EXCLUDED.step_order,
    impl_code = EXCLUDED.impl_code,
    step_params = EXCLUDED.step_params,
    timeout_seconds = EXCLUDED.timeout_seconds,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
SQL

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/import-stage2b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

CUSTOMER = "S2BUPS000001"
request_ids = []

def xml_payload(customer_no, name, status="ACTIVE"):
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<customers>
  <customer>
    <customer_no>{customer_no}</customer_no>
    <customer_name>{name}</customer_name>
    <customer_type>PERSONAL</customer_type>
    <certificate_no>S2B{customer_no}</certificate_no>
    <mobile_no>13900009001</mobile_no>
    <email>s2b@x.io</email>
    <status>{status}</status>
  </customer>
</customers>
"""

def launch(label, job, params):
    rid = f"sim-stage2b-{label}-{int(time.time()*1000)%100000000}"
    request_ids.append(rid)
    body = {
        "tenantId": "ta",
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": params,
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": "ta",
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {label:18s} {job:38s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {label}")
    return rid

def psql(db, sql, tuples=False):
    args = [
        "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
        "-d", db, "-P", "pager=off"
    ]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def wait_for(job, rid, expected):
    deadline = time.time() + 150
    while time.time() < deadline:
        sql = (
            "select coalesce(i.instance_status,'') "
            "from batch.trigger_request tr "
            "left join batch.job_instance i on i.id = tr.related_job_instance_id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' and tr.job_code='{job}' "
            "order by tr.created_at desc limit 1"
        )
        out = psql("batch_platform", sql, tuples=True)
        status = (out.stdout or "").strip()
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            marker = "✓" if status == expected else "✗"
            print(f"  [result] {job:38s} {status:14s} expected={expected} {marker}", flush=True)
            return status
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {job}/{rid}")

print("==> launch upsert first", flush=True)
rid_first = launch("upsert_first", "TA_IMPORT_CUSTOMER_XML", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
    "fileFormatType": "XML",
    "content": xml_payload(CUSTOMER, "Stage2b Original"),
    "batchNo": BATCH + "-upsert-1",
})
wait_for("TA_IMPORT_CUSTOMER_XML", rid_first, "SUCCESS")

print("==> launch upsert second", flush=True)
rid_second = launch("upsert_second", "TA_IMPORT_CUSTOMER_XML", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_TPL",
    "fileFormatType": "XML",
    "content": xml_payload(CUSTOMER, "Stage2b Updated"),
    "batchNo": BATCH + "-upsert-2",
})
wait_for("TA_IMPORT_CUSTOMER_XML", rid_second, "SUCCESS")

print("==> launch LOAD bad target", flush=True)
rid_load_bad = launch("load_bad", "TA_IMPORT_CUSTOMER_XML_LOAD_BAD", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_LOAD_BAD_TPL",
    "fileFormatType": "XML",
    "content": xml_payload("S2BLOADBAD01", "Stage2b Load Bad"),
    "batchNo": BATCH + "-load-bad",
})
wait_for("TA_IMPORT_CUSTOMER_XML_LOAD_BAD", rid_load_bad, "FAILED")

print("==> launch partition replace copy guard", flush=True)
rid_partition = launch("partition_guard", "TA_IMPORT_CUSTOMER_XML_PARTITION_COPY", {
    "templateCode": "TA_IMPORT_CUSTOMER_XML_PARTITION_COPY_TPL",
    "fileFormatType": "XML",
    "content": xml_payload("S2BPARTGUARD", "Stage2b Partition Guard"),
    "batchNo": BATCH + "-partition-guard",
    "partitionCount": 2,
})
wait_for("TA_IMPORT_CUSTOMER_XML_PARTITION_COPY", rid_partition, "FAILED")

print("\n-- job_status --", flush=True)
request_list = ",".join("'" + rid + "'" for rid in request_ids)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select i.id,i.job_code,i.instance_status,i.expected_partition_count,"
    "t.task_status,t.error_code,left(coalesce(t.error_message,''),180) as error_message "
    "from batch.trigger_request tr "
    "join batch.job_instance i on i.id = tr.related_job_instance_id "
    "left join batch.job_task t on t.job_instance_id = i.id "
    f"where tr.request_id in ({request_list}) "
    "order by i.created_at,i.id,t.id"
], check=False)

print("\n-- upsert_business_row --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    f"select tenant_id, customer_no, count(*) as rows, max(customer_name) as customer_name, "
    f"max(source_batch_no) as source_batch_no from biz.customer_account "
    f"where tenant_id='ta' and customer_no='{CUSTOMER}' "
    f"group by tenant_id, customer_no"
], check=False)

check = psql("batch_business", (
    f"select count(*) || '|' || coalesce(max(customer_name),'') "
    f"from biz.customer_account where tenant_id='ta' and customer_no='{CUSTOMER}'"
), tuples=True)
value = (check.stdout or "").strip()
if value != "1|Stage2b Updated":
    print(f"❌ UPSERT business assertion failed: {value}", flush=True)
    sys.exit(1)

print(f"\n==> Stage 2b import scenario PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
