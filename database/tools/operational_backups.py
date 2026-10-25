"""Daily checked logical backups for the dedicated local FazeMES database.

No database mutation or restore is performed. Credentials remain in the local
MySQL option file. Reset-test-data recovery points have a separate policy.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

SHANGHAI = timezone(timedelta(hours=8), 'Asia/Shanghai')
SUITE = 'fazemes-operational-backup-v1'
RETAIN_DAYS = 30
NAME = re.compile(r'ops-\d{8}-020000-(?:daily|weekly)-[0-9a-f]{12}\Z')
DATABASE = 'yudao_mes'


class BackupError(RuntimeError):
    pass


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.part-' + uuid.uuid4().hex)
    try:
        with temporary.open('x', encoding='utf-8') as output:
            json.dump(value, output, ensure_ascii=False, indent=2)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def checked_directory(path):
    path = Path(path)
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink() or ancestor.is_junction():
            raise BackupError('Backup paths must not contain links or junctions')
    path.mkdir(parents=True, exist_ok=True)
    return path.resolve()


def local_file(directory, name):
    directory, path = Path(directory), Path(directory) / name
    if Path(name).name != str(name) or path.is_symlink() or path.is_junction():
        raise BackupError('Unmanaged backup path')
    if path.resolve().parent != directory.resolve():
        raise BackupError('Backup path is outside its dedicated directory')
    return path


@contextmanager
def file_lock(path):
    """An OS byte-range lock also used by PowerShell migration/reset entry points."""
    path = Path(path)
    if path.is_symlink() or path.is_junction():
        raise BackupError('Lock path must not be a link')
    stream = path.open('a+b')
    locked = False
    try:
        if path.stat().st_size == 0:
            stream.write(b'\0')
            stream.flush()
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        locked = True
        yield
    except OSError as error:
        if not locked:
            raise BackupError('A backup or maintenance operation is already running') from error
        raise
    finally:
        if locked:
            stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_UN)
        stream.close()


def due_slot(now):
    now = now.astimezone(SHANGHAI)
    slot = now.replace(hour=2, minute=0, second=0, microsecond=0)
    return slot if now >= slot else slot - timedelta(days=1)


def run_command(arguments, *, timeout=1200, stopping=lambda: False):
    """Never print command output: diagnostics can include connection secrets."""
    process = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    started = time.monotonic()
    try:
        while True:
            if stopping():
                raise BackupError('Backup stopped before completion')
            if time.monotonic() - started >= timeout:
                raise BackupError('Backup command exceeded its time limit')
            try:
                stdout, _ = process.communicate(timeout=1)
                if process.returncode:
                    raise BackupError('MySQL backup command failed; verify local database access and backup privileges')
                return stdout.decode('utf-8', errors='strict')
            except subprocess.TimeoutExpired:
                continue
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()


def inspect_sql(path, expected_tables):
    digest, size, tables, tail = hashlib.sha256(), 0, 0, b''
    opener = gzip.open if path.name.endswith('.gz') else open
    with opener(path, 'rb') as source:
        for line in source:
            digest.update(line)
            size += len(line)
            if re.match(br'^CREATE TABLE `[^`]+` \(', line):
                tables += 1
            tail = (tail + line)[-1024:]
    if expected_tables < 1 or tables != expected_tables or b'-- Dump completed on ' not in tail:
        raise BackupError('Dump completeness check failed; no backup was accepted or expired backups removed')
    return dict(sqlBytes=size, sqlSha256=digest.hexdigest(), createTables=tables)


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def manifests(directory):
    result = []
    for path in sorted(directory.glob('ops-*.json')):
        if not NAME.fullmatch(path.stem):
            continue
        path = local_file(directory, path.name)
        try:
            info = json.loads(path.read_text(encoding='utf-8'))
            scheduled = datetime.fromisoformat(info['scheduledFor'])
            started = datetime.fromisoformat(info['startedAt'])
            completed = datetime.fromisoformat(info['completedAt'])
            prefix = ('ops-' + scheduled.astimezone(SHANGHAI).strftime('%Y%m%d-%H%M%S-') +
                      ('weekly-' if scheduled.weekday() == 6 else 'daily-'))
            if (info['suite'] != SUITE or info['version'] != 1 or
                    info['payload'] != path.stem + '.sql.gz' or
                    not path.stem.startswith(prefix) or info['database'] != DATABASE or
                    scheduled.tzinfo is None or started.tzinfo is None or completed.tzinfo is None or
                    scheduled != due_slot(scheduled) or started < scheduled or completed < started or
                    info['kind'] != ('WEEKLY_FULL' if scheduled.weekday() == 6 else 'DAILY_FULL') or
                    not re.fullmatch(r'[0-9a-f]{64}', info['sha256']) or
                    not re.fullmatch(r'[0-9a-f]{64}', info['sqlSha256']) or
                    not isinstance(info['createTables'], int) or info['createTables'] < 1):
                raise ValueError('Invalid operational backup manifest')
            result.append((path, info))
        except (ValueError, KeyError, TypeError) as error:
            raise BackupError('Operational backup manifest is invalid; existing backups were retained') from error
    return result


def verify(directory, info):
    path = local_file(directory, info['payload'])
    if not path.is_file() or path.stat().st_size != info['bytes'] or digest(path) != info['sha256']:
        raise BackupError('Backup payload checksum check failed; existing backups were retained')
    sql = inspect_sql(path, info['createTables'])
    if any(sql[key] != info[key] for key in sql):
        raise BackupError('Backup SQL checksum check failed; existing backups were retained')
    return path


def retain(directory, records, now):
    """Verify every managed record before deleting anything, keep latest daily/weekly."""
    for _, info in records:
        verify(directory, info)
    newest = set()
    for kind in ('DAILY_FULL', 'WEEKLY_FULL'):
        candidates = [(path, info) for path, info in records if info['kind'] == kind]
        if candidates:
            newest.add(max(candidates, key=lambda pair: datetime.fromisoformat(pair[1]['completedAt']))[0].name)
    cutoff = now.astimezone(SHANGHAI) - timedelta(days=RETAIN_DAYS)
    removed = []
    for manifest, info in records:
        if manifest.name not in newest and datetime.fromisoformat(info['completedAt']) < cutoff:
            # Retire the manifest first. Interrupted cleanup only leaves an unreferenced payload.
            retired = local_file(directory, manifest.name + '.retired')
            manifest.replace(retired)
            local_file(directory, info['payload']).unlink()
            retired.unlink()
            removed.append(info['payload'])
    return removed


class Backups:
    def __init__(self, project, mysql, mysqldump, *, runner=run_command, stopping=lambda: False):
        self.project = Path(project).resolve()
        self.runtime = checked_directory(self.project / '.runtime')
        self.directory = checked_directory(self.runtime / 'backups' / 'operations')
        self.mysql, self.mysqldump = str(mysql), str(mysqldump)
        self.runner, self.stopping = runner, stopping
        # Stored-routine definitions need metadata privileges the application
        # account does not have. Use the existing local administrator option
        # file without broadening the application's database privileges.
        self.options = self.runtime / 'mysql-admin.local.ini'

    def preflight(self):
        if not self.options.is_file() or self.options.is_symlink() or self.options.is_junction():
            raise BackupError('The local MySQL option file is required')
        # Explicit endpoint options prevent a modified option file from targeting another server.
        arguments = [self.mysql, '--defaults-extra-file=' + str(self.options), '--protocol=TCP',
                     '--host=127.0.0.1', '--port=3308', '--database=' + DATABASE,
                     '--batch', '--skip-column-names', '--execute=SELECT @@datadir;'
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE';"
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND ENGINE<>'InnoDB';"]
        result = self.runner(arguments, timeout=30, stopping=self.stopping).strip().splitlines()
        try:
            data, tables, unsupported = result
            if Path(data.strip()).resolve() != (self.runtime / 'mysql' / 'data').resolve():
                raise BackupError('Database data directory does not belong to this project')
            count = int(tables)
            if count < 1 or int(unsupported):
                raise BackupError('Consistent backup requires a nonempty InnoDB database')
            return count
        except ValueError as error:
            raise BackupError('Unexpected database ownership response') from error

    def due(self, now=None):
        now = (now or datetime.now(SHANGHAI)).astimezone(SHANGHAI)
        slot = due_slot(now)
        with file_lock(self.runtime / 'operations-backup.lock'):
            with file_lock(self.runtime / 'maintenance.lock'):
                records = manifests(self.directory)
                for _, info in records:
                    if datetime.fromisoformat(info['scheduledFor']) == slot:
                        verify(self.directory, info)
                        return dict(status='ALREADY_COMPLETED', scheduledFor=slot.isoformat())
                count = self.preflight()
                weekly = slot.weekday() == 6
                stem = 'ops-' + slot.strftime('%Y%m%d-%H%M%S-') + ('weekly-' if weekly else 'daily-') + uuid.uuid4().hex[:12]
                sql = local_file(self.directory, stem + '.sql.part')
                compressed = local_file(self.directory, stem + '.sql.gz.part')
                final = local_file(self.directory, stem + '.sql.gz')
                try:
                    args = [self.mysqldump, '--defaults-extra-file=' + str(self.options), '--protocol=TCP',
                            '--host=127.0.0.1', '--port=3308', '--single-transaction', '--quick',
                            '--no-tablespaces', '--set-gtid-purged=OFF', '--column-statistics=0',
                            '--routines', '--events', '--triggers', '--hex-blob', '--default-character-set=utf8mb4',
                            '--databases', DATABASE, '--result-file=' + str(sql)]
                    self.runner(args, timeout=1200, stopping=self.stopping)
                    basis = inspect_sql(sql, count)
                    with sql.open('rb') as source, compressed.open('xb') as output:
                        with gzip.GzipFile(filename='', fileobj=output, mode='wb', mtime=0) as packed:
                            while chunk := source.read(1024 * 1024):
                                if self.stopping():
                                    raise BackupError('Backup stopped before completion')
                                packed.write(chunk)
                        output.flush()
                        os.fsync(output.fileno())
                    # Read the full gzip stream and verify its CRC before the atomic promotion.
                    with gzip.open(compressed, 'rb') as source:
                        if hashlib.file_digest(source, 'sha256').hexdigest() != basis['sqlSha256']:
                            raise BackupError('Compressed backup read-back check failed')
                    compressed.replace(final)
                    info = dict(version=1, suite=SUITE, database=DATABASE,
                                kind='WEEKLY_FULL' if weekly else 'DAILY_FULL',
                                trigger='ON_TIME' if now - slot < timedelta(minutes=5) else 'CATCH_UP',
                                scheduledFor=slot.isoformat(), startedAt=now.isoformat(),
                                completedAt=datetime.now(SHANGHAI).isoformat(), payload=final.name,
                                bytes=final.stat().st_size, sha256=digest(final), **basis)
                    # Tests may use a future clock; completion cannot precede its scheduled slot.
                    info['completedAt'] = max(now, datetime.fromisoformat(info['completedAt'])).isoformat()
                    verify(self.directory, info)
                    atomic_json(local_file(self.directory, stem + '.json'), info)
                    records = manifests(self.directory)
                    removed = retain(self.directory, records, datetime.fromisoformat(info['completedAt']))
                    return dict(status='COMPLETED', scheduledFor=slot.isoformat(), kind=info['kind'],
                                payload=final.name, removed=removed)
                finally:
                    sql.unlink(missing_ok=True)
                    compressed.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root', type=Path, required=True)
    parser.add_argument('--mysql-client', type=Path, required=True)
    parser.add_argument('--mysqldump', type=Path, required=True)
    parser.add_argument('--mode', choices=['due', 'daemon', 'verify'], default='due')
    parser.add_argument('--instance-id', default='')
    args = parser.parse_args()
    runtime = checked_directory(args.project_root.resolve() / '.runtime')
    stop = runtime / 'backup.stop'
    stopping = (lambda: stop.exists()) if args.mode == 'daemon' else (lambda: False)
    target = Backups(args.project_root, args.mysql_client, args.mysqldump, stopping=stopping)
    status_file = runtime / 'operational-backup-status.json'
    if args.mode == 'verify':
        records = manifests(target.directory)
        for _, info in records:
            verify(target.directory, info)
        print(json.dumps(dict(status='VERIFIED', backups=len(records))))
        return
    def once():
        try:
            result = target.due()
            atomic_json(status_file, dict(**result, checkedAt=datetime.now(SHANGHAI).isoformat()))
            return result['scheduledFor']
        except Exception as error:
            # No raw subprocess diagnostic, command or connection configuration is persisted.
            message = str(error) if isinstance(error, BackupError) else 'Operational backup failed; existing backups retained'
            atomic_json(status_file, dict(status='FAILED', checkedAt=datetime.now(SHANGHAI).isoformat(), message=message))
            if args.mode != 'daemon':
                raise BackupError(message) from error
            return None
    if args.mode == 'due':
        once()
        return
    with file_lock(runtime / 'backup-scheduler.lock'):
        atomic_json(runtime / 'backup-scheduler-ready.json', dict(pid=os.getpid(), instanceId=args.instance_id,
                                                                startedAt=datetime.now(SHANGHAI).isoformat()))
        completed_slot = None
        while not stopping():
            slot = due_slot(datetime.now(SHANGHAI)).isoformat()
            if slot != completed_slot:
                completed_slot = once()
            for _ in range(60):
                if stopping():
                    return
                time.sleep(1)


if __name__ == '__main__':
    try:
        main()
    except BackupError as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(1)
