"""Immutable, explicitly published files in one SQLite database per profile and session."""
from contextlib import closing, contextmanager
import hashlib
import heapq
import mimetypes
import os
from pathlib import Path
import re
import sqlite3
import stat
import time
import unicodedata
import uuid

BLOCK = 65536
DEFAULT_LIMIT = 16 * 1024 * 1024
COLUMNS = 'id, name, mime, size, sha256, created'
SCHEMA = '''CREATE TABLE IF NOT EXISTS files(
    id TEXT UNIQUE NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL,
    size INTEGER NOT NULL, sha256 TEXT NOT NULL, created INTEGER NOT NULL, content BLOB NOT NULL)'''


def byte_limit():
    limit = int(os.environ.get('MOBILE_ARTIFACT_MAX_BYTES', DEFAULT_LIMIT))
    if not 1 <= limit <= 64 * 1024 * 1024:
        raise ValueError('Invalid artifact size limit')
    return limit


def name_for(value):
    if not value or len(value.encode()) > 240 or value in {'.', '..'} or any(
            c in '/\\' or unicodedata.category(c).startswith('C') for c in value):
        raise ValueError('Invalid file name')
    return value


def location(home, session, create=False):
    if not session or len(session.encode()) > 256 or '/' in session or '\\' in session:
        raise ValueError('Invalid session')
    root = Path(home) / 'mobile-artifacts'
    directory = root / hashlib.sha256(session.encode()).hexdigest()
    for item in (root, directory):
        if create:
            item.mkdir(mode=0o700, exist_ok=True)
        if item.is_symlink() or (item.exists() and not item.is_dir()):
            raise ValueError('Invalid artifact directory')
    db = directory / 'published.sqlite3'
    if db.is_symlink() or (db.exists() and not db.is_file()):
        raise ValueError('Invalid artifact database')
    return db


@contextmanager
def workspace_source(workspace, path):
    """Pin each component while walking a relative workspace path; accept regular files only."""
    relative = Path(path)
    if relative.is_absolute() or '..' in relative.parts or not relative.parts:
        raise ValueError('Choose a relative workspace file')
    directory = os.open(workspace, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for component in relative.parts[:-1]:
            child = os.open(component, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, 'rb') as stream:
            metadata = os.fstat(fd)
            if not stat.S_ISREG(metadata.st_mode):
                raise ValueError('Expected a regular workspace file')
            yield stream, metadata.st_size
    finally:
        os.close(directory)


def publish(home, session, workspace, path, name=None):
    name = name_for(name or Path(path).name)
    mime = mimetypes.guess_type(name)[0] or 'application/octet-stream'
    ident = uuid.uuid4().hex
    digest = hashlib.sha256()
    with workspace_source(workspace, path) as (source, size):
        if size > byte_limit():
            raise ValueError('Artifact exceeded the size limit')
        db = location(home, session, create=True)
        with closing(sqlite3.connect(db)) as conn, conn:
            conn.execute(SCHEMA)
            conn.execute('CREATE INDEX IF NOT EXISTS files_page ON files(created DESC,id DESC)')
            row = conn.execute('INSERT INTO files VALUES (?,?,?,?,?,?,zeroblob(?))',
                               (ident, name, mime, size, '', time.time(), size))
            copied = 0
            with conn.blobopen('files', 'content', row.lastrowid) as blob:
                while chunk := source.read(BLOCK):
                    copied += len(chunk)
                    if copied > size:
                        raise ValueError('Source changed during publication')
                    blob.write(chunk)
                    digest.update(chunk)
            if copied != size:
                raise ValueError('Source changed during publication')
            conn.execute('UPDATE files SET sha256=? WHERE id=?', (digest.hexdigest(), ident))
    return ident


def reader(home, session):
    db = location(home, session)
    if not db.exists():
        raise FileNotFoundError
    conn = sqlite3.connect(db.absolute().as_uri() + '?mode=ro', uri=True, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def decode_cursor(value):
    if value is None:
        return (2**63 - 1, '')
    match = re.fullmatch(r'([0-9]{1,18}(?:[.][0-9]{1,9})?):([0-9a-f]{32})', value)
    if not match:
        raise ValueError('Invalid cursor')
    return float(match[1]), match[2]


def inventory(home, segments, before, limit):
    rows = []
    for session in segments:
        try:
            conn = reader(home, session)
        except FileNotFoundError:
            continue
        with closing(conn):
            batch = conn.execute(f'SELECT {COLUMNS} FROM files WHERE (created, id)<(?,?) '
                                 'ORDER BY created DESC, id DESC LIMIT ?', (*before, limit + 1))
            # Retain at most one page across the verified compression generations.
            rows = heapq.nlargest(limit + 1, [*rows, *map(dict, batch)], key=lambda row: (row['created'], row['id']))
    cursor = f"{rows[limit-1]['created']}:{rows[limit-1]['id']}" if len(rows) > limit else None
    return {'data': rows[:limit], 'next': cursor, 'max_artifact_bytes': byte_limit()}


def download(home, segments, ident):
    if not re.fullmatch('[0-9a-f]{32}', ident):
        raise FileNotFoundError
    for session in segments:
        try:
            conn = reader(home, session)
        except FileNotFoundError:
            continue
        try:
            row = conn.execute(f'SELECT rowid, {COLUMNS} FROM files WHERE id=?', (ident,)).fetchone()
            if row is not None:
                return conn, conn.blobopen('files', 'content', row['rowid'], readonly=True), dict(row)
        except BaseException:
            conn.close()
            raise
        conn.close()
    raise FileNotFoundError
