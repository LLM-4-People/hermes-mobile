from concurrent.futures import ThreadPoolExecutor
import json
import math
import sqlite3
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

    def test_firebase_delivers_live_subsecond_message_with_zero_storage_ttl(self):
        from dataclasses import replace
        with patch("server.mobile_push.fcm.google.auth.default", return_value=(Mock(), None)):
            sender = FirebaseSender(replace(self.settings, retention=1))
        self.addCleanup(sender.session.close)
        self.store.record([self.event])
        item = self.store.claim(100, 5)
        with patch.object(sender.session, "post") as post:
            post.return_value.__enter__.return_value.status_code = 200
            for now in (100.02, 100.999, 101, 101.1):
                with patch("server.mobile_push.fcm.time.time", return_value=now):
                    self.assertTrue(sender.send(item))
            self.assertEqual([r.kwargs["json"]["message"]["android"]["ttl"] for r in post.call_args_list],
                             ["0s", "0s"])

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

    def test_prompt_withdrawal_has_fresh_ttl_after_public_history_expires(self):
        from dataclasses import replace
        from server.mobile_push.provider import lifetime, opaque_notice

        for request, settled, field in (("question_requested", "question_settled", "pending_questions"),
                                        ("approval_requested", "approval_settled", "pending_approvals")):
            for expire_first in (True, False):
                with self.subTest(request=request, expire_first=expire_first), TemporaryDirectory() as root:
                    settings, store = fixture(root)
                    settings = replace(settings, retention=5)
                    for device in ("accepted", "unattempted"):
                        store.device(device, settings.profile_key, profile=True, now=90,
                                     token=device, management_key="a"*64)
                    runs = create_runs(settings)
                    self.addCleanup(runs.close)
                    status = "waiting_for_input" if field == "pending_questions" else "waiting_for_approval"
                    insert_run(runs, settings.run_scope, "private-run", 100, status,
                               session_id="private-session", **{field: {"private-prompt": 100}})
                    ledgers = HermesLedgers(settings)
                    self.addCleanup(ledgers.close)
                    ledgers.runs.scan((settings.run_scope, 95), store, 95)
                    sent = store.claim(101, 60)
                    self.assertEqual(sent["kind"], request)
                    # Simulate a provider acceptance whose acknowledgement never reached the worker.
                    self.assertTrue(store.begin_send(sent))
                    store = Store(settings.state)
                    with patch("server.mobile_push.store.time.time", return_value=108):
                        if expire_first:
                            store.expire(103)
                        runs.execute("UPDATE run_idempotency SET status_json=?,updated_at=108",
                                     (json.dumps({"status": "running", "session_id": "private-session"}),))
                        runs.commit()
                        ledgers.runs.scan((settings.run_scope, 103), store, 103)
                        store.expire(103)
                    store.settle(sent, unregistered=True)
                    store = Store(settings.state)
                    withdrawal = store.claim(108, 1)
                    self.assertEqual(opaque_notice(withdrawal), {"kind": settled, "event_id": sent["event_id"],
                                                                 "installation_id": sent["device_id"]})
                    self.assertIsNone(withdrawal["run_id"])
                    self.assertEqual(lifetime(withdrawal, settings.retention, 108), 5)
                    self.assertIsNone(store.events(event_id=sent["event_id"]))
                    self.assertIsNone(store.events(event_id=withdrawal["storage_id"]))
                    self.assertEqual(store.events()["events"], [])
                    with patch("server.mobile_push.fcm.google.auth.default", return_value=(Mock(), None)):
                        sender = FirebaseSender(settings)
                    self.addCleanup(sender.session.close)
                    with patch.object(sender.session, "post") as post, self.assertLogs("hermes.mobile.push"):
                        response = post.return_value.__enter__.return_value
                        response.ok = False
                        response.status_code = 503
                        response.headers = {}
                        with patch("server.mobile_push.worker.time.time", return_value=109):
                            deliver(withdrawal, settings, store, sender)
                        self.assertIsNone(store.claim(110, 2))
                        store.expire(105)
                        retried = Store(settings.state).claim(111, 1)
                        self.assertEqual((retried["created_at"], retried["attempts"]), (108, 1))
                        response.ok = True
                        response.status_code = 200
                        with patch("server.mobile_push.worker.time.time", return_value=111):
                            deliver(retried, settings, store, sender)
                        message = post.call_args.kwargs["json"]["message"]
                        self.assertEqual(post.call_count, 2)
                        self.assertEqual(message["android"]["ttl"], "2s")
                        self.assertEqual(message["data"], opaque_notice(withdrawal))
                    self.assertEqual(lifetime(retried, settings.retention, 114), 0)
                    store.expire(109)
                    self.assertIsNone(store.claim(120, 5))

    def test_previous_outbox_migrates_queued_dismissal_with_fresh_clock(self):
        event = Event.make(["old"], "question_requested", 100, run_id="private-run")
        self.store.record([event])
        with self.store.write() as db:
            db.execute("DROP INDEX visible_events")
            db.execute("ALTER TABLE events DROP COLUMN supersedes")
            db.execute("ALTER TABLE prompt_recipients RENAME TO question_recipients")
            db.execute("ALTER TABLE deliveries ADD COLUMN dismiss INTEGER NOT NULL DEFAULT 0")
            db.execute("UPDATE deliveries SET dismiss=1")
        with patch("server.mobile_push.store.time.time", return_value=110):
            migrated = Store(self.settings.state)
        migrated.expire(106)
        item = Store(self.settings.state).claim(111, 2)
        self.assertEqual((item["kind"], item["event_id"], item["created_at"]), ("question_settled", event.event_id, 110))
        migrated.device("d1", self.settings.profile_key, profile=True, now=111)
        self.assertFalse(migrated.begin_send(item))
        self.assertIsNone(migrated.claim(120, 2))

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

    # At this clock origin, subtracting 600.1 from the 1200.1 deadline rounds below 600.
    @patch("server.mobile_push.ledgers.time.monotonic", return_value=600.1)
    def test_incremental_scan_retries_atomic_batch_and_catches_backdated_commits(self, clock):
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
        late_id = Event.make(["late"], "run_failed", 150).event_id
        deadline = clock.return_value + self.settings.reconcile_seconds
        clock.return_value = math.nextafter(deadline, -math.inf)
        stream.scan((scope, 0), self.store, 0)
        self.assertIsNone(self.store.events(event_id=late_id))
        clock.return_value = deadline
        stream.scan((scope, 0), self.store, 0)
        self.assertEqual(self.store.events(event_id=late_id)["run_id"], "late")
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

    def test_typed_deliveries_enforce_zero_leak_fcm_wire_and_priority(self):
        from server.mobile_push.events import DELIVERY_KINDS, opaque_profile_hint
        from server.mobile_push.provider import opaque_notice

        hint = opaque_profile_hint(self.settings.run_scope)
        secret_title = "Confidentiel: bilan financier Q4"
        secret_path = "/srv/secrets/clients/dossier-confidentiel.pdf"
        with patch("server.mobile_push.fcm.google.auth.default", return_value=(Mock(), None)):
            sender = FirebaseSender(self.settings)
        self.addCleanup(sender.session.close)
        resp = MagicMock()
        resp.__enter__.return_value = resp
        resp.status_code = 200
        resp.ok = True

        with patch.object(sender.session, "post", return_value=resp) as post, \
             patch("server.mobile_push.fcm.time.time", return_value=201), \
             self.assertLogs("hermes.mobile.push", level="INFO") as logs:
            for idx, (kind, urgency, expected_prio) in enumerate([
                ("notice", "normal", "normal"),
                ("card", "normal", "normal"),
                ("file", "high", "high"),
                ("ui_patch", "silent", "normal"),
                ("widget_update", "silent", "normal"),
                ("device_action", "high", "high"),
                ("reminder_result", "normal", "normal"),
                ("briefing", "normal", "normal"),
            ]):
                self.assertIn(kind, DELIVERY_KINDS)
                created, is_new = self.store.create_delivery(
                    {
                        "kind": kind,
                        "urgency": urgency,
                        "title": secret_title,
                        "payload": {"path": secret_path, "snippet": "ne jamais fuiter"},
                        "session_id": "sess-priv-99",
                    },
                    profile_hint_opaque=hint,
                    now=200 + idx,
                )
                self.assertTrue(is_new)
                item = self.store.claim(200 + idx, 5)
                self.assertIsNotNone(item)
                self.assertEqual(
                    opaque_notice(item),
                    {
                        "event_id": created["delivery_id"],
                        "installation_id": "d1",
                        "kind": kind,
                        "urgency": urgency,
                        "profile_hint_opaque": hint,
                    },
                )
                deliver(item, self.settings, self.store, sender)
                msg = post.call_args.kwargs["json"]["message"]
                self.assertEqual(msg["android"]["priority"], expected_prio)
                self.assertEqual(set(msg["data"].keys()),
                                 {"event_id", "installation_id", "kind", "urgency", "profile_hint_opaque"})
                wire_dump = json.dumps(msg)
                for forbidden in (secret_title, secret_path, "ne jamais fuiter", "sess-priv-99"):
                    self.assertNotIn(forbidden, wire_dump)

        joined_logs = "\n".join(logs.output)
        for forbidden in (secret_title, secret_path, "ne jamais fuiter", "sess-priv-99", self.settings.profile_key):
            self.assertNotIn(forbidden, joined_logs)
        self.assertIn("delivery_queued kind=briefing urgency=normal recipients=1", joined_logs)
        self.assertIn("push_delivered provider=fcm kind=briefing active=True", joined_logs)

    def test_delivery_validation_dedup_ack_and_expiry(self):
        from server.mobile_push.events import opaque_profile_hint, validate_delivery_input

        hint = opaque_profile_hint(self.settings.run_scope)
        for bad in (
            "not-a-dict",
            {"kind": "unknown", "title": "ok"},
            {"kind": "notice", "title": "ok", "urgency": "critical"},
            {"kind": "notice", "title": "   "},
            {"kind": "notice", "title": "x" * 241},
            {"kind": "notice", "title": "line1\nline2"},
            {"kind": "notice", "title": "ok", "payload": ["not-an-object"]},
            {"kind": "notice", "title": "ok", "payload": {"blob": "a" * 140000}},
            {"kind": "notice", "title": "ok", "extra": 1},
            {"kind": "notice", "title": "ok", "delivery_id": "not-32-hex"},
            {"kind": "notice", "title": "ok", "session_id": "bad\nid"},
        ):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_delivery_input(bad)

        fixed_id = "a" * 32
        first, created1 = self.store.create_delivery(
            {"delivery_id": fixed_id, "kind": "briefing", "title": "Point du matin", "payload": {"lines": ["ok"]}},
            profile_hint_opaque=hint,
            now=150,
        )
        second, created2 = self.store.create_delivery(
            {"delivery_id": fixed_id, "kind": "briefing", "title": "Point du matin", "payload": {"lines": ["ok"]}},
            profile_hint_opaque=hint,
            now=155,
        )
        self.assertTrue(created1)
        self.assertFalse(created2)
        self.assertEqual(first["delivery_id"], second["delivery_id"])
        self.assertIsNone(first["acked_at"])

        acked = self.store.ack_delivery(fixed_id, now=160)
        self.assertEqual(acked["acked_at"], 160.0)
        acked_again = self.store.ack_delivery(fixed_id, now=170)
        self.assertEqual(acked_again["acked_at"], 160.0)
        self.assertEqual(self.store.list_deliveries(include_acked=False)["deliveries"], [])
        self.assertEqual(len(self.store.list_deliveries(include_acked=True)["deliveries"]), 1)

        self.store.expire(200)
        self.assertIsNone(self.store.get_delivery(fixed_id))
