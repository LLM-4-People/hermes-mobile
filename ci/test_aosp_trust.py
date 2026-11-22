"""Verify isolated certificate issuance, emulator selection and failure cleanup."""

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import aosp_trust


class FakePhone:
    def __init__(self):
        self.uid = "2000"
        self.anchor = None
        self.checks = []
        self.staging = None
        self.google = False
        self.boot = 1
        self.verity = True
        self.next_verity = True

    def run(self, *args, **kwargs):
        if args in [("root",), ("unroot",)]:
            self.uid = "0" if args[0] == "root" else "2000"
        if args == ("remount",):
            self.next_verity = False
        if args == ("enable-verity",):
            self.next_verity = True
        if args == ("reboot",):
            self.boot += 1
            self.uid = "2000"
            self.verity = self.next_verity
        if args[0] == "push" and args[2].startswith(aosp_trust.CA_DIRECTORY):
            if self.verity:
                raise RuntimeError("verified image is read-only")
            self.anchor = args[2]
        return b""

    def shell(self, *args):
        if args[0] == "getprop":
            return {"partition.system.verified": "2" if self.verity else "", "ro.boot.veritymode": "enforcing", "sys.boot_completed": "1",
                    "ro.boot.qemu.avd_name": "Acceptance", "ro.kernel.qemu": "1", "ro.build.version.sdk": "33", "ro.build.type": "userdebug",
                    "ro.product.cpu.abi": "x86_64", "ro.debuggable": "1"}[args[1]]
        if args == ("cat", "/proc/sys/kernel/random/boot_id"):
            return str(self.boot)
        if args == ("getenforce",):
            return "Enforcing"
        if args[:2] == ("pm", "list"):
            return "package:com.android.systemui" + ("\npackage:com.google.android.gms" if self.google else "")
        if args[0] == "am":
            return "0"
        if args == ("id", "-u"):
            return self.uid
        if args[0] == "mkdir":
            self.staging = args[1]
        if args[0] == "env":
            state = args[5]
            assert state == ("present" if self.anchor else "absent")
            self.checks.append((state, self.uid))
            return "platform-trust-" + state
        if args[0:2] == ("rm", "-f"):
            assert self.uid == "0"
            self.anchor = None
        if args[0:2] == ("rm", "-r"):
            self.staging = None
        return ""


class TestPrivateTrust(unittest.TestCase):
    def test_leaf_verifies_only_for_signed_loopback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ca, leaf, key = aosp_trust.issue_certificates(root)
            aosp_trust.openssl("verify", "-CAfile", ca, "-verify_ip", "127.0.0.1", leaf)
            with self.assertRaises(subprocess.CalledProcessError):
                aosp_trust.openssl("verify", "-CAfile", ca, "-verify_ip", "127.0.0.2", leaf)
            with self.assertRaises(subprocess.CalledProcessError):
                aosp_trust.openssl("verify", leaf)
            self.assertEqual(key.stat().st_mode & 0o077, 0)
            self.assertFalse((root / "ca.key").exists())

    def test_adbd_restart_may_close_the_root_command_before_acknowledging_it(self):
        phone = FakePhone()
        original = phone.run

        def restarting(*args, **options):
            result = original(*args, **options)
            if args == ("root",):
                raise RuntimeError("ADB connection closed")
            return result

        with patch.object(phone, "run", side_effect=restarting), patch("aosp_trust.time.sleep"):
            aosp_trust.adb_mode(phone, "root")
            self.assertEqual(phone.uid, "0")
            aosp_trust.adb_mode(phone, "unroot")
            self.assertEqual(phone.uid, "2000")

    def test_foreign_phone_is_rejected_before_writes(self):
        phone = FakePhone()
        with self.assertRaisesRegex(ValueError, "name differs"):
            aosp_trust.require_disposable_phone(phone, "Foreign")
        phone.google = True
        with self.assertRaisesRegex(ValueError, "Google-free"):
            aosp_trust.require_disposable_phone(phone, "Acceptance")
        self.assertIsNone(phone.staging)
        self.assertEqual(phone.uid, "2000")

    def test_cleanup_on_failed_child_verifies_untrusted_and_restores_adb(self):
        phone = FakePhone()
        with tempfile.TemporaryDirectory() as tmp, patch.object(aosp_trust, "build_probe", return_value=Path("probe.jar")):
            with self.assertRaisesRegex(RuntimeError, "child failed"):
                with aosp_trust.system_trust(phone, "Acceptance", Path(tmp)) as paths:
                    self.assertTrue(all(Path(path).is_file() for path in paths.values()))
                    self.assertEqual(phone.uid, "2000")
                    raise RuntimeError("child failed")
        self.assertTrue(phone.verity)
        self.assertEqual(phone.checks, [("absent", "2000"), ("present", "2000"), ("absent", "2000")])
        self.assertEqual(phone.uid, "2000")
        self.assertIsNone(phone.anchor)
        self.assertIsNone(phone.staging)
