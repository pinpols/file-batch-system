#!/usr/bin/env python3
# 用途：以可重复方式初始化、执行并清理 ta/tb/tc 全类型覆盖场景。
# 用法：python3 scripts/local/coverage-harness.py all --operator admin --allow-local-bypass
"""本地覆盖验收 harness；默认前后只清理 *_COV_* 运行产物，保留定义与验收证据。"""
import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from openpyxl import load_workbook

ROOT = Path(__file__).resolve().parents[2]
COVERAGE_CONFIG_SPEC = importlib.util.spec_from_file_location(
    'coverage_config', ROOT / 'scripts/lib/coverage-config.py')
coverage_config = importlib.util.module_from_spec(COVERAGE_CONFIG_SPEC)
COVERAGE_CONFIG_SPEC.loader.exec_module(coverage_config)
sys.modules['coverage_config'] = coverage_config
from coverage_config import (
    FIELD_MAPPINGS,
    apply_runtime_contract,
    compact,
    export_query_param_schema,
    export_query_sql,
    import_query_param_schema,
)

TENANTS = ('ta', 'tb', 'tc')
TERMINAL = {'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED'}
PACKAGE_DIR = ROOT / 'test-data/tenant-config-packages/coverage-examples'
CLEANUP_SQL = ROOT / 'scripts/local/sql/cleanup-coverage-runtime.sql'
CLOSED_DAY_SQL = ROOT / 'scripts/local/sql/prepare-coverage-closed-day.sql'
MINIO_MC = ROOT / 'scripts/lib/minio-mc.sh'
IMPORT_CONTENT = (
    'customerNo,customerName,customerType,certificateNo,mobileNo,email,status\n'
    'COV-001,Coverage Customer,PERSONAL,CERT-001,13900000000,cov@example.test,ACTIVE\n'
)
INIT_SCRIPT = ROOT / 'scripts/data/init-coverage-tenants.py'
SPEC = importlib.util.spec_from_file_location('coverage_init', INIT_SCRIPT)
INIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INIT)


def log(message):
    print(f'[coverage] {message}', flush=True)


def load_api(args):
    origin = INIT.local_origin(args.base_url)
    api = INIT.Api(origin)
    password = '' if args.allow_local_bypass else args.operator_password or __import__('getpass').getpass('平台管理员密码：')
    api.login(args.operator, password, args.allow_local_bypass)
    return api


def request_json(api, method, path, tenant, payload=None, key=None):
    headers = INIT.Api.csrf_headers(api, tenant, key or f'coverage-{uuid.uuid4().hex}')
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps(payload, ensure_ascii=False).encode()
    return api.request(method, path, payload, headers)


def query_items(api, tenant, path):
    data = api.request('GET', path)
    return data if isinstance(data, list) else data.get('items', [])


def ensure_upstreams():
    subprocess.run(
        ['docker', 'compose', '-f', 'docker-compose.yml', '-f', 'deploy/docker/compose/test.yml',
         '--profile', 'test', 'up', '-d', 'sftp', 'mockserver'],
        cwd=ROOT,
        check=True,
    )
    for name in ('batch-sftp', 'batch-mockserver'):
        for _ in range(45):
            status = subprocess.run(
                ['docker', 'inspect', '--format', '{{.State.Health.Status}}', name],
                cwd=ROOT,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            if status == 'healthy':
                break
            time.sleep(1)
        else:
            raise INIT.Stop(f'{name} 未进入 healthy')


def publish_known_hosts():
    target = ROOT / 'build/runtime-config/sftp-known_hosts'
    target.parent.mkdir(parents=True, exist_ok=True)
    keys = subprocess.run(
        ['docker', 'exec', 'batch-sftp', 'sh', '-c', 'cat /etc/ssh/ssh_host_*_key.pub'],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    lines = [line for line in keys.splitlines() if line.startswith(('ssh-ed25519 ', 'ssh-rsa ', 'ecdsa-sha2-'))]
    if not lines:
        raise INIT.Stop('SFTP 容器未提供有效主机公钥')
    target.write_text(''.join(f'[127.0.0.1]:12222 {line}\n' for line in lines), encoding='utf-8')
    return target


def preflight(args):
    for tenant in TENANTS:
        if not (PACKAGE_DIR / f'{tenant}-coverage-example.xlsx').is_file():
            raise INIT.Stop(f'缺少覆盖包：{tenant}')
    api = load_api(args)
    for tenant in TENANTS:
        workers = query_items(api, tenant, f'/api/console/queries/workers?tenantId=default-tenant&pageNo=1&pageSize=100')
        if not any(worker.get('status') == 'ONLINE' for worker in workers):
            raise INIT.Stop('default-tenant 没有 ONLINE Worker')
    ensure_upstreams()
    log('preflight PASS：覆盖包、Worker 与上下游容器可用')


def init_tenants(args):
    api = load_api(args)
    existing = [tenant for tenant in TENANTS if api.exists(tenant)]
    if existing and len(existing) != len(TENANTS):
        raise INIT.Stop(f'仅部分租户存在，拒绝部分初始化：{existing}')
    if not existing:
        receipt = Path(args.receipt).expanduser().absolute()
        if receipt.is_relative_to(ROOT):
            raise INIT.Stop('回执必须位于仓库外')
        credentials = Path(args.credentials_file).expanduser().absolute() if args.credentials_file else None
        if credentials is None:
            raise INIT.Stop('首次创建租户必须指定仓库外 --credentials-file')
        INIT.run(api, {tenant: PACKAGE_DIR / f'{tenant}-coverage-example.xlsx' for tenant in TENANTS},
                 receipt, args.base_url.rstrip('/'), args.operator, '-admin', {}, False)
        passwords = INIT.credentials(credentials, '-admin', receipt.exists())
        INIT.run(api, {tenant: PACKAGE_DIR / f'{tenant}-coverage-example.xlsx' for tenant in TENANTS},
                 receipt, args.base_url.rstrip('/'), args.operator, '-admin', passwords, True)
        log('ta/tb/tc 已创建并导入')
    else:
        log('ta/tb/tc 已存在，复用而不重建')


def configure(args):
    api = load_api(args)
    known_hosts = publish_known_hosts()
    for tenant in TENANTS:
        source = PACKAGE_DIR / f'{tenant}-coverage-example.xlsx'
        target = ROOT / 'target/coverage-harness' / f'{tenant}-coverage-ready.xlsx'
        target.parent.mkdir(parents=True, exist_ok=True)
        workbook = load_workbook(source)
        apply_runtime_contract(tenant, workbook, str(known_hosts))
        workbook.save(target)
        converge_templates(api, tenant, known_hosts)
        converge_catch_up_policy(api, tenant)
        log(f'{tenant} 模板、渠道与补跑策略已收敛')


def converge_catch_up_policy(api, tenant):
    if tenant != 'tb':
        return
    calendars = query_items(api, tenant, f'/api/console/calendars?tenantId={tenant}&pageNo=1&pageSize=100')
    current = next(item for item in calendars if item['calendarCode'].replace('-', '_').lower() == 'tb_cov_calendar')
    payload = {
        'tenantId': tenant,
        'calendarCode': current['calendarCode'],
        'calendarName': current['calendarName'],
        'timezone': current['timezone'],
        'holidayRollRule': current.get('holidayRollRule'),
        'catchUpPolicy': 'AUTO',
        'catchUpMaxDays': current.get('catchUpMaxDays'),
    }
    request_json(api, 'PUT', f'/api/console/calendars/{current["id"]}', tenant, payload)


def converge_templates(api, tenant, known_hosts):
    templates = query_items(api, tenant, f'/api/console/file-templates?tenantId={tenant}&pageNo=1&pageSize=100')
    for suffix in ('import', 'export'):
        code = f'{tenant}_cov_tpl_{suffix}'
        current = next(item for item in templates if item['templateCode'] == code)
        payload = dict(current)
        payload['tenantId'] = tenant
        payload['fieldMappingsJson'] = compact(FIELD_MAPPINGS) if suffix == 'import' else current.get('fieldMappings')
        payload['validationRuleSetJson'] = current.get('validationRuleSet')
        payload['headerTemplateJson'] = current.get('headerTemplate')
        payload['trailerTemplateJson'] = current.get('trailerTemplate')
        if suffix == 'import':
            payload['queryParamSchemaJson'] = compact(import_query_param_schema())
        else:
            payload['defaultQuerySql'] = export_query_sql()
            payload['queryParamSchemaJson'] = compact(export_query_param_schema())
            payload['exportDataRef'] = 'sql_template_export'
        request_json(api, 'PUT', f'/api/console/file-templates/{current["id"]}', tenant, payload)
    channels = query_items(api, tenant, f'/api/console/file-channels?tenantId={tenant}&pageNo=1&pageSize=100')
    for current in channels:
        if current['channelCode'] != f'{tenant}-cov-sftp':
            continue
        config = json.loads(current['configJson'])
        config.update({'sftp_host': '127.0.0.1', 'sftp_port': 12222, 'sftp_known_hosts_path': str(known_hosts)})
        payload = {
            'tenantId': tenant,
            'channelName': current.get('channelName'),
            'channelType': current.get('channelType'),
            'targetEndpoint': '127.0.0.1:12222',
            'authType': current.get('authType'),
            'configJson': compact(config),
            'receiptPolicy': current.get('receiptPolicy'),
            'timeoutSeconds': current.get('timeoutSeconds'),
            'enabled': current.get('enabled'),
        }
        request_json(api, 'PUT', f'/api/console/file-channels/{current["id"]}', tenant, payload)


def external_run_dir(args):
    run_dir = Path(args.run_dir).expanduser().absolute()
    if run_dir.is_relative_to(ROOT):
        raise INIT.Stop('结果目录必须位于仓库外，避免误提交运行证据')
    run_dir.mkdir(parents=True, exist_ok=True)
    return run_dir


def unregister_coverage_triggers(api):
    triggers = query_items(api, 'default-tenant', '/api/console/ops/triggers?tenantId=default-tenant')
    for trigger in triggers:
        tenant = trigger.get('tenantId')
        code = trigger.get('workflowCode', '')
        if tenant not in TENANTS or '_COV_' not in code:
            continue
        try:
            request_json(api, 'POST', f'/api/console/ops/triggers/{code}/unregister?tenantId={tenant}', tenant, None)
        except INIT.Stop as exc:
            if 'HTTP 404' not in str(exc):
                raise


def active_coverage_instances(api):
    active = []
    for tenant in TENANTS:
        instances = query_items(api, tenant, f'/api/console/queries/instances?tenantId={tenant}&pageNo=1&pageSize=100')
        active.extend((tenant, item) for item in instances
                      if '_COV_' in item.get('jobCode', '') and item.get('instanceStatus') not in TERMINAL)
    return active


def quiesce_coverage_instances(api):
    for tenant, instance in active_coverage_instances(api):
        try:
            request_json(api, 'POST', f'/api/console/instances/{instance["id"]}/terminate?tenantId={tenant}', tenant, None)
        except INIT.Stop as exc:
            if 'HTTP 409' not in str(exc):
                raise
    for _ in range(60):
        if not active_coverage_instances(api):
            return
        time.sleep(2)
    raise INIT.Stop('仍有活跃覆盖实例，拒绝删除在途运行现场')


def await_coverage_publishing():
    query = """
SELECT
  (SELECT count(*) FROM batch.worker_report_outbox
   WHERE tenant_id IN ('ta','tb','tc') AND publish_status = 'PUBLISHING'
     AND (task_id IN (SELECT id FROM batch.job_task WHERE tenant_id IN ('ta','tb','tc')
          AND job_instance_id IN (SELECT id FROM batch.job_instance
          WHERE tenant_id IN ('ta','tb','tc') AND job_code LIKE '%_COV_%'))
          OR payload_json::text ILIKE '%_COV_%'))
+ (SELECT count(*) FROM batch.outbox_event
   WHERE tenant_id IN ('ta','tb','tc') AND publish_status = 'PUBLISHING'
     AND (event_key ILIKE '%_COV_%' OR payload_json::text ILIKE '%_COV_%'));
"""
    command = [
        'docker', 'exec', '-i', os.environ['PG_CONTAINER'],
        'psql', '-U', os.environ['PGUSER'], '-d', os.environ['PLATFORM_DB'], '-At', '-c', query,
    ]
    for _ in range(60):
        result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
        if result.stdout.strip() == '0':
            return
        time.sleep(2)
    raise INIT.Stop('覆盖 Outbox 仍有 PUBLISHING 在途请求，拒绝清理后续资产')


def cleanup_database():
    command = [
        'docker', 'exec', '-i', os.environ['PG_CONTAINER'],
        'psql', '-U', os.environ['PGUSER'], '-d', os.environ['PLATFORM_DB'],
        '-v', f'business_db={os.environ["BUSINESS_DB"]}', '-v', 'ON_ERROR_STOP=1', '-f', '-',
    ]
    subprocess.run(command, cwd=ROOT, input=CLEANUP_SQL.read_text(encoding='utf-8'),
                   check=True, capture_output=True, text=True)


def cleanup_sftp():
    script = r"""find /home/ta/inbound /home/tb/inbound /home/tc/inbound -maxdepth 1 -type f \
      \( -name '*coverage*' -o -name 'customer_${batchDate}*' \) -print -delete"""
    result = subprocess.run(['docker', 'exec', os.environ.get('SFTP_CONTAINER', 'batch-sftp'), 'sh', '-c', script],
                            cwd=ROOT, check=True, capture_output=True, text=True)
    return [line for line in result.stdout.splitlines() if line]


def is_coverage_minio_object(path):
    lowered = path.lower()
    return 'coverage' in lowered or re.search(r'/outbound/(?:ta|tb|tc)_cov_', lowered) is not None


def minio_objects():
    bucket = os.environ['MINIO_BUCKET']
    result = subprocess.run(['bash', str(MINIO_MC), 'find', f'local/{bucket}', '--print', '{}'],
                            cwd=ROOT, check=True, capture_output=True, text=True)
    prefix = f'local/{bucket}/'
    objects = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if any(not item.startswith(prefix) for item in objects):
        raise INIT.Stop('MinIO 返回了目标 bucket 之外的对象，停止清理')
    return objects


def cleanup_minio():
    matched = [item for item in minio_objects() if is_coverage_minio_object(item)]
    for item in matched:
        subprocess.run(['bash', str(MINIO_MC), 'rm', '--force', '--quiet', item],
                       cwd=ROOT, check=True, capture_output=True, text=True)
    remaining = [item for item in minio_objects() if is_coverage_minio_object(item)]
    if remaining:
        raise INIT.Stop(f'MinIO 覆盖对象清理后仍有残留：{remaining}')
    return matched


def cleanup(args, phase):
    run_dir = external_run_dir(args)
    api = load_api(args)
    unregister_coverage_triggers(api)
    quiesce_coverage_instances(api)
    await_coverage_publishing()
    cleanup_rerun_endpoint()
    cleanup_database()
    sftp_files = cleanup_sftp()
    minio_objects_removed = cleanup_minio()
    evidence = {
        'phase': phase,
        'scope': 'ta/tb/tc *_COV_* runtime only',
        'sftpFilesRemoved': sftp_files,
        'minioObjectsRemoved': minio_objects_removed,
        'definitionsAndTenantsPreserved': True,
    }
    (run_dir / f'cleanup-{phase}.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    log(f'{phase} 清理完成：SFTP {len(sftp_files)} 个文件，MinIO {len(minio_objects_removed)} 个对象')


def latest_run(api, tenant, workflow_id):
    runs = query_items(api, tenant,
                       f'/api/console/queries/workflow-runs?tenantId={tenant}&workflowDefinitionId={workflow_id}&pageNo=1&pageSize=20')
    return runs[0] if runs else None


def trigger(api, tenant, workflow_id, label, params, trigger_type='MANUAL', timeout=120):
    before = latest_run(api, tenant, workflow_id)
    before_id = before['id'] if before else 0
    payload = {
        'tenantId': tenant,
        'bizDate': '2026-11-03',
        'triggerType': trigger_type,
        'payload': json.dumps(params, ensure_ascii=False),
    }
    accepted = request_json(api, 'POST', f'/api/console/workflow-definitions/{workflow_id}/trigger', tenant, payload)
    for _ in range(timeout // 2 + 1):
        run = latest_run(api, tenant, workflow_id)
        if run and run['id'] > before_id and run['runStatus'] in TERMINAL:
            result = {'tenant': tenant, 'label': label, 'status': run['runStatus'], 'runId': run['id'], 'accepted': accepted}
            log(json.dumps(result, ensure_ascii=False))
            return result
        time.sleep(2)
    return {'tenant': tenant, 'label': label, 'status': 'RUNNING', 'runId': None, 'accepted': accepted}


def create_arrival(api, tenant):
    name = f'{tenant}-coverage-{uuid.uuid4().hex}.csv'
    query = urllib.parse.urlencode({'tenantId': tenant, 'channelCode': f'{tenant}-cov-sftp', 'fileName': name})
    pre = request_json(api, 'POST', f'/api/console/files/presign-upload?{query}', tenant, None)
    content = (
        'customerNo,customerName,customerType,certificateNo,mobileNo,email,status\n'
        'COV-001,Coverage Customer,PERSONAL,CERT-001,13900000000,cov@example.test,ACTIVE\n'
    ).encode()
    boundary = f'coverage-{uuid.uuid4().hex}'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            'Content-Type: text/csv\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    headers = INIT.Api.csrf_headers(api, tenant, f'coverage-arrival-content-{uuid.uuid4().hex}')
    headers['Content-Type'] = f'multipart/form-data; boundary={boundary}'
    api.request('PUT', f'/api/console/files/{pre["fileId"]}/content?tenantId={tenant}', body, headers)
    request_json(api, 'POST', f'/api/console/files/{pre["fileId"]}/confirm-arrival?tenantId={tenant}', tenant, None)
    return pre['fileId']


def latest_file(api, tenant, biz_type):
    files = query_items(api, tenant, f'/api/console/queries/files?tenantId={tenant}&pageNo=1&pageSize=20')
    matched = [item for item in files if item.get('bizType') == biz_type]
    if not matched:
        raise INIT.Stop(f'{tenant} 尚无 {biz_type} 文件')
    return matched[0]['id']


def job_params(api, tenant, kind, arrival_id):
    if kind == 'IMPORT':
        return {'content': IMPORT_CONTENT}
    if kind == 'DISPATCH':
        return {'fileId': latest_file(api, tenant, f'{tenant.upper()}_COV_EXPORT'),
                'channelCode': f'{tenant}-cov-sftp'}
    if kind == 'BUNDLE_IMPORT':
        return {'bundleFiles': [{'sourceFileId': arrival_id, 'templateCode': f'{tenant}_cov_tpl_import'}]}
    if kind == 'BUNDLE_EXPORT':
        return {'bundleFiles': [{'templateCode': f'{tenant}_cov_tpl_export'}]}
    if kind == 'BUNDLE_DISPATCH':
        return {'bundleFiles': [{'sourceFileId': latest_file(api, tenant, f'{tenant.upper()}_COV_EXPORT'),
                                 'targetRef': f'{tenant}-cov-sftp'}]}
    return {}


def execute(args):
    api = load_api(args)
    results = []
    for tenant in TENANTS:
        arrival_id = create_arrival(api, tenant)
        jobs = query_items(api, tenant, f'/api/console/queries/job-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
        by_code = {item['jobCode']: item for item in jobs}
        for kind in ('IMPORT', 'EXPORT', 'PROCESS', 'ATOMIC', 'DISPATCH',
                     'BUNDLE_IMPORT', 'BUNDLE_EXPORT', 'BUNDLE_DISPATCH'):
            code = f'{tenant.upper()}_COV_{kind}'
            workflow_id = by_code[code]['defaultWorkflowDefinitionId']
            results.append(trigger(api, tenant, workflow_id, f'job:{kind}',
                                   job_params(api, tenant, kind, arrival_id)))
        workflows = query_items(api, tenant, f'/api/console/queries/workflow-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
        serial = next(item for item in workflows if item['workflowCode'] == f'{tenant.upper()}_COV_WF_SERIAL')
        results.append(trigger(api, tenant, serial['id'], 'workflow:SERIAL', {'content': IMPORT_CONTENT}))
        branch = next(item for item in workflows if '_COV_WF_BRANCH_' in item['workflowCode'])
        for proceed in (True, False):
            results.append(trigger(api, tenant, branch['id'], f'workflow:BRANCH proceed={proceed}',
                                   {'content': IMPORT_CONTENT, 'proceed': proceed}))
        wait = next(item for item in workflows if item['workflowCode'] == f'{tenant.upper()}_COV_WF_WAIT')
        results.append(trigger(api, tenant, wait['id'], 'workflow:WAIT', {}))
    return verify_results(args, results)


def verify_results(args, results, filename='results.json'):
    run_dir = external_run_dir(args)
    output = run_dir / filename
    output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    failures = [item for item in results if item['status'] != 'SUCCESS']
    if failures:
        raise INIT.Stop(f'存在未成功场景：{failures}；结果见 {output}')
    log(f'全部 {len(results)} 个场景 SUCCESS；结果见 {output}')
    return results


def mockserver_request(method, path, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(
        f'http://127.0.0.1:11080{path}', data=body, method=method,
        headers={'Content-Type': 'application/json'} if body else {})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.status not in (200, 201, 204):
                raise INIT.Stop(f'MockServer {method} {path} 返回 HTTP {response.status}')
    except urllib.error.HTTPError as exc:
        if method != 'DELETE' or exc.code != 404:
            raise INIT.Stop(f'MockServer {method} {path} 失败（HTTP {exc.code}）') from None


def prepare_rerun_endpoint():
    mockserver_request('PUT', '/mockserver/expectation', {
        'id': 'coverage-rerun-target',
        'httpRequest': {'method': 'GET', 'path': '/coverage-rerun-target'},
        'httpResponse': {'statusCode': 200, 'body': 'coverage-rerun-ok'},
        'times': {'remainingTimes': 1, 'unlimited': False},
    })


def cleanup_rerun_endpoint():
    mockserver_request('PUT', '/mockserver/expectation', {
        'id': 'coverage-rerun-target',
        'httpRequest': {'method': 'GET', 'path': '/coverage-rerun-target'},
        'httpResponse': {'statusCode': 404},
        'times': {'remainingTimes': 1, 'unlimited': False},
        'priority': -1,
    })


def update_job(api, tenant, code, changes):
    jobs = query_items(api, tenant, f'/api/console/queries/job-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
    job = next(item for item in jobs if item['jobCode'] == code)
    request_json(api, 'PUT', f'/api/console/job-definitions/{job["id"]}', tenant, {'tenantId': tenant, **changes})


def update_schedule(api, tenant, workflow_id, changes):
    workflows = query_items(api, tenant, f'/api/console/queries/workflow-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
    workflow = next(item for item in workflows if item['id'] == workflow_id)
    payload = {'tenantId': tenant, 'expectedVersion': workflow['version']}
    for field in ('scheduleType', 'scheduleExpr', 'timezone', 'triggerMode', 'calendarCode', 'dependsOnJobCode'):
        payload[field] = workflow.get(field)
    payload.update(changes)
    request_json(api, 'PUT', f'/api/console/workflow-definitions/{workflow_id}/schedule', tenant, payload)


def prepare_closed_day():
    command = [
        'docker', 'exec', '-i', os.environ['PG_CONTAINER'],
        'psql', '-U', os.environ['PGUSER'], '-d', os.environ['PLATFORM_DB'],
        '-v', 'ON_ERROR_STOP=1', '-f', '-',
    ]
    subprocess.run(command, cwd=ROOT, input=CLOSED_DAY_SQL.read_text(encoding='utf-8'),
                   check=True, capture_output=True, text=True)


def await_rejected_occurrence(api, tenant, workflow_id):
    path = f'/api/console/queries/batch-days/2026-11-03/window?tenantId={tenant}&calendarCode=tb_cov_calendar'
    for _ in range(75):
        window = api.request('GET', path)
        for occurrence in window.get('scheduledOccurrences', []):
            if (occurrence['workflowDefinitionId'] == workflow_id
                    and occurrence['occurrenceStatus'] == 'CLOSED_DAY_REJECTED'):
                return occurrence['scheduledFireTime']
        time.sleep(2)
    raise INIT.Stop(f'{tenant} 未产生 CLOSED_DAY_REJECTED occurrence')


def trigger_matrix(args):
    api = load_api(args)
    results = []
    for tenant in TENANTS:
        workflows = query_items(api, tenant, f'/api/console/queries/workflow-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
        serial = next(item for item in workflows if item['workflowCode'] == f'{tenant.upper()}_COV_WF_SERIAL')
        results.append(trigger(api, tenant, serial['id'], 'trigger:API', {'content': IMPORT_CONTENT}, 'API'))
    tenant = 'ta'
    code = 'TA_COV_ATOMIC'
    valid_params = {'sql': 'SELECT 1', 'taskType': 'sql'}
    invalid_params = {'taskType': 'http', 'url': 'http://127.0.0.1:11080/coverage-rerun-target',
                      'method': 'GET', 'expectStatus': 200, 'timeoutSeconds': 10}
    cleanup_rerun_endpoint()
    update_job(api, tenant, code, {'defaultParams': compact(invalid_params)})
    try:
        jobs = query_items(api, tenant, f'/api/console/queries/job-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
        workflow_id = next(item for item in jobs if item['jobCode'] == code)['defaultWorkflowDefinitionId']
        failed = trigger(api, tenant, workflow_id, 'trigger:RERUN setup', {}, timeout=60)
        if failed['status'] != 'FAILED':
            raise INIT.Stop(f'RERUN 失败样本未按预期失败：{failed}')
        failed_instance = query_items(api, tenant, f'/api/console/queries/instances?tenantId={tenant}&exactJobCode={code}&pageNo=1&pageSize=1')[0]
    finally:
        update_job(api, tenant, code, {'defaultParams': compact(valid_params)})
    try:
        prepare_rerun_endpoint()
        rerun = request_json(api, 'POST', '/api/console/jobs/rerun', tenant, {
            'tenantId': tenant,
            'jobCode': code,
            'bizDate': '2026-11-03',
            'targetInstanceNo': failed_instance['instanceNo'],
            'configVersionPolicy': 'USE_LATEST_CONFIG',
            'reason': 'coverage harness RERUN',
        })
        rerun_status = 'RUNNING'
        for _ in range(60):
            candidate = query_items(api, tenant, f'/api/console/queries/instances?tenantId={tenant}&exactJobCode={code}&pageNo=1&pageSize=1')[0]
            if candidate['id'] > failed_instance['id'] and candidate['instanceStatus'] in TERMINAL:
                rerun_status = candidate['instanceStatus']
                break
            time.sleep(2)
        results.append({'tenant': tenant, 'label': 'trigger:RERUN', 'status': rerun_status, 'accepted': rerun})
        results.extend(scheduled_matrix(api))
    finally:
        cleanup_rerun_endpoint()
    return verify_results(args, results, 'trigger-results.json')


def scheduled_matrix(api):
    results = []
    for tenant, mode in (('ta', 'FIXED_RATE'), ('tb', 'CATCH_UP'), ('tc', 'MIXED')):
        code = f'{tenant.upper()}_COV_ATOMIC'
        jobs = query_items(api, tenant, f'/api/console/queries/job-definitions?tenantId={tenant}&pageNo=1&pageSize=100')
        workflow_id = next(item for item in jobs if item['jobCode'] == code)['defaultWorkflowDefinitionId']
        before = latest_run(api, tenant, workflow_id)
        before_id = before['id'] if before else 0
        if mode == 'CATCH_UP':
            prepare_closed_day()
        update_schedule(api, tenant, workflow_id, {
            'scheduleType': 'FIXED_RATE',
            'scheduleExpr': '60',
            'triggerMode': 'MIXED' if mode == 'MIXED' else 'SCHEDULED',
        })
        registered = False
        try:
            request_json(api, 'POST', f'/api/console/ops/triggers/{code}/register?tenantId={tenant}', tenant, None)
            registered = True
            if mode == 'CATCH_UP':
                scheduled_fire_time = await_rejected_occurrence(api, tenant, workflow_id)
                caught = request_json(api, 'POST', f'/api/console/workflow-definitions/{workflow_id}/scheduled-occurrences/catch-up', tenant, {
                    'tenantId': tenant,
                    'scheduledFireTime': scheduled_fire_time,
                    'bizDate': '2026-11-03',
                    'reason': 'coverage harness CATCH_UP',
                })
                catch_up_status, catch_up_run_id = 'RUNNING', None
                for _ in range(60):
                    candidate = latest_run(api, tenant, workflow_id)
                    if candidate and candidate['id'] > before_id and candidate['runStatus'] in TERMINAL:
                        catch_up_status, catch_up_run_id = candidate['runStatus'], candidate['id']
                        break
                    time.sleep(2)
                results.append({'tenant': tenant, 'label': 'trigger:CATCH_UP', 'status': catch_up_status,
                                'runId': catch_up_run_id, 'accepted': caught})
            else:
                scheduled = None
                for _ in range(150):
                    candidate = latest_run(api, tenant, workflow_id)
                    if candidate and candidate['id'] > before_id and candidate['runStatus'] in TERMINAL:
                        scheduled = candidate
                        break
                    time.sleep(2)
                if not scheduled:
                    raise INIT.Stop(f'{tenant} {mode} 未产生调度运行')
                results.append({'tenant': tenant, 'label': f'trigger:{mode}', 'status': scheduled['runStatus'], 'runId': scheduled['id']})
            if mode == 'MIXED':
                results.append(trigger(api, tenant, workflow_id, 'trigger:MIXED manual', {}, 'API'))
        finally:
            if registered:
                request_json(api, 'POST', f'/api/console/ops/triggers/{code}/unregister?tenantId={tenant}', tenant, None)
            update_schedule(api, tenant, workflow_id,
                            {'scheduleType': 'MANUAL', 'scheduleExpr': None, 'triggerMode': 'MANUAL'})
            if mode == 'CATCH_UP':
                update_job(api, tenant, code, {'defaultParams': compact({'sql': 'SELECT 1', 'taskType': 'sql'})})
    return results


def run_selected(args):
    if args.command == 'preflight':
        preflight(args)
    elif args.command == 'init':
        init_tenants(args)
    elif args.command == 'configure':
        configure(args)
    elif args.command == 'execute':
        execute(args)
    elif args.command == 'trigger-matrix':
        trigger_matrix(args)
    else:
        preflight(args)
        init_tenants(args)
        configure(args)
        execute(args)
        if args.include_trigger_matrix:
            trigger_matrix(args)
        else:
            log('SKIP：调度/补跑矩阵未执行；使用 --include-trigger-matrix 纳入')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'init', 'configure', 'execute', 'trigger-matrix', 'all'))
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--operator', required=True)
    parser.add_argument('--operator-password')
    parser.add_argument('--allow-local-bypass', action='store_true')
    parser.add_argument('--receipt', default='/private/tmp/coverage-harness-receipt.json')
    parser.add_argument('--credentials-file')
    parser.add_argument('--run-dir', default=f'/private/tmp/coverage-harness-{uuid.uuid4().hex}')
    parser.add_argument('--include-trigger-matrix', action='store_true')
    parser.add_argument('--cleanup', choices=('never', 'before', 'after', 'both'), default='both')
    args = parser.parse_args()
    try:
        if args.cleanup in ('before', 'both'):
            cleanup(args, 'before')
        try:
            run_selected(args)
        finally:
            if args.cleanup in ('after', 'both'):
                cleanup(args, 'after')
        return 0
    except (INIT.Stop, OSError, ValueError, KeyError, subprocess.CalledProcessError) as exc:
        detail = exc.stderr.strip() if isinstance(exc, subprocess.CalledProcessError) and exc.stderr else str(exc)
        print(f'[coverage] 已停止：{detail}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
