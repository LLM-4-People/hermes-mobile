"""SQLite sidecar state. A committed event and its recipient rows share one transaction."""
from contextlib import closing, contextmanager
from dataclasses import asdict
import hashlib
import hmac
import json
import sqlite3
import uuid


class Forbidden(Exception):
    pass


class TokenConflict(Exception):
    pass


def digest(value):
    return hashlib.sha256(value.encode()).digest()


class Store:
    def __init__(self, directory):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if directory.stat().st_mode & 0o077:
            raise ValueError("MOBILE_PUSH_STATE must be an owner-only directory")
        self.path = directory / "mobile-push.sqlite3"
        with closing(self.connect()) as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, token TEXT NOT NULL UNIQUE, key_digest BLOB NOT NULL,
                    enrolled REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
                    kind TEXT NOT NULL, created_at REAL NOT NULL, run_id TEXT, session_id TEXT, job_id TEXT);
                CREATE INDEX IF NOT EXISTS event_time ON events(created_at);
                CREATE INDEX IF NOT EXISTS run_events ON events(run_id,kind);
                CREATE TABLE IF NOT EXISTS deliveries (
                    device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
                    event_id TEXT REFERENCES events(event_id) ON DELETE CASCADE,
                    due REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, lease TEXT,
                    PRIMARY KEY(device_id,event_id));
                CREATE INDEX IF NOT EXISTS next_delivery ON deliveries(due);
            """)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    @contextmanager
    def write(self):
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            yield db

    def device(self, device_id, credential, *, profile, now, token=None, management_key=None):
        with self.write() as db:
            if token is None:
                if profile:
                    db.execute("DELETE FROM devices WHERE id=?", (device_id,))
                else:
                    # A capability revokes only its own binding. Replays have the same
                    # result after revocation, an abandoned enrollment, or key rotation.
                    db.execute("DELETE FROM devices WHERE id=? AND key_digest=?", (device_id, digest(credential)))
                return
            row = db.execute("SELECT key_digest,token FROM devices WHERE id=?", (device_id,)).fetchone()
            if not profile and (not row or not hmac.compare_digest(digest(credential), row["key_digest"])):
                raise Forbidden
            if profile and management_key:
                key_digest = digest(management_key)
            elif row and not management_key:
                key_digest = row["key_digest"]
            else:
                raise Forbidden
            try:
                db.execute("""
                    INSERT INTO devices(id,token,key_digest,enrolled) VALUES(?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET token=excluded.token,key_digest=excluded.key_digest
                """, (device_id, token, key_digest, now))
            except sqlite3.IntegrityError as exc:
                raise TokenConflict from exc
            if row and row["token"] != token:
                # Release any in-flight lease tied to the previous address.
                db.execute("UPDATE deliveries SET lease=NULL,due=?,attempts=0 WHERE device_id=?", (now, device_id))

    @staticmethod
    def _record(db, event):
        inserted = db.execute("""
            INSERT INTO events(event_id,kind,created_at,run_id,session_id,job_id)
            SELECT :event_id,:kind,:created_at,:run_id,:session_id,:job_id
             WHERE NOT EXISTS (SELECT 1 FROM events WHERE event_id=:event_id)
        """, asdict(event))
        if inserted.rowcount:
            db.execute("""
                INSERT INTO deliveries(device_id,event_id,due)
                SELECT id,?,? FROM devices WHERE enrolled<=?
            """, (event.event_id, event.created_at, event.created_at))

    def record(self, events):
        with self.write() as db:
            for event in events:
                self._record(db, event)

    def reconcile(self, observations, cutoff):
        with self.write() as db:
            for observation in observations:
                if observation.run_id is not None:
                    pending = [e.event_id for e in observation.events if e.kind == "approval_requested"]
                    db.execute("""
                        DELETE FROM deliveries WHERE event_id IN (
                            SELECT event_id FROM events WHERE run_id=? AND kind='approval_requested'
                            AND event_id NOT IN (SELECT value FROM json_each(?)))
                    """, (observation.run_id, json.dumps(pending)))
                for event in observation.events:
                    if event.created_at >= cutoff:
                        self._record(db, event)

    def events(self, *, event_id=None, after=0, limit=50):
        with closing(self.connect()) as db:
            if event_id is not None:
                row = db.execute("SELECT * FROM events WHERE event_id=?", (event_id,)).fetchone()
                return self._public_event(row) if row else None
            rows = db.execute("SELECT * FROM events WHERE seq>? ORDER BY seq LIMIT ?", (after, limit + 1)).fetchall()
        page = rows[:limit]
        return {"events": [self._public_event(r) for r in page],
                "has_more": len(rows) > limit, "next_cursor": page[-1]["seq"] if page else after}

    @staticmethod
    def _public_event(row):
        return {k: row[k] for k in row.keys() if k != "seq"}

    def expire(self, cutoff):
        with self.write() as db:
            db.execute("DELETE FROM events WHERE created_at<?", (cutoff,))

    def claim(self, now, lease_seconds):
        with self.write() as db:
            row = db.execute("""
                SELECT d.device_id,d.event_id,d.attempts,v.token,e.kind,e.created_at
                  FROM deliveries d JOIN devices v ON v.id=d.device_id JOIN events e USING(event_id)
                 WHERE d.due<=? ORDER BY d.due,d.rowid LIMIT 1
            """, (now,)).fetchone()
            if not row:
                return None
            item = dict(row)
            item["lease"] = uuid.uuid4().hex
            db.execute("UPDATE deliveries SET due=?,lease=? WHERE device_id=? AND event_id=?",
                       (now + lease_seconds, item["lease"], item["device_id"], item["event_id"]))
            return item

    def owns_delivery(self, item):
        with closing(self.connect()) as db:
            return db.execute("SELECT 1 FROM deliveries WHERE device_id=? AND event_id=? AND lease=?",
                              (item["device_id"], item["event_id"], item["lease"])).fetchone() is not None

    def settle(self, item, *, retry_at=None, unregistered=False):
        args = (item["device_id"], item["event_id"], item["lease"])
        with self.write() as db:
            if retry_at is not None:
                db.execute("""UPDATE deliveries SET due=?, attempts=attempts+1, lease=NULL
                               WHERE device_id=? AND event_id=? AND lease=?""", (retry_at, *args))
            else:
                updated = db.execute("DELETE FROM deliveries WHERE device_id=? AND event_id=? AND lease=?", args)
                if unregistered and updated.rowcount:
                    db.execute("DELETE FROM devices WHERE id=? AND token=?", (item["device_id"], item["token"]))
