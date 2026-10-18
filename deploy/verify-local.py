"""Check authenticated MES list APIs and local attachment storage."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import re
import sys
import uuid
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from urllib.error import HTTPError

root = Path(__file__).resolve().parents[1]
secrets = json.loads((root / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
base = 'http://127.0.0.1:48080/admin-api'
headers = {'Content-Type': 'application/json', 'tenant-id': '1'}
def request(path, payload=None, extra=None):
    data = None if payload is None else json.dumps(payload).encode()
    with urlopen(Request(base + path, data=data, headers=headers | (extra or {})), timeout=30) as response:
        return json.load(response)
login = request('/system/auth/login', {'username': secrets['AdminUsername'], 'password': secrets['AdminPassword']})
assert login['code'] == 0, login
headers['Authorization'] = 'Bearer ' + login['data']['accessToken']
permission = request('/system/auth/get-permission-info')
assert permission['code'] == 0, permission
menus = permission['data']['menus']
assert any(m['path'] == '/mes' for m in menus), 'MES menu missing'
controllers = root / 'backend/yudao-module-mes/src/main/java/cn/iocoder/yudao/module/mes/controller/admin'
paths = []
for source in controllers.rglob('*Controller.java'):
    text = source.read_text(encoding='utf-8')
    route = re.search(r'@RequestMapping\("([^"]+)"\)', text)
    page_method = re.search(r'@GetMapping\("/page"\)([\s\S]*?)(?:\{)', text)
    if route and page_method:
        params = {'pageNo': 1, 'pageSize': 1}
        page_type = re.search(r'(\w+PageReqVO)\s+\w+', page_method.group(1))
        if page_type:
            sources = list(controllers.rglob(page_type.group(1) + '.java'))
            if sources:
                vo = sources[0].read_text(encoding='utf-8')
                for field in re.finditer(r'@NotNull\b[\s\S]*?private\s+(?:Long|Integer)\s+(\w+)\s*;', vo):
                    # Querying an absent parent is intentional for unseeded domains;
                    # it still verifies the real mapper and generated table columns.
                    params[field.group(1)] = {'itemId': 900003, 'workOrderId': 900501, 'qcType': 1}.get(field.group(1), 0)
        paths.append(route.group(1) + '/page?' + urlencode(params))
def check(path):
    try:
        result = request(path)
        return {'path': path, 'code': result['code'], 'message': result.get('msg'), 'total': (result.get('data') or {}).get('total')}
    except HTTPError as error:
        return {'path': path, 'code': error.code, 'message': error.read().decode()[:500]}
    except Exception as error:
        return {'path': path, 'code': -1, 'message': str(error)}
with ThreadPoolExecutor(max_workers=5) as pool:
    results = list(pool.map(check, sorted(set(paths))))
failed = [r for r in results if r['code'] not in (0, 400)]
required_parameters = [r for r in results if r['code'] == 400]
# A non-sensitive, disposable fixture exercises upload and unauthenticated retrieval.
content = b'MES local attachment storage acceptance\n'
boundary = 'MesAcceptance' + uuid.uuid4().hex
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="mes-storage-check.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode() + content + f'\r\n--{boundary}--\r\n'.encode())
with urlopen(Request(base + '/infra/file/upload', data=body, headers=headers | {'Content-Type': f'multipart/form-data; boundary={boundary}'}), timeout=30) as response:
    uploaded = json.load(response)
assert uploaded['code'] == 0, uploaded
assert uploaded['data'].startswith('http://127.0.0.1:48080/admin-api/infra/file/29/get/'), uploaded
with urlopen(uploaded['data'], timeout=10) as response:
    assert response.read() == content, 'Uploaded attachment differs from downloaded content'
report = {'timestamp': datetime.now().isoformat(), 'login': True, 'mesMenu': True, 'pagesChecked': len(results), 'pagesPassed': sum(r['code'] == 0 for r in results), 'requiresAdditionalParameters': required_parameters, 'failures': failed, 'attachmentStorage': 'local upload and download passed', 'pages': results}
(root / '.runtime/local-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k != 'pages'}, ensure_ascii=False, indent=2))
sys.exit(bool(failed))
