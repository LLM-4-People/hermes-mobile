import subprocess
import unittest
from unittest.mock import patch

from run import Emulator, cleanup_all


class DeviceHarnessTest(unittest.TestCase):
    def test_two_gateways_keep_distinct_reverse_registrations(self):
        phone = Emulator("test", "adb", 5037)
        registrations = {}

        def adb(command, operation, socket, *target):
            self.assertEqual(command, "reverse")
            if operation == "--no-rebind":
                self.assertNotIn(socket, registrations)
                registrations[socket] = target[0]
            elif operation == "--remove":
                del registrations[socket]

        with patch.object(phone, "run", side_effect=adb):
            with phone.reverse("http://127.0.0.1:39101"), phone.reverse("http://127.0.0.1:39102"):
                self.assertEqual(registrations, {"tcp:39101": "tcp:39101", "tcp:39102": "tcp:39102"})
            self.assertEqual(registrations, {})

    def test_cleanup_preserves_primary_failure_and_all_restoration_steps_run(self):
        restored = []
        with self.assertRaisesRegex(AssertionError, "primary") as error:
            try:
                raise AssertionError("primary")
            finally:
                def offline():
                    raise RuntimeError("transport offline")
                cleanup_all(offline, lambda: restored.append("night"))
        self.assertEqual(restored, ["night"])
        self.assertIn("transport offline", error.exception.__notes__[0])

    def test_empty_adb_failure_identifies_operation_without_command_arguments(self):
        phone = Emulator("test", "adb", 5037)
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], -6, b"", b"")):
            with self.assertRaisesRegex(RuntimeError, "adb shell am exited with status -6: no output"):
                phone.run("shell", "am", "private-value")


if __name__ == "__main__":
    unittest.main()
