# 用途：统一三租户覆盖包的运行契约，供生成器与本地 harness 复用。
# 用法：from scripts.lib.coverage_config import apply_runtime_contract
"""三租户覆盖包的运行配置事实源；不包含环境重置或清理逻辑。"""
import json

FIELD_MAPPINGS = [
    {'name': 'customerNo', 'targetColumn': 'customer_no', 'type': 'STRING', 'required': True, 'maxLength': 64},
    {'name': 'customerName', 'targetColumn': 'customer_name', 'type': 'STRING', 'required': True, 'maxLength': 256},
    {'name': 'customerType', 'targetColumn': 'customer_type', 'type': 'STRING', 'required': True, 'maxLength': 32},
    {'name': 'certificateNo', 'targetColumn': 'certificate_no', 'type': 'STRING', 'required': False, 'maxLength': 64},
    {'name': 'mobileNo', 'targetColumn': 'mobile_no', 'type': 'STRING', 'required': False, 'maxLength': 32},
    {'name': 'email', 'targetColumn': 'email', 'type': 'STRING', 'required': False, 'maxLength': 128},
    {'name': 'status', 'targetColumn': 'status', 'type': 'STRING', 'required': True, 'maxLength': 32},
]
PASSWORDS = {'ta': 'ta_pass_123', 'tb': 'tb_pass_123', 'tc': 'tc_pass_123'}


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def import_query_param_schema():
    return {
        'jdbcMappedImport': {
            'schema': 'biz',
            'table': 'customer_account',
            'tenantColumn': 'tenant_id',
            'columnMappings': [],
            'conflictColumns': ['customer_no'],
            'standardAuditBindings': True,
        }
    }


def export_query_param_schema():
    return {'sqlTemplateExport': {'cursorColumn': 'id'}}


def export_query_sql():
    return 'SELECT id, customer_no FROM biz.customer_account WHERE tenant_id = :tenantId AND CAST(:batchNo AS text) IS NOT NULL'


def channel_config(tenant, known_hosts_path=None):
    config = {
        'sftp_host': '127.0.0.1',
        'sftp_port': 12222,
        'sftp_user': tenant,
        'sftp_password': PASSWORDS[tenant],
        'sftp_remote_directory': '/inbound',
    }
    if known_hosts_path:
        config['sftp_known_hosts_path'] = known_hosts_path
    return config


def apply_runtime_contract(tenant, workbook, known_hosts_path=None):
    """把可执行配置写入覆盖包，保留原 Job、Workflow 与调度定义。"""
    template_sheet = workbook['file_template_config']
    rows = template_sheet.iter_rows()
    headers = next(rows)
    indexes = {cell.value: cell.column for cell in headers}
    for row in range(2, template_sheet.max_row + 1):
        code = template_sheet.cell(row=row, column=indexes['template_code']).value
        if str(code).lower() == f'{tenant}_cov_tpl_import':
            template_sheet.cell(row=row, column=indexes['field_mappings']).value = compact(FIELD_MAPPINGS)
            template_sheet.cell(row=row, column=indexes['query_param_schema']).value = compact(import_query_param_schema())
        elif str(code).lower() == f'{tenant}_cov_tpl_export':
            template_sheet.cell(row=row, column=indexes['default_query_sql']).value = export_query_sql()
            template_sheet.cell(row=row, column=indexes['query_param_schema']).value = compact(export_query_param_schema())

    channel_sheet = workbook['file_channel_config']
    rows = channel_sheet.iter_rows()
    headers = next(rows)
    indexes = {cell.value: cell.column for cell in headers}
    for row in range(2, channel_sheet.max_row + 1):
        channel_sheet.cell(row=row, column=indexes['target_endpoint']).value = '127.0.0.1:12222'
        channel_sheet.cell(row=row, column=indexes['config_json']).value = compact(channel_config(tenant, known_hosts_path))

    job_sheet = workbook['job_definition']
    rows = job_sheet.iter_rows()
    headers = next(rows)
    indexes = {cell.value: cell.column for cell in headers}
    for row in range(2, job_sheet.max_row + 1):
        if job_sheet.cell(row=row, column=indexes['job_type']).value == 'DISPATCH':
            params = json.loads(job_sheet.cell(row=row, column=indexes['default_params']).value or '{}')
            params.setdefault('systemPipelineStepParams', {}).setdefault('DISPATCH', {})['channelCode'] = f'{tenant}-cov-sftp'
            job_sheet.cell(row=row, column=indexes['default_params']).value = compact(params)

    if tenant == 'tb':
        calendar_sheet = workbook['business_calendar']
        rows = calendar_sheet.iter_rows()
        headers = next(rows)
        indexes = {cell.value: cell.column for cell in headers}
        for row in range(2, calendar_sheet.max_row + 1):
            calendar_sheet.cell(row=row, column=indexes['catch_up_policy']).value = 'AUTO'
