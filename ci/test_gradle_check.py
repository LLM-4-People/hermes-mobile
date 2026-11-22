from contextlib import redirect_stdout
import io
from pathlib import Path
import sys
import tempfile
import unittest

from gradle_check import build


class BuildRetryTest(unittest.TestCase):
    central = "> Could not GET 'https://repo.maven.apache.org/maven2/example/pom'"
    limited = "> Received status code 429 from server: Too Many Requests"

    def run_build(self, error, failures):
        with tempfile.TemporaryDirectory() as directory:
            count = Path(directory) / "runs"
            command = [sys.executable, "-c", """
import os
from pathlib import Path
import sys
counter = Path(sys.argv[1])
modes = counter.read_text().splitlines() if counter.exists() else []
modes.append(os.environ["HERMES_CI_CENTRAL_MIRROR"])
attempt = len(modes)
counter.write_text(chr(10).join(modes))
if attempt <= int(sys.argv[2]):
    print(sys.argv[3])
    sys.exit(7)
""", str(count), str(failures), error]
            with redirect_stdout(io.StringIO()):
                status = build(command, delays=(0, 0))
            return status, count.read_text().splitlines()

    def test_retries_the_same_dependency_request_with_a_limit(self):
        error = self.central + "\n" + self.limited
        self.assertEqual(self.run_build(error, 1), (0, ["0", "1"]))
        self.assertEqual(self.run_build(error, 5), (7, ["0", "1", "1"]))

    def test_other_failures_propagate_immediately(self):
        for error in ("Compilation failed", self.central + "\n> HTTP 404",
                      "Could not GET 'https://example.invalid/file'\n" + self.limited):
            with self.subTest(error=error):
                self.assertEqual(self.run_build(error, 1), (7, ["0"]))
