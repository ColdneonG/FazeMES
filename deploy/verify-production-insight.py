"""Real HTTP regression for the decision center. Dedicated synthetic rows are removed in finally.

No existing order, stock, SOP or device row is changed. Model calls are never made by this test.
"""
from pathlib import Path
from datetime import datetime, timedelta
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import json
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:48080/admin-api'
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
tag = 'INSIGHT-CHECK-' + uuid.uuid4().hex[:10]
checks = []
owned = {'orders': [], 'tasks': [], 'feedback': [], 'andons': [], 'cases': [], 'drafts': []}
headers = {'Content-Type': 'application/json', 'tenant-id': '1'}

def sql(statement):
    completed = subprocess.run([MYSQL, '--defaults-extra-file=' + str(ROOT / '.runtime/mysql-client.local.ini'),
        '--database=yudao_mes', '--batch', '--skip-column-names', '--execute=' + statement], capture_output=True, encoding='utf-8')
    if completed.returncode:
        raise RuntimeError(completed.stderr)
    return completed.stdout.strip()

def insert(table, columns, values, category):
    result = int(sql(f'INSERT INTO {table} ({columns}) VALUES ({values}); SELECT LAST_INSERT_ID();'))
    owned[category].append(result)
    return result

def call(method, path, data=None, authenticated=True):
    request_headers = headers if authenticated else {'Content-Type': 'application/json', 'tenant-id': '1'}
    payload = None if data is None else json.dumps(data).encode()
    try:
        with urlopen(Request(BASE + path, data=payload, headers=request_headers, method=method), timeout=90) as response:
            return json.load(response)
    except HTTPError as error:
        return json.loads(error.read())

def ok(method, path, data=None):
    result = call(method, path, data)
    assert result['code'] == 0, (path, result)
    return result.get('data')

def check(name, condition):
    assert condition, name
    checks.append({'check': name, 'passed': True})

try:
    secrets = json.loads((ROOT / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
    login = ok('POST', '/system/auth/login', {'username': secrets['AdminUsername'], 'password': secrets['AdminPassword']})
    headers['Authorization'] = 'Bearer ' + login['accessToken']
    del secrets, login
    check('anonymous access rejected', call('GET', '/mes/insight/risks', authenticated=False)['code'] != 0)
    permission = ok('GET', '/system/auth/get-permission-info')
    check('new menu and permissions registered', 'mes:insight:query' in permission['permissions'] or '*:*:*' in permission['permissions'])
    station = int(sql('SELECT id FROM mes_md_workstation WHERE tenant_id=1 AND deleted=0 ORDER BY id LIMIT 1'))
    product = int(sql('SELECT id FROM mes_md_item WHERE tenant_id=1 AND deleted=0 ORDER BY id LIMIT 1'))
    process = int(sql('SELECT id FROM mes_pro_process WHERE tenant_id=1 AND deleted=0 ORDER BY id LIMIT 1'))
    route = int(sql('SELECT id FROM mes_pro_route WHERE tenant_id=1 AND deleted=0 ORDER BY id LIMIT 1'))
    tomorrow = datetime.now().replace(hour=8, minute=0, second=0, microsecond=0) + timedelta(days=1)
    while tomorrow.weekday() >= 5:
        tomorrow += timedelta(days=1)
    when = tomorrow.strftime('%Y-%m-%d %H:%M:%S')
    due = (tomorrow + timedelta(days=5)).strftime('%Y-%m-%d %H:%M:%S')
    order_a = insert('mes_pro_work_order', 'tenant_id,code,name,product_id,quantity,status,request_date', f"1,'{tag}-A','验收临时工单A',{product},10,1,'{due}'", 'orders')
    order_b = insert('mes_pro_work_order', 'tenant_id,code,name,product_id,quantity,status,request_date', f"1,'{tag}-B','验收临时工单B',{product},10,1,'{due}'", 'orders')
    foreign = insert('mes_pro_work_order', 'tenant_id,code,name,product_id,quantity,status,request_date', f"2,'{tag}-FOREIGN','外租户临时样本',{product},10,1,'{due}'", 'orders')
    for label, order in [('A', order_a), ('B', order_b)]:
        insert('mes_pro_task', 'tenant_id,code,name,work_order_id,workstation_id,route_id,process_id,item_id,quantity,duration,status,start_time,end_time',
            f"1,'{tag}-{label}','验收临时任务{label}',{order},{station},{route},{process},{product},10,1,0,'{when}','{due}'", 'tasks')
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    for index, bad in enumerate([0, 2]):
        insert('mes_pro_feedback', 'tenant_id,code,work_order_id,task_id,workstation_id,process_id,item_id,feedback_quantity,unqualified_quantity,status,feedback_time',
            f"1,'{tag}-FB{index}',{order_a},{owned['tasks'][0]},{station},{process},{product},10,{bad},4,'{now}'", 'feedback')
    andon = insert('mes_pro_andon_record', 'tenant_id,work_order_id,workstation_id,reason,status,user_id', f"1,{order_a},{station},'{tag}-待验证异常',0,1", 'andons')
    risk = ok('GET', '/mes/insight/risks')
    indexed = {r['id']: r for r in risk['orders']}
    check('risk board excludes foreign tenants', foreign not in indexed)
    check('risk explanations reference real andon', any(r['type'] == 'ANDON' for r in indexed[order_a]['reasons']))
    check('scheduled order has explainable forecast', indexed[order_a]['forecastAt'] is not None)
    simulation = ok('POST', '/mes/insight/simulate', {'workOrderId': order_b, 'dailyHours': 10, 'hourlyCost': 80, 'manualHours': 4})
    check('three scenarios returned', len(simulation['scenarios']) == 3)
    before = {r['id']: r for r in simulation['scenarios'][0]['impacts']}
    priority = {r['id']: r for r in simulation['scenarios'][1]['impacts']}
    check('priority advances selected order and exposes displaced order', priority[order_b]['forecastAt'] < before[order_b]['forecastAt'] and priority[order_a]['shiftHours'] > 0)
    check('simulation does not write official task schedule', sql(f'SELECT start_time FROM mes_pro_task WHERE id={owned["tasks"][0]}') == when)
    check('simulation rejects foreign work order', call('POST', '/mes/insight/simulate', {'workOrderId': foreign, 'dailyHours': 10, 'hourlyCost': 80, 'manualHours': 4})['code'] != 0)
    insert('mes_pro_task', 'tenant_id,code,name,work_order_id,workstation_id,route_id,process_id,item_id,quantity,duration,status,start_time',
        f"1,'{tag}-INVALID','验收不完整工时任务',{order_a},{station},{route},{process},{product},10,0,0,'{when}'", 'tasks')
    check('partial work order cannot produce a misleading simulation', call('POST', '/mes/insight/simulate', {'workOrderId': order_a, 'dailyHours': 10, 'hourlyCost': 80, 'manualHours': 4})['code'] != 0)
    quality = ok('GET', '/mes/insight/quality?days=30&minSample=10')
    fixture_groups = [g for g in quality['groups'] if any(str(e['code']).startswith(tag) for e in g['evidence'])]
    check('quality analysis includes completed feedback evidence', fixture_groups and any(g['rejected'] >= 2 for g in fixture_groups))
    flow = ok('GET', '/mes/insight/flow?days=30')
    check('flow analysis links task evidence', any(any(str(e['code']).startswith(tag) for e in s['evidence']) for s in flow['stations']))
    timeline = ok('GET', f'/mes/insight/timeline?workOrderId={order_a}')
    check('timeline shows real feedback and andon events', any(e['type'] == '完成报工' for e in timeline['events']) and any(e['type'] == '安灯呼叫' for e in timeline['events']))
    check('foreign timeline rejected', call('GET', f'/mes/insight/timeline?workOrderId={foreign}')['code'] != 0)
    case = ok('POST', '/mes/insight/incident', {'title': tag, 'category': 'QUALITY', 'andonId': andon, 'ownerId': 1})
    owned['cases'].append(case)
    def move(version, state, cause='', action='', evidence=''):
        return call('PUT', '/mes/insight/incident', {'id': case, 'version': version, 'status': state, 'ownerId': 1,
            'rootCause': cause, 'correctiveAction': action, 'evidence': evidence, 'note': '验收临时状态流转'})
    check('cannot close unprocessed incident', move(0, 'CLOSED')['code'] != 0)
    check('incident can enter in-progress', move(0, 'IN_PROGRESS')['code'] == 0)
    check('stale edit rejected', move(0, 'IN_PROGRESS')['code'] != 0)
    check('verification requires confirmed cause and action', move(1, 'VERIFYING')['code'] != 0)
    check('case can enter verification', move(1, 'VERIFYING', '已确认测试原因', '已完成测试措施')['code'] == 0)
    check('source andon stays open before verification passed', sql(f'SELECT status FROM mes_pro_andon_record WHERE id={andon}') == '0')
    check('closing requires evidence', move(2, 'CLOSED', '原因', '措施')['code'] != 0)
    check('verified closure succeeds', move(2, 'CLOSED', '原因', '措施', '测试复检通过')['code'] == 0)
    check('source andon closed only after verified closure', sql(f'SELECT status FROM mes_pro_andon_record WHERE id={andon}') == '1')
    check('audit trail contains accepted changes only', len(ok('GET', f'/mes/insight/incident-history?id={case}')) == 4)
    check('invalid related foreign order rejected', call('POST', '/mes/insight/incident', {'title': 'bad ref', 'category': 'OTHER', 'workOrderId': foreign})['code'] != 0)
    answer = ok('POST', '/mes/insight/assistant', {'question': f'{tag}-A 的交期风险是什么？', 'useModel': False})
    check('business assistant uses scoped live facts without a model', answer['mode'] == 'RULES' and all(r['id'] == order_a for r in answer['evidence']['orders']) and answer['suggestions'])
    draft = ok('POST', '/mes/insight/draft', {'title': tag, 'content': '人工确认的测试建议', 'workOrderId': order_a})
    owned['drafts'].append(draft)
    converted = ok('POST', f'/mes/insight/draft-convert?id={draft}')
    owned['cases'].append(converted)
    check('draft conversion is idempotent', ok('POST', f'/mes/insight/draft-convert?id={draft}') == converted)
    check('new overview is readable', isinstance(ok('GET', '/mes/insight/incidents')['cases'], list))
finally:
    # Only remove exact primary keys inserted by this run, including immutable audit test fixtures.
    for case in owned['cases']:
        sql(f'DELETE FROM mes_insight_incident_event WHERE tenant_id=1 AND incident_id={case}')
        sql(f'DELETE FROM mes_insight_incident WHERE tenant_id=1 AND id={case}')
    for draft in owned['drafts']:
        sql(f'DELETE FROM mes_insight_action_draft WHERE tenant_id=1 AND id={draft}')
    for key, table in [('feedback','mes_pro_feedback'),('andons','mes_pro_andon_record'),('tasks','mes_pro_task'),('orders','mes_pro_work_order')]:
        for row_id in owned[key]:
            sql(f'DELETE FROM {table} WHERE id={row_id}')

report = {'timestamp': datetime.now().isoformat(), 'checksPassed': len(checks), 'fixturesCleaned': True, 'checks': checks}
(ROOT / '.runtime/production-insight-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
