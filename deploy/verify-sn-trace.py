#!/usr/bin/env python3
"""HTTP acceptance for a prepared first-route, FINISHED feedback with available material shares.

Creates one explicitly named SN and immutable station snapshot. Run only against a test fixture:
  $env:MES_TOKEN='<access token>'
  python deploy/verify-sn-trace.py --work-order-id 123 --feedback-id 456 --sn-code QA-SN-20261018-1
No credentials are read from local secret files and no business rows are deleted.
"""
import argparse
import json
import os
import uuid
from decimal import Decimal
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:48080/admin-api')
    parser.add_argument('--tenant-id', default='1')
    parser.add_argument('--work-order-id', type=int, required=True)
    parser.add_argument('--feedback-id', type=int, required=True)
    parser.add_argument('--sn-code', required=True)
    parser.add_argument('--token-env', default='MES_TOKEN')
    args = parser.parse_args()
    token = os.environ.get(args.token_env)
    if not token:
        parser.error(f'Set {args.token_env} to a test administrator access token')
    headers = {'Authorization': 'Bearer ' + token, 'tenant-id': args.tenant_id,
               'Content-Type': 'application/json'}

    def call(method, path, body=None, query=None, expected_error=None):
        url = args.base_url.rstrip('/') + path
        if query:
            url += '?' + urlencode(query)
        request = Request(url, headers=headers, method=method,
                          data=None if body is None else json.dumps(body).encode('utf-8'))
        try:
            with urlopen(request, timeout=30) as response:
                result = json.loads(response.read())
        except HTTPError as error:
            raise AssertionError(f'{method} {path}: unexpected HTTP {error.code}') from None
        if expected_error is None:
            assert result.get('code') == 0, f'{method} {path}: {result.get("msg", result.get("code"))}'
            return result.get('data')
        assert 1_047_120_000 <= expected_error <= 1_047_120_999, 'Use an explicit SN trace business error'
        assert result.get('code') == expected_error, (
            f'{path}: expected SN business error {expected_error}, received {result.get("code")}: {result.get("msg")}')
        return result

    base = '/mes/pro/sn-trace'
    sources = call('GET', base + '/consume-options', query={'feedbackId': args.feedback_id})
    assert sources, 'Use a finished first-route feedback with actual WIP consumption'
    needed = {}
    materials = []
    for source in sources:
        line = source['consumeLineId']
        remaining = needed.get(line, Decimal(str(source['unitQuantity'])))
        amount = min(remaining, Decimal(str(source['availableQuantity'])))
        if amount > 0:
            materials.append({'consumeDetailId': source['consumeDetailId'], 'quantity': float(amount)})
        needed[line] = remaining - amount
    assert all(q == 0 for q in needed.values()), 'Fixture needs one complete unallocated unit'
    stock_ids = sorted({s['materialStockId'] for s in sources})
    before_stock = {i: call('GET', '/mes/wm/material-stock/get', query={'id': i})['quantity'] for i in stock_ids}
    identity = {'workOrderId': args.work_order_id, 'snCode': args.sn_code}
    trace_id = call('POST', base + '/register', identity)
    assert call('POST', base + '/register', identity) == trace_id, 'Registration must be idempotent'
    payload = {'requestKey': str(uuid.uuid4()), 'traceId': trace_id,
               'feedbackId': args.feedback_id, 'materials': materials}
    invalid = {**payload, 'requestKey': str(uuid.uuid4()), 'materials': [
        {'consumeDetailId': 9223372036854770000, 'quantity': 1}]}
    call('POST', base + '/station', invalid, expected_error=1_047_120_004)
    oversized = {**payload, 'requestKey': str(uuid.uuid4()), 'materials': [
        {**m, 'quantity': m['quantity'] * 2} for m in materials]}
    call('POST', base + '/station', oversized, expected_error=1_047_120_004)
    station_id = call('POST', base + '/station', payload)
    assert call('POST', base + '/station', payload) == station_id, 'Station retry duplicated snapshot'
    call('POST', base + '/station', oversized | {'requestKey': payload['requestKey']}, expected_error=1_047_120_006)
    call('POST', base + '/station', payload | {'requestKey': str(uuid.uuid4())}, expected_error=1_047_120_005)
    detail = call('GET', base + '/get', query={'id': trace_id})
    assert detail['sn']['snCode'] == args.sn_code
    assert detail['sn']['workOrderId'] == args.work_order_id
    snapshots = [s for s in detail['stations'] if s['id'] == station_id]
    assert len(snapshots) == 1 and snapshots[0]['feedbackId'] == args.feedback_id
    assert snapshots[0]['operatorId'], 'Operator must be derived from authenticated user'
    assert len(snapshots[0]['materials']) == len(materials)
    source_by_id = {s['consumeDetailId']: s for s in sources}
    for material in snapshots[0]['materials']:
        source = source_by_id[material['consumeDetailId']]
        assert material['itemId'] == source['itemId'] and material.get('batchId') == source.get('batchId')
        if source.get('batchId') or source.get('batchCode'):
            query = {'batchId': source['batchId']} if source.get('batchId') else {'batchCode': source['batchCode']}
            affected = call('GET', base + '/affected', query=query)
            assert any(s['id'] == trace_id for s in affected), 'Reverse trace missed the registered SN'
    after_sources = call('GET', base + '/consume-options', query={'feedbackId': args.feedback_id})
    # This endpoint revalidates every original consume detail against actual OUT
    # movements. A duplicated consume movement fails that server-side equality.
    after_by_id = {s['consumeDetailId']: s for s in after_sources}
    for material in materials:
        source = source_by_id[material['consumeDetailId']]
        after = after_by_id[material['consumeDetailId']]
        assert Decimal(str(source['availableQuantity'])) - Decimal(str(after['availableQuantity'])) == Decimal(str(material['quantity']))
    after_stock = {i: call('GET', '/mes/wm/material-stock/get', query={'id': i})['quantity'] for i in stock_ids}
    assert before_stock == after_stock, 'SN allocation must never consume stock a second time'
    print(json.dumps({'passed': True, 'traceId': trace_id, 'stationId': station_id,
                      'snCode': args.sn_code, 'checks': ['register', 'actual-sources', 'unit-share',
                      'idempotency', 'conflicts', 'forward', 'reverse', 'no-second-stock-consumption']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
