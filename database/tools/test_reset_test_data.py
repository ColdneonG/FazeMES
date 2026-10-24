"""Safety regression for the reset harness; no running services are needed."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import reset_test_data as target


class ResetSafetyTest(unittest.TestCase):
    def identity(self, **changes):
        row={'port':3308,'db':'yudao_mes','datadir':str(target.RUNTIME/'mysql/data')}
        row.update(changes)
        return SimpleNamespace(query=MagicMock(return_value=[row]),now=datetime(2026,10,24,10))

    def test_foreign_database_is_rejected_before_dump(self):
        for change in ({'db':'other'},{'port':3306},{'datadir':r'C:\unrelated\mysql'}):
            with self.subTest(change=change),patch.object(target.subprocess,'run') as run:
                with self.assertRaisesRegex(RuntimeError,'Reset refused'):
                    target.backup(self.identity(**change))
                run.assert_not_called()

    def test_running_writer_is_rejected_before_dump(self):
        with patch.object(target.socket,'create_connection',return_value=MagicMock()),patch.object(target.subprocess,'run') as run:
            with self.assertRaisesRegex(RuntimeError,'Stop the managed backend'):
                target.backup(self.identity())
            run.assert_not_called()

    def test_failed_backup_cannot_reach_deletion(self):
        ctx=SimpleNamespace(execute=MagicMock())
        with patch.object(target.importlib,'import_module',return_value=MagicMock()),patch.object(target,'backup',side_effect=RuntimeError('dump failed')):
            with self.assertRaisesRegex(RuntimeError,'dump failed'):
                target.reset(ctx)
        ctx.execute.assert_not_called()

    def test_sql_import_pins_verified_local_instance(self):
        with patch.object(target.subprocess,'run',return_value=SimpleNamespace(returncode=0,stderr='')) as run:
            target.mysql_source(Path('fixture.sql'))
        args=run.call_args.args[0]
        for option in ('--protocol=TCP','--host=127.0.0.1','--port=3308','--database=yudao_mes'):
            self.assertIn(option,args)

    def test_incomplete_phase_cannot_be_certified(self):
        for phase in ('masters-ready','reset-failed','api-failed'):
            ctx=SimpleNamespace(restore_state=lambda:{'phase':phase})
            with self.subTest(phase=phase),self.assertRaisesRegex(RuntimeError,'completed API fixtures'):
                target.verify(ctx)

    def api(self, path):
        api=target.Api.__new__(target.Api)
        api.headers={'Content-Type':'application/json'}
        api.calls=0
        api.journal_path=path
        api.journal=[]
        api.position=0
        api.Request=lambda *a,**kw:None
        api.urlopen=MagicMock()
        return api

    def response(self, body):
        from io import StringIO
        result=MagicMock()
        result.__enter__.return_value=StringIO(json.dumps(body))
        return result

    def test_business_rejection_is_not_checkpointed(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'journal.jsonl'
            api=self.api(path)
            api.urlopen.return_value=self.response({'code':1040708001,'msg':'wrong state'})
            with self.assertRaisesRegex(RuntimeError,'1040708001'):
                api.call('POST','/mes/sample/create',{'quantity':1})
            self.assertFalse(path.exists())

    def test_authentication_secrets_are_never_checkpointed(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'journal.jsonl'
            api=self.api(path)
            api.urlopen.return_value=self.response({'code':0,'data':{'accessToken':'private-value'}})
            self.assertEqual(api.call('POST','/system/auth/login',{'password':'private-password'})['accessToken'],'private-value')
            self.assertFalse(path.exists())

    def test_checkpoint_replay_does_not_repeat_mutation(self):
        api=self.api(Path('unused'))
        request=['PUT','/mes/wm/item-receipt/finish',None,{'id':123}]
        digest=hashlib.sha256(json.dumps(request,sort_keys=True,default=target.serial).encode()).hexdigest()
        api.journal=[{'requestHash':digest,'result':True}]
        self.assertTrue(api.call(request[0],request[1],id=123))
        api.urlopen.assert_not_called()
        self.assertEqual(api.position,1)

    def test_changed_checkpoint_request_requires_new_reset(self):
        api=self.api(Path('unused'))
        api.journal=[{'requestHash':'different','result':True}]
        with self.assertRaisesRegex(RuntimeError,'replay diverged'):
            api.call('POST','/mes/sample/create',{'quantity':2})
        api.urlopen.assert_not_called()

    def test_unknown_seed_column_is_rejected_before_write(self):
        ctx=target.Context.__new__(target.Context)
        ctx.meta={'sample':{'id':{}}}
        ctx.execute=MagicMock()
        with self.assertRaisesRegex(ValueError,'unknown columns'):
            ctx.add('sample',invented_column=1)
        ctx.execute.assert_not_called()


if __name__=='__main__':
    unittest.main()
