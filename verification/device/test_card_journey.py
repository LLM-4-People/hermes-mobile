"""Keep required card evidence tied to the installed app, test APK and captured images."""

import base64
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from verification.device.card_journey import APP, CAPTURES, DRIVER, CardPhone


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNQUDgCAAFoAQXnHg1YAAAAAElFTkSuQmCC"
)


class CardJourneyTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name)
        args = SimpleNamespace(serial="emulator", adb="adb", adb_port=5037, output=self.output, verify_apks=None)
        self.phone = CardPhone(args, None, [])
        self.phone.device = Mock()

    def test_wrong_app_or_instrumentation_apk_fails_before_creating_a_tunnel(self):
        apks = [self.output / "app.apk", self.output / "test.apk"]
        for path in apks:
            path.write_bytes(path.name.encode())
        self.phone.verify_apks = apks
        hashes = [sha256(path.read_bytes()).hexdigest() for path in apks]
        for wrong in (0, 1):
            with self.subTest(package=apks[wrong].name):
                actual = hashes.copy()
                actual[wrong] = "a" * 64
                self.phone.device.shell.side_effect = ["1", "instrumentation:" + DRIVER]
                with patch("verification.device.card_journey.installed_apk_sha256", side_effect=actual):
                    with self.assertRaisesRegex(ValueError, apks[wrong].name):
                        self.phone.__enter__()
                self.phone.device.reverse.assert_not_called()

    def test_receipt_hashes_each_export_and_requires_all_eight_screens(self):
        self.phone.apk_hash = "a" * 64
        self.phone.test_apk_hash = "b" * 64
        self.phone.device.shell.side_effect = ["\n".join(CAPTURES), ""]
        self.phone.device.run.return_value = PNG
        self.phone.cleanup()
        evidence = self.phone.evidence()
        self.assertEqual(evidence["apk_sha256"], self.phone.apk_hash)
        self.assertEqual(evidence["test_apk_sha256"], self.phone.test_apk_hash)
        self.assertEqual(len(evidence["captures_sha256"]), 8)
        self.assertEqual(evidence["captures_sha256"], dict.fromkeys(CAPTURES, sha256(PNG).hexdigest()))
        (self.output / "card-voice-draft.png").unlink()
        with self.assertRaises(FileNotFoundError):
            self.phone.evidence()

    def test_truncated_export_fails_and_private_fixture_is_removed(self):
        self.phone.device.shell.side_effect = ["card-1.png", ""]
        self.phone.device.run.return_value = PNG[:-1]
        with self.assertRaisesRegex(ExceptionGroup, "Device cleanup failed"):
            self.phone.cleanup()
        self.assertFalse((self.output / "card-1.png").exists())
        self.phone.device.shell.assert_called_with("run-as", APP, "rm", "-r", self.phone.directory)
