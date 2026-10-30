#!/usr/bin/env python3
# 用途：只在导入私有副本中归一化已知 SIM API 渠道，保持原工作簿不变。
# 用法：python3 scripts/sim/prepare-tenant-fixture.py --source INPUT.xlsx --output COPY.xlsx --mockserver-base-url URL。
"""仅改目标单元格，保留其它 ZIP 成员内容与成员顺序，确保导入指纹稳定。"""
import argparse
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
from zipfile import ZipFile

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
REL_NS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
CHANNEL_PATHS = {('tb', 'tb_api_push'): '/tb/callback',
                 ('tb', 'tb_api_ingest'): '/tb/ingest',
                 ('tc', 'tc_api_risk_push'): '/tc/ingest'}
OLD_KEYS = {'url', 'method', 'tokenHeader'}


def cell_text(cell, strings):
    if cell.get('t') == 's':
        return strings[int(cell.findtext('m:v', namespaces=NS))]
    inline = cell.find('m:is', NS)
    if inline is not None:
        return ''.join(inline.itertext())
    return cell.findtext('m:v', default='', namespaces=NS)


def replace_cell(xml, reference, value):
    pattern = rf'(<c\b[^>]*\br="{re.escape(reference)}"[^>]*>).*?(</c>)'

    def replacement(match):
        opening = match.group(1)
        if re.search(r'\bt="[^"]*"', opening):
            opening = re.sub(r'\bt="[^"]*"', 't="inlineStr"', opening)
        else:
            opening = opening[:-1] + ' t="inlineStr">'
        return opening + '<is><t>' + escape(value) + '</t></is>' + match.group(2)

    result, count = re.subn(pattern, replacement, xml, flags=re.S)
    if count != 1:
        raise ValueError(f'目标单元格不唯一或不存在: {reference}')
    return result


def prepare(source, output, base_url):
    parsed = urlsplit(base_url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ValueError('模拟器地址必须为不带凭据和路径的 origin')
    if source.resolve() == output.resolve():
        raise ValueError('必须使用私有副本，不能覆盖原配置包')
    with ZipFile(source) as archive:
        relationships = {item.get('Id'): item.get('Target') for item in ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))}
        workbook = ET.fromstring(archive.read('xl/workbook.xml'))
        sheet = next(item for item in workbook.findall('m:sheets/m:sheet', NS) if item.get('name') == 'file_channel_config')
        target = relationships[sheet.get('{' + REL_NS + '}id')]
        member = target.lstrip('/') if target.startswith('/') else 'xl/' + target
        strings = []
        if 'xl/sharedStrings.xml' in archive.namelist():
            shared = ET.fromstring(archive.read('xl/sharedStrings.xml'))
            strings = [''.join(item.itertext()) for item in shared.findall('m:si', NS)]
        original = archive.read(member)
        xml = original.decode('utf-8')
        rows = ET.fromstring(original).findall('m:sheetData/m:row', NS)
        headers = {re.sub(r'\d+$', '', cell.get('r')): cell_text(cell, strings) for cell in rows[0].findall('m:c', NS)}
        required = {'tenant_id', 'channel_code', 'channel_type', 'target_endpoint', 'config_json'}
        if not required <= set(headers.values()):
            raise ValueError('渠道配置缺少必需列')
        for row in rows[1:]:
            cells = {headers.get(re.sub(r'\d+$', '', cell.get('r'))): cell for cell in row.findall('m:c', NS)}
            values = {key: cell_text(cell, strings) for key, cell in cells.items()}
            identity = (values.get('tenant_id'), values.get('channel_code'))
            if identity not in CHANNEL_PATHS:
                continue
            if values.get('channel_type') not in ('API', 'API_PUSH'):
                raise ValueError('已知模拟渠道类型不符合 API 契约')
            config = json.loads(values['config_json'])
            if not isinstance(config, dict):
                raise ValueError('渠道 config_json 必须是对象')
            endpoint = base_url.rstrip('/') + CHANNEL_PATHS[identity]
            canonical = {key: value for key, value in config.items() if key not in OLD_KEYS}
            canonical['target_endpoint'] = endpoint
            if values['target_endpoint'] != endpoint:
                xml = replace_cell(xml, cells['target_endpoint'].get('r'), endpoint)
            if config != canonical:
                value = json.dumps(canonical, ensure_ascii=False, separators=(',', ':'))
                xml = replace_cell(xml, cells['config_json'].get('r'), value)
        updated = xml.encode('utf-8')
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, 'wb') as stream:
                if original == updated:
                    stream.write(source.read_bytes())
                else:
                    with ZipFile(stream, 'w') as result:
                        result.comment = archive.comment
                        for info in archive.infolist():
                            content = updated if info.filename == member else archive.read(info.filename)
                            result.writestr(info, content)
        except Exception:
            output.unlink(missing_ok=True)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mockserver-base-url', required=True)
    args = parser.parse_args()
    prepare(args.source, args.output, args.mockserver_base_url)


if __name__ == '__main__':
    main()
