import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import Mock, patch

from .prove import delivery_state, new_binary_publication
from .stack import NTFY_MANAGER_LOG, wait_for_ntfy_subscriber


class ColdDeliveryTest(unittest.TestCase):
    def test_waits_for_one_additional_binary_publication(self):
        prior = [{"encoding": "base64"}]
        self.assertFalse(new_binary_publication(prior, 1))
        self.assertTrue(new_binary_publication(prior * 2, 1))
        for messages in ([], prior + [{}], prior * 3):
            with self.subTest(messages=messages), self.assertRaises(AssertionError):
                new_binary_publication(messages, 1)

    def test_only_a_fresh_live_count_for_the_enrolled_topic_is_ready(self):
        def sample(topic, count):
            return json.dumps({"tag": "manager", "topic": topic, "topic_subscribers": count}).encode() + b"\n"

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            path = root / NTFY_MANAGER_LOG
            historical = sample("upEnrolled", 1)
            path.write_bytes(historical + historical[:10])
            with path.open("ab", buffering=0) as log:
                def exercise(_description, active):
                    self.assertFalse(active())  # Ignore a subscriber that existed before this wait.
                    log.write(historical[10:])
                    self.assertFalse(active())
                    log.write(sample("upOther", 1) + sample("upEnrolled", 0))
                    self.assertFalse(active())
                    record = sample("upEnrolled", 1)
                    log.write(record[:-1])
                    self.assertFalse(active())
                    log.write(record[-1:])
                    self.assertTrue(active())
                    log.write(record + sample("upEnrolled", 0))
                    self.assertFalse(active())

                with patch("verification.unifiedpush.stack.eventually", side_effect=exercise):
                    wait_for_ntfy_subscriber(root, "http://127.0.0.1:54321/upEnrolled")

    def test_reports_only_state_for_the_exact_delivery(self):
        phone = Mock()
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "state.db"
            with closing(sqlite3.connect(db)) as connection, connection:
                connection.execute("CREATE TABLE deliveries(device_id, event_id, attempts INTEGER)")
                connection.execute("INSERT INTO deliveries VALUES ('private-id', 'private-event', 2)")
            for event, attempts in (("private-event", 2), ("another-event", None)):
                phone.shell.side_effect = ["private android.permission.POST_NOTIFICATIONS: granted=true",
                                          "private SubscriberService"]
                self.assertEqual(delivery_state(db, phone, "private-id", event), {
                    "pending_relay_attempts": attempts, "phone_notification_permission": True,
                    "distributor_subscriber_service": True,
                })
