#!/usr/bin/python3
"""Controller-installed root collector. No target command, archive extraction or host materialization.
An export is operational evidence; it does not qualify a sandbox or validate compiler/model assertions.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import sys

LONG_MAX = (1 << 63) - 1


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def decode_request(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('DUPLICATE_EXPORT_FIELD')
            result[key] = value
        return result
    if len(raw) > 65536:
        raise ValueError('EXPORT_REQUEST_WORKER_REQUIRED')
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique)
    if not isinstance(value, dict) or set(value) != {'attempt', 'nonce', 'work', 'maxRowBytes', 'maxObjectBytes', 'maxCaptureBytes'}:
        raise ValueError('INVALID_EXPORT_REQUEST')
    for key in ['attempt', 'nonce']:
        if not isinstance(value[key], str) or not re.fullmatch('[a-f0-9]{32}', value[key]):
            raise ValueError('INVALID_EXPORT_NAMESPACE')
    for key in ['maxRowBytes', 'maxObjectBytes', 'maxCaptureBytes']:
        if type(value[key]) is not int or not 0 <= value[key] <= LONG_MAX:
            raise ValueError('INVALID_EXPORT_RESOURCE')
    if not 1024 <= value['maxRowBytes'] <= 1048576 or value['work'] != '/work/' + value['attempt']:
        raise ValueError('INVALID_EXPORT_REQUEST')
    return value


def checked_add(a, b):
    if not 0 <= a <= LONG_MAX or not 0 <= b <= LONG_MAX or a > LONG_MAX - b:
        raise ValueError('EXPORT_COUNTER_OVERFLOW')
    return a + b


def safe_directory(path):
    path = Path(path)
    if not path.is_absolute():
        raise ValueError('INVALID_EXPORT_DIRECTORY')
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise ValueError('EXPORT_DIRECTORY_ALIAS')
    if not path.is_dir():
        raise ValueError('EXPORT_DIRECTORY_UNAVAILABLE')
    return path


def no_target_processes(uid):
    for item in Path('/proc').iterdir():
        if not item.name.isdigit():
            continue
        try:
            # stat's owner is the effective uid; status additionally checks real/saved/fs ids.
            if item.stat().st_uid == uid:
                return False
            with (item / 'status').open() as source:
                for line in source:
                    if line.startswith('Uid:') and uid in map(int, line.split()[1:]):
                        return False
        except FileNotFoundError:
            pass
    return True


def stop_target(attempt):
    import pwd
    unit = 'evo-' + attempt + '.service'
    result = subprocess.run(['/usr/bin/systemctl', 'stop', '--', unit], stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
                            env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})
    # A collected/nonexistent transient unit is acceptable only with an independent account-wide check.
    if result.returncode not in (0, 5) or not no_target_processes(pwd.getpwnam('evobuild').pw_uid):
        raise ValueError('TARGET_QUIESCENCE_UNCONFIRMED')


def b64(value):
    return base64.b64encode(value).decode('ascii')


def open_relative(root_fd, relative, directory=False):
    """Walk every ancestor by fd; never follow a link introduced through a path component."""
    cursor = os.dup(root_fd)
    try:
        parts = relative.split(b'/') if relative else []
        for index, part in enumerate(parts):
            if part in (b'', b'.', b'..') or b'\x00' in part:
                raise ValueError('INVALID_EXPORT_PATH')
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            if index < len(parts) - 1 or directory:
                flags |= os.O_DIRECTORY
            next_fd = os.open(part, flags, dir_fd=cursor)
            os.close(cursor)
            cursor = next_fd
        return cursor
    except BaseException:
        os.close(cursor)
        raise


def kind(mode):
    if stat.S_ISREG(mode):
        return 'FILE'
    for test, name in [(stat.S_ISDIR, 'DIRECTORY'), (stat.S_ISLNK, 'SYMLINK'),
                       (stat.S_ISFIFO, 'FIFO'), (stat.S_ISSOCK, 'SOCKET'),
                       (stat.S_ISCHR, 'DEVICE'), (stat.S_ISBLK, 'DEVICE')]:
        if test(mode):
            return name
    return 'UNKNOWN'


def capture(work, destination, policy):
    if os.name != 'posix' or os.geteuid() != 0:
        raise ValueError('ROOT_GUEST_COLLECTOR_REQUIRED')
    work = safe_directory(work)
    destination = Path(destination)
    safe_directory(destination.parent)
    destination.mkdir(mode=0o700)  # A fresh nonce; never overwrite a preceding export.
    scope = canonical(dict(schema='guest-output-scope:m8.4-v1', work=b64(os.fsencode(work)), nonce=destination.name,
                           budget={key: policy[key] for key in ['maxRowBytes', 'maxObjectBytes', 'maxCaptureBytes']}))
    with (destination / 'scope.json').open('xb') as initial:
        initial.write(scope)
        initial.flush()
        os.fsync(initial.fileno())
    objects = destination / 'objects'
    objects.mkdir(mode=0o700)
    root_fd = os.open(work, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    db = sqlite3.connect(str(destination / 'frontier.sqlite'))
    db.execute('PRAGMA synchronous=FULL')
    db.execute('PRAGMA temp_store=FILE')
    db.execute('PRAGMA cache_size=-4096')
    db.execute('CREATE TABLE dirs(path BLOB PRIMARY KEY,done INTEGER NOT NULL) WITHOUT ROWID')
    db.execute('CREATE TABLE rows(path BLOB PRIMARY KEY,metadata BLOB NOT NULL,kind TEXT NOT NULL,device TEXT NOT NULL,inode TEXT NOT NULL,links INTEGER NOT NULL) WITHOUT ROWID')
    db.execute('INSERT INTO dirs VALUES(?,0)', (b'',))
    inventory_closed = True
    root_device = os.fstat(root_fd).st_dev
    try:
        while True:
            found = db.execute('SELECT path FROM dirs WHERE done=0 ORDER BY path LIMIT 1').fetchone()
            if found is None:
                break
            parent = found[0]
            try:
                fd = open_relative(root_fd, parent, directory=True)
            except OSError:
                if not parent:
                    raise
                inventory_closed = False
                metadata = db.execute('SELECT metadata FROM rows WHERE path=?', (parent,)).fetchone()
                row = json.loads(metadata[0])
                row.update(disposition='PENDING', reason='DIRECTORY_CENSUS_UNAVAILABLE')
                db.execute('UPDATE rows SET metadata=? WHERE path=?', (canonical(row), parent))
                db.execute('UPDATE dirs SET done=1 WHERE path=?', (parent,))
                db.commit()
                continue
            try:
                with os.scandir(fd) as children:
                    for child in children:
                        name = os.fsencode(child.name)
                        relative = parent + b'/' + name if parent else name
                        if len(relative) > policy['maxRowBytes']:
                            raise ValueError('OUTPUT_PATH_WORKER_REQUIRED')
                        try:
                            info = os.stat(name, dir_fd=fd, follow_symlinks=False)
                            entry_kind = kind(info.st_mode)
                            if entry_kind == 'FILE' and info.st_nlink > 1:
                                entry_kind = 'HARDLINK'
                            row = dict(path=b64(relative), kind=entry_kind, size=info.st_size,
                                       mode=stat.S_IMODE(info.st_mode), blocks=getattr(info, 'st_blocks', 0),
                                       inode=info.st_ino, device=info.st_dev, mtime=info.st_mtime_ns,
                                       links=info.st_nlink, content=None, linkTarget=None, linkGroup=None, disposition='PRESENT', reason='')
                            if entry_kind == 'SYMLINK':
                                row['linkTarget'] = b64(os.fsencode(os.readlink(name, dir_fd=fd)))
                            elif entry_kind not in ('FILE', 'DIRECTORY', 'HARDLINK'):
                                row.update(disposition='REJECTED', reason='SPECIAL_OUTPUT_NOT_OPENED')
                            if entry_kind == 'DIRECTORY':
                                if info.st_dev != root_device:
                                    row.update(disposition='PENDING', reason='MOUNT_CENSUS_REQUIRED')
                                    inventory_closed = False
                                else:
                                    db.execute('INSERT INTO dirs VALUES(?,0)', (relative,))
                        except OSError:
                            row = dict(path=b64(relative), kind='UNKNOWN', size=0, mode=0, blocks=0,
                                       inode=0, device=0, mtime=0, links=0, content=None, linkTarget=None, linkGroup=None,
                                       disposition='PENDING', reason='ENTRY_METADATA_UNAVAILABLE')
                            inventory_closed = False
                        encoded_metadata = canonical(row)
                        if len(encoded_metadata) > policy['maxRowBytes']:
                            raise ValueError('OUTPUT_ROW_WORKER_REQUIRED')
                        db.execute('INSERT INTO rows VALUES(?,?,?,?,?,?)', (relative, encoded_metadata, row['kind'], str(row['device']), str(row['inode']), row['links']))
            except OSError:
                if not parent:
                    raise
                inventory_closed = False
                metadata = db.execute('SELECT metadata FROM rows WHERE path=?', (parent,)).fetchone()
                row = json.loads(metadata[0])
                row.update(disposition='PENDING', reason='DIRECTORY_CENSUS_UNAVAILABLE')
                db.execute('UPDATE rows SET metadata=? WHERE path=?', (canonical(row), parent))
            finally:
                os.close(fd)
            db.execute('UPDATE dirs SET done=1 WHERE path=?', (parent,))
            db.commit()
        db.execute('CREATE TABLE link_groups(device TEXT,inode TEXT,members INTEGER,first_path BLOB,min_links INTEGER,max_links INTEGER,PRIMARY KEY(device,inode)) WITHOUT ROWID')
        db.execute("INSERT INTO link_groups SELECT device,inode,COUNT(*),MIN(path),MIN(links),MAX(links) FROM rows WHERE kind='HARDLINK' GROUP BY device,inode")
        db.commit()
        count = logical_bytes = captured_bytes = pending = rejected = 0
        manifest_hash = hashlib.sha256()
        with (destination / 'entries.ndjson').open('xb') as manifest:
            cursor = db.execute('SELECT path,metadata FROM rows ORDER BY path')
            for relative, metadata in cursor:
                row = json.loads(metadata)
                count = checked_add(count, 1)
                if row['kind'] in ('FILE', 'HARDLINK'):
                    logical_bytes = checked_add(logical_bytes, row['size'])
                if row['kind'] == 'HARDLINK':
                    group = db.execute('SELECT members,first_path,min_links,max_links FROM link_groups WHERE device=? AND inode=?', (str(row['device']), str(row['inode']))).fetchone()
                    if not inventory_closed or group[0] != group[2] or group[2] != group[3] or group[0] != row['links']:
                        row.update(disposition='PENDING', reason='HARDLINK_SCOPE_PROOF_REQUIRED')
                    else:
                        row['linkGroup'] = b64(group[1])
                if row['kind'] in ('FILE', 'HARDLINK') and row['disposition'] == 'PRESENT':
                    remaining = policy['maxCaptureBytes'] - captured_bytes
                    if row['size'] > min(policy['maxObjectBytes'], remaining):
                        row.update(disposition='PENDING', reason='OUTPUT_BYTES_WORKER_REQUIRED')
                    else:
                        try:
                            fd = open_relative(root_fd, relative)
                            try:
                                before = os.fstat(fd)
                                if not stat.S_ISREG(before.st_mode) or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_nlink) != (row['device'], row['inode'], row['size'], row['mtime'], row['links']):
                                    raise ValueError('OUTPUT_CHANGED_DURING_CAPTURE')
                                digest = hashlib.sha256()
                                length = 0
                                temporary = destination / 'capturing.part'
                                with temporary.open('xb') as target:
                                    while True:
                                        chunk = os.read(fd, min(65536, max(1, min(policy['maxObjectBytes'], remaining) - length + 1)))
                                        if not chunk:
                                            break
                                        length = checked_add(length, len(chunk))
                                        if length > min(policy['maxObjectBytes'], remaining):
                                            raise ValueError('OUTPUT_CHANGED_DURING_CAPTURE')
                                        digest.update(chunk)
                                        target.write(chunk)
                                    target.flush()
                                    os.fsync(target.fileno())
                                after = os.fstat(fd)
                                if (after.st_size, after.st_mtime_ns, after.st_nlink, length) != (before.st_size, before.st_mtime_ns, before.st_nlink, before.st_size):
                                    raise ValueError('OUTPUT_CHANGED_DURING_CAPTURE')
                                hex_digest = digest.hexdigest()
                                object_path = objects / hex_digest
                                if object_path.exists():
                                    existing_hash = hashlib.sha256()
                                    if object_path.is_symlink() or object_path.stat().st_size != length:
                                        raise ValueError('EXPORT_OBJECT_COLLISION')
                                    with object_path.open('rb') as existing:
                                        for chunk in iter(lambda: existing.read(65536), b''):
                                            existing_hash.update(chunk)
                                    if existing_hash.hexdigest() != hex_digest:
                                        raise ValueError('EXPORT_OBJECT_COLLISION')
                                    temporary.unlink()
                                else:
                                    temporary.rename(object_path)
                                row['content'] = dict(digest='sha256:' + hex_digest, size=length)
                                captured_bytes = checked_add(captured_bytes, length)
                            finally:
                                os.close(fd)
                        except (OSError, ValueError) as unavailable:
                            reason = str(unavailable) if isinstance(unavailable, ValueError) and str(unavailable) in {'OUTPUT_CHANGED_DURING_CAPTURE', 'EXPORT_OBJECT_COLLISION', 'EXPORT_COUNTER_OVERFLOW'} else 'OUTPUT_CHANGED_OR_UNAVAILABLE'
                            row.update(disposition='PENDING', reason=reason)
                            # Preserve any failed partial bytes in this export's quarantine; never reuse them.
                            if (destination / 'capturing.part').exists():
                                (destination / 'capturing.part').rename(destination / ('partial-' + str(count)))
                if row['disposition'] == 'PENDING':
                    pending = checked_add(pending, 1)
                if row['disposition'] == 'REJECTED':
                    rejected = checked_add(rejected, 1)
                # Device/inode/mtime support race checks in the private spool, not stable output identity.
                for field in ['inode', 'device', 'mtime']:
                    row.pop(field)
                encoded = canonical(row) + b'\n'
                if len(encoded) > policy['maxRowBytes']:
                    raise ValueError('OUTPUT_ROW_WORKER_REQUIRED')
                manifest.write(encoded)
                manifest_hash.update(encoded)
            manifest.flush()
            os.fsync(manifest.fileno())
        result = dict(schema='guest-output-census:m8.4-v2', entries=count, logicalBytes=logical_bytes,
                      capturedBytes=captured_bytes, pending=pending, rejected=rejected,
                      inventoryClosed=inventory_closed, captureClosed=inventory_closed and pending == 0 and rejected == 0,
                      manifest=dict(digest='sha256:' + manifest_hash.hexdigest(), size=(destination / 'entries.ndjson').stat().st_size),
                      scope=dict(digest='sha256:' + hashlib.sha256(scope).hexdigest(), size=len(scope)),
                      trust='PRODUCER_ASSERTION')
        with (destination / 'census.json').open('xb') as output:
            output.write(canonical(result))
            output.flush()
            os.fsync(output.fileno())
        return result
    finally:
        db.close()
        os.close(root_fd)


def main():
    request = decode_request(sys.stdin.buffer.read(65537))
    if os.name != 'posix' or os.geteuid() != 0:
        raise ValueError('ROOT_GUEST_COLLECTOR_REQUIRED')
    work = safe_directory(request['work'])
    stop_target(request['attempt'])
    base = Path('/var/lib/evo/export')
    base.mkdir(parents=True, mode=0o700, exist_ok=True)
    safe_directory(base)
    if base.stat().st_uid != 0 or stat.S_IMODE(base.stat().st_mode) & 0o077:
        raise ValueError('EXPORT_CONTROL_DIRECTORY_UNPROTECTED')
    result = capture(work, base / request['nonce'], request)
    print(canonical(dict(result, attempt=request['attempt'], nonce=request['nonce'])).decode('ascii'))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, sqlite3.Error, subprocess.TimeoutExpired):
        print('EXPORT_INCOMPLETE', file=sys.stderr)
        sys.exit(75)
