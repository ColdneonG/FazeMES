"""Add seven days of clearly labelled dashboard exercises through normal APIs.

No reset, direct business-table writes, clock rewriting or automatic daily job.
Existing documents are retained. Stable codes and saved task ownership allow a
rerun to resume without repeating stock posting or approval. Historical report
business dates are synthetic; audit and inventory timestamps remain real.
"""
from __future__ import annotations

import argparse
from datetime import timedelta
from decimal import Decimal
import json
import subprocess
import uuid

from reset_test_data import Api, Context, DUMP, RUNTIME, serial

PREFIX = 'TST-DASH-'
REMARK = '生产大屏合成演练；非真实生产。历史业务日期回填，审批与库存审计时间保持实际执行时间。'
SCENARIOS = [{'code': 'DASHBOARD-7D', 'name': '生产大屏七日跨车间场景',
              'coverage': '全部启用车间、七日连续排产与已完成报工、多城市客户、同工位设备、过程检验草稿与活动安灯；通过真实领料耗料及产出接口生成。'}]


def stamp(value):
    return value.replace(microsecond=0).isoformat()


def scenario_times(now, day):
    start = day.replace(hour=7, minute=0, second=0)
    reported = day.replace(hour=7, minute=30, second=0)
    if day.date() == now.date():
        start = min(start, now.replace(minute=0, second=0))
        reported = min(reported, now)
    return start, reported


def require_local(ctx):
    row = ctx.query('SELECT @@port port,@@datadir datadir,DATABASE() db')[0]
    expected = (RUNTIME / 'mysql/data').resolve().as_posix().lower().rstrip('/')
    actual = row['datadir'].replace('\\', '/').lower().rstrip('/')
    if row['port'] != 3308 or row['db'] != 'yudao_mes' or actual != expected:
        raise RuntimeError('Dashboard fixtures require the dedicated project-local test database')
    marker = json.loads((RUNTIME / 'testdata-state.json').read_text(encoding='utf8'))
    if marker.get('suite') != 'fazemes-test-suite-v1':
        raise RuntimeError('Dashboard fixtures require the documented synthetic test suite')


def backup(ctx):
    from testdata_backups import inspect_dump
    path = RUNTIME / 'backups' / f'dashboard-before-{ctx.now:%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:6]}.sql'
    result = subprocess.run([str(DUMP), f'--defaults-extra-file={RUNTIME / "mysql-client.local.ini"}',
        '--protocol=TCP', '--host=127.0.0.1', '--port=3308', '--single-transaction',
        '--routines', '--triggers', '--events', '--hex-blob', '--no-tablespaces',
        '--set-gtid-purged=OFF', f'--result-file={path}', 'yudao_mes'], capture_output=True)
    if result.returncode:
        raise RuntimeError('Dashboard backup failed; no fixture mutation started')
    info = dict(path=str(path), **inspect_dump(path))
    path.with_suffix('.json').write_text(json.dumps(info, indent=2), encoding='utf8')
    return info


def seed_api(api, ctx):
    require_local(ctx)
    now = ctx.now.replace(microsecond=0)
    workshops = ctx.query('SELECT id,name FROM mes_md_workshop WHERE tenant_id=1 AND deleted=0 AND status=0 ORDER BY id')
    raw = ctx.ids.get('raw', 900101)
    admin = ctx.ids.get('admin', 1)

    def existing(table, **fields):
        keys = list(fields)
        rows = ctx.query(f'SELECT * FROM `{table}` WHERE tenant_id=1 AND deleted=0 AND '
                         + ' AND '.join(f'`{key}`=%s' for key in keys), [fields[key] for key in keys])
        if len(rows) > 1:
            raise RuntimeError(f'Ambiguous dashboard fixture identity in {table}')
        return rows[0] if rows else None

    def ensure(table, path, identity, body):
        found = existing(table, **identity)
        return found['id'] if found else api.post(path + '/create', body)

    sample = api.get('/mes/md/item/get', id=ctx.ids.get('product', 900103))
    item = ensure('mes_md_item', '/mes/md/item', {'code': PREFIX + 'PRODUCT'}, {
        'code': PREFIX + 'PRODUCT', 'name': '大屏演练·精密衬套',
        'specification': '合成单工序演练，每件消耗一件试制毛坯',
        'unitMeasureId': sample['unitMeasureId'], 'itemTypeId': sample['itemTypeId'],
        'safeStockFlag': False, 'batchFlag': True, 'highValue': False, 'remark': REMARK})
    if not existing('mes_md_item_batch_config', item_id=item):
        flags = {name + 'Flag': False for name in (
            'produceDate', 'expireDate', 'receiptDate', 'vendor', 'client', 'salesOrderCode',
            'purchaseOrderCode', 'workOrder', 'task', 'workstation', 'tool', 'mold', 'lotNumber', 'qualityStatus')}
        api.post('/mes/md/item-batch-config/save', {'itemId': item, **flags, 'workOrderFlag': True, 'produceDateFlag': True})
    ensure('mes_md_product_bom', '/mes/md/product-bom', {'item_id': item, 'bom_item_id': raw},
           {'itemId': item, 'bomItemId': raw, 'quantity': 1, 'status': 0, 'remark': REMARK})
    process = ensure('mes_pro_process', '/mes/pro/process', {'code': PREFIX + 'PROCESS'},
        {'code': PREFIX + 'PROCESS', 'name': '大屏演练·精密加工', 'status': 0, 'attention': REMARK})
    route = ensure('mes_pro_route', '/mes/pro/route', {'code': PREFIX + 'ROUTE'},
        {'code': PREFIX + 'ROUTE', 'name': '大屏演练·单工序路线', 'description': REMARK})
    ensure('mes_pro_route_process', '/mes/pro/route-process', {'route_id': route, 'process_id': process},
        {'routeId': route, 'processId': process, 'sort': 1, 'keyFlag': True, 'checkFlag': False,
         'linkType': 0, 'prepareTime': 0, 'waitTime': 0, 'colorCode': '#35d5c0'})
    ensure('mes_pro_route_product', '/mes/pro/route-product', {'route_id': route, 'item_id': item},
        {'routeId': route, 'itemId': item, 'quantity': 1, 'productionTime': 6, 'timeUnitType': 'MINUTE'})
    ensure('mes_pro_route_product_bom', '/mes/pro/route-product-bom',
        {'route_id': route, 'process_id': process, 'product_id': item, 'item_id': raw},
        {'routeId': route, 'processId': process, 'productId': item, 'itemId': raw, 'quantity': 1})
    if api.get('/mes/md/item/get', id=item)['status'] != 0:
        api.put('/mes/md/item/update-status', id=item, status=0)
    if api.get('/mes/pro/route/get', id=route)['status'] != 0:
        api.put('/mes/pro/route/update-status', id=route, status=0)
    # A real template is required even for manual, unsubmitted first-inspection drafts.
    template = ensure('mes_qc_template', '/mes/qc/template', {'code': PREFIX + 'QC'},
        {'code': PREFIX + 'QC', 'name': '大屏演练·首检模板', 'types': [2], 'ipqcTypes': [2], 'status': 0, 'remark': REMARK})
    ensure('mes_qc_template_item', '/mes/qc/template/item', {'template_id': template, 'item_id': item},
        {'templateId': template, 'itemId': item, 'quantityCheck': 1, 'quantityUnqualified': 0,
         'criticalRate': 0, 'majorRate': 0, 'minorRate': 0})
    indicator = ensure('mes_qc_indicator', '/mes/qc/indicator', {'code': PREFIX + 'APPEARANCE'},
        {'code': PREFIX + 'APPEARANCE', 'name': '大屏演练·外观', 'type': 1, 'resultType': 3,
         'tool': '目视', 'remark': REMARK})
    ensure('mes_qc_template_indicator', '/mes/qc/template/indicator', {'template_id': template, 'indicator_id': indicator},
        {'templateId': template, 'indicatorId': indicator, 'checkMethod': '外观目视首检：无毛刺及磕碰'})
    config = ensure('mes_pro_andon_config', '/mes/pro/andon-config', {'reason': '大屏演练·物料补给支援'},
        {'reason': '大屏演练·物料补给支援', 'level': 1, 'handlerUserId': admin, 'remark': REMARK})
    machine_type = ctx.scalar('SELECT MIN(id) FROM mes_dv_machinery_type WHERE tenant_id=1 AND deleted=0')
    clients = ctx.query("SELECT id FROM mes_md_client WHERE tenant_id=1 AND deleted=0 AND code BETWEEN 'TST-CLI-001' AND 'TST-CLI-008' AND address LIKE '%%市%%' ORDER BY id")
    if not clients or not machine_type:
        raise RuntimeError('Missing city clients or equipment types in the synthetic baseline')
    portfolio = []
    for index, workshop in enumerate(workshops):
        wid = workshop['id']
        station = ensure('mes_md_workstation', '/mes/md-workstation', {'code': PREFIX + f'WS-{wid}'},
            {'code': PREFIX + f'WS-{wid}', 'name': workshop['name'] + '·大屏演练工位',
             'workshopId': wid, 'processId': process, 'status': 0, 'remark': REMARK})
        machine = ensure('mes_dv_machinery', '/mes/dv/machinery', {'code': PREFIX + f'DV-{wid}'},
            {'code': PREFIX + f'DV-{wid}', 'name': workshop['name'] + '·演练加工台',
             'machineryTypeId': machine_type, 'workshopId': wid, 'status': 2, 'remark': REMARK})
        ensure('mes_md_workstation_machine', '/mes/md-workstation-machine', {'workstation_id': station, 'machinery_id': machine},
            {'workstationId': station, 'machineryId': machine, 'quantity': 1, 'remark': REMARK})
        for age in range(6, -1, -1):
            day = now - timedelta(days=age)
            start, reported = scenario_times(now, day)
            key = PREFIX + f'W{wid}-{day:%Y%m%d}'
            plan = 20
            produced = 8 + (day.date().toordinal() + index * 3) % 11
            order = ensure('mes_pro_work_order', '/mes/pro/work-order', {'code': key}, {
                'code': key, 'name': workshop['name'] + '·大屏演练', 'type': 1,
                'orderSourceType': 2, 'orderSourceCode': key, 'productId': item,
                'quantity': plan, 'clientId': clients[(index + day.date().toordinal()) % len(clients)]['id'],
                'batchCode': key, 'requestDate': stamp(day.replace(hour=23, minute=0, second=0)),
                'parentId': 0, 'remark': REMARK})
            if api.get('/mes/pro/work-order/get', id=order)['status'] == 0:
                api.put('/mes/pro/work-order/confirm', id=order)
            task = ensure('mes_pro_task', '/mes/pro/task', {'work_order_id': order, 'workstation_id': station}, {
                'workOrderId': order, 'workstationId': station, 'routeId': route, 'processId': process,
                'itemId': item, 'quantity': plan, 'startTime': stamp(start),
                'duration': 1, 'remark': REMARK, 'colorCode': '#35d5c0'})
            issue = ensure('mes_wm_product_issue', '/mes/wm/product-issue', {'code': key + '-ISS'}, {
                'code': key + '-ISS', 'name': '大屏演练·毛坯领料', 'workOrderId': order,
                'workstationId': station, 'requiredTime': stamp(now), 'remark': REMARK})
            issue_state = api.get('/mes/wm/product-issue/get', id=issue)
            if issue_state['status'] == 0:
                ensure('mes_wm_product_issue_line', '/mes/wm/product-issue-line', {'issue_id': issue, 'item_id': raw},
                    {'issueId': issue, 'itemId': raw, 'quantity': plan, 'remark': REMARK})
                api.put('/mes/wm/product-issue/submit', id=issue)
            if api.get('/mes/wm/product-issue/get', id=issue)['status'] == 2:
                line = existing('mes_wm_product_issue_line', issue_id=issue, item_id=raw)['id']
                stocks = api.page('/mes/wm/material-stock', itemId=raw, warehouseId=900001)
                picks = ctx.query('SELECT material_stock_id,SUM(quantity) quantity FROM mes_wm_product_issue_detail WHERE tenant_id=1 AND deleted=0 AND issue_id=%s GROUP BY material_stock_id', (issue,))
                picked = {row['material_stock_id']: row['quantity'] for row in picks}
                remaining = Decimal(plan) - sum(picked.values(), Decimal(0))
                for stock in sorted(stocks, key=lambda row: (row.get('receiptTime') or '', row['id'])):
                    if stock['itemId'] != raw or stock['warehouseId'] != 900001 or stock.get('frozen') or stock.get('qualityHold'):
                        continue
                    take = min(remaining, Decimal(str(stock['quantity'])) - picked.get(stock['id'], Decimal(0)))
                    if take <= 0:
                        continue
                    api.post('/mes/wm/product-issue-detail/create', {
                        'issueId': issue, 'lineId': line, 'itemId': raw, 'materialStockId': stock['id'],
                        'quantity': str(take), 'batchId': stock.get('batchId'), 'batchCode': stock.get('batchCode'),
                        'warehouseId': stock['warehouseId'], 'locationId': stock['locationId'], 'areaId': stock['areaId']})
                    remaining -= take
                    if remaining == 0:
                        break
                if remaining:
                    raise RuntimeError(f'Insufficient priced raw stock for {key}')
            state = api.get('/mes/wm/product-issue/get', id=issue)['status']
            if state == 2:
                api.put('/mes/wm/product-issue/stock', id=issue)
            if api.get('/mes/wm/product-issue/get', id=issue)['status'] == 3:
                api.put('/mes/wm/product-issue/finish', id=issue)
            rejected = 1 if day.date().toordinal() % 3 == index % 3 else 0
            report = ensure('mes_pro_feedback', '/mes/pro/feedback', {'code': key + '-FB'}, {
                'code': key + '-FB', 'type': 2, 'workstationId': station, 'routeId': route,
                'processId': process, 'workOrderId': order, 'taskId': task, 'itemId': item,
                'feedbackQuantity': produced, 'qualifiedQuantity': produced - rejected,
                'unqualifiedQuantity': rejected, 'uncheckQuantity': 0, 'laborScrapQuantity': rejected,
                'materialScrapQuantity': 0, 'otherScrapQuantity': 0,
                'feedbackUserId': admin, 'approveUserId': admin,
                'feedbackTime': stamp(reported), 'sources': [], 'remark': REMARK})
            state = api.get('/mes/pro/feedback/get', id=report)['status']
            if state == 0:
                api.put('/mes/pro/feedback/submit', id=report)
            if api.get('/mes/pro/feedback/get', id=report)['status'] == 2:
                api.put('/mes/pro/feedback/approve', id=report)
            saved = api.get('/mes/pro/feedback/get', id=report)
            assert saved['status'] == 4 and saved['flowComplete'], (key, saved)
            portfolio.append({'date': day.date().isoformat(), 'workshopId': wid, 'stationId': station,
                              'workOrderId': order, 'taskId': task, 'feedbackId': report})
            if age == 0:
                ensure('mes_qc_ipqc', '/mes/qc/ipqc', {'code': key + '-QC'}, {
                    'code': key + '-QC', 'name': '大屏演练·下一批首检待办', 'type': 2,
                    'workOrderId': order, 'taskId': task, 'workstationId': station, 'processId': process,
                    'itemId': item, 'checkQuantity': 1, 'qualifiedQuantity': 1, 'unqualifiedQuantity': 0,
                    'laborScrapQuantity': 0, 'materialScrapQuantity': 0, 'otherScrapQuantity': 0,
                    'inspectorUserId': admin, 'remark': REMARK + '草稿仅为待办，预填值不代表检验放行。'})
                ensure('mes_pro_andon_record', '/mes/pro/andon-record', {'work_order_id': order, 'config_id': config}, {
                    'configId': config, 'workstationId': station, 'workOrderId': order,
                    'processId': process, 'userId': admin, 'remark': REMARK})
        print(f'Dashboard exercises ready: {workshop["name"]}', flush=True)
    ctx.ids['dashboard_portfolio'] = portfolio
    return portfolio


def verify_api(api, ctx):
    today = ctx.now.date().isoformat()
    cases = [('all', {})]
    cases.extend((row['name'], {'workshopId': row['id']}) for row in ctx.query(
        'SELECT id,name FROM mes_md_workshop WHERE tenant_id=1 AND deleted=0 AND status=0 ORDER BY id'))
    cases.extend((row['code'], {'workstationId': row['id']}) for row in ctx.query(
        "SELECT id,code FROM mes_md_workstation WHERE tenant_id=1 AND deleted=0 AND code LIKE 'TST-DASH-WS-%'"))
    checks = []
    for name, scope in cases:
        result = api.get('/mes/dashboard/production', date=today, **scope)
        assert result['taskCount'] > 0 and result['feedbackCount'] > 0, (name, 'missing current production')
        assert len(result['trend']) == 7, (name, 'missing daily trend')
        assert result['devices'] and result['tasks'] and result['workOrders'], (name, 'missing linked resources')
        assert result['regionalDistribution']['locatedOrderCount'] > 0, (name, 'missing mapped orders')
        assert result['pendingInspection'] > 0 and result['activeAndon'] > 0, (name, 'missing current pending work')
        if name == 'all':
            assert len(result['regionalDistribution']['cities']) >= min(5, len(cases) // 2), 'missing multi-city coverage'
        checks.append({'scope': name, 'passed': True, 'produced': result['produced'],
                       'target': result['target'], 'trendDays': len(result['trend']),
                       'mappedCities': len(result['regionalDistribution']['cities'])})
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    import msvcrt
    with (RUNTIME / 'maintenance.lock').open('a+b') as lock:
        lock.seek(0, 2)
        if lock.tell() == 0:
            lock.write(b'0'); lock.flush()
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise RuntimeError('Database maintenance or backup is running; retry after it completes') from None
        ctx = Context()
        api = None
        try:
            require_local(ctx)
            info = None if args.verify_only else backup(ctx)
            api = Api(ctx)
            portfolio = [] if args.verify_only else seed_api(api, ctx)
            checks = verify_api(api, ctx)
            # Global conservation checks do not assert fixed suite counts.
            from reset_test_data import checks_sql
            for name, sql in checks_sql(ctx):
                if name.startswith(('reference:', 'nonnegative_stock', 'stock_equals_signed_ledger',
                                    'feedback_quantity_balance', 'order_progress_limits', 'feedback_source_balances')):
                    assert ctx.scalar(sql) == 0, name
            output = {'result': 'PASS', 'anchor': ctx.now.isoformat(), 'backup': info,
                      'portfolio': portfolio, 'checks': checks, 'businessWritesViaApiOnly': True}
            (RUNTIME / 'dashboard-fixtures-verification.json').write_text(
                json.dumps(output, ensure_ascii=False, indent=2, default=serial), encoding='utf8')
            print(json.dumps({'result': 'PASS', 'checkedScopes': len(checks), 'newOrExistingScenarios': len(portfolio)}))
        finally:
            if api:
                api.post('/system/auth/logout', {})
            ctx.conn.rollback(); ctx.conn.close()


if __name__ == '__main__':
    main()
