"""Read-only checks after upstream sync. Never calls a cloud model or executes production."""
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:48080/admin-api'
headers = {'Content-Type': 'application/json', 'tenant-id': '1'}
checks = []


def call(method, path, body=None):
    assert method == 'GET' or path in {'/system/auth/login', '/mes/insight/assistant'}
    request = Request(BASE + path, headers=headers, method=method,
                      data=None if body is None else json.dumps(body).encode())
    with urlopen(request, timeout=30) as response:
        result = json.load(response)
    assert result['code'] == 0, (path, result.get('code'), result.get('msg'))
    return result.get('data')


def check(name, passed):
    assert passed, name
    checks.append(name)


secrets = json.loads((ROOT / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
login = call('POST', '/system/auth/login', {'username': secrets['AdminUsername'], 'password': secrets['AdminPassword']})
headers['Authorization'] = 'Bearer ' + login['accessToken']
del secrets, login

config = call('GET', '/mes/assistant/model-config')
check('existing model configuration stays redacted', 'apiKey' not in config and 'secretCiphertext' not in config)
check('knowledge bases remain available', isinstance(call('GET', '/mes/assistant/bases'), list))
check('assistant sessions remain available', isinstance(call('GET', '/mes/assistant/sessions'), list))
jev = call('GET', '/mes/insight/jev/status')
check('Jev remains independently configured', 'stats' in jev)
check('production risks remain available', 'orders' in call('GET', '/mes/insight/risks'))
check('incident workflow remains available', 'cases' in call('GET', '/mes/insight/incidents'))

answer = call('POST', '/mes/insight/assistant', {'question': 'BROKY-FG-V2 的 BOM 标准成本是多少？', 'useModel': False})
check('configured business Agent recognizes cost intent', answer['topic'] == 'cost' and answer['mode'] == 'RULES')
row = answer['evidence']['items'][0]
product = call('GET', '/mes/cost/product?itemId=' + str(row['recordId']))
check('Agent uses canonical cost engine amounts', row['totalCost'] == product['totalCost'] and row['salePrice'] == product['salePrice'])
check('Agent distinguishes standards from actuals', row['sourceType'] == 'PRODUCT' and '标准' in row['costBasis'])
check('BOM details link to actual item id', row['recordId'] == product['itemId'] and row['bomLines'] == len(product['children']))
check('currency and tax basis are explicit', answer['evidence']['currency'] == 'CNY' and answer['evidence']['taxBasis'] == 'EXCLUSIVE')
check('cost query does not generate execution drafts', not answer['suggestions'])

missing = call('POST', '/mes/insight/assistant', {'question': 'UNKNOWN-CODE-123456 成本是多少？', 'useModel': False})
check('unknown code never invents a cost', missing['topic'] == 'cost' and not missing['evidence']['items'] and '具体编码' in missing['answer'])
check('local model settings preserved throughout checks', call('GET', '/mes/assistant/model-config') == config)
print(json.dumps({'passed': len(checks), 'checks': checks, 'cloudCalls': 0, 'businessWrites': 0}, ensure_ascii=False))
