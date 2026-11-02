#!/usr/bin/env python3
# 用途：使用 Console 当前模板生成三租户覆盖示例。
# 用法：python3 generate.py --sample-template 模板路径 --output-dir 空目录。
"""用 Console ALL 场景示例工作簿生成 ta/tb/tc 覆盖包；不修改 SIM 原始包。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from openpyxl import load_workbook

HERE = Path(__file__).resolve().parent
DATA_SHEETS = (
    'resource_queue', 'business_calendar', 'batch_window', 'job_definition',
    'job_monitoring_policy', 'file_channel_config', 'file_template_config',
    'workflow_definition', 'workflow_node', 'workflow_edge', 'workflow_monitoring_policy',
)


def append(sheet, values):
    headers = [cell.value for cell in sheet[1]]
    unknown = set(values) - set(headers)
    if unknown:
        raise ValueError(f'{sheet.title} 缺少字段: {sorted(unknown)}')
    sheet.append([values.get(name) for name in headers])


def make_package(source, tenant, mode, target):
    book = load_workbook(source)
    if any(name not in book.sheetnames for name in DATA_SHEETS):
        raise ValueError('必须使用当前 Console 生成的 ALL 场景示例，缺少数据 sheet')
    if {'pipeline_definition', 'pipeline_step_definition'} & set(book.sheetnames):
        raise ValueError('不接受已退役 Pipeline sheet')
    prefix = tenant.upper() + '_COV_'
    replacements = {
        'JOB_IMPORT_CUSTOMER': prefix + 'IMPORT',
        'JOB_EXPORT_CUSTOMER': prefix + 'EXPORT',
        'JOB_PROCESS_CUSTOMER': prefix + 'PROCESS',
        'JOB_DISPATCH_CUSTOMER': prefix + 'DISPATCH',
        'JOB_ATOMIC_SQL_CHECK': prefix + 'ATOMIC',
        'TPL_IMPORT_CUSTOMER': prefix + 'TPL_IMPORT',
        'TPL_EXPORT_CUSTOMER': prefix + 'TPL_EXPORT',
        'WF_CUSTOMER_EOD': prefix + 'WF_SERIAL',
        'demo-queue': tenant + '-cov-queue',
        'default-calendar': tenant + '-cov-calendar',
        'always-open': tenant + '-cov-window',
        'demo-sftp': tenant + '-cov-sftp',
    }
    for name in DATA_SHEETS:
        sheet = book[name]
        headers = [cell.value for cell in sheet[1]]
        for row in sheet.iter_rows(min_row=2):
            if not any(cell.value is not None and str(cell.value).strip() for cell in row):
                continue
            for name_, cell in zip(headers, row):
                if name_ == 'tenant_id':
                    cell.value = tenant
                elif isinstance(cell.value, str):
                    for old, new in replacements.items():
                        cell.value = cell.value.replace(old, new)

    jobs = book['job_definition']
    job_headers = [cell.value for cell in jobs[1]]
    for row in jobs.iter_rows(min_row=2):
        values = {name: cell for name, cell in zip(job_headers, row)}
        values['calendar_code'].value = tenant + '-cov-calendar'
        if values['job_type'].value == 'EXPORT':
            values['depends_on_job_code'].value = prefix + 'IMPORT'

    # 束作业在触发时接收 manifest；不伪造文件、模板目标或外部凭证。
    for kind, group in [('BUNDLE_IMPORT', 'import'), ('BUNDLE_EXPORT', 'export'), ('BUNDLE_DISPATCH', 'dispatch'), ('GENERAL', 'general')]:
        append(jobs, {
            'tenant_id': tenant, 'job_code': prefix + kind, 'job_name': kind + ' 覆盖示例',
            'job_type': kind, 'biz_type': 'CUSTOMER', 'queue_code': tenant + '-cov-queue',
            'worker_group': group, 'schedule_type': 'MANUAL', 'calendar_code': tenant + '-cov-calendar', 'retry_policy': 'NONE',
            'retry_max_count': 0, 'timeout_seconds': 3600, 'shard_strategy': 'NONE',
            'execution_mode': 'FULL', 'param_schema': '{}', 'default_params': '{}',
            'enabled': kind != 'GENERAL',
            'description': '占位类型，无平台通用 Worker' if kind == 'GENERAL' else '触发时需提供对应文件束 manifest',
        })

    definitions = book['workflow_definition']
    nodes = book['workflow_node']
    edges = book['workflow_edge']
    # 修改所有示例工作流为手动触发，保证仅导入不自动运行。
    for row in definitions.iter_rows(min_row=2):
        for header, cell in zip([c.value for c in definitions[1]], row):
            if header == 'schedule_enabled': cell.value = False
            if header == 'schedule_type': cell.value = 'MANUAL'
            if header == 'calendar_code': cell.value = tenant + '-cov-calendar'

    def workflow(code, name, dependency=None):
        append(definitions, {'tenant_id': tenant, 'workflow_code': code, 'workflow_name': name,
            'workflow_type': 'DAG', 'version': 1, 'enabled': True, 'schedule_enabled': False,
            'schedule_type': 'MANUAL', 'timezone': 'Asia/Shanghai',
            'calendar_code': tenant + '-cov-calendar', 'depends_on_job_code': dependency,
            'description': '手动示例；运行前核对当前租户与外部依赖'})

    def node(wf, code, kind, job=None, params=None, order=0):
        append(nodes, {'tenant_id': tenant, 'workflow_code': wf, 'workflow_version': 1,
            'node_code': code, 'node_name': code, 'node_type': kind, 'related_job_code': job,
            'node_order': order, 'retry_policy': 'NONE', 'retry_max_count': 0,
            'timeout_seconds': 300, 'node_params': json.dumps(params or {}, ensure_ascii=False),
            'enabled': True})

    def edge(wf, start, end, kind='ALWAYS', condition=None):
        append(edges, {'tenant_id': tenant, 'workflow_code': wf, 'workflow_version': 1,
            'from_node_code': start, 'to_node_code': end, 'edge_type': kind,
            'condition_expr': condition, 'enabled': True})

    branch = prefix + 'WF_BRANCH_' + mode
    workflow(branch, f'分支汇聚 {mode}')
    params = {'joinMode': mode}
    if mode == 'N_OF': params['joinThreshold'] = 2
    for order, (code, kind, job, options) in enumerate([
        ('START', 'START', None, {}), ('FORK', 'GATEWAY', None, {}),
        ('IMPORT', 'JOB', prefix + 'IMPORT', {}), ('PROCESS', 'JOB', prefix + 'PROCESS', {}),
        ('ATOMIC', 'JOB', prefix + 'ATOMIC', {}), ('MERGE', 'GATEWAY', None, params),
        ('FALLBACK', 'JOB', prefix + 'DISPATCH', {}), ('END', 'END', None, {}),
    ], 1): node(branch, code, kind, job, options, order)
    for start, end, kind, expr in [
        ('START', 'FORK', 'ALWAYS', None), ('FORK', 'IMPORT', 'ALWAYS', None),
        ('FORK', 'PROCESS', 'CONDITION', 'proceed == true'), ('FORK', 'ATOMIC', 'ALWAYS', None),
        ('IMPORT', 'MERGE', 'SUCCESS', None), ('PROCESS', 'MERGE', 'SUCCESS', None),
        ('ATOMIC', 'MERGE', 'SUCCESS', None), ('ATOMIC', 'FALLBACK', 'FAILURE', None),
        ('FALLBACK', 'END', 'ALWAYS', None), ('MERGE', 'END', 'ALWAYS', None),
    ]: edge(branch, start, end, kind, expr)

    wait = prefix + 'WF_WAIT'
    workflow(wait, '文件到达等待', prefix + 'IMPORT')
    node(wait, 'START', 'START', order=1)
    node(wait, 'WAIT_FILE', 'WAIT', params={
        'sensor_type': 'FILE_ARRIVAL',
        'sensor_spec': {'pattern': tenant + '-coverage-*.csv', 'maxAgeSeconds': 3600},
        'timeout_seconds': 300, 'poll_interval_seconds': 30, 'on_timeout': 'FAIL',
    }, order=2)
    node(wait, 'END', 'END', order=3)
    edge(wait, 'START', 'WAIT_FILE')
    edge(wait, 'WAIT_FILE', 'END', 'SUCCESS')
    book.save(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sample-template', type=Path, required=True, help='Console ALL 场景示例 xlsx')
    parser.add_argument('--output-dir', type=Path, default=HERE)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    tenants = [('ta', 'ALL'), ('tb', 'ANY'), ('tc', 'N_OF')]
    targets = [args.output_dir / f'{tenant}-coverage-example.xlsx' for tenant, _ in tenants]
    existing = [str(path) for path in targets if path.exists()]
    if existing:
        raise SystemExit(f'目标已存在，拒绝覆盖: {existing}')
    for (tenant, mode), target in zip(tenants, targets):
        make_package(args.sample_template, tenant, mode, target)
        print(target)


if __name__ == '__main__':
    main()
