"""Verify exact return-source migration only in a disposable schema on loopback port 59685."""
import argparse, json, subprocess, uuid
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--mysql',required=True)
parser.add_argument('--port',type=int,required=True)
parser.add_argument('--result',required=True)
args=parser.parse_args()
if args.port!=59685: raise ValueError('Only the isolated loopback instance on port 59685 is allowed')
client=args.mysql
schema='return_source_migration_it_'+uuid.uuid4().hex
base=[client,'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=59685','--user=root','--batch','--skip-column-names','--default-character-set=utf8mb4']
checks=[]
def sql(text, db=True):
    result=subprocess.run(base+(['--database='+schema] if db else []),input=text,encoding='utf-8',capture_output=True)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout.strip()
def check(name, condition):
    assert condition,name
    checks.append({'name':name,'passed':True})
assert schema.startswith('return_source_migration_it_')
check('disposable-native-version',sql('SELECT VERSION()',False).startswith('8.0.46'))
sql('CREATE DATABASE `'+schema+'`',False)
check('own-schema-selected',sql('SELECT DATABASE()')==schema)
sql('''CREATE TABLE mes_wm_return_sales_line(id BIGINT PRIMARY KEY,tenant_id BIGINT DEFAULT 0,return_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,deleted BIT DEFAULT 0);
CREATE TABLE mes_wm_transaction(id BIGINT PRIMARY KEY,tenant_id BIGINT DEFAULT 0,biz_type INT,type INT,biz_id BIGINT,biz_line_id BIGINT,item_id BIGINT,quantity DECIMAL(20,6),batch_id BIGINT,deleted BIT DEFAULT 0);
CREATE TABLE mes_qc_rqc(id BIGINT PRIMARY KEY,tenant_id BIGINT DEFAULT 0,source_doc_type INT,source_doc_id BIGINT,source_line_id BIGINT,deleted BIT DEFAULT 0);
INSERT INTO mes_wm_return_sales_line(id,return_id,item_id,quantity,batch_id) VALUES(6,7,8,1.123456,9);
INSERT INTO mes_wm_transaction(id,biz_type,type,biz_id,biz_line_id,item_id,quantity,batch_id) VALUES(23,118,2,20,21,8,-5.123456,9),(24,119,1,7,6,8,1.123456,9);''')
before=sql('SELECT id,biz_type,quantity FROM mes_wm_transaction ORDER BY id')
migration=(Path(__file__).resolve().parents[1]/'migrations'/'20261020_return_sales_source.sql').read_text(encoding='utf-8-sig')
sql(migration)
sql(migration)
check('old-118-and119-quantities-unchanged',before==sql('SELECT id,biz_type,quantity FROM mes_wm_transaction ORDER BY id'))
check('old-line-origin-not-guessed',sql('SELECT origin_transaction_id IS NULL FROM mes_wm_return_sales_line WHERE id=6')=='1')
check('old119-source-and-detail-not-guessed',sql('SELECT return_source_transaction_id IS NULL AND return_detail_id IS NULL FROM mes_wm_transaction WHERE id=24')=='1')
check('three-new-tables-no-synthetic-reservations',sql('SELECT (SELECT COUNT(*) FROM mes_wm_return_source_budget)+(SELECT COUNT(*) FROM mes_wm_return_source_reservation)+(SELECT COUNT(*) FROM mes_wm_return_posted_evidence)')=='0')
check('detail-unique-index-created',sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND index_name='uk_return_posted_detail' AND non_unique=0")=='3')
sql('''INSERT INTO mes_wm_return_source_budget(tenant_id,origin_transaction_id,reserved_quantity,returned_quantity) VALUES(0,23,1,1);
INSERT INTO mes_wm_return_source_reservation(tenant_id,return_line_id,return_id,origin_transaction_id,quantity,posted_quantity,state) VALUES(0,8,7,23,2,1,'RESERVED');
INSERT INTO mes_wm_transaction(id,biz_type,type,biz_id,biz_line_id,item_id,quantity,batch_id,return_source_transaction_id,return_detail_id) VALUES(99,119,1,7,8,8,1,9,23,88);
INSERT INTO mes_wm_return_posted_evidence(tenant_id,return_transaction_id,return_id,return_line_id,return_detail_id,origin_transaction_id,quantity) VALUES(0,99,7,8,88,23,1);''')
budget_before=sql('SELECT * FROM mes_wm_return_source_budget')
reserve_before=sql('SELECT * FROM mes_wm_return_source_reservation')
evidence_before=sql('SELECT * FROM mes_wm_return_posted_evidence')
sql(migration)
check('retry-does-not-reset-budget-counters',budget_before==sql('SELECT * FROM mes_wm_return_source_budget'))
check('retry-does-not-reset-reservation-posted-count',reserve_before==sql('SELECT * FROM mes_wm_return_source_reservation'))
check('retry-does-not-duplicate-immutable-evidence',evidence_before==sql('SELECT * FROM mes_wm_return_posted_evidence'))
check('known-and-unknown-history-coexist',sql('SELECT COUNT(*) FROM mes_wm_transaction WHERE biz_type=119 AND return_source_transaction_id IS NULL')=='1')
duplicate=subprocess.run(base+['--database='+schema],input='INSERT INTO mes_wm_transaction(id,biz_type,type,biz_id,biz_line_id,item_id,quantity,batch_id,return_source_transaction_id,return_detail_id) VALUES(100,119,1,7,8,8,1,9,23,88);',encoding='utf-8',capture_output=True)
check('duplicate-detail119-rejected-by-database',duplicate.returncode!=0 and 'Duplicate entry' in duplicate.stderr)
check('quantity-columns-20-6',sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='mes_wm_return_source_budget' AND column_name IN('reserved_quantity','returned_quantity')) OR (table_name='mes_wm_return_source_reservation' AND column_name IN('quantity','posted_quantity')) OR (table_name='mes_wm_return_posted_evidence' AND column_name='quantity')) AND numeric_precision=20 AND numeric_scale=6")=='5')
report={'schema':schema,'port':59685,'migrationApplications':3,'checks':checks,'passed':len(checks),'failed':0,'appDatabaseTouched':False,'originalTransactions':before}
Path(args.result).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'report':args.result,'passed':len(checks),'failed':0}))
sql('DROP DATABASE `'+schema+'`',False)
