"""Checked local dump compression and success-only retention for test-data resets.

Only testdata-before-<timestamp>-<id>.json and their checked payloads are managed.
The first backup is pinned permanently; unrelated files and partial dumps stay put.
Callers must hold the project's startup/reset mutex during all mutations.
"""
from __future__ import annotations

from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import uuid

SUITE = 'fazemes-test-suite-v1'
KEEP_RECENT = 3
MANIFEST = re.compile(r'testdata-before-\d{8}-\d{6}-[0-9a-f]{6}\.json\Z')
ORIGINAL = 'testdata-original-backup.json'


def write_json(path, value):
    temporary = path.with_name(path.name + '.tmp-' + uuid.uuid4().hex)
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _local_file(root, path):
    root, path = Path(root), Path(path)
    # Reject junctions and links before resolving. Cleanup is deliberately flat.
    if root.is_symlink() or root.is_junction() or path.is_symlink() or path.is_junction():
        raise RuntimeError('Backup links/junctions are not managed')
    if path.resolve().parent != root.resolve():
        raise RuntimeError(f'Backup path is outside the dedicated backup directory: {path.name}')
    return path


def _digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def inspect_dump(path):
    digest, size, tables, tail = hashlib.sha256(), 0, 0, b''
    opener = gzip.open if path.name.endswith('.sql.gz') else open
    with opener(path, 'rb') as source:
        for line in source:
            digest.update(line)
            size += len(line)
            tables += line.count(b'CREATE TABLE')
            tail = (tail + line)[-500:]
    if tables < 200 or b'-- Dump completed on ' not in tail:
        raise RuntimeError(f'Backup completeness verification failed: {path.name}')
    return dict(sqlBytes=size, sqlSha256=digest.hexdigest(), createTables=tables)


def checked_info(root, info, stem):
    path = _local_file(root, Path(info['path']))
    if path.name not in (stem + '.sql', stem + '.sql.gz') or not path.is_file():
        raise RuntimeError(f'Backup payload is missing or has an unexpected name: {stem}')
    dump = inspect_dump(path)
    digest = dump['sqlSha256'] if path.suffix == '.sql' else _digest(path)
    if (path.stat().st_size != info['bytes'] or digest != info['sha256'] or
            dump['createTables'] != info['createTables']):
        raise RuntimeError(f'Backup checksum/size mismatch: {path.name}')
    if path.name.endswith('.gz') and (info.get('compression') != 'gzip' or
            any(info.get(key) != dump[key] for key in ('sqlBytes', 'sqlSha256'))):
        raise RuntimeError(f'Compressed SQL checksum/size mismatch: {path.name}')
    return info


def inventory(root):
    records = {}
    for manifest in sorted(root.iterdir()):
        if MANIFEST.fullmatch(manifest.name):
            _local_file(root, manifest)
            info = json.loads(manifest.read_text(encoding='utf8'))
            records[manifest.stem] = checked_info(root, info, manifest.stem)
    return records


def pin_original(root, records):
    path = _local_file(root, root / ORIGINAL)
    if path.exists():
        pin = json.loads(path.read_text(encoding='utf8'))
        stem = pin['manifest'][:-5]
        if pin.get('version') != 1 or not MANIFEST.fullmatch(pin['manifest']):
            raise RuntimeError('Unrecognized original-backup pin')
        if stem not in records or records[stem] != pin['backup']:
            raise RuntimeError('Pinned original backup is missing or has changed; no backups were removed')
        return stem
    if not records:
        raise RuntimeError('No checked backup is available to preserve as the original')
    stem = min(records)
    write_json(path, dict(version=1, pinnedAt=datetime.now(timezone.utc).isoformat(),
                         manifest=stem + '.json', backup=records[stem]))
    return stem


def compress_backup(root, stem, info):
    if info['path'].endswith('.gz'):
        # Resume after a crash between committing the gzip manifest and removing
        # its redundant SQL. Never remove a differing or unregistered raw file.
        raw = _local_file(root, Path(info['path']).with_suffix(''))
        if raw.exists():
            dump = inspect_dump(raw)
            if (dump['sqlBytes'], dump['sqlSha256']) != _sql_identity(info):
                raise RuntimeError(f'Uncompressed duplicate differs: {raw.name}')
            raw.unlink()
        return info
    source = _local_file(root, Path(info['path']))
    expected = inspect_dump(source)
    destination = _local_file(root, source.with_suffix('.sql.gz'))
    temporary = source.with_name(source.name + '.part-' + uuid.uuid4().hex)
    try:
        if destination.exists():
            # Recover a compression interrupted before its manifest was replaced.
            if inspect_dump(destination) != expected:
                raise RuntimeError(f'Existing compressed backup differs: {destination.name}')
        else:
            with source.open('rb') as raw, temporary.open('xb') as output:
                with gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0) as compressed:
                    shutil.copyfileobj(raw, compressed)
            # Read back the entire stream, including gzip's CRC, before removing SQL.
            with gzip.open(temporary, 'rb') as compressed:
                digest = hashlib.file_digest(compressed, 'sha256').hexdigest()
            if digest != expected['sqlSha256']:
                raise RuntimeError('Compressed backup read-back verification failed')
            temporary.replace(destination)
        compressed_info = dict(path=str(destination), bytes=destination.stat().st_size,
                               sha256=_digest(destination), compression='gzip', **expected)
        checked_info(root, compressed_info, stem)
        write_json(root / (stem + '.json'), compressed_info)
        source.unlink()
        return compressed_info
    finally:
        temporary.unlink(missing_ok=True)


def register_backup(root, source):
    """Pin the first complete dump; compress every later dump before DB deletion."""
    root, source = Path(root), Path(source)
    _local_file(root, source)
    if not MANIFEST.fullmatch(source.stem + '.json') or source.suffix != '.sql':
        raise RuntimeError('Unexpected automatic backup filename')
    dump = inspect_dump(source)
    info = dict(path=str(source), bytes=dump['sqlBytes'], sha256=dump['sqlSha256'],
                createTables=dump['createTables'])
    write_json(root / (source.stem + '.json'), info)
    records = inventory(root)
    original = pin_original(root, records)
    return info if source.stem == original else compress_backup(root, source.stem, info)


def _sql_identity(info):
    return (info.get('sqlBytes', info['bytes']), info.get('sqlSha256', info['sha256']))


def retired_inventory(root):
    """Finish obsolete-file deletions interrupted after manifest retirement."""
    records = {}
    for retired in sorted(root.iterdir()):
        if retired.name.endswith('.retired') and MANIFEST.fullmatch(retired.name[:-8]):
            _local_file(root, retired)
            info = json.loads(retired.read_text(encoding='utf8'))
            stem = retired.name[:-13]  # strip .json.retired
            payload = _local_file(root, Path(info['path']))
            if payload.name not in (stem + '.sql', stem + '.sql.gz'):
                raise RuntimeError('Retired backup has an unexpected payload name')
            if payload.exists():
                checked_info(root, info, stem)
            records[stem] = (retired, payload)
    return records


def finalize_backups(runtime):
    """Prune only with matching successful state/report; never open the database."""
    runtime = Path(runtime)
    state_path, report_path = runtime / 'testdata-state.json', runtime / 'testdata-verification.json'
    state = json.loads(state_path.read_text(encoding='utf8'))
    report = json.loads(report_path.read_text(encoding='utf8'))
    if (state.get('suite') != SUITE or report.get('suite') != SUITE or
            state.get('phase') != 'verified' or report.get('result') != 'PASS' or
            not state.get('anchor') or state['anchor'] != report.get('anchor') or
            not report.get('checks') or report.get('failures') or
            any(check.get('passed') is not True for check in report['checks'])):
        raise RuntimeError('Backup retention requires a matching successful reset and verification; all backups retained')
    root = runtime / 'backups'
    _local_file(root, root / ORIGINAL)
    records = inventory(root)  # Validate every managed payload before any pruning.
    current_path = _local_file(root, Path(state['backup']['path']))
    stem = current_path.name.removesuffix('.gz').removesuffix('.sql')
    current = records.get(stem)
    if (not current or _sql_identity(current) != _sql_identity(state['backup']) or
            _sql_identity(current) != _sql_identity(report['backup']) or
            Path(report['backup']['path']).name.removesuffix('.gz').removesuffix('.sql') != stem):
        raise RuntimeError('Verification does not reference the current checked backup; all backups retained')
    # A newer reset may have dumped data before replacing its state checkpoint.
    # Never use an old PASS to remove backups from that interrupted run.
    anchor_prefix = 'testdata-before-' + datetime.fromisoformat(state['anchor']).strftime('%Y%m%d-%H%M%S') + '-'
    if not stem.startswith(anchor_prefix) or max(records) != stem:
        raise RuntimeError('A newer or mismatched reset backup exists; all backups retained')
    original = pin_original(root, records)
    recent = sorted((key for key in records if key != original), reverse=True)[:KEEP_RECENT]
    keep = {original, *recent}
    retired = retired_inventory(root)
    if set(retired) & (keep | set(records)):
        raise RuntimeError('A retired backup overlaps a registered backup; no backups removed')
    for key in recent:
        records[key] = compress_backup(root, key, records[key])
    # Persist updated gzip paths before pruning; repeat finalization is safe.
    state['backup'] = report['backup'] = records[stem]
    policy = dict(original=records[original]['path'], keepRecent=KEEP_RECENT,
                  retained=[records[key]['path'] for key in sorted(keep)])
    state['backupRetention'] = report['backupRetention'] = policy
    write_json(state_path, state)
    write_json(report_path, report)
    removed = []
    for key in sorted(set(records) - keep):
        payload = _local_file(root, Path(records[key]['path']))
        manifest = _local_file(root, root / (key + '.json'))
        # Retire registration atomically first, so a failed file deletion does
        # not leave a broken active manifest that blocks future resets.
        retirement = _local_file(root, manifest.with_name(manifest.name + '.retired'))
        manifest.replace(retirement)
        retired[key] = (retirement, payload)
    for retirement, payload in retired.values():
        payload.unlink(missing_ok=True)
        retirement.unlink()
        removed.append(payload.name)
    return dict(**policy, removed=removed)
