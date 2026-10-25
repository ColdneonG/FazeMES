"""File-only backup regressions. No MySQL process, service or live database is used."""
from datetime import datetime, timedelta
import gzip
import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

import operational_backups as target


class OperationalBackupsTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory(prefix='FazeMES ops 备份 ')
        self.addCleanup(folder.cleanup)
        self.project = Path(folder.name)
        self.runtime = self.project / '.runtime'
        (self.runtime / 'mysql/data').mkdir(parents=True)
        (self.runtime / 'mysql-admin.local.ini').write_text('[client]\npassword=do-not-expose\n')
        self.calls = []
        self.worker = target.Backups(self.project, 'mysql-fixture.exe', 'dump-fixture.exe', runner=self.runner)
        self.now = datetime(2026, 10, 25, 9, tzinfo=target.SHANGHAI)

    def sql(self):
        return b'-- MySQL dump\nCREATE TABLE `one` (\n);\nCREATE TABLE `two` (\n);\n-- Dump completed on fixture\n'

    def runner(self, arguments, **kwargs):
        self.calls.append(arguments)
        if arguments[0] == 'mysql-fixture.exe':
            return str(self.runtime / 'mysql/data') + '\n2\n0\n'
        result_path = Path(next(arg.split('=', 1)[1] for arg in arguments if arg.startswith('--result-file=')))
        result_path.write_bytes(self.sql())
        return ''

    def snapshots(self):
        return {path.name: path.read_bytes() for path in self.worker.directory.iterdir() if path.is_file()}

    def seed(self, now, *, weekly=None):
        slot = target.due_slot(now)
        if weekly is not None:
            while (slot.weekday() == 6) != weekly:
                slot -= timedelta(days=1)
        clock = slot + timedelta(hours=1)
        class FixedClock(datetime):
            @classmethod
            def now(cls, tz=None):
                return clock.astimezone(tz) if tz else clock.replace(tzinfo=None)
        with patch.object(target, 'datetime', FixedClock):
            result = self.worker.due(clock)
        return result

    def test_shanghai_slot_and_week_boundary(self):
        before = datetime(2026, 10, 25, 1, 59, 59, tzinfo=target.SHANGHAI)
        self.assertEqual(target.due_slot(before).isoformat(), '2026-10-24T02:00:00+08:00')
        self.assertEqual(target.due_slot(before + timedelta(seconds=1)).isoformat(), '2026-10-25T02:00:00+08:00')
        self.assertEqual(target.due_slot(self.now.astimezone(target.timezone.utc)), target.due_slot(self.now))

    def test_weekly_full_gzip_checksums_manifest_and_catchup(self):
        result = self.seed(self.now)
        self.assertEqual(result['kind'], 'WEEKLY_FULL')
        records = target.manifests(self.worker.directory)
        self.assertEqual(len(records), 1)
        info = records[0][1]
        self.assertEqual(info['trigger'], 'CATCH_UP')
        payload = target.verify(self.worker.directory, info)
        self.assertEqual(gzip.decompress(payload.read_bytes()), self.sql())
        self.assertEqual(info['createTables'], 2)
        self.assertFalse(list(self.worker.directory.glob('*.part*')))

    def test_daily_is_also_a_complete_backup(self):
        result = self.seed(self.now - timedelta(days=1))
        self.assertEqual(result['kind'], 'DAILY_FULL')
        self.assertEqual(target.manifests(self.worker.directory)[0][1]['createTables'], 2)

    def test_same_slot_is_idempotent_without_database_access(self):
        self.seed(self.now)
        self.calls.clear()
        before = self.snapshots()
        self.assertEqual(self.worker.due(self.now)['status'], 'ALREADY_COMPLETED')
        self.assertEqual(before, self.snapshots())
        self.assertEqual(self.calls, [])

    def test_db_endpoint_is_pinned_and_credentials_not_in_arguments(self):
        self.seed(self.now)
        for call in self.calls:
            joined = ' '.join(call)
            self.assertNotIn('do-not-expose', joined)
            self.assertNotIn('--password', joined)
            self.assertIn('--defaults-extra-file=' + str(self.runtime / 'mysql-admin.local.ini'), call)
            self.assertIn('--host=127.0.0.1', call)
            self.assertIn('--port=3308', call)
        dump = self.calls[1]
        for option in ('--single-transaction', '--routines', '--events', '--triggers', '--hex-blob', '--no-tablespaces'):
            self.assertIn(option, dump)
        self.assertEqual(dump[dump.index('--databases') + 1], 'yudao_mes')

    def test_foreign_data_directory_rejected_before_dump(self):
        self.worker.runner = lambda *a, **k: 'C:\\another\\database\n2\n0\n'
        with self.assertRaisesRegex(target.BackupError, 'does not belong'):
            self.worker.due(self.now)
        self.assertFalse(list(self.worker.directory.glob('*.json')))

    def test_nontransactional_tables_are_not_misrepresented_as_consistent(self):
        self.worker.runner = lambda *a, **k: str(self.runtime / 'mysql/data') + '\n2\n1\n'
        with self.assertRaisesRegex(target.BackupError, 'InnoDB'):
            self.worker.due(self.now)

    def test_incomplete_dump_or_nonzero_exit_keeps_all_previous_backups(self):
        self.seed(self.now - timedelta(days=50))
        before = self.snapshots()
        normal = self.runner
        for failure in ('incomplete', 'command'):
            def fail(arguments, **kwargs):
                if arguments[0] == 'mysql-fixture.exe':
                    return normal(arguments, **kwargs)
                if failure == 'command':
                    raise target.BackupError('MySQL backup command failed')
                Path(next(arg.split('=', 1)[1] for arg in arguments if arg.startswith('--result-file='))).write_bytes(b'CREATE TABLE `one` (\n);')
                return ''
            self.worker.runner = fail
            with self.subTest(failure=failure), self.assertRaises(target.BackupError):
                self.worker.due(self.now)
            self.assertEqual(before, self.snapshots())

    def test_corrupt_payload_blocks_skip_and_all_retention(self):
        self.seed(self.now - timedelta(days=60))
        old_path, old = target.manifests(self.worker.directory)[0]
        payload = self.worker.directory / old['payload']
        payload.write_bytes(payload.read_bytes() + b'corruption')
        before = self.snapshots()
        with self.assertRaisesRegex(target.BackupError, 'checksum'):
            self.worker.due(self.now)
        self.assertEqual(old_path.read_bytes(), before[old_path.name])
        self.assertEqual(payload.read_bytes(), before[payload.name])
        self.assertTrue(old_path.exists())

    def test_retention_keeps_30_days_and_latest_of_each_kind(self):
        removed = []
        for days, weekly in ((70, True), (60, False), (40, False), (29, False), (10, True), (0, True)):
            removed.extend(self.seed(self.now - timedelta(days=days), weekly=weekly)['removed'])
        self.assertEqual(len(removed), 3)
        retained = target.manifests(self.worker.directory)
        self.assertEqual(len(retained), 3)
        self.assertTrue(any(datetime.fromisoformat(i['completedAt']) > self.now - timedelta(days=30) for _, i in retained))
        for _, info in retained:
            target.verify(self.worker.directory, info)

    def test_latest_old_weekly_survives_when_only_daily_backups_follow(self):
        self.seed(self.now - timedelta(days=70), weekly=True)
        self.seed(self.now - timedelta(days=5), weekly=False)
        self.seed(self.now - timedelta(days=1), weekly=False)
        records = target.manifests(self.worker.directory)
        self.assertTrue(any(i['kind'] == 'WEEKLY_FULL' for _, i in records))

    def test_unrelated_files_and_reset_recovery_points_are_never_managed(self):
        ordinary = self.worker.directory / 'manual.sql'
        ordinary.write_bytes(b'manual')
        reset = self.runtime / 'backups/testdata-original-backup.json'
        reset.write_bytes(b'original recovery policy')
        self.seed(self.now)
        self.assertEqual(ordinary.read_bytes(), b'manual')
        self.assertEqual(reset.read_bytes(), b'original recovery policy')

    def test_manifest_cannot_escape_backup_directory(self):
        self.seed(self.now)
        path, info = target.manifests(self.worker.directory)[0]
        info['payload'] = '../mysql-client.local.ini'
        target.atomic_json(path, info)
        before = self.snapshots()
        with self.assertRaisesRegex(target.BackupError, 'manifest'):
            self.worker.due(self.now)
        self.assertEqual(before, self.snapshots())

    def test_concurrent_backup_or_migration_lock_prevents_any_database_access(self):
        for name in ('operations-backup.lock', 'maintenance.lock'):
            with self.subTest(name=name), target.file_lock(self.runtime / name):
                with self.assertRaisesRegex(target.BackupError, 'already running'):
                    self.worker.due(self.now)
                self.assertEqual(self.calls, [])

    def test_stop_before_compression_preserves_previous_recovery_points(self):
        self.seed(self.now - timedelta(days=3))
        before = self.snapshots()
        self.worker.stopping = lambda: True
        with self.assertRaisesRegex(target.BackupError, 'stopped'):
            self.worker.due(self.now)
        self.assertEqual(before, self.snapshots())

    def test_compression_failure_does_not_prune_or_accept_partial_backup(self):
        self.seed(self.now - timedelta(days=50))
        before = self.snapshots()
        with patch.object(target.gzip, 'GzipFile', side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'):
                self.worker.due(self.now)
        self.assertEqual(before, self.snapshots())

    def test_subprocess_failure_does_not_expose_diagnostic_secrets(self):
        with self.assertRaises(target.BackupError) as failure:
            target.run_command([sys.executable, '-c', "import sys; print('private-password',file=sys.stderr); sys.exit(1)"])
        self.assertNotIn('private-password', str(failure.exception))

    def test_stopped_or_timed_out_command_is_terminated(self):
        for kwargs in (dict(stopping=lambda: True), dict(timeout=0)):
            with self.subTest(kwargs=kwargs), self.assertRaises(target.BackupError):
                target.run_command([sys.executable, '-c', 'import time; time.sleep(30)'], **kwargs)


if __name__ == '__main__':
    unittest.main()
