from concurrent.futures import ThreadPoolExecutor
import json
import sqlite3
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import MagicMock, Mock, patch
from datetime import datetime, timezone
from server.mobile_push.events import Event
from server.mobile_push.ledgers import HermesLedgers
from server.mobile_push.store import Store
from server.mobile_push.fcm import FirebaseSender, ProviderError
from server.mobile_push.worker import deliver
from support import fixture, create_runs, insert_run


class Delivery(unittest.TestCase):
    def setUp(self):
        root = TemporaryDirectory()
        self.addCleanup(root.cleanup)
        self.settings, self.store = fixture(root.name)
        self.device("d1", "token")
        self.event = Event.make(["r"], "run_completed", 100, session_id="s", run_id="r")

    def device(self, ident, token):
        self.store.device(ident, self.settings.profile_key, profile=True,
                          now=10, token=token, management_key="a"*64)

    def test_reopen_dedup_and_expired_claim(self):
        self.store.record([self.event]*3)
        with ThreadPoolExecutor(2) as pool:
            claims = list(pool.map(lambda _: self.store.claim(101,5), range(2)))
        self.assertEqual(sum(c is not None for c in claims), 1)
        stale = next(c for c in claims if c)
        recovered = Store(self.settings.state)
        self.assertIsNone(recovered.claim(105, 5))
        item = recovered.claim(106, 5)
        self.assertEqual(item["event_id"], stale["event_id"])
        self.assertNotEqual(item["lease"], stale["lease"])
        recovered.settle(stale, unregistered=True)
        recovered.settle(item)
        recovered.record([self.event])
        self.assertIsNone(recovered.claim(500, 5))

    def test_invalid_old_token_cannot_remove_rotated_token(self):
        self.store.record([self.event])
        old = self.store.claim(101, 5)
        self.device("d1", "new-token")
        self.store.settle(old, unregistered=True)
        current = self.store.claim(101, 5)
        self.assertEqual(current["token"], "new-token")
        deliver(current, self.settings, self.store, Mock(send=Mock(return_value=False)))
        self.store.record([Event.make(["another"], "reminder", 105)])
        self.assertIsNone(self.store.claim(106, 5))

    def test_transient_retry_retention_and_no_historical_delivery(self):
        self.store.record([self.event, Event.make(["earlier"], "reminder", 9)])
        item = self.store.claim(101, 5)
        with patch("server.mobile_push.worker.time.time",return_value=101), self.assertLogs("hermes.mobile.push"):
            deliver(item, self.settings, self.store, Mock(send=Mock(side_effect=RuntimeError("confidential"))))
        self.assertIsNone(self.store.claim(101, 5))
        self.assertEqual(self.store.claim(162,5)["attempts"], 1)
        self.store.expire(101)
        self.assertIsNone(self.store.claim(170,5))

    def test_firebase_uses_only_opaque_data_and_no_collapse_for_distinct_events(self):
        with patch("server.mobile_push.fcm.google.auth.default", return_value=(Mock(), None)):
            sender = FirebaseSender(self.settings)
        self.addCleanup(sender.session.close)
        self.store.record([self.event])
        item = self.store.claim(101, 5)
        resp = MagicMock()
        resp.__enter__.return_value = resp
        resp.status_code = 200
        with patch.object(sender.session, "post", return_value=resp) as post, \
             patch("server.mobile_push.fcm.time.time", return_value=102):
            self.assertTrue(sender.send(item))
            self.assertEqual(post.call_args.args, ("https://fcm.googleapis.com/v1/projects/test-project/messages:send",))
            msg = post.call_args.kwargs["json"]["message"]
            self.assertEqual(msg, {"token":"token", "data":{"kind":"run_completed","event_id":self.event.event_id,
                             "installation_id":"d1"}, "android":{"priority":"high","ttl":"9998s"}})
            with patch("server.mobile_push.fcm.time.time", return_value=item["created_at"] + sender.retention - 2):
                self.assertTrue(sender.send(item))
                self.assertEqual(post.call_args.kwargs["json"]["message"]["android"]["ttl"],"2s")
            count = post.call_count
            with patch("server.mobile_push.fcm.time.time", return_value=item["created_at"] + sender.retention):
                self.assertTrue(sender.send(item))
                self.assertEqual(post.call_count, count)
            resp.status_code, resp.headers = 429, {"Retry-After": "450"}
            with self.assertRaises(ProviderError) as raised:
                sender.send(item)
            self.assertEqual(raised.exception.retry_after, 450)
            resp.status_code = 404
            resp.json.return_value = {"error":{"details":[{"errorCode":"UNREGISTERED"}]}}
            with self.assertRaises(ProviderError):
                sender.send(item)
            resp.json.return_value["error"]["details"][0]["@type"] = "type.googleapis.com/google.firebase.fcm.v1.FcmError"
            self.assertFalse(sender.send(item))

    def test_reconciles_actual_fields_across_writes_scope_and_restart(self):
        runs = create_runs(self.settings)
        self.addCleanup(runs.close)
        sources = HermesLedgers(self.settings)
        self.addCleanup(sources.close)
        def scan():
            for ledger, args in sources.sources(0):
                ledger.scan(args, self.store, 0)
        scan()
        insert_run(runs, "foreign", "r2", 101, "completed")
        insert_run(runs, self.settings.run_scope,"r1",101,"running",session_id="s")
        scan()
        self.assertFalse(self.store.events()["events"])
        runs.execute("""UPDATE run_idempotency SET status_json='{"status":"waiting_for_approval",
                     "session_id":"s","approval":{"timestamp":101,"command":"sensitive"}}' WHERE run_id='r1'""")
        runs.commit()
        scan()
        (approval,) = self.store.events()["events"]
        self.assertEqual(approval["kind"], "approval_requested")
        self.assertEqual(approval["session_id"], "s")
        runs.execute("""UPDATE run_idempotency SET status_json='{"status":"completed","session_id":"s",
                     "output":"private response"}',updated_at=104 WHERE run_id='r1'""")
        runs.commit()
        cron = self.settings.home / "cron"
        cron.mkdir()
        db = sqlite3.connect(cron/"executions.db")
        self.addCleanup(db.close)
        db.execute("CREATE TABLE executions(id TEXT,job_id TEXT,started_at TEXT)")
        db.executemany("INSERT INTO executions VALUES(?,?,?)",
                       [("started","j",datetime.fromtimestamp(105,timezone.utc).isoformat()), ("unfired","j2",None)])
        db.commit()
        scan()
        sources.close()
        scan()
        events = self.store.events()["events"]
        self.assertEqual([e["kind"] for e in events], ["approval_requested","run_completed","reminder"])
        self.assertEqual(events[-1]["job_id"], "j")
        self.assertNotIn("private", repr(events))
        self.assertNotIn("sensitive", repr(events))

    def test_checkpointed_wal_on_read_only_profile_mount_and_subsequent_writer(self):
        source = create_runs(self.settings)
        source.execute("PRAGMA journal_mode=WAL")
        insert_run(source, self.settings.run_scope, "run1", 101, "completed")
        source.close()
        path = self.settings.home / "runs_idempotency.db"
        path.chmod(0o444)
        self.settings.home.chmod(0o555)
        ledgers = HermesLedgers(self.settings)
        self.addCleanup(ledgers.close)
        try:
            ledgers.runs.scan((self.settings.run_scope, 0), self.store, 0)
            self.assertEqual(self.store.events()["events"][0]["run_id"], "run1")
        finally:
            self.settings.home.chmod(0o700)
            path.chmod(0o600)
        writer = sqlite3.connect(path)
        self.addCleanup(writer.close)
        insert_run(writer, self.settings.run_scope, "run2", 102, "failed")
        ledgers.runs.scan((self.settings.run_scope, 0), self.store, 0)
        self.assertEqual([v["run_id"] for v in self.store.events()["events"]], ["run1", "run2"])

    def test_settled_cancelled_and_replaced_approvals_are_retracted_before_delivery(self):
        runs = create_runs(self.settings)
        self.addCleanup(runs.close)
        sources = HermesLedgers(self.settings)
        self.addCleanup(sources.close)
        scope = self.settings.run_scope
        insert_run(runs, scope, "run", 110, "waiting_for_approval",
                   session_id="s", pending_approvals={"p1": 101, "p2": 102})
        def scan():
            sources.runs.scan((scope, 0), self.store, 0)
        def change(status, **fields):
            runs.execute("UPDATE run_idempotency SET status_json=?, updated_at=updated_at+1",
                         (json.dumps({"status":status,"session_id":"s",**fields}),))
            runs.commit()
            scan()
        scan()
        first,second = self.store.events()["events"]
        self.assertNotEqual(first["event_id"], second["event_id"])
        change("waiting_for_approval",pending_approvals={"p2":102})
        claim = self.store.claim(120, 1)
        self.assertEqual(claim["event_id"], second["event_id"])
        change("cancelled")
        sender = Mock()
        deliver(claim, self.settings, self.store, sender)
        sender.send.assert_not_called()
        self.assertIsNone(self.store.claim(130, 1))
        change("completed")
        self.assertEqual(self.store.claim(130, 1)["kind"], "run_completed")

    def test_answer_retracts_unsent_questions_and_dismisses_only_possible_recipients(self):
        self.device("other-device", "other-token")
        scope = self.settings.run_scope
        runs = create_runs(self.settings)
        self.addCleanup(runs.close)
        insert_run(runs, scope, "run", 110, "waiting_for_approval", session_id="s",
                   pending_questions={"private-question-id": 105}, pending_approvals={"approval-id": 106})
        insert_run(runs, "other-user", "foreign", 110, "waiting_for_input", pending_questions={"q": 104})
        ledgers = HermesLedgers(self.settings)
        self.addCleanup(ledgers.close)
        ledgers.runs.scan((scope, 0), self.store, 0)
        first = self.store.claim(110, 30)
        other = self.store.claim(110, 30)
        self.assertEqual((first["kind"], other["kind"]), ("question_requested", "question_requested"))
        # A provider accepted one request without acknowledging it. The other recipient
        # is only leased. No dismissal is necessary for that unattempted delivery.
        self.assertTrue(self.store.begin_send(first))
        changed = {"status": "waiting_for_approval", "session_id": "s", "pending_approvals": {"approval-id": 106}}
        runs.execute("UPDATE run_idempotency SET status_json=?,updated_at=111 WHERE scope=?",
                     (json.dumps(changed), scope))
        runs.commit()
        sender = Mock(send=Mock(return_value=True))
        deliver(other, self.settings, self.store, sender)
        sender.send.assert_not_called()
        reopened = Store(self.settings.state)
        ledgers.runs.scan((scope, 0), reopened, 0)
        dismissal = reopened.claim(112, 1)
        self.assertEqual((dismissal["kind"], dismissal["event_id"], dismissal["device_id"]),
                         ("question_settled", first["event_id"], first["device_id"]))
        reopened.settle(first)
        ledgers.close()
        ledgers.runs.scan((scope, 0), reopened, 0)
        retried = []
        for _ in range(3):
            item = reopened.claim(115, 1)
            retried.append(item["kind"])
            deliver(item, self.settings, reopened, sender)
        self.assertCountEqual(retried, ["approval_requested", "approval_requested", "question_settled"])
        self.assertIsNone(reopened.claim(120, 1))
        events = reopened.events()["events"]
        self.assertEqual([event["run_id"] for event in events], ["run", "run"])
        self.assertNotIn("private-question-id", repr(events))

    def test_restart_with_closed_ledger_retires_previously_dispatched_question(self):
        runs = create_runs(self.settings)
        self.addCleanup(runs.close)
        scope = self.settings.run_scope
        insert_run(runs, scope, "gone", 101, "waiting_for_input", pending_questions={"q": 101})
        ledgers = HermesLedgers(self.settings)
        ledgers.runs.scan((scope, 0), self.store, 0)
        posted = self.store.claim(102, 1)
        deliver(posted, self.settings, self.store, Mock(send=Mock(return_value=True)))
        ledgers.close()
        runs.execute("DELETE FROM run_idempotency WHERE scope=?", (scope,))
        runs.commit()
        recovered = HermesLedgers(self.settings)
        self.addCleanup(recovered.close)
        recovered.runs.scan((scope, 0), self.store, 0)
        retired = self.store.claim(103, 1)
        self.assertEqual((retired["kind"], retired["event_id"]), ("question_settled", posted["event_id"]))

    def test_incremental_scan_retries_atomic_batch_and_catches_backdated_commits(self):
        runs = create_runs(self.settings)
        self.addCleanup(runs.close)
        scope = self.settings.run_scope
        runs.executemany("INSERT INTO run_idempotency VALUES(?,?,?,?,?)",
                         [(scope,f"r{i}","fp",i,'{"status":"completed"}') for i in range(5000)])
        runs.commit()
        sources = HermesLedgers(self.settings)
        self.addCleanup(sources.close)
        stream = sources.runs
        stream.scan((scope,0), self.store,0)
        insert_run(runs, scope, "rnew", 5020, "completed")
        with patch.object(self.store, "reconcile", side_effect=sqlite3.OperationalError("locked")):
            with self.assertRaises(sqlite3.OperationalError):
                stream.scan((scope,0), self.store,0)
        with patch.object(self.store, "reconcile", wraps=self.store.reconcile) as write:
            stream.scan((scope,0), self.store,0)
            self.assertLessEqual(len(write.call_args.args[0]), 4)
        insert_run(runs, scope, "late", 150, "failed")
        stream.scan((scope,0), self.store,0)
        with patch("server.mobile_push.ledgers.time.monotonic", return_value=stream.last_full + self.settings.reconcile_seconds):
            stream.scan((scope,0), self.store,0)
        late_id = Event.make(["late"],"run_failed",150).event_id
        self.assertEqual(self.store.events(event_id=late_id)["run_id"],"late")
        reopened = HermesLedgers(self.settings)
        try:
            reopened.runs.scan((scope,0), self.store,0)
        finally:
            reopened.close()
        self.assertEqual(len(self.store.events(limit=6000)["events"]), 5002)

    def test_recovered_lease_checks_authoritative_prompt_before_first_sweep(self):
        runs = create_runs(self.settings)
        self.addCleanup(runs.close)
        scope = self.settings.run_scope
        insert_run(runs, scope, "run", 110, "waiting_for_approval", pending_approvals={"p1": 101})
        ledgers = HermesLedgers(self.settings)
        ledgers.runs.scan((scope,0), self.store,0)
        ledgers.close()
        stale = self.store.claim(112, 10)
        runs.execute("""UPDATE run_idempotency SET status_json='{"status":"cancelled"}' WHERE run_id='run'""")
        runs.commit()
        sender = Mock()
        deliver(stale, self.settings, self.store, sender)
        sender.send.assert_not_called()
        self.assertIsNone(self.store.claim(150, 10))

    def test_approval_retraction_cost_is_bounded_by_affected_deliveries(self):
        from server.mobile_push.events import Observation

        def measure(size):
            with self.store.write() as db:
                db.executemany("INSERT OR IGNORE INTO events(event_id,kind,created_at) VALUES(?,'reminder',1)",
                               [(str(i),) for i in range(size)])
                db.execute("INSERT OR IGNORE INTO deliveries(device_id,event_id,due) SELECT 'd1',event_id,1 FROM events WHERE kind='reminder'")
            approval = Event.make([size], "approval_requested", 101, run_id="target")
            self.store.record([approval])
            # Count SQLite VM instructions (in tens). Independent of machine speed.
            steps = 0
            start = self.store.connect
            def measured_connection():
                db = start()
                def progress():
                    nonlocal steps
                    steps += 1
                    return 0
                db.set_progress_handler(progress, 10)
                return db
            with patch.object(self.store, "connect", side_effect=measured_connection):
                self.store.reconcile([Observation(105, (), "target")], 0)
            return steps

        small, large = measure(100), measure(10000)
        self.assertLessEqual(large, small + 20, (small,large))
