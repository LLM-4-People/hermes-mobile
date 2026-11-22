"""SQLite sidecar state. A committed event and its recipient rows share one transaction."""
from contextlib import closing, contextmanager
from dataclasses import asdict
import hashlib
import json
import hmac
import sqlite3
import time
import uuid
from .events import APPROVAL, DISMISS_APPROVAL, DISMISS_QUESTION, PROMPT_KINDS, Observation


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
                CREATE INDEX IF NOT EXISTS event_deliveries ON deliveries(event_id);
                CREATE TABLE IF NOT EXISTS prompt_recipients (
                    event_id TEXT REFERENCES events(event_id) ON DELETE CASCADE,
                    device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
                    PRIMARY KEY(event_id, device_id));
                CREATE INDEX IF NOT EXISTS prompt_recipient_device ON prompt_recipients(device_id);
            """)
            # Upgrade pre-Web-Push databases atomically; their existing tokens are FCM registrations.
            with db:
                db.execute("BEGIN IMMEDIATE")
                columns = {row["name"] for row in db.execute("PRAGMA table_info(devices)")}
                if "provider" not in columns:
                    db.execute("ALTER TABLE devices ADD COLUMN provider TEXT NOT NULL DEFAULT 'fcm'")
                if "webpush_keys" not in columns:
                    db.execute("ALTER TABLE devices ADD COLUMN webpush_keys TEXT")
                if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='question_recipients'").fetchone():
                    db.execute("INSERT OR IGNORE INTO prompt_recipients SELECT event_id,device_id FROM question_recipients")
                    db.execute("DROP TABLE question_recipients")
                if not any(row["name"] == "supersedes" for row in db.execute("PRAGMA table_info(events)")):
                    db.execute("ALTER TABLE events ADD COLUMN supersedes TEXT")
                    if any(row["name"] == "dismiss" for row in db.execute("PRAGMA table_info(deliveries)")):
                        legacy = db.execute("""DELETE FROM deliveries WHERE dismiss=1
                                               RETURNING event_id,device_id""").fetchall()
                        self._queue_withdrawals(db, legacy)
                db.execute("CREATE INDEX IF NOT EXISTS visible_events ON events(seq) WHERE supersedes IS NULL")

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

    def device(self, device_id, credential, *, profile, now, token=None, management_key=None,
               provider="fcm", webpush_keys=None, reserve=False):
        with self.write() as db:
            if reserve:
                if not profile or not management_key:
                    raise Forbidden
                # A pending enrollment has a management capability but no delivery address.
                # The marker contains a space, which both supported address formats reject.
                provider, token = "pending", "pending " + device_id
            if token is None:
                if profile:
                    db.execute("DELETE FROM devices WHERE id=?", (device_id,))
                else:
                    # A capability revokes only its own binding. Replays have the same
                    # result after revocation, an abandoned enrollment, or key rotation.
                    db.execute("DELETE FROM devices WHERE id=? AND key_digest=?", (device_id, digest(credential)))
                return
            row = db.execute("SELECT key_digest,token,provider,webpush_keys FROM devices WHERE id=?", (device_id,)).fetchone()
            if not profile and (not row or not hmac.compare_digest(digest(credential), row["key_digest"])):
                raise Forbidden
            if profile and management_key:
                key_digest = digest(management_key)
            elif row and not management_key:
                key_digest = row["key_digest"]
            else:
                raise Forbidden
            if reserve and row and hmac.compare_digest(key_digest, row["key_digest"]):
                # A retried reservation may arrive after the initial binding callback.
                return
            keys = json.dumps(webpush_keys, sort_keys=True, separators=(",", ":")) if webpush_keys else None
            try:
                db.execute("""
                    INSERT INTO devices(id,token,key_digest,enrolled,provider,webpush_keys) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET token=excluded.token,key_digest=excluded.key_digest,
                        provider=excluded.provider,webpush_keys=excluded.webpush_keys,
                        enrolled=CASE WHEN devices.provider='pending' OR excluded.provider='pending'
                                 THEN excluded.enrolled ELSE devices.enrolled END
                """, (device_id, token, key_digest, now, provider, keys))
            except sqlite3.IntegrityError as exc:
                raise TokenConflict from exc
            if reserve:
                db.execute("DELETE FROM deliveries WHERE device_id=?", (device_id,))
                db.execute("DELETE FROM prompt_recipients WHERE device_id=?", (device_id,))
            elif row and (row["token"] != token or row["webpush_keys"] != keys or row["provider"] != provider):
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
                SELECT id,?,? FROM devices WHERE enrolled<=? AND provider!='pending'
            """, (event.event_id, event.created_at, event.created_at))

    @staticmethod
    def _queue_withdrawals(db, recipients):
        if not recipients:
            return
        # A withdrawal has its own clock and no run or session link. Its storage
        # ID cannot collide with a public (hex) event. Both envelopes use the
        # original opaque event ID so Android can dismiss out of order.
        now = time.time()
        source_ids = {row[0] for row in recipients}
        db.executemany("""INSERT OR IGNORE INTO events(event_id,kind,created_at,supersedes)
                          SELECT 'withdraw:' || event_id,
                                 CASE kind WHEN ? THEN ? ELSE ? END, ?, event_id
                          FROM events WHERE event_id=?""",
                       ((APPROVAL, DISMISS_APPROVAL, DISMISS_QUESTION, now, source) for source in source_ids))
        db.executemany("""INSERT OR IGNORE INTO deliveries(event_id,device_id,due)
                          VALUES('withdraw:' || ?,?,0)""", recipients)

    def _withdraw(self, db, affected, args):
        # Only a previous or in-flight send can have posted a notification.
        recipients = db.execute(f"""DELETE FROM prompt_recipients WHERE event_id IN ({affected})
                                    RETURNING event_id,device_id""", args).fetchall()
        self._queue_withdrawals(db, recipients)

    def record(self, events):
        with self.write() as db:
            for event in events:
                self._record(db, event)

    def reconcile(self, observations, cutoff, *, complete_run_scope=False):
        with self.write() as db:
            if complete_run_scope:
                # A removed/closed ledger row yields no incremental observation. Once a
                # complete owned snapshot is available, also retire alerts from those runs.
                watched = db.execute("""SELECT DISTINCT e.run_id FROM prompt_recipients r
                                        JOIN events e ON e.event_id=r.event_id WHERE e.created_at>=?""", (cutoff,))
                missing = {row[0] for row in watched} - {item.run_id for item in observations}
                observations = [*observations, *(Observation(cutoff, (), run_id) for run_id in missing)]
            for observation in observations:
                if observation.run_id is not None:
                    pending = [e.event_id for e in observation.events if e.kind in PROMPT_KINDS]
                    stale = """SELECT event_id FROM events WHERE run_id=? AND kind IN (?, ?)
                               AND event_id NOT IN (SELECT value FROM json_each(?))"""
                    args = (observation.run_id, *PROMPT_KINDS, json.dumps(pending))
                    db.execute(f"DELETE FROM deliveries WHERE event_id IN ({stale})", args)
                    self._withdraw(db, stale, args)
                for event in observation.events:
                    if event.created_at >= cutoff:
                        self._record(db, event)

    def events(self, *, event_id=None, after=0, limit=50):
        with closing(self.connect()) as db:
            if event_id is not None:
                row = db.execute("SELECT * FROM events WHERE event_id=? AND supersedes IS NULL", (event_id,)).fetchone()
                return self._public_event(row) if row else None
            rows = db.execute("SELECT * FROM events WHERE supersedes IS NULL AND seq>? ORDER BY seq LIMIT ?", (after, limit + 1)).fetchall()
        page = rows[:limit]
        return {"events": [self._public_event(r) for r in page],
                "has_more": len(rows) > limit, "next_cursor": page[-1]["seq"] if page else after}

    @staticmethod
    def _public_event(row):
        return {k: row[k] for k in row.keys() if k not in {"seq", "supersedes"}}

    def expire(self, cutoff):
        with self.write() as db:
            expired = "SELECT event_id FROM events WHERE created_at<?"
            self._withdraw(db, expired, (cutoff,))
            db.execute("DELETE FROM events WHERE created_at<?", (cutoff,))

    def claim(self, now, lease_seconds):
        with self.write() as db:
            row = db.execute("""
                SELECT d.device_id,COALESCE(e.supersedes,e.event_id) AS event_id,d.event_id AS storage_id,
                       d.attempts,v.token,v.provider,v.webpush_keys,e.kind,e.created_at,e.run_id
                  FROM deliveries d JOIN devices v ON v.id=d.device_id JOIN events e USING(event_id)
                 WHERE d.due<=? ORDER BY d.due,d.rowid LIMIT 1
            """, (now,)).fetchone()
            if not row:
                return None
            item = dict(row)
            item["lease"] = uuid.uuid4().hex
            db.execute("UPDATE deliveries SET due=?,lease=? WHERE device_id=? AND event_id=?",
                       (now + lease_seconds, item["lease"], item["device_id"], item["storage_id"]))
            return item

    def begin_send(self, item):
        args = (item["device_id"], item["storage_id"], item["lease"])
        current = "SELECT 1 FROM deliveries WHERE device_id=? AND event_id=? AND lease=?"
        if item["kind"] not in PROMPT_KINDS:
            with closing(self.connect()) as db:
                return db.execute(current, args).fetchone() is not None
        # Record possible sends before the transport call, including those whose
        # acknowledgement might be lost. Only these recipients need a dismissal.
        with self.write() as db:
            if db.execute(current, args).fetchone() is None:
                return False
            db.execute("INSERT OR IGNORE INTO prompt_recipients(device_id,event_id) VALUES(?,?)", args[:2])
            return True

    def settle(self, item, *, retry_at=None, unregistered=False):
        args = (item["device_id"], item["storage_id"], item["lease"])
        with self.write() as db:
            if retry_at is not None:
                db.execute("""UPDATE deliveries SET due=?, attempts=attempts+1, lease=NULL
                               WHERE device_id=? AND event_id=? AND lease=?""", (retry_at, *args))
            else:
                updated = db.execute("DELETE FROM deliveries WHERE device_id=? AND event_id=? AND lease=?", args)
                if unregistered and updated.rowcount:
                    db.execute("DELETE FROM devices WHERE id=? AND token=?", (item["device_id"], item["token"]))
