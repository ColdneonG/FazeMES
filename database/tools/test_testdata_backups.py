"""File-only regression tests for backup integrity, retention and failure paths."""
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import testdata_backups as target


class BackupRetentionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='MES 备份 test ')
        self.addCleanup(self.directory.cleanup)
        self.runtime = Path(self.directory.name)
        self.root = self.runtime / 'backups'
        self.root.mkdir()

    def dump(self, number, *, registered=True):
        stem = f'testdata-before-20261024-10{number:02}00-{number:06x}'
        path = self.root / (stem + '.sql')
        data = (f'-- sample {number}\n' + 'CREATE TABLE sample;\n' * 251 +
                '-- Dump completed on 2026-10-24 10:00:00\n').encode()
        path.write_bytes(data)
        info = dict(path=str(path), bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), createTables=251)
        if registered:
            target.write_json(self.root / (stem + '.json'), info)
        return info

    def success(self, current, **changes):
        name = Path(current['path']).name
        time = name.split('-')[3]
        anchor = f'2026-10-24T{time[:2]}:{time[2:4]}:{time[4:6]}'
        state = dict(suite=target.SUITE, phase='verified', anchor=anchor, backup=current)
        report = dict(suite=target.SUITE, result='PASS', anchor=anchor, backup=current,
                      checks=[dict(check='fixture', passed=True)], failures=[])
        state.update(changes.get('state', {}))
        report.update(changes.get('report', {}))
        target.write_json(self.runtime / 'testdata-state.json', state)
        target.write_json(self.runtime / 'testdata-verification.json', report)
        return state, report

    def files(self):
        return {file.name: file.read_bytes() for file in self.root.iterdir() if file.is_file()}

    def test_first_backup_is_pinned_and_kept_uncompressed(self):
        raw = self.dump(1, registered=False)
        info = target.register_backup(self.root, Path(raw['path']))
        self.assertEqual(info, raw)
        pin = json.loads((self.root / target.ORIGINAL).read_text(encoding='utf8'))
        self.assertEqual(pin['backup'], raw)
        self.assertTrue(Path(raw['path']).exists())

    def test_later_backup_is_compressed_checked_and_has_both_hashes(self):
        first = self.dump(1)
        raw = self.dump(2, registered=False)
        original_bytes = Path(raw['path']).read_bytes()
        info = target.register_backup(self.root, Path(raw['path']))
        self.assertEqual(gzip.decompress(Path(info['path']).read_bytes()), original_bytes)
        self.assertEqual(info['sqlSha256'], raw['sha256'])
        self.assertEqual(info['sqlBytes'], len(original_bytes))
        self.assertEqual(info['sha256'], hashlib.sha256(Path(info['path']).read_bytes()).hexdigest())
        self.assertLess(info['bytes'], info['sqlBytes'])
        self.assertFalse(Path(raw['path']).exists())
        self.assertEqual(Path(first['path']).read_bytes(), self.files()[Path(first['path']).name])

    def test_compression_failure_preserves_raw_and_every_old_backup(self):
        self.dump(1)
        raw = self.dump(2, registered=False)
        with patch.object(target.shutil, 'copyfileobj', side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'):
                target.register_backup(self.root, Path(raw['path']))
        self.assertTrue(Path(raw['path']).exists())
        self.assertFalse(list(self.root.glob('*.gz')))
        self.assertFalse(list(self.root.glob('*.part-*')))
        self.assertEqual(len(list(self.root.glob('*.sql'))), 2)

    def test_incomplete_dump_is_never_registered(self):
        raw = self.dump(1, registered=False)
        Path(raw['path']).write_bytes(b'CREATE TABLE bad;')
        with self.assertRaisesRegex(RuntimeError, 'completeness'):
            target.register_backup(self.root, Path(raw['path']))
        self.assertFalse(list(self.root.glob('*.json')))

    def test_retention_keeps_original_and_three_recent_compressed(self):
        backups = [self.dump(n) for n in range(1, 8)]
        original_bytes = Path(backups[0]['path']).read_bytes()
        self.success(backups[-1])
        result = target.finalize_backups(self.runtime)
        self.assertEqual(len(result['retained']), 4)
        self.assertEqual(len(result['removed']), 3)
        self.assertEqual(Path(backups[0]['path']).read_bytes(), original_bytes)
        self.assertEqual(len(list(self.root.glob('*.sql'))), 1)
        self.assertEqual(len(list(self.root.glob('*.sql.gz'))), 3)
        records = target.inventory(self.root)
        self.assertEqual(set(records), {Path(backups[n]['path']).stem for n in (0, 4, 5, 6)})
        for document in ('testdata-state.json', 'testdata-verification.json'):
            value = json.loads((self.runtime / document).read_text(encoding='utf8'))
            self.assertTrue(value['backup']['path'].endswith('.sql.gz'))
            self.assertTrue(Path(value['backup']['path']).is_file())

    def test_repeated_finalization_is_idempotent(self):
        self.dump(1)
        current = self.dump(2)
        self.success(current)
        target.finalize_backups(self.runtime)
        before = self.files()
        result = target.finalize_backups(self.runtime)
        self.assertEqual(result['removed'], [])
        self.assertEqual(self.files(), before)

    def test_first_success_with_one_backup_keeps_the_original(self):
        current = self.dump(1)
        self.success(current)
        result = target.finalize_backups(self.runtime)
        self.assertEqual(result['retained'], [current['path']])
        self.assertTrue(Path(current['path']).exists())

    def test_failure_or_stale_verification_cannot_change_backup_files(self):
        backups = [self.dump(n) for n in range(1, 6)]
        cases = [dict(state=dict(phase=phase)) for phase in ('masters-ready', 'reset-failed', 'api-failed', 'verification-failed')]
        cases += [dict(report=dict(result='FAIL')), dict(report=dict(anchor='2026-10-23T10:00:00')),
                  dict(report=dict(checks=[])), dict(report=dict(checks=[dict(passed=False)])),
                  dict(report=dict(failures=['bad'])), dict(state=dict(suite='unrelated'))]
        for changes in cases:
            with self.subTest(changes=changes):
                self.success(backups[-1], **changes)
                before = self.files()
                with self.assertRaisesRegex(RuntimeError, 'matching successful'):
                    target.finalize_backups(self.runtime)
                self.assertEqual(self.files(), before)

    def test_backup_after_old_success_cannot_be_pruned(self):
        self.dump(1)
        current = self.dump(2)
        self.success(current)
        self.dump(3)
        before = self.files()
        with self.assertRaisesRegex(RuntimeError, 'newer or mismatched'):
            target.finalize_backups(self.runtime)
        self.assertEqual(self.files(), before)

    def test_report_backup_must_match_checked_current_payload(self):
        first, current = self.dump(1), self.dump(2)
        self.success(current, report=dict(backup=first))
        before = self.files()
        with self.assertRaisesRegex(RuntimeError, 'current checked backup'):
            target.finalize_backups(self.runtime)
        self.assertEqual(self.files(), before)

    def test_unrelated_files_and_partial_dumps_are_preserved(self):
        backups = [self.dump(n) for n in range(1, 6)]
        untouched = ['pre-feature.sql', 'yudao-server.previous.jar', 'testdata-ready-20261024-103304.sql',
                     'testdata-before-20261024-105500-aaaaaa.sql', 'testdata-before-partial.sql.part']
        for name in untouched:
            (self.root / name).write_bytes(b'unrelated data')
        self.success(backups[-1])
        target.finalize_backups(self.runtime)
        for name in untouched:
            self.assertEqual((self.root / name).read_bytes(), b'unrelated data')

    def test_corrupt_managed_backup_prevents_all_pruning(self):
        backups = [self.dump(n) for n in range(1, 6)]
        self.success(backups[-1])
        Path(backups[1]['path']).write_bytes(b'corrupt')
        before = self.files()
        with self.assertRaisesRegex(RuntimeError, 'completeness'):
            target.finalize_backups(self.runtime)
        self.assertEqual(self.files(), before)

    def test_pinned_original_cannot_be_replaced_by_a_new_earlier_filename(self):
        first = self.dump(2)
        target.pin_original(self.root, target.inventory(self.root))
        self.dump(1)
        current = self.dump(3)
        self.success(current)
        result = target.finalize_backups(self.runtime)
        self.assertEqual(result['original'], first['path'])
        self.assertTrue(Path(first['path']).exists())

    def test_missing_pinned_original_stops_before_any_deletion(self):
        first, current = self.dump(1), self.dump(2)
        target.pin_original(self.root, target.inventory(self.root))
        Path(first['path']).unlink()
        (self.root / (Path(first['path']).stem + '.json')).unlink()
        self.success(current)
        before = self.files()
        with self.assertRaisesRegex(RuntimeError, 'Pinned original backup'):
            target.finalize_backups(self.runtime)
        self.assertEqual(self.files(), before)

    def test_manifest_cannot_point_outside_backup_directory(self):
        self.dump(1)
        current = self.dump(2)
        outside = self.runtime / Path(current['path']).name
        outside.write_bytes(Path(current['path']).read_bytes())
        changed = dict(current, path=str(outside))
        target.write_json(self.root / (outside.stem + '.json'), changed)
        self.success(current)
        before = self.files()
        with self.assertRaisesRegex(RuntimeError, 'outside'):
            target.finalize_backups(self.runtime)
        self.assertEqual(self.files(), before)
        self.assertTrue(outside.exists())

    def test_compressed_corruption_is_detected(self):
        self.dump(1)
        raw = self.dump(2, registered=False)
        info = target.register_backup(self.root, Path(raw['path']))
        self.success(info)
        path = Path(info['path'])
        path.write_bytes(path.read_bytes()[:-5])
        before = self.files()
        with self.assertRaises((EOFError, OSError, RuntimeError)):
            target.finalize_backups(self.runtime)
        self.assertEqual(self.files(), before)

    def test_compression_failure_during_migration_does_not_prune(self):
        backups = [self.dump(n) for n in range(1, 6)]
        self.success(backups[-1])
        with patch.object(target.shutil, 'copyfileobj', side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'):
                target.finalize_backups(self.runtime)
        self.assertEqual(len(list(self.root.glob('*.sql'))), 5)

    def test_interrupted_compression_can_reuse_checked_gzip(self):
        raw = self.dump(2)
        path = Path(raw['path'])
        destination = path.with_suffix('.sql.gz')
        destination.write_bytes(gzip.compress(path.read_bytes(), mtime=0))
        result = target.compress_backup(self.root, path.stem, raw)
        self.assertEqual(result['path'], str(destination))
        self.assertFalse(path.exists())
        target.checked_info(self.root, result, path.stem)

    def test_partial_migration_can_resume_with_old_state_paths(self):
        backups = [self.dump(n) for n in range(1, 6)]
        self.success(backups[-1])
        copy = target.shutil.copyfileobj
        calls = 0

        def fail_second_copy(*args):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError('disk full on older backup')
            return copy(*args)

        with patch.object(target.shutil, 'copyfileobj', side_effect=fail_second_copy):
            with self.assertRaisesRegex(OSError, 'disk full'):
                target.finalize_backups(self.runtime)
        self.assertEqual(len(target.inventory(self.root)), 5)
        self.assertFalse(Path(backups[-1]['path']).exists())
        result = target.finalize_backups(self.runtime)
        self.assertEqual(len(result['retained']), 4)
        self.assertEqual(len(result['removed']), 1)

    def test_interrupted_raw_removal_is_retried_after_manifest_commit(self):
        self.dump(1)
        raw = self.dump(2, registered=False)
        source = Path(raw['path'])
        unlink = Path.unlink

        def fail_raw(path, *args, **kwargs):
            if path == source:
                raise PermissionError('raw file busy')
            return unlink(path, *args, **kwargs)

        with patch.object(Path, 'unlink', fail_raw):
            with self.assertRaisesRegex(PermissionError, 'raw file busy'):
                target.register_backup(self.root, source)
        self.assertTrue(source.exists())
        info = target.inventory(self.root)[source.stem]
        target.compress_backup(self.root, source.stem, info)
        self.assertFalse(source.exists())
        self.assertTrue(Path(info['path']).exists())

    def test_interrupted_retirement_does_not_break_next_run(self):
        backups = [self.dump(n) for n in range(1, 6)]
        self.success(backups[-1])
        obsolete = Path(backups[1]['path'])
        unlink = Path.unlink

        def fail_old_file(path, *args, **kwargs):
            if path == obsolete:
                raise PermissionError('backup busy')
            return unlink(path, *args, **kwargs)

        with patch.object(Path, 'unlink', fail_old_file):
            with self.assertRaisesRegex(PermissionError, 'backup busy'):
                target.finalize_backups(self.runtime)
        self.assertEqual(len(target.inventory(self.root)), 4)
        self.assertTrue(list(self.root.glob('*.retired')))
        result = target.finalize_backups(self.runtime)
        self.assertEqual(len(result['removed']), 1)
        self.assertFalse(list(self.root.glob('*.retired')))
        self.assertFalse(obsolete.exists())


if __name__ == '__main__':
    unittest.main()
