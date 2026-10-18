"""Local HTTP acceptance. No real Jev requests; only this test's synthetic incident is removed."""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import json
import subprocess
import uuid
import hashlib

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:48080/admin-api'
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
headers = {'Content-Type': 'application/json', 'tenant-id': '1'}
checks = []
tag = 'JEV-CHECK-' + uuid.uuid4().hex
advice_ids = []

def sql(statement):
    result = subprocess.run([MYSQL, '--defaults-extra-file=' + str(ROOT / '.runtime/mysql-client.local.ini'),
        '--database=yudao_mes', '--batch', '--skip-column-names', '--execute=' + statement], capture_output=True, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

def call(method, path, data=None, anonymous=False):
    request_headers = {'Content-Type': 'application/json', 'tenant-id': '1'} if anonymous else headers
    request = Request(BASE + path, data=None if data is None else json.dumps(data).encode(), headers=request_headers, method=method)
    try:
        with urlopen(request, timeout=15) as response:
            return json.load(response)
    except HTTPError as error:
        return json.loads(error.read())

def ok(method, path, data=None):
    response = call(method, path, data)
    assert response['code'] == 0, (path, response.get('msg'))
    return response.get('data')

def check(name, value):
    assert value, name
    checks.append({'check': name, 'passed': True})

try:
    secrets = json.loads((ROOT / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
    login = ok('POST', '/system/auth/login', {'username': secrets['AdminUsername'], 'password': secrets['AdminPassword']})
    headers['Authorization'] = 'Bearer ' + login['accessToken']
    actor = login['userId']
    del secrets, login
    for method, path, body in [
        ('GET', '/mes/insight/jev/status', None), ('GET', '/mes/insight/jev/config', None),
        ('PUT', '/mes/insight/jev/config', {'model': 'jev-1.13.0', 'enabled': False}),
        ('POST', '/mes/insight/jev/test', None), ('POST', '/mes/insight/jev/suggest', {'title': 'test'})]:
        check('anonymous denied: ' + method + ' ' + path, call(method, path, body, True)['code'] != 0)
    config = ok('GET', '/mes/insight/jev/config')
    check('official decision endpoint exposed', config['endpoint'] == 'https://api.typesafe.ai/v1/systemone')
    check('configuration never returns raw key', 'apiKey' not in config and 'secret_ciphertext' not in config)
    before = sql('SELECT COUNT(*) FROM mes_insight_jev_triage WHERE tenant_id=1')
    status = ok('GET', '/mes/insight/jev/status')
    check('pilot counters and departments available', 'reviewed' in status['stats'] and isinstance(status['departments'], list))
    if not config['apiKeyConfigured']:
        check('enabling without a Jev key rejected', call('PUT', '/mes/insight/jev/config', {'model': 'jev-1.13.0', 'enabled': True})['code'] != 0)
    if not config['configured']:
        check('unconfigured analysis explains manual fallback', call('POST', '/mes/insight/jev/suggest', {'title': '尺寸异常', 'description': '本机验收示例'})['code'] != 0)
        check('unconfigured connection test rejected', call('POST', '/mes/insight/jev/test')['code'] != 0)
    check('invalid model rejected', call('PUT', '/mes/insight/jev/config', {'model': 'not-a-jev-model', 'enabled': False})['code'] != 0)
    check('blank incident input rejected', call('POST', '/mes/insight/jev/suggest', {'title': ' '})['code'] != 0)
    data = ok('GET', '/mes/insight/incidents')
    check('existing incidents remain readable', isinstance(data['cases'], list))
    department = data['departments'][0] if data['departments'] else None
    payload = {'title': tag, 'category': 'QUALITY', 'description': '本机验收合成记录，结束后清理'}
    if department:
        payload['departmentId'] = department['id']
    incident_id = ok('POST', '/mes/insight/incident', payload)
    incident = next(row for row in ok('GET', '/mes/insight/incidents')['cases'] if row['id'] == incident_id)
    check('manual incident works without cloud inference', incident['category'] == 'QUALITY' and incident['status'] == 'OPEN')
    check('responsible user never automatically assigned', incident.get('ownerId') is None)
    if department:
        check('confirmed department persists and displays', incident['departmentId'] == department['id'] and incident['departmentName'] == department['name'])
    check('manual creation not counted as Jev evaluation', sql('SELECT COUNT(*) FROM mes_insight_jev_triage WHERE tenant_id=1') == before)
    # SQL fixtures exercise actual MySQL confirmation/expiry semantics, without claiming cloud inference.
    fixture_title = tag + '-fixture'
    fixture_description = '合成验证数据'
    digest = hashlib.sha256((fixture_title + '\0' + fixture_description).encode()).hexdigest()
    advice_id = int(sql("INSERT INTO mes_insight_jev_triage(tenant_id,creator,input_hash,model_name,category,category_confidence,department_confidence,create_time) "
        f"VALUES(1,{int(actor)},'{digest}','TEST_FIXTURE_NOT_JEV','QUALITY',0.8,0.5,TIMESTAMPADD(HOUR,-25,CURRENT_TIMESTAMP)); SELECT LAST_INSERT_ID();"))
    advice_ids.append(advice_id)
    fixture = {'title': fixture_title, 'description': fixture_description, 'category': 'QUALITY', 'triageId': advice_id}
    check('expired advice rejected using actual MySQL timestamp semantics', call('POST', '/mes/insight/incident', fixture)['code'] != 0)
    sql(f'UPDATE mes_insight_jev_triage SET create_time=CURRENT_TIMESTAMP WHERE id={advice_id} AND tenant_id=1')
    check('edited description cannot consume older advice', call('POST', '/mes/insight/incident', {**fixture, 'description': '修改后的文字'})['code'] != 0)
    confirmed = ok('POST', '/mes/insight/incident', fixture)
    check('human confirmation binds stored advice atomically', int(sql(f'SELECT incident_id FROM mes_insight_jev_triage WHERE id={advice_id} AND tenant_id=1')) == confirmed)
    check('advice cannot create duplicate incident', call('POST', '/mes/insight/incident', fixture)['code'] != 0)
    print(json.dumps({'passed': len(checks), 'cloudModelTested': False}, ensure_ascii=False))
finally:
    ids = sql(f"SELECT id FROM mes_insight_incident WHERE tenant_id=1 AND title IN('{tag}','{tag}-fixture')")
    for record in ids.splitlines():
        record_id = int(record)
        sql(f'DELETE FROM mes_insight_incident_assignment WHERE tenant_id=1 AND incident_id={record_id}; '
            f'DELETE FROM mes_insight_incident_event WHERE tenant_id=1 AND incident_id={record_id}; '
            f'DELETE FROM mes_insight_incident WHERE tenant_id=1 AND id={record_id}')
    for advice_id in advice_ids:
        sql(f'DELETE FROM mes_insight_jev_triage WHERE tenant_id=1 AND id={int(advice_id)}')
    (ROOT / '.runtime/jev-triage-verification.json').write_text(json.dumps({'checks': checks, 'cloudModelTested': False, 'syntheticIncidentCleaned': True}, ensure_ascii=False, indent=2), encoding='utf-8')
