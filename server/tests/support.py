import json
from pathlib import Path
import sqlite3
from server.mobile_push.config import Settings
from server.mobile_push.store import Store


def fixture(root):
    path = Path(root)
    return (Settings(path, path / "state", "profile-bearer-for-tests",
                     {"applicationId": "testapp", "projectId": "test-project",
                      "apiKey": "test-key", "gcmSenderId": "123"}, 8766, 1, 10000, 60, 5),
            Store(path / "state"))


def create_runs(settings):
    db = sqlite3.connect(settings.home / "runs_idempotency.db")
    db.execute("""CREATE TABLE run_idempotency(scope TEXT,run_id TEXT,fingerprint TEXT,
                                                updated_at REAL,status_json TEXT)""")
    return db


def insert_run(db, scope, rid, updated, status, **fields):
    db.execute("INSERT INTO run_idempotency VALUES(?,?,?,?,?)",
               (scope, rid, "fingerprint", updated, json.dumps({"status": status, **fields})))
    db.commit()
