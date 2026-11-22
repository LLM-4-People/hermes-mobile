import sqlite3
import unittest
from tempfile import TemporaryDirectory
from server.mobile_push.events import normalize_urgency, opaque_profile_hint
from server.mobile_push.ledgers import HermesLedgers
from support import fixture

# Verbatim schema from Fork tools/mobile_deliver.py (_DELIVERY_SCHEMA_SQL).
FORK_DELIVERY_SCHEMA = """
CREATE TABLE IF NOT EXISTS mobile_deliveries (
    delivery_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    urgency TEXT NOT NULL,
    session_id TEXT,
    origin TEXT NOT NULL,
    created_at REAL NOT NULL,
    acked_at REAL
);
CREATE INDEX IF NOT EXISTS idx_mobile_deliveries_created ON mobile_deliveries (created_at DESC);
"""
PRIVATE_TITLE = "Revue salariale confidentielle"
PRIVATE_PAYLOAD = '{"body":"sk-live-PRIVATE-TOKEN","note":"Diagnostic medical"}'


class ForkDeliveryLedger(unittest.TestCase):
    def setUp(self):
        root = TemporaryDirectory()
        self.addCleanup(root.cleanup)
        self.settings, self.store = fixture(root.name)
        self.store.device("d1", self.settings.profile_key, profile=True,
                          now=10, token="token", management_key="a" * 64)
        self.ledgers = HermesLedgers(self.settings)
        self.addCleanup(self.ledgers.close)

    def fork_db(self):
        db = sqlite3.connect(self.settings.home / "mobile_state.db")
        self.addCleanup(db.close)
        db.executescript(FORK_DELIVERY_SCHEMA)
        return db

    @staticmethod
    def insert(db, delivery_id, *, kind="briefing", urgency="normal", created_at=200.0,
               acked_at=None, session_id="s1"):
        db.execute("INSERT INTO mobile_deliveries VALUES(?,?,?,?,?,?,?,?,?)",
                   (delivery_id, kind, PRIVATE_TITLE, PRIVATE_PAYLOAD, urgency, session_id,
                    "agent", created_at, acked_at))
        db.commit()

    def scan(self, cutoff=0):
        for ledger, args in self.ledgers.sources(cutoff):
            ledger.scan(args, self.store, cutoff)

    def state_bytes(self):
        return b"".join(p.read_bytes() for p in self.settings.state.rglob("*") if p.is_file())

    def test_unacked_fork_delivery_wakes_closed_phone_with_routing_metadata_only(self):
        db = self.fork_db()
        self.insert(db, "dlv_0123456789abcdef", kind="ui_patch", urgency="urgent")
        self.scan()
        (event,) = self.store.events()["events"]
        self.assertEqual(event["event_id"], "dlv_0123456789abcdef")
        self.assertEqual(event["kind"], "ui_patch")
        self.assertEqual(event["urgency"], "high")
        self.assertEqual(event["session_id"], "s1")
        self.assertEqual(event["created_at"], 200.0)
        self.assertEqual(event["profile_hint_opaque"], opaque_profile_hint(self.settings.run_scope))
        self.assertNotIn("title", event)
        self.assertNotIn("payload", event)
        item = self.store.claim(300, 5)
        self.assertEqual(item["event_id"], "dlv_0123456789abcdef")
        # Negative leak check: no private title or payload byte reaches the push bridge state.
        state = self.state_bytes()
        for secret in (PRIVATE_TITLE, "sk-live-PRIVATE-TOKEN", "Diagnostic medical"):
            self.assertNotIn(secret.encode("utf-8"), state)

    def test_acked_malformed_unknown_and_pre_cutoff_rows_never_wake(self):
        db = self.fork_db()
        self.insert(db, "dlv_aaaaaaaaaaaaaaaa", acked_at=250.0)
        self.insert(db, "not-a-delivery-id")
        self.insert(db, "dlv_bbbbbbbbbbbbbbbb", kind="shell_exec")
        self.insert(db, "dlv_cccccccccccccccc", created_at=50.0)
        self.insert(db, "dlv_dddddddddddddddd", session_id="bad\nlink", urgency="critical")
        self.scan(cutoff=100)
        (event,) = self.store.events()["events"]
        self.assertEqual(event["event_id"], "dlv_dddddddddddddddd")
        self.assertIsNone(event["session_id"])
        self.assertEqual(event["urgency"], "normal")

    def test_rescans_and_bridge_posted_twin_deduplicate_while_missing_db_is_quiet(self):
        self.scan()  # Fork has not created mobile_state.db yet.
        self.assertFalse(self.store.events()["events"])
        hint = opaque_profile_hint(self.settings.run_scope)
        self.store.create_delivery({"delivery_id": "dlv_eeeeeeeeeeeeeeee", "kind": "notice", "title": "ok"},
                                   profile_hint_opaque=hint, now=200.0)
        db = self.fork_db()
        self.insert(db, "dlv_eeeeeeeeeeeeeeee", kind="notice")
        self.scan()
        self.ledgers.close()
        self.scan()
        events = self.store.events()["events"]
        self.assertEqual([e["event_id"] for e in events], ["dlv_eeeeeeeeeeeeeeee"])

    def test_normalize_urgency_maps_fork_levels_and_rejects_unknown(self):
        self.assertEqual(normalize_urgency("urgent"), "high")
        for level in ("normal", "high", "silent"):
            self.assertEqual(normalize_urgency(level), level)
        for invalid in ("critical", "HIGH", "", None, 3):
            self.assertIsNone(normalize_urgency(invalid))


if __name__ == "__main__":
    unittest.main()
