import json
import unittest

from aosp_receipt import APK, PINNED_REVISION, SERVER, device_receipt


class DeviceReceiptTest(unittest.TestCase):
    identity = {"signedSha256": "a" * 64, "unsignedSha256": "b" * 64, "sourceCommit": "c" * 40,
                "applicationId": "org.llm4people.hermes", "versionName": "0.1.0", "versionCode": 1}

    def setUp(self):
        self.row = {"notification_event_matched": True, "owning_profile_unlocked": True,
                    "owning_thread_opened": True, "revoked_delivery_suppressed": True,
                    "absent_before_delivery": True, "webpush_messages": 1, "distributor": APK.version,
                    "ntfy_server": SERVER.version, "hermes_revision": PINNED_REVISION,
                    "apk_sha256": self.identity["signedSha256"], "sdk": "33"}

    def receipt(self, *rows):
        return device_receipt(["Log line", *(json.dumps(r) for r in rows)], self.identity, "33")

    def test_only_verified_fields_leave_device_job(self):
        result = self.receipt(dict(self.row, private_topic="synthetic"))
        self.assertEqual(result["apk_sha256"], self.identity["signedSha256"])
        self.assertEqual(result["unsigned_apk_sha256"], self.identity["unsignedSha256"])
        self.assertNotIn("private_topic", result)

    def test_absent_or_duplicate_receipts_fail(self):
        for lines in [(), (self.row, self.row)]:
            with self.subTest(size=len(lines)), self.assertRaises(ValueError):
                self.receipt(*lines)

    def test_missing_wrong_or_false_fields_fail(self):
        for field, valid in self.row.items():
            if valid is True:
                wrong = (False, 1, "true")
            elif field == "webpush_messages":
                wrong = (0, 2, 1.0, True, "1")
            else:
                wrong = ("other", None)
            incomplete = self.row.copy()
            del incomplete[field]
            with self.subTest(field=field, case="missing"), self.assertRaises(ValueError):
                self.receipt(incomplete)
            for val in wrong:
                with self.subTest(field=field, value=val), self.assertRaises(ValueError):
                    self.receipt(dict(self.row, **{field: val}))
