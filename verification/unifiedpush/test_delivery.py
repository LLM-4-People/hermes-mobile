import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from .prove import delivery_state, new_binary_publication


class ColdDeliveryTest(unittest.TestCase):
    def test_waits_for_one_additional_binary_publication(self):
        prior = [{"encoding": "base64"}]
        self.assertFalse(new_binary_publication(prior, 1))
        self.assertTrue(new_binary_publication(prior * 2, 1))
        for messages in ([], prior + [{}], prior * 3):
            with self.subTest(messages=messages), self.assertRaises(AssertionError):
                new_binary_publication(messages, 1)

    def test_reports_only_state_for_the_exact_delivery(self):
        phone = Mock()
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "state.db"
            with sqlite3.connect(db) as connection:
                connection.execute("CREATE TABLE deliveries(device_id, event_id, attempts INTEGER)")
                connection.execute("INSERT INTO deliveries VALUES ('private-id', 'private-event', 2)")
            for event, attempts in (("private-event", 2), ("another-event", None)):
                phone.shell.side_effect = ["private android.permission.POST_NOTIFICATIONS: granted=true",
                                          "private SubscriberService"]
                self.assertEqual(delivery_state(db, phone, "private-id", event), {
                    "pending_relay_attempts": attempts, "phone_notification_permission": True,
                    "distributor_subscriber_service": True,
                })
