#!/usr/bin/env python3
"""Exercise native assistant HTTP APIs with synthetic documents and a local protocol stub.

Never calls a paid/external model. Existing model configuration is preserved. A
temporary OpenAI-compatible HTTP fixture is used only if configuration is pristine;
its fake key is cleared with DELETE /model-config in finally. Documents, knowledge
base and user-owned sessions created by this run are soft-deleted in finally.
Credentials, tokens, keys, source text and model payloads are never printed/saved.
"""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import sys
from threading import Thread
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
STUB_ANSWER = 'HTTP协议验收桩：仅验证连接及引用流转，不代表真实LLM生成能力。[1]'


def docx(text):
    from xml.sax.saxutils import escape
    output = io.BytesIO()
    with ZipFile(output, 'w') as archive:
        archive.writestr('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        archive.writestr('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        archive.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>' + escape(text) + '</w:t></w:r></w:p></w:body></w:document>')
    return output.getvalue()


def pdf(lines):
    """Build a small real PDF with two text pages; no external files/libraries."""
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>']
    for index, text in enumerate(lines):
        page_id, content_id = 3 + index * 2, 4 + index * 2
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents {content_id} 0 R >>'.encode())
        safe = text.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')
        stream = f'BT /F1 9 Tf 35 700 Td ({safe}) Tj ET'.encode('ascii')
        objects.append(b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream')
    objects.append(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    output = bytearray(b'%PDF-1.4\n'); offsets = [0]
    for index, body in enumerate(objects, 1):
        offsets.append(len(output)); output.extend(f'{index} 0 obj\n'.encode() + body + b'\nendobj\n')
    xref = len(output); output.extend(f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]: output.extend(f'{offset:010d} 00000 n \n'.encode())
    output.extend(f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return bytes(output)


class LocalModelFixture(BaseHTTPRequestHandler):
    calls = 0
    all_authorized = True
    prompt_had_source = False
    protocol_valid = True
    expected_key = ''
    def log_message(self, *_args):
        pass
    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(min(int(self.headers.get('Content-Length', '0')), 2 * 1024 * 1024)))
            type(self).calls += 1
            type(self).all_authorized &= self.headers.get('Authorization') == 'Bearer ' + self.expected_key
            type(self).protocol_valid &= self.path == '/v1/chat/completions' and body.get('stream') is False and body.get('model') == 'acceptance-http-stub'
            type(self).prompt_had_source |= any('本次检索资料' in message.get('content', '') for message in body.get('messages', []))
            response = json.dumps({'choices':[{'message':{'content':STUB_ANSWER}}]}, ensure_ascii=False).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(response)))
            self.end_headers(); self.wfile.write(response)
        except Exception:
            self.send_response(400); self.end_headers()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:48080/admin-api')
    parser.add_argument('--tenant', type=int, default=1)
    parser.add_argument('--other-tenant', type=int, default=2)
    parser.add_argument('--output', type=Path, default=ROOT / '.runtime/assistant-rag-http-acceptance.json')
    args = parser.parse_args()
    if args.other_tenant == args.tenant:
        raise RuntimeError('The other tenant must differ from the current tenant.')
    spec = importlib.util.spec_from_file_location('report_acceptance_api', ROOT / 'deploy/verify-report-dashboard.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    api = module.Api(args.base, args.tenant)
    secrets = json.loads((ROOT / '.runtime/secrets.local.json').read_text(encoding='utf-8-sig'))
    login = api.request('POST', '/system/auth/login', {'username':secrets['AdminUsername'], 'password':secrets['AdminPassword']})
    api.headers['Authorization'] = 'Bearer ' + login['accessToken']; secrets.clear(); login.clear()
    checks, issues, observations = [], [], []
    bases, documents, sessions = [], [], []
    counts = Counter()
    model_changed, model_restored = False, True
    stub = None
    keyword = 'assistantacceptance' + uuid4().hex
    marker = 'AI-RAG-HTTP-' + datetime.now().strftime('%Y%m%d%H%M%S')
    original_config = None
    temporary_base_url = None

    def check(name, condition):
        checks.append({'check':name,'passed':bool(condition)})
        if not condition: issues.append({'check':name})
    def request(method, path, body=None, **params):
        counts['httpRequests'] += 1
        return api.request(method, path, body, **params)
    def missing(path, **params):
        try:
            request('GET', path, **params)
        except module.BusinessError as error:
            return error.code == 1_047_140_001
        return False
    def raw(method, path, body=None, headers=None, **params):
        counts['httpRequests'] += 1
        target = api.base + path + ('?' + urlencode(params) if params else '')
        final_headers = dict(api.headers)
        if headers: final_headers.update(headers)
        with urlopen(Request(target, data=body, headers=final_headers, method=method), timeout=90) as response:
            return response.read()
    def upload(base_id, file_name, data):
        boundary = '----FazeMESAcceptance' + uuid4().hex
        parts = []
        for name, value in [('knowledgeBaseId',str(base_id)), ('title',marker+'-'+file_name)]:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file_name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+data+b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        result = json.loads(raw('POST','/mes/assistant/document/upload',b''.join(parts),{'Content-Type':'multipart/form-data; boundary='+boundary}))
        if result.get('code') != 0: raise RuntimeError('Synthetic document upload rejected.')
        return result['data']
    def clean(method, path, body=None, **params):
        try:
            request(method,path,body,**params); return True
        except module.BusinessError as error:
            return error.code == 1_047_140_001
        except Exception as error:
            issues.append({'check':'fixture_cleanup','errorType':type(error).__name__}); return False

    try:
        status = request('GET','/mes/assistant/status')
        original_config = request('GET','/mes/assistant/model-config')
        check('configuration_get_contains_no_key_or_ciphertext', not {'apiKey','secretCiphertext','ciphertext','masterKey','masterKeyFile'} & set(original_config))
        check('configuration_exposes_only_key_presence', type(original_config.get('apiKeyConfigured')) is bool)
        check('retrieval_is_explicitly_lexical_bm25', status.get('retrievalMode') == 'lexical_bm25')
        base_id = request('POST','/mes/assistant/base',{'name':marker,'description':'Synthetic acceptance fixture only; deleted in finally.'}); bases.append(base_id)
        fixtures = {
            'fixture.txt':(keyword+' 点检异常必须停机并登记；仅为合成验收资料。').encode(),
            'fixture.md':('# Inspection\n'+keyword+' inspection approval is required.\n# Release\n'+keyword+' release follows inspection.').encode(),
            'fixture.docx':docx(keyword+' DOCX inspection anomalies must be recorded.'),
            'fixture.pdf':pdf([keyword+' PDF inspection must finish before release.',keyword+' PDF alarms require stopping the equipment.'])}
        total_chunks = 0
        for file_name, data in fixtures.items():
            saved = upload(base_id,file_name,data); documents.append(saved['id']); counts['documentsCreated'] += 1
            detail = request('GET','/mes/assistant/document',id=saved['id'])
            total_chunks += detail['chunkCount']
            check(file_name+'_parsed_into_real_chunks', detail['status']=='DRAFT' and detail['chunkCount']>0 and len(detail['chunks'])==detail['chunkCount'])
            check(file_name+'_source_hash_matches_original_bytes', detail['fileSha256']==hashlib.sha256(data).hexdigest())
            check(file_name+'_chunk_offsets_and_hashes_trace_to_source', all(detail['contentText'][chunk['startOffset']:chunk['endOffset']]==chunk['content'] and hashlib.sha256(chunk['content'].encode()).hexdigest()==chunk['contentHash'] for chunk in detail['chunks']))
            check(file_name+'_download_preserves_original_bytes', raw('GET','/mes/assistant/document/download',id=saved['id'])==data)
            if file_name.endswith('.pdf'): check('pdf_retains_both_actual_page_numbers',{chunk['pageNumber'] for chunk in detail['chunks']}=={1,2})
        draft = request('POST','/mes/assistant/search',{'query':keyword,'knowledgeBaseIds':[base_id],'limit':10})
        check('draft_documents_never_enter_retrieval',not draft['items'] and draft['eligibleChunkCount']==0)
        for document_id in documents: request('PUT','/mes/assistant/document/publish',id=document_id,published='true')
        result = request('POST','/mes/assistant/search',{'query':keyword,'knowledgeBaseIds':[base_id],'limit':10})
        check('all_four_published_formats_are_retrievable',{item['documentId'] for item in result['items']}==set(documents))
        check('retrieval_uses_all_actual_published_chunks',result['eligibleChunkCount']==total_chunks)
        check('retrieval_citations_are_source_grounded',all(item['score']>0 and item['contentHash']==hashlib.sha256(item['content'].encode()).hexdigest() and item['version']==1 for item in result['items']))
        no_match = request('POST','/mes/assistant/search',{'query':'unrelated'+uuid4().hex,'knowledgeBaseIds':[base_id],'limit':5})
        check('unknown_terms_do_not_generate_matches',not no_match['items'])
        session_id = request('POST','/mes/assistant/session',{'title':marker}); sessions.append(session_id)
        check('session_is_owned_by_authenticated_user',any(session['id']==session_id for session in request('GET','/mes/assistant/sessions')))
        check('client_supplied_owner_query_does_not_change_authenticated_scope',request('GET','/mes/assistant/session',id=session_id,userId=9223372036854775000)['id']==session_id)
        check('nonexistent_session_is_rejected',missing('/mes/assistant/session',id=9223372036854775000))
        original_tenant = api.headers['tenant-id']; api.headers['tenant-id'] = str(args.other_tenant)
        try:
            try:
                request('GET','/mes/assistant/document',id=documents[0]); check('token_tenant_mismatch_rejected',False)
            except module.BusinessError as error: check('token_tenant_mismatch_rejected',error.code==403)
        finally: api.headers['tenant-id']=original_tenant
        snapshot = None
        if not status['configured']:
            response = request('POST','/mes/assistant/message',{'sessionId':session_id,'question':keyword,'knowledgeBaseIds':[base_id]})
            check('unconfigured_model_does_not_invent_an_answer',response['status']=='MODEL_NOT_CONFIGURED' and response['answer'] is None and response['assistantMessageId'] is None and bool(response['citations']))
            history = request('GET','/mes/assistant/session',id=session_id)
            check('unconfigured_model_persists_only_the_user_request',[message['role'] for message in history['messages']]==['user'])
            snapshot = response['citations']
        else: observations.append('User model is already configured. No prompt was sent to that model and its settings/key were preserved.')
        pristine_config = {'provider':'openai','baseUrl':'https://api.openai.com/v1','model':'','apiKeyConfigured':False,'temperature':.2,'maxTokens':1024,'contextTokens':8192,'enabled':False}
        if not status['configured'] and original_config == pristine_config:
            fake_key = 'fixture-only-' + uuid4().hex
            LocalModelFixture.expected_key=fake_key
            stub=ThreadingHTTPServer(('127.0.0.1',0),LocalModelFixture)
            Thread(target=stub.serve_forever,daemon=True).start()
            model_changed=True; model_restored=False
            temporary_base_url=f'http://127.0.0.1:{stub.server_port}/v1'
            request('PUT','/mes/assistant/model-config',{'provider':'openai','baseUrl':temporary_base_url,'model':'acceptance-http-stub','apiKey':fake_key,'clearApiKey':False,'temperature':.2,'maxTokens':256,'contextTokens':8192,'enabled':True})
            saved_config = request('GET','/mes/assistant/model-config')
            check('saved_fake_key_is_redacted_from_get',saved_config['apiKeyConfigured'] and 'apiKey' not in saved_config and fake_key not in json.dumps(saved_config))
            connection = request('POST','/mes/assistant/model-config/test')
            check('stored_configuration_connects_to_actual_local_http_stub',connection.get('connected') is True)
            response=request('POST','/mes/assistant/message',{'sessionId':session_id,'question':keyword,'knowledgeBaseIds':[base_id]})
            check('local_protocol_stub_answer_is_explicitly_a_test_fixture',response['status']=='ANSWERED' and response['answer']==STUB_ANSWER and bool(response['citations']))
            check('model_request_uses_expected_protocol_and_authorization',LocalModelFixture.calls>=2 and LocalModelFixture.protocol_valid and LocalModelFixture.all_authorized and LocalModelFixture.prompt_had_source)
            history=request('GET','/mes/assistant/session',id=session_id)
            check('real_http_answer_and_citations_are_persisted',history['messages'][-1]['role']=='assistant' and history['messages'][-1]['content']==STUB_ANSWER and len(history['messages'][-1]['citations'])==len(response['citations']))
            snapshot=response['citations']; counts['localProtocolStubCalls']=LocalModelFixture.calls
        else: observations.append('Temporary model fixture skipped because configuration is not pristine; no user model/key was overwritten.')
        if snapshot:
            withdrawn=snapshot[0]['documentId']
            request('PUT','/mes/assistant/document/publish',id=withdrawn,published='false')
            remaining=request('POST','/mes/assistant/search',{'query':keyword,'knowledgeBaseIds':[base_id],'limit':10})
            check('withdrawn_document_is_excluded_from_new_retrieval',all(item['documentId']!=withdrawn for item in remaining['items']))
            request('DELETE','/mes/assistant/document',id=withdrawn)
            check('deleted_document_cannot_be_reopened',missing('/mes/assistant/document',id=withdrawn))
            history=request('GET','/mes/assistant/session',id=session_id)
            check('historical_citation_snapshot_survives_source_withdrawal_and_deletion',any(citation.get('contentHash')==snapshot[0]['contentHash'] and citation.get('content')==snapshot[0]['content'] for message in history['messages'] for citation in message['citations']))
        observations.append('HTTP used one authenticated admin principal. Actual cross-user session ownership and independent config permission rejection are additionally covered by the standalone real-H2/Spring-method-security tests; no test user account was created.')
    except Exception as error:
        issues.append({'check':'unexpected_http_acceptance_error','errorType':type(error).__name__})
    finally:
        if model_changed:
            try:
                current=request('GET','/mes/assistant/model-config')
                if current != original_config and not (current.get('provider')=='openai' and current.get('baseUrl')==temporary_base_url and current.get('model')=='acceptance-http-stub'):
                    raise RuntimeError('Model configuration changed concurrently; preserve it instead of deleting.')
                if current != original_config: request('DELETE','/mes/assistant/model-config')
                restored=request('GET','/mes/assistant/model-config')
                model_restored=restored==original_config and not restored['apiKeyConfigured']
                check('temporary_model_and_fake_key_are_removed_and_original_get_restored',model_restored)
            except Exception as error: issues.append({'check':'model_fixture_cleanup','errorType':type(error).__name__})
        if stub: stub.shutdown(); stub.server_close()
        for session_id in sessions:
            if clean('DELETE','/mes/assistant/session',id=session_id): counts['sessionsCleaned']+=1
        for document_id in documents:
            if clean('DELETE','/mes/assistant/document',id=document_id): counts['documentsCleaned']+=1
        for base_id in bases:
            if clean('DELETE','/mes/assistant/base',id=base_id): counts['basesCleaned']+=1
        check('all_created_session_fixtures_cleaned',counts['sessionsCleaned']==len(sessions))
        check('all_created_document_fixtures_cleaned',counts['documentsCleaned']==len(documents))
        check('all_created_knowledge_base_fixtures_cleaned',counts['basesCleaned']==len(bases))
        evidence={'result':'passed' if not issues else 'failed','generatedAt':datetime.now().astimezone().isoformat(),
            'mode':'synthetic_knowledge_and_session_fixtures','businessDocumentMutationCount':0,
            'documentFormats':['txt','md','docx','pdf'],'counts':dict(counts),'checksPassed':sum(item['passed'] for item in checks),
            'checksFailed':sum(not item['passed'] for item in checks),'checks':checks,'issues':issues,
            'localProtocolStubUsed':model_changed,'modelConfigurationRestored':model_restored,
            'secretHandling':'Credentials, token and fake key were used only in process and are not recorded.',
            'observations':observations}
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'result':evidence['result'],'checksPassed':evidence['checksPassed'],'checksFailed':evidence['checksFailed'],
            'issueCount':len(issues),'documentsCreated':len(documents),'documentsCleaned':counts['documentsCleaned'],
            'localProtocolStubUsed':model_changed,'modelConfigurationRestored':model_restored},ensure_ascii=False))
    return 0 if not issues else 1


if __name__=='__main__':
    try: sys.exit(main())
    except Exception as error:
        print(json.dumps({'result':'error','errorType':type(error).__name__}))
        sys.exit(1)
