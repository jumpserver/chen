#!/usr/bin/env python3
"""Read-only acceptance of an isolated PostgreSQL-backed Chen image."""
import csv
import io
import json
import sys
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
import zipfile

base, expected_db2 = sys.argv[1:]
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = ''
def request(method, path, data=None, status=200):
    payload = None if data is None else json.dumps(data).encode()
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + '/chen/api/v1' + path, data=payload, headers=headers, method=method)
    for attempt in range(100):
        try:
            with opener.open(req, timeout=10) as response:
                assert response.status == status, (method, path, response.status)
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            try:
                code = json.loads(error.read()).get('error', {}).get('code', '')
            except (ValueError, AttributeError):
                code = ''
            # Execution completion can briefly precede the final journal flush
            # and console unlock. Metadata is explicitly retryable while busy.
            if error.code == 409 and code == 'console_busy' and path.endswith('/completion') and attempt < 99:
                time.sleep(.05)
                continue
            raise AssertionError((method, path, error.code, code)) from None

for attempt in range(60):
    try:
        capabilities = request('GET', '/capabilities')
        break
    except (OSError, AssertionError):
        time.sleep(1)
else:
    raise AssertionError('Chen image listener did not start')
assert capabilities['mode'] == 'single'
assert capabilities['transport'] == 'http+sse'
assert ('db2' in capabilities['protocols']) == (expected_db2 == '1')
owner = request('POST', '/sessions', {'selector': 'image-pg', 'request_id': str(uuid.uuid4())}, 201)
token = owner['token']
sid = owner['session']['id']
console = request('POST', '/sessions/' + sid + '/consoles', {'database': 'chen_test'}, 201)
cid = console['id']
def submit(sql):
    return request('POST', '/consoles/' + cid + '/executions', {'sql': sql, 'request_id': str(uuid.uuid4())}, 202)
def wait(eid, expected='succeeded'):
    for attempt in range(1200):
        execution = request('GET', '/executions/' + eid)
        if execution.get('finished_at'):
            assert execution['status'] == expected, (execution['status'], execution.get('error'))
            assert execution['audit_status'] == 'recorded'
            return execution
        time.sleep(.05)
    raise AssertionError('execution timed out')
execution = wait(submit("SELECT 9007199254740993::bigint, repeat('中文😀',10000), NULL, ''")['id'])
result = request('GET', '/results/' + execution['result_ids'][0] + '/rows')
assert result['rows'][0] == ['9007199254740993', '中文😀' * 10000, None, '']
# The retired synchronous export must not bypass the audited export-job path.
for format in ('csv', 'json'):
    legacy = urllib.request.Request(base + '/chen/api/v1/results/' + execution['result_ids'][0] + '/export?format=' + format, headers={'Authorization': 'Bearer ' + token})
    try:
        opener.open(legacy, timeout=10)
        raise AssertionError('retired export route is still available')
    except urllib.error.HTTPError as error:
        assert error.code == 404
        assert error.headers.get('Content-Disposition') is None
for marked, expected in [
    ('SELECT * FROM pg_catalog.pg_cl|', 'pg_class'),
    ('SELECT c.| FROM pg_catalog.pg_class c', 'relname'),
    ('SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_attribute a ON a.|', 'attrelid'),
]:
    offset = marked.index('|'); document = marked.replace('|', '')
    result = request('POST', '/consoles/' + cid + '/completion', {'document': document, 'position_utf16': offset, 'document_version': 1})
    assert expected in [item['label'] for item in result['items']], ('completion missing', expected)
execution = submit('SELECT pg_sleep(15)')
time.sleep(.15)
request('POST', '/executions/' + execution['id'] + '/cancel', {}, 202)
wait(execution['id'], 'canceled')
# Run the exact image's full-query exporter beyond the interactive row window.
# Only generated values are used, and download tickets are sent in POST bodies.
export_sql = "SELECT i, '中文😀' AS unicode_value, 9007199254740993::bigint AS precise_value, '=1+1' AS formula_text, NULL AS null_value, '' AS empty_value FROM generate_series(1,10001) AS i"
for format in ('csv', 'xlsx'):
    execution = request('POST', '/consoles/' + cid + '/exports', {'sql': export_sql, 'format': format, 'request_id': str(uuid.uuid4())}, 202)
    wait(execution['id'])
    xid = execution['export_id']
    for attempt in range(100):
        export = request('GET', '/exports/' + xid)
        if export['ready']:
            break
        time.sleep(.05)
    assert export['ready'] and export['rows'] == 10001 and not export['truncated']
    ticket = request('POST', '/exports/' + xid + '/download-ticket', {}, 201)
    form = urllib.parse.urlencode(ticket['fields']).encode()
    download = urllib.request.Request(base + ticket['download_url'], data=form, headers={'Content-Type': 'application/x-www-form-urlencoded'}, method='POST')
    with opener.open(download, timeout=30) as response:
        data = response.read()
        assert response.status == 200
        assert response.headers['X-Chen-Result-Truncated'] == 'false'
    if format == 'csv':
        rows = list(csv.reader(io.StringIO(data.decode())))
        assert len(rows) == 10002
        assert rows[1] == ['1', '中文😀', '9007199254740993', "'=1+1", '\\N', '']
        assert rows[-1][0] == '10001'
    else:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            sheet = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
        ns = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        rows = sheet.findall('s:sheetData/s:row', ns)
        assert len(rows) == 10002
        assert sheet.find('.//s:f', ns) is None
        cells = {c.attrib['r']: c.find('s:is/s:t', ns).text or '' for c in rows[1]}
        assert cells == {'A2': '1', 'B2': '中文😀', 'C2': '9007199254740993', 'D2': '=1+1', 'F2': ''}
    try:
        opener.open(download, timeout=10)
        raise AssertionError('download ticket was reusable')
    except urllib.error.HTTPError as error:
        assert error.code == 410
    request('DELETE', '/exports/' + xid, status=204)
execution = request('POST', '/consoles/' + cid + '/exports', {'sql': 'SELECT pg_sleep(15)', 'format': 'csv', 'request_id': str(uuid.uuid4())}, 202)
xid = execution['export_id']
time.sleep(.15)
export = request('POST', '/exports/' + xid + '/cancel', {}, 202)
assert export['id'] == xid and export['execution_id'] == execution['id'] and export['format'] == 'csv'
wait(execution['id'], 'canceled')
export = request('POST', '/exports/' + xid + '/cancel', {}, 200)
assert export['id'] == xid and export['execution_id'] == execution['id'] and not export['ready']
request('DELETE', '/exports/' + xid, status=204)
request('DELETE', '/sessions/' + sid, status=204)
print('HTTP acceptance passed, including CSV/XLSX export, export cancellation resource identity and single-use downloads (no application tables or writes)')
