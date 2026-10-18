#!/usr/bin/env python3
"""HTTP acceptance for report versions, injection rejection, XLSX and real dashboard.

Run after deploying 20261018_reports_dashboard.sql and the new backend. Creates
one named report with two immutable versions; no business documents are changed.
Credentials and response data are never printed. Fixtures remain for inspection.
"""
import argparse
from copy import deepcopy
from datetime import datetime
import io
import json
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
REPORT_INVALID = 1_047_130_000
REPORT_CONFLICT = 1_047_130_002
TENANT_FORBIDDEN = 403


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class BusinessError(Exception):
    """A valid JSON business rejection, distinct from HTTP/transport failures."""
    def __init__(self, code):
        self.code = code
        super().__init__(f'business code={code}')


def expect_business_rejection(api, expected_code, method, path, body=None, **params):
    try:
        api.request(method, path, body, **params)
    except BusinessError as error:
        require(error.code == expected_code,
                f'{method} {path}: expected business code={expected_code}, got code={error.code}')
        return error.code
    # HTTPError, URLError, invalid JSON and unexpected exceptions must propagate.
    raise RuntimeError(f'{method} {path}: unsafe request unexpectedly accepted')


class Api:
    def __init__(self, base, tenant):
        self.base = base.rstrip('/')
        self.headers = {'Content-Type': 'application/json', 'tenant-id': str(tenant)}

    def request(self, method, path, body=None, binary=False, **params):
        url = self.base + path + ('?' + urlencode(params) if params else '')
        payload = None if body is None else json.dumps(body).encode('utf-8')
        try:
            with urlopen(Request(url, data=payload, headers=self.headers, method=method), timeout=90) as response:
                data = response.read()
                if binary and response.headers.get('Content-Type', '').startswith('application/vnd.openxmlformats'):
                    return data
                result = json.loads(data)
        except HTTPError as exc:
            raise RuntimeError(f'{method} {path}: HTTP {exc.code}') from None
        except URLError:
            raise RuntimeError(f'{method} {path}: backend unavailable') from None
        require(isinstance(result, dict), f'{method} {path}: invalid JSON response shape')
        code = result.get('code')
        require(type(code) is int, f'{method} {path}: missing or invalid business code')
        if code != 0:
            raise BusinessError(code)
        return result.get('data')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:48080/admin-api')
    parser.add_argument('--tenant', type=int, default=1)
    parser.add_argument('--other-tenant', type=int, default=2)
    parser.add_argument('--output', type=Path, default=ROOT / '.runtime/report-dashboard-acceptance.json')
    args = parser.parse_args()
    require(args.other_tenant != args.tenant, 'Cross-tenant check requires a different other-tenant')
    api = Api(args.base, args.tenant)
    secrets = json.loads((ROOT / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
    login = api.request('POST', '/system/auth/login', {'username': secrets['AdminUsername'], 'password': secrets['AdminPassword']})
    api.headers['Authorization'] = 'Bearer ' + login['accessToken']
    checks = []

    def check(name, condition):
        require(condition, name)
        checks.append({'check': name, 'passed': True})

    def rejected(name, method, path, body=None, *, expected_code, **params):
        code = expect_business_rejection(api, expected_code, method, path, body, **params)
        checks.append({'check': name, 'passed': True, 'businessCode': code})

    datasets = api.request('GET', '/mes/report/datasets')
    check('five_business_datasets', {'workorders', 'feedback', 'inventory', 'quality', 'equipmentTasks'} <= {d['key'] for d in datasets})
    config = {'dataset': 'workorders', 'chart': 'bar', 'columns': [
        {'field': 'status', 'title': '状态', 'width': 120, 'visible': True},
        {'field': 'quantity', 'title': '计划数量', 'width': 160, 'visible': True, 'aggregate': 'SUM'}],
        'filters': [], 'groupBy': ['status'], 'sortField': 'status', 'sortDirection': 'ASC'}
    published_name = 'HTTP验收-' + datetime.now().strftime('%Y%m%d-%H%M%S')
    saved = api.request('POST', '/mes/report/save', {'name': published_name, 'config': config})
    report_id = saved['id']
    api.request('PUT', '/mes/report/publish', id=report_id, version=1)
    config['columns'][1]['title'] = '计划数量-v2'
    api.request('POST', '/mes/report/save', {'id': report_id, 'name': 'HTTP验收-版本记录', 'config': config, 'expectedVersion': 1})
    old = api.request('GET', '/mes/report/get', id=report_id, version=1)
    check('immutable_version_one', old['config']['columns'][1]['title'] == '计划数量')
    check('immutable_version_name', old['definition']['name'] == published_name)
    current = api.request('GET', '/mes/report/get', id=report_id)
    check('published_version_remains_pinned', current['definition']['latestVersion'] == 2 and current['definition']['publishedVersion'] == 1)
    rejected('stale_save_rejected', 'POST', '/mes/report/save', {'id': report_id, 'name': 'stale', 'config': config, 'expectedVersion': 1}, expected_code=REPORT_CONFLICT)
    rejected('stale_publish_rejected', 'PUT', '/mes/report/publish', id=report_id, version=1, expected_code=REPORT_CONFLICT)
    preview = api.request('POST', '/mes/report/preview', {'id': report_id, 'version': 1, 'pageNo': 1, 'pageSize': 100})
    check('aggregate_preview_shape', len(preview['columns']) == 2 and len(preview['rows']) <= 100)
    xlsx = api.request('POST', '/mes/report/export-excel', {'id': report_id, 'version': 1}, binary=True)
    with ZipFile(io.BytesIO(xlsx)) as workbook:
        check('real_xlsx_workbook', 'xl/workbook.xml' in workbook.namelist() and 'xl/worksheets/sheet1.xml' in workbook.namelist())
    bad = deepcopy(config)
    bad['columns'][0]['field'] = 'status); DROP TABLE mes_pro_work_order; --'
    rejected('field_injection_rejected', 'POST', '/mes/report/preview', {'config': bad}, expected_code=REPORT_INVALID)
    bad = deepcopy(config)
    bad['columns'][1]['aggregate'] = 'java.lang.Runtime'
    rejected('java_expression_rejected', 'POST', '/mes/report/preview', {'config': bad}, expected_code=REPORT_INVALID)
    bad = deepcopy(config)
    bad['dataset'] = 'system_user'
    rejected('unapproved_dataset_rejected', 'POST', '/mes/report/preview', {'config': bad}, expected_code=REPORT_INVALID)
    bad = deepcopy(config)
    bad['filters'] = [{'field': 'status', 'operator': 'eq', 'value': '1 OR 1=1'}]
    rejected('typed_filter_rejected', 'POST', '/mes/report/preview', {'config': bad}, expected_code=REPORT_INVALID)
    rejected('preview_row_cap', 'POST', '/mes/report/preview', {'config': config, 'pageSize': 201}, expected_code=REPORT_INVALID)
    api.request('PUT', '/mes/report/publish', id=report_id, version=2)
    republished = api.request('GET', '/mes/report/get', id=report_id, version=2)
    check('name_follows_republished_version', republished['definition']['name'] == 'HTTP验收-版本记录' and republished['definition']['publishedVersion'] == 2)
    prior_tenant = api.headers['tenant-id']
    api.headers['tenant-id'] = str(args.other_tenant)
    try:
        # TenantSecurityWebFilter returns JSON code 403 for token/header tenant mismatch.
        # Actual HTTP errors, including HTTP 403/500, still fail this acceptance run.
        rejected('cross_tenant_version_inaccessible', 'GET', '/mes/report/get', id=report_id, version=1, expected_code=TENANT_FORBIDDEN)
    finally:
        api.headers['tenant-id'] = prior_tenant
    board = api.request('GET', '/mes/dashboard/production', date=datetime.now().strftime('%Y-%m-%d'))
    check('dashboard_real_contract', {'produced', 'qualified', 'target', 'devices', 'tasks', 'pendingInspection', 'activeAndon', 'updatedAt', 'basis'} <= board.keys())
    check('no_fabricated_oee', 'oee' not in {k.lower() for k in board})
    check('dashboard_quantities_nonnegative', all(float(board[k]) >= 0 for k in ('produced', 'qualified', 'target', 'pendingInspection', 'activeAndon')))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'result': 'passed', 'reportId': report_id, 'checks': checks, 'retainedFixtures': True}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'passed: {len(checks)} checks; retained report {report_id}')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Messages intentionally omit raw response data, request payloads and credentials.
        print('Acceptance failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
