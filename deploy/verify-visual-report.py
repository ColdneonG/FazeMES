#!/usr/bin/env python3
"""Real HTTP acceptance for the native visual report designer.

Creates only qa-visual-report-* definitions and retains them for UI inspection.
Existing reports and business documents are never modified. Credentials remain
inside this process; the output file contains assertion summaries and QA IDs only.
Run after deploying the updated backend; this script does not build or deploy it.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
REPORT_INVALID = 1_047_130_000
REPORT_CONFLICT = 1_047_130_002


class AcceptanceError(Exception):
    pass


class BusinessError(AcceptanceError):
    def __init__(self, code, status=200):
        self.code = code
        self.status = status
        super().__init__(f'Business rejection code={code}; HTTP={status}')


def require(condition, message):
    if not condition:
        raise AcceptanceError(message)


class Api:
    def __init__(self, base, tenant):
        self.base = base.rstrip('/')
        self.headers = {'Content-Type': 'application/json', 'tenant-id': str(tenant)}

    def request(self, method, path, body=None, *, binary=False, **params):
        url = self.base + path + ('?' + urlencode(params) if params else '')
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        status = 200
        try:
            with urlopen(Request(url, data=payload, headers=self.headers, method=method), timeout=90) as response:
                content = response.read()
                content_type = response.headers.get('Content-Type', '')
                status = response.status
        except HTTPError as error:
            status = error.code
            content = error.read()
            content_type = error.headers.get('Content-Type', '')
            if status >= 500:
                raise AcceptanceError(f'{method} {path}: unexpected server HTTP {status}') from None
        except (URLError, TimeoutError):
            raise AcceptanceError(f'{method} {path}: backend unavailable or request timed out') from None
        if binary and status == 200 and content_type.startswith('application/vnd.openxmlformats'):
            return content
        try:
            result = json.loads(content)
        except (ValueError, UnicodeDecodeError):
            raise AcceptanceError(f'{method} {path}: invalid JSON response; HTTP={status}') from None
        require(isinstance(result, dict) and type(result.get('code')) is int,
                f'{method} {path}: missing business response code')
        if result['code'] != 0:
            raise BusinessError(result['code'], status)
        require(status < 400, f'{method} {path}: HTTP failure with a success business code')
        return result.get('data')

    def authenticate(self):
        token = os.environ.get('MES_QA_ACCESS_TOKEN')
        if not token:
            try:
                credentials = json.loads((ROOT / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
                login = self.request('POST', '/system/auth/login', {
                    'username': credentials['AdminUsername'], 'password': credentials['AdminPassword']})
                token = login['accessToken']
            except (OSError, KeyError, ValueError):
                raise AcceptanceError('Local administrator credentials could not be loaded') from None
        require(isinstance(token, str) and bool(token), 'Administrator authentication failed')
        self.headers['Authorization'] = 'Bearer ' + token


def block(identifier, kind, x, y, width, height, *, title='', text='', dimension=None, metrics=None):
    return {
        'id': identifier, 'type': kind, 'x': x, 'y': y, 'width': width, 'height': height,
        'title': title, 'text': text,
        'style': {'fontSize': 24 if kind == 'text' else 12, 'color': '#183247',
                  'background': '#ffffff', 'align': 'left', 'bold': kind == 'text',
                  'border': kind == 'table'},
        'binding': {'dimension': dimension, 'metrics': metrics or [], 'summary': 'SUM', 'rowLimit': 6}
    }


def canvas_config():
    columns = [('code', '工单编码'), ('name', '工单名称'), ('quantity', '计划数量'),
               ('quantity_produced', '已产数量'), ('status', '状态'), ('request_date', '需求日期')]
    return {
        'dataset': 'workorders', 'chart': 'table',
        'columns': [{'field': field, 'title': title, 'width': 160, 'visible': True, 'aggregate': None}
                    for field, title in columns],
        'filters': [], 'groupBy': [], 'sortField': None, 'sortDirection': 'ASC',
        'canvas': {'schemaVersion': 1, 'paper': 'A4', 'orientation': 'portrait',
                   'background': '#ffffff', 'blocks': [
                       block('qa_text', 'text', 40, 32, 714, 48, text='QA 可视化业务报表'),
                       block('qa_metric', 'metric', 40, 104, 344, 92,
                             title='当前页计划数量', metrics=['quantity']),
                       block('qa_bar', 'bar', 40, 224, 344, 230,
                             title='计划与已产对比', dimension='code', metrics=['quantity', 'quantity_produced']),
                       block('qa_line', 'line', 410, 224, 344, 230,
                             title='当前页工单数量', dimension='code', metrics=['quantity']),
                       block('qa_pie', 'pie', 40, 480, 344, 210,
                             title='当前页计划数量构成', dimension='code', metrics=['quantity']),
                       block('qa_divider', 'divider', 40, 712, 714, 16),
                       block('qa_table', 'table', 40, 752, 714, 320, title='真实工单数据')
                   ]}
    }


def run(api, summary):
    checks = summary['checks']

    def check(label, condition):
        require(condition, label)
        checks.append({'check': label, 'passed': True})

    def reject(label, body, *, method='POST', path='/mes/report/preview', expected=REPORT_INVALID):
        try:
            api.request(method, path, body)
        except BusinessError as error:
            check(label, error.code == expected and error.status < 500)
            return
        raise AcceptanceError(label + ': unsafe request was accepted')

    datasets = api.request('GET', '/mes/report/datasets')
    workorders = next((dataset for dataset in datasets if dataset['key'] == 'workorders'), None)
    check('workorder_whitelist_available', workorders is not None)
    config = canvas_config()
    check('seven_component_types_in_fixture', {item['type'] for item in config['canvas']['blocks']}
          == {'text', 'table', 'metric', 'bar', 'line', 'pie', 'divider'})
    name = 'qa-visual-report-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    created = api.request('POST', '/mes/report/save', {'name': name, 'config': config})
    report_id = created['id']
    summary['retainedReports'].append({'id': report_id, 'name': name, 'purpose': 'canvas-version-acceptance'})
    check('initial_version_created', created['version'] == 1)
    first = api.request('GET', '/mes/report/get', id=report_id, version=1)
    check('all_layout_style_and_bindings_round_trip', first['config']['canvas'] == config['canvas'])
    preview = api.request('POST', '/mes/report/preview', {'id': report_id, 'version': 1, 'pageNo': 1, 'pageSize': 100})
    check('saved_canvas_queries_real_whitelisted_columns',
          [column['field'] for column in preview['columns']] == [column['field'] for column in config['columns']]
          and len(preview['rows']) <= 100)
    summary['previewRowCount'] = len(preview['rows'])
    api.request('PUT', '/mes/report/publish', id=report_id, version=1)
    check('version_one_published', api.request('GET', '/mes/report/get', id=report_id)['definition']['publishedVersion'] == 1)

    second_config = deepcopy(config)
    second_config['canvas']['blocks'][0]['text'] = 'QA 可视化业务报表 · 新版本'
    second_config['canvas']['blocks'][2]['x'] = 48
    second_config['canvas']['blocks'][2]['width'] = 336
    second_name = name + '-重命名-v2'
    second = api.request('POST', '/mes/report/save', {
        'id': report_id, 'name': second_name, 'expectedVersion': 1, 'config': second_config})
    check('second_version_saved', second['version'] == 2)
    old = api.request('GET', '/mes/report/get', id=report_id, version=1)
    current = api.request('GET', '/mes/report/get', id=report_id)
    check('historical_canvas_and_name_immutable', old['config']['canvas'] == config['canvas'] and old['definition']['name'] == name)
    check('new_layout_and_renamed_name_persisted', current['config']['canvas'] == second_config['canvas'] and current['definition']['name'] == second_name)
    check('published_pointer_remains_on_old_snapshot', current['definition']['publishedVersion'] == 1 and current['definition']['latestVersion'] == 2)
    check('published_version_can_be_read_explicitly', old['version'] == 1 and old['config']['canvas']['blocks'][2]['x'] == 40)
    check('two_immutable_versions_listed', {item['version'] for item in current['versions']} == {1, 2})
    reject('stale_save_is_rejected', {'id': report_id, 'name': name, 'expectedVersion': 1, 'config': config}, path='/mes/report/save', expected=REPORT_CONFLICT)

    workbook = api.request('POST', '/mes/report/export-excel', {'id': report_id, 'version': 2}, binary=True)
    with ZipFile(io.BytesIO(workbook)) as archive:
        required = {'[Content_Types].xml', 'xl/workbook.xml', 'xl/worksheets/sheet1.xml'}
        check('export_is_real_xlsx_zip', required <= set(archive.namelist()))
        sheet = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
        ns = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        header = sheet.find('.//m:sheetData/m:row', ns)
        check('xlsx_contains_query_headers', header is not None and len(header.findall('m:c', ns)) == len(config['columns']))

    bad = deepcopy(second_config)
    bad['canvas']['blocks'][0]['x'] = -1
    reject('negative_coordinate_rejected', {'config': bad})
    reject('invalid_layout_save_cannot_create_version', {'id': report_id, 'name': second_name, 'expectedVersion': 2, 'config': bad}, path='/mes/report/save')
    bad = deepcopy(second_config)
    bad['canvas']['blocks'][0]['x'] = 780
    reject('component_outside_page_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['columns'][2]['visible'] = False
    reject('binding_to_hidden_field_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['canvas']['blocks'][2]['binding']['dimension'] = 'tenant_id'
    reject('binding_to_non_whitelisted_field_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['canvas']['blocks'][1]['binding']['metrics'] = ['code']
    reject('text_field_as_metric_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['sql'] = 'SELECT * FROM system_user'
    reject('unknown_sql_provider_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['canvas']['blocks'][2]['binding']['script'] = 'alert(1)'
    reject('unknown_script_binding_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['canvas']['blocks'][0]['url'] = 'https://example.invalid/remote'
    reject('unknown_remote_url_rejected', {'config': bad})
    bad = deepcopy(second_config)
    bad['canvas']['blocks'][0]['style']['background'] = 'url(https://example.invalid/remote)'
    reject('css_url_in_color_rejected', {'config': bad})
    after = api.request('GET', '/mes/report/get', id=report_id)
    check('rejections_leave_saved_report_unchanged', after['version'] == 2 and after['config']['canvas'] == second_config['canvas'] and len(after['versions']) == 2)

    # The production UI duplicates via the existing save API with no id, rather than a special endpoint.
    copied_name = name + '-副本'
    copied = api.request('POST', '/mes/report/save', {'name': copied_name, 'config': deepcopy(second_config)})
    summary['retainedReports'].append({'id': copied['id'], 'name': copied_name, 'purpose': 'independent-copy'})
    copied_detail = api.request('GET', '/mes/report/get', id=copied['id'])
    check('copy_creates_independent_definition_and_version', copied['id'] != report_id and copied['version'] == 1 and copied_detail['config']['canvas'] == second_config['canvas'])
    check('copy_does_not_mutate_original', api.request('GET', '/mes/report/get', id=report_id)['definition']['latestVersion'] == 2)
    summary.update({'latestVersion': 2, 'publishedVersion': 1, 'retainedFixtures': True,
                    'viewerPermissionNote': 'HTTP assertions use an administrator token; published-only viewer authorization remains covered by isolated service/security regression tests.'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:48080/admin-api')
    parser.add_argument('--tenant', type=int, default=1)
    parser.add_argument('--output', type=Path, default=ROOT / '.runtime/visual-report-http-acceptance.json')
    args = parser.parse_args()
    summary = {'result': 'running', 'checks': [], 'retainedReports': [],
               'startedAt': datetime.now(timezone.utc).isoformat()}
    try:
        api = Api(args.base, args.tenant)
        api.authenticate()
        run(api, summary)
        summary['result'] = 'passed'
    except AcceptanceError as error:
        summary['result'] = 'failed'
        summary['failure'] = str(error)
    except Exception as error:
        summary['result'] = 'failed'
        summary['failure'] = f'Unexpected {type(error).__name__}; no request, response or credential details retained'
    summary['finishedAt'] = datetime.now(timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"{summary['result']}: {len(summary['checks'])} assertions; retained QA reports={len(summary['retainedReports'])}")
    if summary['result'] != 'passed':
        print(summary['failure'], file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
