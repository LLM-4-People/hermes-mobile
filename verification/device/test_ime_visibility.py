import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from run import Emulator


class ImeVisibilityTest(unittest.TestCase):
    def test_remote_query_returns_only_a_single_confirmed_boolean(self):
        device = Emulator("fixture", "adb", 5037)
        with tempfile.TemporaryDirectory() as directory:
            dumpsys = Path(directory) / "dumpsys"
            dumpsys.write_text('#!/bin/sh\ncat "$TEST_IME_DUMP"\nexit "$TEST_IME_STATUS"\n')
            dumpsys.chmod(0o755)
            dump = Path(directory) / "ime.txt"
            for fields, status, expected in [
                    ("mInputShown=true", 0, True),
                    ("mInputShown=false", 0, False),
                    ("mInputShown=true\nmInputShown=true", 0, True),
                    ("mInputShown=true\nmInputShown=false", 0, None),
                    ("mInputShown=true", 255, None),
                    ("mInputShown=trueLater", 0, None),
                    ("unrelatedmInputShown=false", 0, None)]:
                with self.subTest(fields=fields, status=status):
                    dump.write_text("unrelated-private-output\n" * 20000 + fields + "\n")
                    env = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"],
                           "TEST_IME_DUMP": str(dump), "TEST_IME_STATUS": str(status)}

                    def shell(command):
                        process = subprocess.run(["bash", "-c", command], env=env,
                                                 capture_output=True, text=True)
                        self.assertNotIn("unrelated-private-output", process.stdout + process.stderr)
                        self.assertLess(len(process.stdout), 12)
                        process.check_returncode()
                        return process.stdout.strip()

                    with patch.object(device, "shell", side_effect=shell):
                        if expected is None:
                            with self.assertRaises((subprocess.CalledProcessError, RuntimeError)):
                                device.ime_shown()
                        else:
                            self.assertIs(device.ime_shown(), expected)
