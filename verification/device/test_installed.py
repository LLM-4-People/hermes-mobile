import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from installed import installed_apk_sha256, verify_disposable_emulator_apks


class InstalledPackageTest(unittest.TestCase):
    path = "/data/app/~~p3T=/org.example-za_10==/base.apk"
    digest = "a" * 64

    def test_digest_is_read_from_installed_package(self):
        device = Mock()
        device.shell.side_effect = ["package:" + self.path, self.digest + "  " + self.path]
        self.assertEqual(installed_apk_sha256(device, "org.example"), self.digest)
        self.assertEqual(device.shell.call_args.args, ("sha256sum", self.path))

    def test_split_unknown_or_malformed_paths_fail_before_hash(self):
        for path in ["package:/other/base.apk", "package:" + self.path + "\npackage:/split.apk", "missing"]:
            device = Mock()
            device.shell.return_value = path
            with self.subTest(path=path), self.assertRaises(ValueError):
                installed_apk_sha256(device, "org.example")
            device.shell.assert_called_once()

    def test_missing_or_wrong_on_device_digest_fails(self):
        for response in ["denied", self.digest + "  /other.apk", "0  " + self.path]:
            device = Mock()
            device.shell.side_effect = ["package:" + self.path, response]
            with self.subTest(response=response), self.assertRaises(ValueError):
                installed_apk_sha256(device, "org.example")

    def test_verify_disposable_emulator_apks_validates_emulator_driver_and_digests(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_apk = Path(tmp) / "app-debug.apk"
            test_apk = Path(tmp) / "app-debug-androidTest.apk"
            app_apk.write_bytes(b"app-bytes")
            test_apk.write_bytes(b"test-bytes")
            app_hash = hashlib.sha256(b"app-bytes").hexdigest()
            test_hash = hashlib.sha256(b"test-bytes").hexdigest()
            test_path = "/data/app/~~p3T=/org.example.test-za_10==/base.apk"

            device = Mock()
            device.shell.side_effect = [
                "1",
                "instrumentation:org.example.test/runner",
                "package:" + self.path,
                app_hash + "  " + self.path,
                "package:" + test_path,
                test_hash + "  " + test_path,
            ]
            self.assertEqual(
                verify_disposable_emulator_apks(
                    device, "org.example.test/runner", "org.example", [app_apk, test_apk]
                ),
                [app_hash, test_hash],
            )

            # Non-emulator rejected fail-closed.
            non_emu = Mock()
            non_emu.shell.return_value = "0"
            with self.assertRaisesRegex(ValueError, "disposable emulator"):
                verify_disposable_emulator_apks(non_emu, "org.example.test/runner", "org.example")

            # Missing instrumentation driver rejected fail-closed.
            no_driver = Mock()
            no_driver.shell.side_effect = ["1", "instrumentation:other/runner"]
            with self.assertRaisesRegex(ValueError, "disposable emulator"):
                verify_disposable_emulator_apks(no_driver, "org.example.test/runner", "org.example")

            # Mismatched APK digest rejected fail-closed.
            mismatch = Mock()
            mismatch.shell.side_effect = [
                "1",
                "instrumentation:org.example.test/runner",
                "package:" + self.path,
                self.digest + "  " + self.path,
                "package:" + test_path,
                test_hash + "  " + test_path,
            ]
            with self.assertRaisesRegex(ValueError, "differs from app-debug.apk"):
                verify_disposable_emulator_apks(
                    mismatch, "org.example.test/runner", "org.example", [app_apk, test_apk]
                )
