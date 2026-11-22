"""Read the fork's persisted, authenticated run and cron records without loading message bodies."""
from contextlib import closing
from datetime import datetime
import json
import sqlite3
import time
from .events import Event, Observation


RUN_QUERY = """
SELECT run_id, json_extract(status_json,'$.status'), json_extract(status_json,'$.session_id'),
           updated_at, json_extract(status_json,'$.approval.timestamp'),
           json_extract(status_json,'$.approval.request_id'), json_extract(status_json,'$.pending_approvals')
      FROM run_idempotency
     WHERE scope=? AND fingerprint!='closed' AND json_valid(status_json)
"""


class Ledger:
    def __init__(self, path, query, convert, settings):
        self.path = path
        self.query = query
        self.convert = convert
        self.connection = None
        self.version = None
        self.identity = None
        self.cursor = 0
        self.next_full_at = None
        self.overlap = settings.poll_seconds * 2
        self.reconcile_seconds = settings.reconcile_seconds

    def close(self):
        if self.connection:
            self.connection.close()
        self.connection = self.version = self.identity = None

    def _snapshot(self):
        try:
            stat = self.path.stat()
        except FileNotFoundError:
            return None
        has_journal = any(self.path.with_name(self.path.name + suffix).exists()
                          for suffix in ("-wal", "-journal", "-shm"))
        # When the last writer closes, SQLite checkpoints/removes the WAL and SHM.
        # A normal read-only open then attempts to recreate them on a read-only mount.
        return (stat.st_dev, stat.st_ino, None if has_journal else (stat.st_ctime_ns, stat.st_size))

    def _uri(self, identity):
        return self.path.as_uri() + ("?mode=ro&immutable=1" if identity[2] is not None else "?mode=ro")

    def one(self, query, params):
        identity = self._snapshot()
        if identity is None:
            raise FileNotFoundError(self.path.name)
        with closing(sqlite3.connect(self._uri(identity), uri=True)) as db:
            row = db.execute(query, params).fetchone()
        if identity[2] is not None and self._snapshot() != identity:
            raise sqlite3.OperationalError("Checkpoint changed while reading")
        return self.convert(*row) if row else None

    def scan(self, params, store, cutoff):
        identity = self._snapshot()
        if identity is None:
            self.close()
            return
        checkpoint = identity[2] is not None
        if self.identity != identity:
            if not self.identity or self.identity[:2] != identity[:2]:
                self.cursor = 0
                self.next_full_at = None
            self.close()
            self.connection = sqlite3.connect(self._uri(identity), uri=True)
            self.identity = identity
        db = self.connection
        version = db.execute("PRAGMA data_version").fetchone()[0]
        now = time.monotonic()
        full = self.next_full_at is None or now >= self.next_full_at
        if version == self.version and not full:
            return
        lower = cutoff if full else max(cutoff, self.cursor - self.overlap)
        with closing(db.execute(self.query, (*params[:-1], lower))) as rows:
            observations = [self.convert(*row) for row in rows]
        # Only use an immutable read if the file stayed checkpointed and unchanged
        # throughout the query. A concurrent writer's next snapshot is read normally.
        if checkpoint and self._snapshot() != identity:
            self.close()
            return
        if observations:
            store.reconcile(observations, cutoff)
            self.cursor = max(self.cursor, max(item.updated_at for item in observations))
        if full:
            self.next_full_at = now + self.reconcile_seconds
        self.version = version


def run_observation(run_id, status, session_id, updated, approval_time, request_id, pending):
    links = {"run_id": run_id, "session_id": session_id}
    events = []
    if status == "waiting_for_approval":
        if pending is not None:
            for prompt_id, stamp in json.loads(pending).items():
                events.append(Event.make([run_id, prompt_id], "approval_requested", stamp, **links))
        elif approval_time is not None:
            # Native gateway approval waiters carry a single timestamped approval object.
            events.append(Event.make([run_id, request_id or approval_time], "approval_requested",
                                     approval_time, **links))
    elif status in {"completed", "failed", "interrupted"}:
        kind = "run_completed" if status == "completed" else "run_failed"
        events.append(Event.make([run_id], kind, updated, **links))
    return Observation(updated, tuple(events), run_id=run_id)


def cron_observation(execution_id, job_id, started_at):
    stamp = datetime.fromisoformat(started_at).timestamp()
    return Observation(stamp, (Event.make([execution_id], "reminder", stamp, job_id=job_id),))


class HermesLedgers:
    def __init__(self, settings):
        self.scope = settings.run_scope
        self.runs = Ledger(settings.home / "runs_idempotency.db",
                           RUN_QUERY + " AND updated_at>=?", run_observation, settings)
        self.cron = Ledger(settings.home / "cron" / "executions.db", """
            SELECT id,job_id,started_at FROM executions
             WHERE started_at IS NOT NULL AND julianday(started_at)>=julianday(?,'unixepoch')
        """, cron_observation, settings)

    def approval_pending(self, item):
        observation = self.runs.one(RUN_QUERY + " AND run_id=?", (self.scope, item["run_id"]))
        return observation is not None and any(e.event_id == item["event_id"] for e in observation.events)

    def sources(self, cutoff):
        return ((self.runs, (self.scope, cutoff)), (self.cron, (cutoff,)))

    def close(self):
        self.runs.close()
        self.cron.close()
