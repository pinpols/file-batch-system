#!/usr/bin/env python3
# 用途：以 Console API 受控创建 ta/tb/tc 并导入对应覆盖包，不清理现有数据。
# 用法：python3 scripts/data/init-coverage-tenants.py --help；先查所属 README 的安全边界。
"""本地覆盖租户初始化；请求结果不明时保持检查点并停止。"""
import argparse
import getpass
import fcntl
import hashlib
import http.cookiejar
import io
import json
import os
import secrets
import stat
from pathlib import Path
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

from openpyxl import load_workbook

TENANTS = ('ta', 'tb', 'tc')
UPLOAD_COUNTS = ('resourceQueueRows', 'businessCalendarRows', 'batchWindowRows', 'jobRows',
                 'fileChannelRows', 'fileTemplateRows', 'pipelineRows', 'pipelineStepRows',
                 'workflowDefinitionRows', 'workflowNodeRows', 'workflowEdgeRows')
COUNT_PAIRS = (('resourceQueueRows', 'resourceQueue'), ('businessCalendarRows', 'businessCalendar'),
               ('batchWindowRows', 'batchWindow'), ('jobRows', 'job'),
               ('fileChannelRows', 'channel'), ('fileTemplateRows', 'fileTemplate'),
               ('workflowDefinitionRows', 'workflow'))
APPLY_COUNTS = tuple(f'{name}{suffix}' for _, name in COUNT_PAIRS for suffix in ('Inserted', 'Updated'))
DATA_SHEETS = ('resource_queue', 'business_calendar', 'batch_window', 'job_definition',
               'job_monitoring_policy', 'file_channel_config', 'file_template_config',
               'workflow_definition', 'workflow_node', 'workflow_edge', 'workflow_monitoring_policy')


class Stop(Exception):
    """输入无效、保护条件不满足或结果不明。"""


def local_origin(value):
    url = urllib.parse.urlsplit(value)
    if (url.scheme not in ('http', 'https') or url.hostname not in ('127.0.0.1', 'localhost', '::1')
            or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment):
        raise Stop('仅接受显式本机 HTTP(S) 地址，不能带路径、凭据或查询参数')
    return value.rstrip('/')


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.coverage-receipt-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            json.dump(data, out, ensure_ascii=False, indent=2)
            out.write('\n')
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def verify_counts(upload, preview, result):
    if (preview.get('invalidRows') != 0 or preview.get('issues') != []
            or preview.get('validRows') != preview.get('totalRows')
            or not preview.get('previewToken')):
        raise Stop('预览未完全通过，停止应用')
    for field in UPLOAD_COUNTS:
        if type(upload.get(field)) is not int or upload[field] < 0:
            raise Stop('上传行数无效')
    if result is None:
        return
    for source, name in COUNT_PAIRS:
        if (type(result.get(name + 'Updated')) is not int
                or type(result.get(name + 'Inserted')) is not int
                or result[name + 'Updated'] != 0 or result[name + 'Inserted'] != upload[source]):
            raise Stop('应用回执包含更新或新增数量不一致，必须人工核对')


def run(api, files, receipt_path, origin, operator, prefix, passwords, apply):
    hashes = {tenant: hashlib.sha256(files[tenant].read_bytes()).hexdigest() for tenant in TENANTS}
    identity = {'baseUrl': origin, 'operator': operator, 'accountSuffix': prefix, 'sha256': hashes,
                'authMode': 'local-bypass' if getattr(api, 'bypass_user', None) else 'authenticated'}
    if receipt_path.exists():
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        if any(receipt.get(k) != v for k, v in identity.items()):
            raise Stop('回执的服务、账号或文件哈希不一致，停止')
        states = receipt['tenants']
        if any(entry['stage'] in ('creating', 'applying') for entry in states.values()):
            raise Stop('上次创建或应用结果不明，须人工核对；禁止重发')
    else:
        states = {tenant: {'stage': 'new'} for tenant in TENANTS}
        receipt = {**identity, 'tenants': states}
    # 所有目标先检查一遍，防止后续已有租户导致前面目标被部分创建。
    for tenant in TENANTS:
        stage = states[tenant]['stage']
        if stage == 'new' and api.exists(tenant):
            raise Stop(f'目标租户 {tenant} 已存在，停止')
        if stage in ('created', 'applied'):
            detail = api.detail(tenant)
            if (detail.get('id') != states[tenant].get('id') or detail.get('tenantId') != tenant
                    or detail.get('createdBy') != operator):
                raise Stop(f'租户 {tenant} 与本次回执不一致，停止')
    if not apply:
        return receipt
    if not receipt_path.exists():
        save(receipt_path, receipt)
    for tenant in TENANTS:
        entry = states[tenant]
        if entry['stage'] == 'applied':
            continue
        if entry['stage'] == 'new':
            entry.update(stage='creating', createKey='coverage-create-' + uuid.uuid4().hex)
            save(receipt_path, receipt)
            created = api.create(tenant, tenant + prefix, passwords[tenant], entry['createKey'])
            if (created.get('tenantId') != tenant or not created.get('id')
                    or created.get('createdBy') != operator):
                raise Stop(f'租户 {tenant} 创建回执不匹配，必须人工核对')
            entry.update(stage='created', id=created['id'])
            save(receipt_path, receipt)
        if api.detail(tenant).get('id') != entry['id']:
            raise Stop(f'租户 {tenant} 身份变化')
        if hashlib.sha256(files[tenant].read_bytes()).hexdigest() != hashes[tenant]:
            raise Stop(f'{tenant} 配置包在执行期间改变')
        upload = api.upload(tenant, files[tenant], 'coverage-upload-' + uuid.uuid4().hex)
        preview = api.preview(tenant, upload['uploadToken'])
        verify_counts(upload, preview, None)
        # 在预览后读取可见配置；previewToken 的版本栅栏保护随后的并发变化。
        api.assert_empty_export(tenant)
        entry.update(stage='applying', applyKey='coverage-apply-' + uuid.uuid4().hex)
        save(receipt_path, receipt)
        result = api.apply(tenant, upload['uploadToken'], preview['previewToken'], entry['applyKey'])
        if result.get('tenantId') != tenant:
            raise Stop('应用回执租户不匹配，必须人工核对')
        verify_counts(upload, preview, result)
        entry.update(stage='applied', counts={k: result[k] for k in APPLY_COUNTS})
        save(receipt_path, receipt)
    return receipt


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Stop('服务返回重定向，停止发送凭据')


class Api:
    def __init__(self, origin):
        self.origin = origin
        self.cookies = http.cookiejar.CookieJar()
        self.bypass_user = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                 urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, method, path, payload=None, headers=None, raw=False):
        headers = dict(headers or {})
        if self.bypass_user:
            headers['X-Console-User'] = self.bypass_user
        req = urllib.request.Request(self.origin + path, data=payload, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=90) as response:
                data = response.read()
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            status = getattr(exc, 'code', None)
            raise Stop(f'{method} {path.split("?")[0]} 失败或结果不明（HTTP {status or "未知"}）') from None
        if raw:
            return data
        try:
            body = json.loads(data)
            if body.get('code') != 'SUCCESS':
                raise Stop(f'{method} 接口拒绝请求，结果需核对')
            return body['data']
        except (ValueError, KeyError, AttributeError):
            raise Stop(f'{method} 响应格式异常，结果需核对') from None

    def csrf_headers(self, tenant, key):
        csrf = next((c.value for c in self.cookies if c.name == 'XSRF-TOKEN'), None)
        if not csrf:
            raise Stop('缺少 CSRF cookie')
        return {'X-XSRF-TOKEN': csrf, 'X-Tenant-Id': tenant, 'Idempotency-Key': key}

    def login(self, username, password, allow_bypass):
        try:
            self.request('GET', '/api/console/auth/me')
        except Stop as e:
            if 'HTTP 401' not in str(e) and 'HTTP 403' not in str(e):
                raise
        else:
            if not allow_bypass:
                raise Stop('服务允许匿名访问；仅经明确允许的本地 bypass 模式可运行')
            self.bypass_user = username
        if not self.bypass_user:
            payload = json.dumps({'username': username, 'password': password}).encode()
            self.request('POST', '/api/console/auth/login', payload, {'Content-Type': 'application/json'})
        self.request('GET', '/api/console/auth/check', raw=True)
        me = self.request('GET', '/api/console/auth/me')
        if me.get('username') != username or 'ROLE_ADMIN' not in me.get('authorities', []):
            raise Stop('当前身份与管理员账号不匹配')

    def exists(self, tenant):
        page = 1
        while True:
            q = urllib.parse.urlencode({'keyword': tenant, 'pageNo': page, 'pageSize': 100})
            data = self.request('GET', '/api/console/tenants?' + q)
            items = data.get('items')
            if not isinstance(items, list) or type(data.get('hasMore')) is not bool:
                raise Stop('租户列表不完整')
            if any(item.get('tenantId') == tenant for item in items):
                return True
            if not data['hasMore']:
                return False
            page += 1

    def detail(self, tenant):
        return self.request('GET', '/api/console/tenants/' + tenant)

    def create(self, tenant, username, password, key):
        data = json.dumps({'tenantId': tenant, 'tenantName': tenant, 'username': username,
                           'password': password}).encode()
        headers = {**self.csrf_headers(tenant, key), 'Content-Type': 'application/json'}
        return self.request('POST', '/api/console/tenants', data, headers)['tenant']

    def upload(self, tenant, workbook, key):
        boundary = 'coverage-' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="package.xlsx"\r\n'
                'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode()
        body += workbook.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        headers = {**self.csrf_headers(tenant, key), 'Content-Type': 'multipart/form-data; boundary=' + boundary}
        return self.request('POST', '/api/console/config/tenant-package/excel/upload?tenantId=' + tenant,
                            body, headers)

    def preview(self, tenant, token):
        return self.request('GET', '/api/console/config/tenant-package/excel/preview/' +
                            urllib.parse.quote(token, safe=''), headers={'X-Tenant-Id': tenant})

    def assert_empty_export(self, tenant):
        content = self.request('GET', '/api/console/config/tenant-package/excel/export?tenantId=' + tenant,
                               headers={'X-Tenant-Id': tenant}, raw=True)
        try:
            workbook = load_workbook(io.BytesIO(content), read_only=True, data_only=True)
            try:
                if not all(name in workbook.sheetnames for name in DATA_SHEETS):
                    raise Stop('导出工作簿缺少完整数据 sheet')
                for name in DATA_SHEETS:
                    if any(any(cell is not None for cell in row)
                           for row in workbook[name].iter_rows(min_row=2, values_only=True)):
                        raise Stop(f'{tenant} 导出 {name} 存在配置，停止')
            finally:
                workbook.close()
        except Stop:
            raise
        except Exception:
            raise Stop('无法解析导出工作簿') from None

    def apply(self, tenant, upload, preview, key):
        data = json.dumps({'previewToken': preview, 'reason': '本地覆盖包初始化'}).encode()
        headers = {**self.csrf_headers(tenant, key), 'Content-Type': 'application/json'}
        return self.request('POST', '/api/console/config/tenant-package/excel/apply/' +
                            urllib.parse.quote(upload, safe=''), data, headers)


def credentials(path, suffix, resume):
    """凭据仅写仓库外私有文件，恢复时只读取，不覆盖已有文件。"""
    path = path.expanduser().absolute()
    repo = Path(__file__).resolve().parents[2]
    if path.resolve().is_relative_to(repo):
        raise Stop('凭据文件必须位于仓库外')
    if resume:
        if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600:
            raise Stop('恢复时凭据文件必须存在、非符号链接且权限为 0600')
        content = json.loads(path.read_text(encoding='utf-8'))
        if set(content) != set(TENANTS):
            raise Stop('凭据文件租户不匹配')
        for tenant in TENANTS:
            item = content[tenant]
            if item.get('username') != tenant + suffix or len(item.get('password', '')) < 12:
                raise Stop('凭据文件账号不匹配')
        return {tenant: content[tenant]['password'] for tenant in TENANTS}
    content = {tenant: {'username': tenant + suffix,
                        'password': 'aA1!' + secrets.token_urlsafe(32)} for tenant in TENANTS}
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as out:
        json.dump(content, out, ensure_ascii=False, indent=2)
        out.write('\n')
        out.flush()
        os.fsync(out.fileno())
    return {tenant: content[tenant]['password'] for tenant in TENANTS}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True, help='仅本机 Console 地址')
    parser.add_argument('--operator', required=True, help='平台管理员账号')
    parser.add_argument('--account-suffix', default='-admin', help='新租户管理员账号后缀')
    parser.add_argument('--credentials-file', type=Path, help='仓库外 0600 凭据文件；存在时只允许恢复本次回执')
    parser.add_argument('--prompt-password', action='store_true', help='交互输入初始密码，不创建凭据文件')
    parser.add_argument('--receipt', type=Path, required=True, help='本次私有回执路径')
    parser.add_argument('--package-dir', type=Path, default=Path(__file__).resolve().parents[2] /
                        'test-data/tenant-config-packages/coverage-examples')
    parser.add_argument('--apply', action='store_true', help='显式创建租户并应用配置包')
    parser.add_argument('--acknowledge-insert-only-limitation', action='store_true',
                        help='知悉接口无法保障不覆盖软删除或并发配置；需先人工核对目标环境')
    parser.add_argument('--allow-local-bypass', action='store_true', help='明确允许本机认证 bypass；不代表权限验收')
    args = parser.parse_args()
    try:
        origin = local_origin(args.base_url)
        files = {t: args.package_dir / f'{t}-coverage-example.xlsx' for t in TENANTS}
        for file in files.values():
            if not file.is_file():
                raise Stop(f'缺少覆盖包：{file}')
        if args.apply and not args.acknowledge_insert_only_limitation:
            raise Stop('后端缺少事务内 insert-only 保护，须先核对环境并显式确认限制')
        api = Api(origin)
        api.login(args.operator, '' if args.allow_local_bypass else getpass.getpass('平台管理员密码：'),
                  args.allow_local_bypass)
        lock = None
        if args.apply:
            if args.receipt.is_symlink():
                raise Stop('回执不能是符号链接')
            args.receipt.parent.mkdir(parents=True, exist_ok=True)
            lock_path = args.receipt.with_name(args.receipt.name + '.lock')
            lock_fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
            lock = os.fdopen(lock_fd, 'w')
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Stop('此回执正在被另一初始化进程使用') from None
        run(api, files, args.receipt, origin, args.operator, args.account_suffix, {}, False)
        passwords = {}
        if args.apply:
            if bool(args.credentials_file) == bool(args.prompt_password):
                raise Stop('须指定凭据文件或选择交互输入，两者选一')
            if args.prompt_password:
                for tenant in TENANTS:
                    passwords[tenant] = getpass.getpass(f'{tenant} 管理员初始密码：')
                    if len(passwords[tenant]) < 12:
                        raise Stop('初始密码至少 12 位')
            else:
                passwords = credentials(args.credentials_file, args.account_suffix, args.receipt.exists())
        run(api, files, args.receipt, origin, args.operator, args.account_suffix, passwords, args.apply)
        print('初始化完成' if args.apply else '租户存在性预检通过；未创建租户或导入配置')
        print('限制：导出不包含软删除项；当前 API 不支持事务内 insert-only，不能保证绝不覆盖。')
    except (Stop, OSError, ValueError, KeyError) as exc:
        print(f'已停止：{exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
