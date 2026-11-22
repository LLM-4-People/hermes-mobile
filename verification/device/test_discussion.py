"""Keep native discussion evidence tied to the packaged APKs and distinct capture states."""
import hashlib
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device.discussion import discussion_device, require_captures
from device.run import DRIVER


class DiscussionEvidenceTest(unittest.TestCase):
    def test_stale_or_duplicate_screens_fail_the_exact_collection(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            names = ("01-screen.png", "02-screen.png")
            first, second = (out / n for n in names)
            first.write_bytes(b"first")
            with self.assertRaises(AssertionError):
                require_captures(out, names)
            second.write_bytes(first.read_bytes())
            with self.assertRaises(AssertionError):
                require_captures(out, names)
            second.write_bytes(b"second")
            self.assertEqual(require_captures(out, names)[second.name], hashlib.sha256(b"second").hexdigest())
            (out / "03-extra.png").touch()
            with self.assertRaises(AssertionError):
                require_captures(out, names)

    def test_mismatched_test_apk_stops_before_device_account_or_fixture_write(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "app.apk"
            apk.write_bytes(b"packaged")
            installed = hashlib.sha256(apk.read_bytes()).hexdigest()
            args = SimpleNamespace(serial="fixture", adb="adb", adb_port=5037, verify_apks=[apk, apk])
            with patch("device.discussion.Emulator") as create, patch(
                    "device.discussion.installed_apk_sha256", side_effect=[installed, "0" * 64]):
                device = create.return_value
                device.shell.side_effect = ["1", DRIVER]
                with self.assertRaisesRegex(ValueError, "differs"):
                    with discussion_device(args, object()):
                        self.fail("The mismatched test APK must not run")
                device.reverse.assert_not_called()
                device.run.assert_not_called()
