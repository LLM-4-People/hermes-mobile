"""Read the fork's persisted, authenticated run and cron records without loading message bodies."""
from contextlib import closing
from datetime import datetime
import sqlite3
from .events import Event


class Ledger:
    def __init__(self, path, query, convert):
        self.path = path
        self.query = query
        self.convert = convert
        self.connection = None
        self.version = None
        self.identity = None

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

    def scan(self, params, store, cutoff):
        identity = self._snapshot()
        if identity is None:
            self.close()
            return
        checkpoint = identity[2] is not None
        if self.identity != identity:
            self.close()
            mode = "?mode=ro&immutable=1" if checkpoint else "?mode=ro"
            self.connection = sqlite3.connect(self.path.as_uri() + mode, uri=True)
            self.identity = identity
        db = self.connection
        version = db.execute("PRAGMA data_version").fetchone()[0]
        if version == self.version:
            return
        with closing(db.execute(self.query, params)) as rows:
            events = [self.convert(*row) for row in rows]
        # Only use an immutable read if the file stayed checkpointed and unchanged
        # throughout the query. A concurrent writer's next snapshot is read normally.
        if checkpoint and self._snapshot() != identity:
            self.close()
            return
        store.record(event for event in events if event and event.created_at >= cutoff)
        self.version = version


def run_event(run_id, status, session_id, updated, approval_time):
    kinds = {"completed": "run_completed", "failed": "run_failed",
             "interrupted": "run_failed", "waiting_for_approval": "approval_requested"}
    kind = kinds.get(status)
    if kind:
        source = [run_id, approval_time] if kind == "approval_requested" else [run_id]
        return Event.make(source, kind, updated, run_id=run_id, session_id=session_id)


def cron_event(execution_id, job_id, started_at):
    return Event.make([execution_id], "reminder", datetime.fromisoformat(started_at).timestamp(),
                      job_id=job_id)


class HermesLedgers:
    def __init__(self, settings):
        self.scope = settings.run_scope
        self.runs = Ledger(settings.home / "runs_idempotency.db", """
            SELECT run_id, json_extract(status_json,'$.status'), json_extract(status_json,'$.session_id'),
                   updated_at, json_extract(status_json,'$.approval.timestamp')
              FROM run_idempotency
             WHERE scope=? AND updated_at>=? AND fingerprint!='closed' AND json_valid(status_json)
        """, run_event)
        self.cron = Ledger(settings.home / "cron" / "executions.db", """
            SELECT id,job_id,started_at FROM executions
             WHERE started_at IS NOT NULL AND julianday(started_at)>=julianday(?,'unixepoch')
        """, cron_event)

    def sources(self, cutoff):
        return ((self.runs, (self.scope, cutoff)), (self.cron, (cutoff,)))

    def close(self):
        self.runs.close()
        self.cron.close()
