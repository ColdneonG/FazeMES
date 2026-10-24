"""Rebuild the dedicated local FazeMES database with traceable synthetic fixtures.

Run via deploy/reset-test-data.ps1. The reset refuses a running backend, verifies
the actual MySQL data directory, and saves a complete checked dump before DELETE.
No schema, migration ledger, admin password or system permission is recreated.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import importlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import uuid

import pymysql
from testdata_backups import finalize_backups, register_backup

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / '.runtime'
STATE = RUNTIME / 'testdata-state.json'
MYSQL = Path(r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe')
DUMP = MYSQL.with_name('mysqldump.exe')
MARKER = 'fazemes-test-suite-v1'
TZ = timezone(timedelta(hours=8))
TEST_PASSWORD = 'Test@2026!'
MODULES = ('testdata_inventory', 'testdata_supporting', 'testdata_manufacturing',
           'testdata_collaboration','testdata_production_quality')


def serial(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime,)):
        return value.isoformat()
    if isinstance(value, bytes):
        return int.from_bytes(value, 'big')
    raise TypeError(type(value).__name__)


class Context:
    def __init__(self):
        self.secrets = json.loads((RUNTIME / 'secrets.local.json').read_text(encoding='utf-8-sig'))
        self.conn = pymysql.connect(host='127.0.0.1', port=int(self.secrets['MysqlPort']),
            user=self.secrets['DbUsername'], password=self.secrets['DbPassword'], database='yudao_mes',
            charset='utf8mb4', autocommit=False, cursorclass=pymysql.cursors.DictCursor)
        with self.conn.cursor() as cur:
            cur.execute('SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED')
        self.now = datetime.now(TZ).replace(tzinfo=None, microsecond=0)
        self.meta = {}
        for row in self.query('SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA '
                              'FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY ORDINAL_POSITION'):
            self.meta.setdefault(row['TABLE_NAME'], {})[row['COLUMN_NAME']] = row
        self.ids = dict(admin=1, raw=900101, bolt=900102, product=900103, client=900001, vendor=900001,
                        warehouse=900001, wip=900002, users=list(range(980001,980013)),
                        posts=list(range(980001,980007)), workshops=list(range(980001,980004)),
                        stations=list(range(980001,980007)))
        self.counts = Counter()
        self.counters = {}
        self.checks = []
        self.scenarios = []
        self.api_evidence = []

    def at(self, key):
        """Record an execution time once, retaining it on a checkpoint replay.

        Current production reports follow the SOP confirmations actually written
        by the API. Historical business-date samples pass explicit dates instead.
        """
        path=RUNTIME/'testdata-business-times.json'
        data=json.loads(path.read_text(encoding='utf8')) if path.exists() else {}
        if data.get('anchor')!=self.now.isoformat():
            data={'anchor':self.now.isoformat(),'times':{}}
        if key not in data['times']:
            value=datetime.now(TZ).replace(tzinfo=None,microsecond=0)
            latest=self.scalar('SELECT MAX(confirmed_at) FROM mes_sop_step_record WHERE deleted=0 AND tenant_id=1')
            if latest and latest>value:
                import time
                time.sleep(min(1.05,(latest-value).total_seconds()+.05))
                value=datetime.now(TZ).replace(tzinfo=None,microsecond=0)
            data['times'][key]=value.isoformat()
            path.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf8')
        return datetime.fromisoformat(data['times'][key])

    def query(self, sql, params=None):
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            return list(cur.fetchall())

    def scalar(self, sql, params=None):
        rows = self.query(sql, params)
        return next(iter(rows[0].values())) if rows else None

    def execute(self, sql, params=None):
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.rowcount

    def add(self, table, **values):
        columns = self.meta[table]
        unknown = set(values) - set(columns)
        if unknown:
            raise ValueError(f'{table}: unknown columns {unknown}')
        for key, value in dict(tenant_id=1,creator=MARKER,updater=MARKER,
                create_time=self.now,update_time=self.now,deleted=0).items():
            if key in columns:
                values.setdefault(key, value)
        if 'id' in columns and 'id' not in values:
            if table not in self.counters:
                self.counters[table] = max(989999, self.scalar(f'SELECT COALESCE(MAX(id),0) FROM `{table}`'))
            self.counters[table] += 1
            values['id'] = self.counters[table]
        keys = list(values)
        sql = f'INSERT INTO `{table}` (' + ','.join(f'`{x}`' for x in keys) + ') VALUES (' + ','.join(['%s']*len(keys)) + ')'
        self.execute(sql, [values[k] for k in keys])
        self.counts[table] += 1
        return values.get('id')

    def save(self, phase, **extra):
        payload = dict(suite=MARKER, phase=phase, anchor=self.now.isoformat(), ids=self.ids,
                       counts=dict(self.counts), checks=self.checks, scenarios=self.scenarios,
                       apiEvidence=self.api_evidence, **extra)
        STATE.write_text(json.dumps(payload,ensure_ascii=False,indent=2,default=serial)+'\n',encoding='utf8')

    def restore_state(self):
        state = json.loads(STATE.read_text(encoding='utf8'))
        if state['suite'] != MARKER:
            raise RuntimeError('Unrecognized fixture state')
        self.now = datetime.fromisoformat(state['anchor'])
        self.ids = state['ids']
        self.counts = Counter(state['counts'])
        self.checks = state.get('checks',[])
        self.scenarios = state.get('scenarios',[])
        self.api_evidence = state.get('apiEvidence',[])
        return state


def mysql_source(path):
    result = subprocess.run([str(MYSQL),f'--defaults-extra-file={RUNTIME / "mysql-client.local.ini"}',
        '--protocol=TCP','--host=127.0.0.1','--port=3308','--database=yudao_mes',
        '--default-character-set=utf8mb4',f'--execute=source {path.as_posix()}'],
        capture_output=True,encoding='utf8',errors='replace')
    if result.returncode or 'ERROR ' in result.stderr:
        raise RuntimeError(f'SQL import failed: {path.name}: {result.stderr[-2000:]}')


def backup(ctx):
    identity = ctx.query('SELECT @@port AS port,@@datadir AS datadir,DATABASE() AS db')[0]
    expected = (RUNTIME / 'mysql/data').resolve().as_posix().lower().rstrip('/')
    actual = str(identity['datadir']).replace('\\','/').lower().rstrip('/')
    if identity['port'] != 3308 or identity['db'] != 'yudao_mes' or actual != expected:
        raise RuntimeError('Reset refused: actual MySQL instance is not this project local development instance')
    try:
        with socket.create_connection(('127.0.0.1',48080),timeout=.3):
            raise RuntimeError('Stop the managed backend before resetting the database')
    except (ConnectionRefusedError,socket.timeout,OSError) as error:
        if isinstance(error,RuntimeError):
            raise
    destination = RUNTIME / 'backups' / f'testdata-before-{ctx.now:%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:6]}.sql'
    destination.parent.mkdir(parents=True,exist_ok=True)
    args = [str(DUMP),f'--defaults-extra-file={RUNTIME / "mysql-client.local.ini"}',
            '--protocol=TCP','--host=127.0.0.1','--port=3308',
            '--single-transaction','--routines','--triggers','--events','--hex-blob',
            '--no-tablespaces','--set-gtid-purged=OFF',f'--result-file={destination}','yudao_mes']
    result = subprocess.run(args,capture_output=True,encoding='utf8',errors='replace')
    if result.returncode:
        raise RuntimeError('Database backup failed: '+result.stderr[-1200:])
    info = register_backup(destination.parent, destination)
    print(json.dumps({'backup':info},ensure_ascii=False),flush=True)
    return info


def foundations(ctx):
    import bcrypt
    # Required singleton seeded by 20261020_barcode_label.sql. It is a schema
    # invariant, not old business history, and must survive a business-data reset.
    ctx.execute('INSERT IGNORE INTO mes_wm_barcode_mutex(tenant_id) VALUES(-1)')
    password_hash = bcrypt.hashpw(TEST_PASSWORD.encode(),bcrypt.gensalt()).decode()
    departments = [(100,'华辰精工测试工厂',0),(980001,'计划与生产部',100),(980002,'仓储物流部',100),
                   (980003,'质量管理部',100),(980004,'设备动力部',100),(980005,'销售服务部',100)]
    for n,(id,name,parent) in enumerate(departments):
        ctx.add('system_dept',id=id,name=name,parent_id=parent,sort=n,status=0,
                leader_user_id=1,email=f'dept{id}@example.invalid')
    posts = ['计划员','仓库管理员','生产操作员','质量检验员','设备工程师','销售专员']
    for n,name in enumerate(posts,1):
        ctx.add('system_post',id=980000+n,code=f'TST-POST-{n:02}',name=name,sort=n,status=0,remark='虚构测试岗位')
    users = [
        ('plan01','林计划',980001,1,'planner'),('store01','周仓管',980002,2,'warehouse'),
        ('operator01','陈操作',980001,3,'operator'),('operator02','李操作',980001,3,'operator'),
        ('qc01','王质检',980003,4,'quality'),('qc02','赵质检',980003,4,'quality'),
        ('device01','孙设备',980004,5,'device'),('sales01','吴销售',980005,6,'sales'),
        ('supervisor01','郑主管',980001,1,'supervisor'),('viewer01','冯经理',980001,1,'viewer'),
        ('disabled01','停用测试员',980001,3,'operator'),('operator03','许操作',980001,3,'operator')]
    menus = ctx.query('SELECT id,parent_id,permission,type FROM system_menu WHERE deleted=0 AND status=0')
    byid = {m['id']:m for m in menus}
    families = {
        'planner':('mes:pro-work-order:','mes:pro-task:','mes:pro:','mes:kitting:','mes:md-','mes:pro-route:'),
        'warehouse':('mes:wm-','mes:wm:','mes:barcode:'),
        'operator':('mes:pro-feedback:create','mes:pro-feedback:update','mes:pro-feedback:submit','mes:pro-work-record:','mes:pro-workrecord:','mes:sop:execute','mes:sn-trace:'),
        'quality':('mes:qc-','mes:sop:query','mes:sn-trace:query'),
        'device':('mes:dv-','mes:tm-','mes:cal-'),
        'sales':('mes:portal:','mes:wm-sales-notice:','mes:wm-product-sales:','mes:md-client:'),
        'supervisor':('mes:pro-','mes:kitting:','mes:sop:','mes:sn-trace:','mes:insight:','mes:report:','mes:dashboard:'), 'viewer':()}
    roleids = {}
    for idx,(role,prefixes) in enumerate(families.items(),1):
        rid = 980000+idx
        roleids[role]=rid
        ctx.add('system_role',id=rid,name={'planner':'测试计划员','warehouse':'测试仓管员','operator':'测试操作员',
                'quality':'测试质检员','device':'测试设备员','sales':'测试销售员','supervisor':'测试生产主管','viewer':'测试只读经理'}[role],
                code='tst_'+role,sort=idx,data_scope=1,data_scope_dept_ids='[]',status=0,type=2,remark='虚构测试角色；按功能权限分配')
        selected=set()
        for m in menus:
            perm=m['permission'] or ''
            read = perm.startswith('mes:') and (perm.endswith((':query',':export',':read')) or perm in ('mes:dashboard:query',))
            shared_read=read and (role in ('viewer','supervisor') or perm.startswith(('mes:md-','mes:dashboard:'))
                or (role in ('operator','quality','planner') and perm.startswith(('mes:pro-work-order:','mes:pro-task:','mes:sop:')))
                or (role=='quality' and perm.startswith(('mes:wm-material-stock:','mes:sn-trace:'))))
            if perm and (shared_read or (role!='viewer' and perm.startswith(prefixes))):
                selected.add(m['id'])
        for mid in list(selected):
            parent=byid[mid]['parent_id']
            while parent and parent in byid:
                selected.add(parent)
                parent=byid[parent]['parent_id']
        for mid in sorted(selected):
            ctx.add('system_role_menu',role_id=rid,menu_id=mid)
    for n,(username,nickname,dept,post,role) in enumerate(users,1):
        uid=980000+n
        ctx.add('system_users',id=uid,username=username,password=password_hash,nickname=nickname,
                dept_id=dept,post_ids=json.dumps([980000+post]),email=f'{username}@example.invalid',
                mobile='',status=1 if n==11 else 0,sex=0,avatar='http://127.0.0.1:5173/placeholder-avatar.svg',
                remark='虚构教学测试账号，无真实个人信息')
        ctx.add('system_user_post',user_id=uid,post_id=980000+post)
        ctx.add('system_user_role',user_id=uid,role_id=roleids[role])
    ctx.add('system_user_role',user_id=1,role_id=1)
    ctx.execute("UPDATE system_users SET dept_id=100,post_ids='[]',nickname='测试工厂管理员',email='admin@example.invalid',mobile='',avatar='http://127.0.0.1:5173/placeholder-avatar.svg' WHERE id=1")
    ctx.execute("UPDATE system_tenant SET name='华辰精工测试工厂',contact_name='测试管理员',websites='127.0.0.1:5173,localhost:5173',expire_time='2099-12-31 23:59:59',status=0 WHERE id=1")
    ctx.add('system_notice',title='测试数据使用说明',type=1,status=0,
            content='<p>全部业务记录为虚构教学测试数据。TST 编码用于功能与边界验证，BROKY 编码用于多级 BOM 教学。待办可继续操作，已完成交易请通过正常业务追溯。</p>')
    ctx.ids['role_ids']=roleids
    for n,name in enumerate(['机加工车间','总装车间','检测包装车间'],1):
        ctx.add('mes_md_workshop',id=980000+n,code=f'TST-SHOP-{n:02}',name=name,area=600+n*200,
                charge_user_id=980009,status=0,remark='虚构教学测试车间')
    ctx.execute("UPDATE mes_md_workshop SET name='教学试制车间',code='TST-SHOP-00',charge_user_id=980009 WHERE id=900001")
    ctx.execute("UPDATE mes_md_workstation SET workshop_id=900001 WHERE id IN(900001,900002)")
    for n in range(1,7):
        ctx.add('mes_md_workstation',id=980000+n,code=f'TST-ST-{n:02}',name=f'{posts[(n-1)%6]}实训工位{n:02}',
                address=f'测试园区{(n-1)//2+1}车间{n:02}工位',workshop_id=980001+(n-1)//2,
                process_id=900001 if n%2 else 900002,warehouse_id=900002,location_id=900002,area_id=900002,
                status=1 if n==6 else 0,remark='第6工位停用；其余可排产')
    for id,name in [(900101,'精密传动组件毛坯'),(900102,'M6装配螺栓'),(900103,'精密传动组件总成')]:
        ctx.execute('UPDATE mes_md_item SET name=%s,remark=%s WHERE id=%s',(name,'虚构测试主线物料',id))
    ctx.execute('UPDATE mes_md_item_batch_config SET lot_number_flag=1,vendor_flag=1 WHERE item_id IN(900101,900102)')
    ctx.execute("UPDATE mes_wm_warehouse SET name='教学原料与成品仓',charge_user_id=980002 WHERE id=900001")
    for n,(code,name,kind) in enumerate([('TST-CONSUMABLE','辅料耗材','ITEM'),('TST-PACK','包装材料','ITEM'),('TST-SPARE','备品备件','ITEM')],1):
        ctx.add('mes_md_item_type',id=980000+n,code=code,name=name,parent_id=0,item_or_product=kind,sort=10+n,status=0)
    material_names=['不锈钢垫片','密封圈','轴承','润滑脂','冷却液','尼龙扎带','防静电袋','瓦楞外箱','缓冲衬垫','说明书',
                    '光电传感器','伺服联轴器','铜导线','铝型材','扭矩标识漆','清洁棉布','隔离待验样件','停用旧规格螺钉','零价格赠送辅料','微量精密添加剂']
    for id,code,name in [(980001,'TST-M','米'),(980002,'TST-L','升'),(980003,'TST-BOX','箱')]:
        ctx.add('mes_md_unit_measure',id=id,code=code,name=name,primary_flag=1,primary_id=0,change_rate=1,status=0)
    for n,name in enumerate(material_names,1):
        id=981000+n
        unit=986103 if n in (4,20) else 980001 if n in (13,14) else 980002 if n==5 else 980003 if n==8 else 900001
        ctx.add('mes_md_item',id=id,code=f'TST-MAT-{n:03}',name=name,specification=f'教学规格{n:02}，虚构样品',
                unit_measure_id=unit,item_type_id=980001+(n%3),status=1 if n==18 else 0,safe_stock_flag=1,
                min_stock=0 if n in(19,20) else 20,max_stock=100000,high_value=n in(11,12),batch_flag=1,
                remark='测试维度：停用/高值/保质期/小数数量/零价/待验；不代表真实采购参数')
        ctx.add('mes_md_item_batch_config',item_id=id,lot_number_flag=1,vendor_flag=1)
        price=Decimal('0') if n==19 else Decimal('.000001') if n==20 else Decimal(n)*Decimal('1.25')
        ctx.add('mes_cost_profile',item_id=id,version=1,purchase_price=price,remark='虚构测试采购预算，人民币不含税')
    for item,kwargs in [(900101,dict(purchase_price=12)),(900102,dict(purchase_price=Decimal('.5'))),
                         (900103,dict(sale_price=35,labor_cost=3,overhead_cost=1))]:
        ctx.add('mes_cost_profile',item_id=item,version=1,remark='虚构测试标准预算，人民币不含税',**kwargs)
    ctx.ids['extra_items']=list(range(981001,981021))
    # Standardize the historical initializer's visible sample identities without
    # changing the stable primary keys required by existing project contracts.
    for table in ('mes_md_item','mes_md_item_type','mes_md_unit_measure','mes_md_client','mes_md_vendor',
                  'mes_pro_process','mes_pro_route','mes_wm_warehouse','mes_wm_warehouse_location','mes_wm_warehouse_area'):
        ctx.execute(f"UPDATE `{table}` SET code=REPLACE(code,'DEMO-','TST-') WHERE code LIKE 'DEMO-%'")
    ctx.execute("UPDATE mes_md_auto_code_part SET fix_character=REPLACE(fix_character,'DEMO-','TST-') WHERE fix_character LIKE 'DEMO-%'")
    ctx.execute('UPDATE mes_md_auto_code_part SET length=CHAR_LENGTH(fix_character) WHERE type=3 AND fix_character IS NOT NULL')
    ctx.execute("UPDATE mes_md_auto_code_rule SET name=REPLACE(name,'本地演示-','测试标准-')")
    ctx.execute("UPDATE mes_pro_work_order SET code='TST-WO-BASE',name='基础草稿测试工单',batch_code='TST-LOT-BASE' WHERE id=900501")
    ctx.execute('UPDATE mes_md_item_batch_config SET work_order_flag=1 WHERE item_id=900103')
    for process,title in [(900001,'毛坯下料'),(900002,'组件装配')]:
        ctx.execute('UPDATE mes_pro_process SET name=%s WHERE id=%s',(title,process))
        for n,(text,device,material) in enumerate([
            ('开工核对工单与图纸版本，确认设备和防护条件。','设备与防护工装','工单指定物料'),
            ('按受控SOP执行作业，记录尺寸或扭矩，异常品隔离。','经校验的卡尺或扭矩扳手','指定批次毛坯或M6螺栓'),
            ('清点合格与不良数量，标识批次并办理工序交接。','流转箱与工位终端','合格半成品')],1):
            ctx.add('mes_pro_process_content',process_id=process,sort=n,content=text,device=device,material=material,
                    remark='虚构教学工艺作业指导；受控版本以任务SOP快照为准')


def normalize_master_snapshots(ctx):
    """Refresh editable stocktaking filter captions after standardizing codes."""
    for kind,table in [(102,'mes_wm_warehouse'),(103,'mes_wm_warehouse_location'),
                       (104,'mes_wm_warehouse_area'),(600,'mes_md_item')]:
        ctx.execute(f'UPDATE mes_wm_stock_taking_plan_param p JOIN `{table}` m ON m.id=p.value_id '
                    'AND m.tenant_id=p.tenant_id AND m.deleted=0 '
                    'SET p.value_code=m.code,p.value_name=m.name WHERE p.deleted=0 AND p.type=%s',(kind,))


def reset(ctx):
    modules=[importlib.import_module(x) for x in MODULES]
    info=backup(ctx)
    # DELETE remains transactional; unlike TRUNCATE it does not auto-commit each table.
    cleanup=['system_user_post','system_user_role','system_role_menu','system_dept','system_post','system_notice',
             'system_login_log','system_operate_log','system_notify_message','system_notify_template',
             'system_oauth2_access_token','system_oauth2_refresh_token','system_oauth2_approve','system_oauth2_code',
             'system_sms_log','system_sms_code','system_mail_log','system_social_user_bind','system_social_user',
             'infra_api_access_log','infra_api_error_log','infra_job_log','infra_file','infra_file_content']
    try:
        for table in sorted(ctx.meta):
            if table.startswith(('mes_','yudao_demo')) or table in cleanup:
                ctx.execute(f'DELETE FROM `{table}`')
        ctx.execute('DELETE FROM system_users WHERE id<>1')
        ctx.execute('DELETE FROM system_role WHERE id<>1')
        ctx.execute('DELETE FROM system_tenant WHERE id<>1')
        ctx.conn.commit()
        mysql_source(ROOT/'database/init/mes_seed.sql')
        mysql_source(ROOT/'database/migrations/20261018_broky_product.sql')
        # The legacy initializer contains artificial opening inventory. This suite
        # replaces it with purchase receipts posted by the real service APIs.
        for table in ('mes_wm_transaction','mes_wm_material_stock','mes_wm_batch'):
            ctx.execute(f'DELETE FROM `{table}`')
        # Reinstate only the local tenant quarantine configuration, using the
        # existing migration's conflict guards; no historical rows remain.
        ctx.conn.commit()
        mysql_source(ROOT/'database/migrations/20261019_quality_quarantine.sql')
        foundations(ctx)
        for module in modules[:2]:
            print('Seeding '+module.__name__,flush=True)
            module.seed(ctx)
            ctx.scenarios.extend(getattr(module,'SCENARIOS',[]))
        for station in (900001,900002):
            ctx.add('mes_md_workstation_worker',workstation_id=station,post_id=980003,quantity=1,
                    user_ids='980003,980004,980012',remark='主线工位授权测试操作员；人员与岗位一致')
        cities=['山东省青岛市','广东省深圳市','江苏省苏州市','浙江省杭州市','四川省成都市','湖北省武汉市','陕西省西安市','辽宁省沈阳市']
        ctx.execute("UPDATE mes_md_client SET address='山东省青岛市测试园区合成路1号（虚构测试地址）' WHERE id=900001")
        for i,city in enumerate(cities,1):
            ctx.execute('UPDATE mes_md_client SET address=%s WHERE id=%s',(city+f'测试园区合成路{i}号（虚构测试地址）',986000+i))
        # Unknown/ambiguous addresses remain deliberately unlocated on the map.
        ctx.execute("UPDATE mes_md_client SET address='北京市或上海市（测试歧义地址）' WHERE id=986009")
        ctx.execute("UPDATE mes_md_client SET address=NULL WHERE id=986010")
        normalize_master_snapshots(ctx)
        ctx.conn.commit()
        clear_local_cache(ctx)
        journal=RUNTIME/'testdata-api-journal.jsonl'
        journal.write_text(json.dumps({'suite':MARKER,'anchor':ctx.now.isoformat()})+'\n',encoding='utf8')
        ctx.save('masters-ready',backup=info)
    except BaseException:
        ctx.conn.rollback()
        ctx.save('reset-failed',backup=info)
        raise
    print(json.dumps({'phase':'masters-ready','rowsWritten':sum(ctx.counts.values()),'backup':info['path']},ensure_ascii=False),flush=True)


def clear_local_cache(ctx):
    """Clear only the verified project Redis database used by application config."""
    if int(ctx.secrets['RedisPort'])!=6381:
        raise RuntimeError('Unexpected local Redis port')
    with socket.create_connection(('127.0.0.1',6381),timeout=5) as connection:
        reader=connection.makefile('rb')
        for command in [('AUTH',ctx.secrets['RedisPassword']),('SELECT','0'),('FLUSHDB',)]:
            encoded=[str(x).encode('utf8') for x in command]
            wire=f'*{len(encoded)}\r\n'.encode()+b''.join(f'${len(x)}\r\n'.encode()+x+b'\r\n' for x in encoded)
            connection.sendall(wire)
            response=reader.readline()
            if response!=b'+OK\r\n':
                raise RuntimeError('Local Redis cache invalidation failed')


class Api:
    def __init__(self, ctx, *, journal=False):
        from urllib.request import Request,urlopen
        self.Request,self.urlopen=Request,urlopen
        self.headers={'Content-Type':'application/json','tenant-id':'1'}
        self.calls=0
        self.journal_path=RUNTIME/'testdata-api-journal.jsonl' if journal else None
        self.journal=[]
        self.position=0
        if journal:
            entries=[json.loads(x) for x in self.journal_path.read_text(encoding='utf8').splitlines() if x]
            if entries[0]!={'suite':MARKER,'anchor':ctx.now.isoformat()}:
                raise RuntimeError('API checkpoint belongs to a different fixture reset')
            self.journal=entries[1:]
        data=self.post('/system/auth/login',{'username':ctx.secrets['AdminUsername'],'password':ctx.secrets['AdminPassword']})
        self.headers['Authorization']='Bearer '+data['accessToken']

    def call(self, method, path, payload=None, **params):
        from urllib.parse import urlencode
        authentication=path in ('/system/auth/login','/system/auth/logout')
        checkpoint=self.journal_path is not None and not authentication
        digest=hashlib.sha256(json.dumps([method,path,payload,params],sort_keys=True,default=serial).encode()).hexdigest()
        # Older checkpoints may contain a logout. A replay creates a fresh login
        # session, so execute logout again while consuming that old position.
        if path=='/system/auth/logout' and self.position<len(self.journal) and self.journal[self.position]['requestHash']==digest:
            self.position+=1
        if checkpoint and self.position<len(self.journal):
            entry=self.journal[self.position]
            if entry['requestHash']!=digest:
                raise RuntimeError(f'API replay diverged at call {self.position+1}: {method} {path}. Reset fixtures to apply changed earlier requests.')
            self.position+=1
            self.calls+=1
            return entry['result']
        url='http://127.0.0.1:48080/admin-api'+path+('?' + urlencode(params) if params else '')
        req=self.Request(url,method=method,headers=self.headers,
                data=None if payload is None else json.dumps(payload,default=serial).encode())
        with self.urlopen(req,timeout=60) as response:
            data=json.load(response)
        self.calls+=1
        if data.get('code')!=0:
            raise RuntimeError(f'{method} {path}: code={data.get("code")}, {data.get("msg")}')
        result=data.get('data')
        if checkpoint:
            entry={'requestHash':digest,'method':method,'path':path,'result':result}
            with self.journal_path.open('a',encoding='utf8') as target:
                target.write(json.dumps(entry,ensure_ascii=False,default=serial)+'\n')
                target.flush()
            self.journal.append(entry)
            self.position+=1
        return result

    def get(self,path,**params): return self.call('GET',path,**params)
    def post(self,path,payload): return self.call('POST',path,payload)
    def put(self,path,**params): return self.call('PUT',path,**params)
    def page(self,path,**params):
        rows=[]
        for n in range(1,1000):
            page=self.get(path+'/page',pageNo=n,pageSize=100,**params)
            rows.extend(page['list'])
            if len(rows)>=page['total']: return rows
        raise RuntimeError('Unbounded pagination')


def receipt(api,ctx,code,lines,warehouse=900001,age=35):
    when=ctx.now-timedelta(days=age)
    id=api.post('/mes/wm/item-receipt/create',dict(code=code,name='虚构测试采购入库 '+code,vendorId=900001,
        receiptDate=when.isoformat(),purchaseOrderCode=code.replace('RCV','PO'),remark='虚构教学测试，含供应商批号及实际采购价'))
    for index,(item,quantity,price) in enumerate(lines,1):
        line=api.post('/mes/wm/item-receipt-line/create',dict(receiptId=id,itemId=item,receivedQuantity=quantity,
            productionDate=(when-timedelta(days=3)).isoformat(),expireDate=(when+timedelta(days=365)).isoformat(),
            lotNumber=f'{code}-L{index:02}',remark='虚构供应商批次'))
        record=api.get('/mes/wm/item-receipt-line/get',id=line)
        api.call('PUT','/mes/cost/receipt-line-price',dict(lineId=line,unitPrice=str(price),remark='虚构测试采购价，CNY不含税'))
        api.post('/mes/wm/item-receipt-detail/create',dict(receiptId=id,lineId=line,itemId=item,quantity=quantity,
            batchId=record.get('batchId'),warehouseId=warehouse,locationId=warehouse,areaId=warehouse))
    for action in ('submit','stock','finish'):
        api.put('/mes/wm/item-receipt/'+action,id=id)
    return id


def api_seed(ctx):
    prior=ctx.restore_state()
    if prior['phase'] not in ('masters-ready','api-failed'):
        raise RuntimeError('API seeding requires a freshly reset masters-ready state; use --verify for a finished suite')
    api=Api(ctx,journal=True)
    try:
        print('Posting priced procurement lots through normal APIs',flush=True)
        receipts=[]
        # H01..H06 consume 99 raw/396 bolts. H07 must cross both supplier
        # lots, providing a real weighted FIFO cost instead of unused price cases.
        for suffix,age,raw,bolt in [('A',40,100,400),('B',35,9900,39600)]:
            receipts.append(receipt(api,ctx,'TST-RCV-'+suffix,[(900101,raw,12 if suffix=='A' else Decimal('12.6')),
                (900102,bolt,Decimal('.50') if suffix=='A' else Decimal('.55'))],age=age))
        extra=[]
        for n in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,19,20):
            price=Decimal('0') if n==19 else Decimal('.000001') if n==20 else Decimal(n)*Decimal('1.25')
            extra.append((981000+n,Decimal('0.125') if n==20 else 5 if n==11 else 20 if n==12 else 500,price))
        receipts.append(receipt(api,ctx,'TST-RCV-C',extra,age=33))
        ctx.ids['procurement_receipts']=receipts
        print('Seeding actual production, SOP, traceability and cost evidence',flush=True)
        module=importlib.import_module('testdata_manufacturing')
        module.seed_api(api,ctx)
        ctx.scenarios.extend(getattr(module,'SCENARIOS',[]))
        ctx.conn.commit()
        inv=importlib.import_module('testdata_inventory')
        if hasattr(inv,'seed_api'):
            inv.seed_api(api,ctx)
        if hasattr(inv,'seed_stock_cases') and not ctx.ids.get('inventory_stock_seeded'):
            inv.seed_stock_cases(ctx)
            ctx.conn.commit()
            ctx.ids['inventory_stock_seeded']=True
        collaboration=importlib.import_module('testdata_collaboration')
        collaboration.seed_api(api,ctx)
        ctx.scenarios.extend(getattr(collaboration,'SCENARIOS',[]))
        if hasattr(module,'seed_broky_api'):
            module.seed_broky_api(api,ctx)
        if hasattr(module,'seed_workforce_api'):
            module.seed_workforce_api(api,ctx)
        if hasattr(inv,'seed_operations_api'):
            inv.seed_operations_api(api,ctx)
            ctx.scenarios.extend(getattr(inv,'OPERATIONS_SCENARIOS',[]))
        production_quality=importlib.import_module('testdata_production_quality')
        production_quality.seed_api(api,ctx)
        ctx.scenarios.extend(production_quality.SCENARIOS)
        warehouse=importlib.import_module('testdata_warehouse_ops')
        warehouse_api=Api(ctx)
        warehouse.seed_api(warehouse_api,ctx)
        ctx.api_evidence.append({'check':'warehouse_execution_service_calls','calls':warehouse_api.calls,'passed':True})
        ctx.scenarios.extend(getattr(warehouse,'SCENARIOS',[]))
        ctx.api_evidence.append(dict(check='normal_business_service_generation',calls=api.calls,
            receipts=receipts,passed=True))
        normalize_master_snapshots(ctx)
        ctx.conn.commit()
        ctx.save('api-ready',backup=prior['backup'])
    except BaseException:
        ctx.conn.rollback()
        ctx.save('api-failed',backup=prior['backup'])
        raise
    verify(ctx)


def checks_sql(ctx):
    result=[
        ('nonnegative_stock','SELECT COUNT(*) FROM mes_wm_material_stock WHERE quantity<0'),
        ('stock_equals_signed_ledger', '''SELECT COUNT(*) FROM mes_wm_material_stock s LEFT JOIN
            (SELECT material_stock_id,SUM(quantity) q FROM mes_wm_transaction WHERE deleted=0 GROUP BY material_stock_id) t
            ON t.material_stock_id=s.id WHERE s.deleted=0 AND s.quantity<>COALESCE(t.q,0)'''),
        ('transaction_stock_dimensions', '''SELECT COUNT(*) FROM mes_wm_transaction t LEFT JOIN mes_wm_material_stock s ON s.id=t.material_stock_id
            WHERE t.deleted=0 AND (s.id IS NULL OR s.item_id<>t.item_id OR s.tenant_id<>t.tenant_id
            OR NOT(s.batch_id<=>t.batch_id) OR s.warehouse_id<>t.warehouse_id OR s.location_id<>t.location_id OR s.area_id<>t.area_id)'''),
        ('warehouse_hierarchy', '''SELECT COUNT(*) FROM mes_wm_warehouse_area a LEFT JOIN mes_wm_warehouse_location l ON l.id=a.location_id
            LEFT JOIN mes_wm_warehouse w ON w.id=l.warehouse_id WHERE a.deleted=0 AND (l.id IS NULL OR w.id IS NULL OR a.tenant_id<>l.tenant_id OR l.tenant_id<>w.tenant_id)'''),
        ('stock_hierarchy', '''SELECT COUNT(*) FROM mes_wm_material_stock s LEFT JOIN mes_wm_warehouse_area a ON a.id=s.area_id
            LEFT JOIN mes_wm_warehouse_location l ON l.id=s.location_id WHERE s.deleted=0 AND
            (a.id IS NULL OR l.id IS NULL OR a.location_id<>s.location_id OR l.warehouse_id<>s.warehouse_id)'''),
        ('order_progress_limits', 'SELECT COUNT(*) FROM mes_pro_work_order WHERE deleted=0 AND (quantity<=0 OR quantity_produced<0 OR quantity_produced>quantity)'),
        ('feedback_quantity_balance', '''SELECT COUNT(*) FROM mes_pro_feedback WHERE deleted=0 AND
            (feedback_quantity<>COALESCE(qualified_quantity,0)+COALESCE(unqualified_quantity,0)+COALESCE(uncheck_quantity,0)
            OR feedback_quantity<=0)'''),
        ('completed_orders_output', 'SELECT COUNT(*) FROM mes_pro_work_order WHERE deleted=0 AND status=2 AND quantity_produced<>quantity'),
        ('wip_owned_by_order', '''SELECT COUNT(*) FROM mes_wm_material_stock s JOIN mes_wm_warehouse w ON w.id=s.warehouse_id
            WHERE s.deleted=0 AND s.quantity>0 AND w.code='WIP_VIRTUAL_WAREHOUSE' AND COALESCE(s.work_order_id,0)=0'''),
        ('order_parent_links', '''SELECT COUNT(*) FROM mes_pro_work_order w LEFT JOIN mes_pro_work_order p ON p.id=w.parent_id
            WHERE w.deleted=0 AND w.parent_id>0 AND (p.id IS NULL OR p.tenant_id<>w.tenant_id)'''),
        ('feedback_source_balances', '''SELECT COUNT(*) FROM (SELECT s.source_feedback_id,SUM(s.quantity) q,MAX(f.qualified_quantity) available
            FROM mes_pro_feedback_source s JOIN mes_pro_feedback f ON f.id=s.source_feedback_id
            JOIN mes_pro_feedback child ON child.id=s.feedback_id WHERE s.deleted=0 AND s.frozen=1 AND child.status IN(3,4) GROUP BY s.source_feedback_id
            HAVING q>available) x'''),
        ('remaining_cost_layer_quantity', 'SELECT COUNT(*) FROM mes_cost_stock_layer WHERE remaining_quantity<0 OR remaining_quantity>quantity'),
        ('fifo_crosses_supplier_lots', '''SELECT IF(COUNT(DISTINCT d.batch_id)>=2,0,1)
            FROM mes_wm_item_consume_detail d JOIN mes_wm_item_consume c ON c.id=d.consume_id
            JOIN mes_pro_work_order w ON w.id=c.work_order_id
            WHERE d.deleted=0 AND c.deleted=0 AND w.code='TST-MFG-H07' AND d.item_id=900101'''),
        ('fifo_weighted_actual_cost', '''SELECT IF(COUNT(*)=1 AND MIN(c.actual_unit_cost)=25.25,0,1)
            FROM mes_cost_workorder c JOIN mes_pro_work_order w ON w.id=c.workorder_id
            WHERE c.deleted=0 AND w.deleted=0 AND w.code='TST-MFG-H07' AND c.status='SETTLED' '''),
        ('sole_local_tenant','SELECT COUNT(*) FROM system_tenant WHERE id<>1'),
        ('no_saved_biometric_secrets','SELECT COUNT(*) FROM mes_face_credential'),
        ('no_external_model_credentials','SELECT COUNT(*) FROM mes_assistant_model_config'),
        ('no_legacy_work_orders',"SELECT COUNT(*) FROM mes_pro_work_order WHERE deleted=0 AND code NOT LIKE 'TST-%' AND code<>'BROKY-WO-FG-100' AND code<>'DEMO-WO-001'"),
    ]
    # Enforce references for every populated table where a column has one
    # unambiguous MES meaning. Polymorphic source IDs have dedicated module checks.
    refs={'item_id':'mes_md_item','product_id':'mes_md_item','bom_item_id':'mes_md_item','item_type_id':'mes_md_item_type',
          'unit_measure_id':'mes_md_unit_measure','warehouse_id':'mes_wm_warehouse','location_id':'mes_wm_warehouse_location',
          'area_id':'mes_wm_warehouse_area','vendor_id':'mes_md_vendor','client_id':'mes_md_client','workshop_id':'mes_md_workshop',
          'workstation_id':'mes_md_workstation','work_order_id':'mes_pro_work_order','workorder_id':'mes_pro_work_order',
          'process_id':'mes_pro_process','route_id':'mes_pro_route','batch_id':'mes_wm_batch','feedback_id':'mes_pro_feedback',
          'material_stock_id':'mes_wm_material_stock','template_id':'mes_qc_template','indicator_id':'mes_qc_indicator',
          'defect_id':'mes_qc_defect','machinery_id':'mes_dv_machinery','tool_id':'mes_tm_tool'}
    for table,cols in ctx.meta.items():
        if not table.startswith('mes_'): continue
        for col,target in refs.items():
            if col not in cols: continue
            # qc_type-specific template and optional "0 means no selection"
            live='s.deleted=0 AND ' if 'deleted' in cols else ''
            result.append((f'reference:{table}.{col}',f'SELECT COUNT(*) FROM `{table}` s LEFT JOIN `{target}` t ON t.id=s.`{col}` '
                f'WHERE {live}s.`{col}`>0 AND (t.id IS NULL OR t.deleted<>0 OR t.tenant_id<>s.tenant_id)'))
    for name in MODULES:
        module=importlib.import_module(name)
        if hasattr(module,'validation'):
            result.extend(module.validation(ctx))
    warehouse=importlib.import_module('testdata_warehouse_ops')
    if hasattr(warehouse,'validation'):
        result.extend(warehouse.validation(ctx))
    return result


def verify(ctx):
    state=ctx.restore_state()
    if state['phase'] not in ('api-ready','verified','verification-failed'):
        raise RuntimeError('Full validation requires completed API fixtures; interrupted or master-only data cannot be marked verified')
    required=('manufacturing','collaboration','broky_portfolio','workforce_portfolio',
              'inventory_api','inventory_operations','production_quality','warehouse_ops')
    missing=[key for key in required if not ctx.ids.get(key)]
    if missing:
        raise RuntimeError('Required fixture portfolios are incomplete: '+','.join(missing))
    checks=[]
    for name,sql in checks_sql(ctx):
        value=ctx.scalar(sql)
        checks.append(dict(check=name,passed=value==0,violations=value))
    from verify_test_data_api import verify_api
    print('Checking authenticated APIs and effective role permissions',flush=True)
    checks.extend(verify_api(ctx))
    counts={t:int(ctx.scalar(f'SELECT COUNT(*) FROM `{t}`')) for t in ctx.meta if t.startswith('mes_')}
    empty=[t for t,n in counts.items() if n==0]
    failures=[c for c in checks if not c['passed']]
    scenarios=list({json.dumps(s,sort_keys=True,ensure_ascii=False):s for s in ctx.scenarios}.values())
    report=dict(suite=MARKER,generatedAt=datetime.now(TZ).isoformat(),anchor=ctx.now.isoformat(),backup=state['backup'],
        result='PASS' if not failures else 'FAIL',checks=checks,failures=failures,tableCounts=counts,
        populatedMesTables=sum(n>0 for n in counts.values()),totalMesRows=sum(counts.values()),
        emptyMesTables=empty,scenarios=scenarios,apiEvidence=ctx.api_evidence,ids=ctx.ids)
    path=RUNTIME/'testdata-verification.json'
    path.write_text(json.dumps(report,ensure_ascii=False,indent=2,default=serial)+'\n',encoding='utf8')
    ctx.checks=checks
    ctx.save('verified' if not failures else 'verification-failed',backup=state['backup'])
    print(json.dumps({k:report[k] for k in ('result','populatedMesTables','totalMesRows','failures')},ensure_ascii=False,default=serial),flush=True)
    if failures: raise RuntimeError(f'{len(failures)} fixture validation failures; see {path}')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('phase',choices=['reset','api','verify','retain-backups'])
    args=p.parse_args()
    if args.phase == 'retain-backups':
        print(json.dumps({'backupRetention':finalize_backups(RUNTIME)}, ensure_ascii=False), flush=True)
        return
    ctx=Context()
    try:
        {'reset':reset,'api':api_seed,'verify':verify}[args.phase](ctx)
    finally:
        ctx.conn.close()


if __name__=='__main__':
    main()
