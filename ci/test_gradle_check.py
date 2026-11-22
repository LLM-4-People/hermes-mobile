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
from pathlib import Path
import sys
counter = Path(sys.argv[1])
attempt = int(counter.read_text()) + 1 if counter.exists() else 1
counter.write_text(str(attempt))
if attempt <= int(sys.argv[2]):
    print(sys.argv[3])
    sys.exit(7)
""", str(count), str(failures), error]
            with redirect_stdout(io.StringIO()):
                status = build(command, delays=(0, 0))
            return status, int(count.read_text())

    def test_retries_the_same_dependency_request_with_a_limit(self):
        error = self.central + "\n" + self.limited
        self.assertEqual(self.run_build(error, 1), (0, 2))
        self.assertEqual(self.run_build(error, 5), (7, 3))

    def test_other_failures_propagate_immediately(self):
        for error in ("Compilation failed", self.central + "\n> HTTP 404",
                      "Could not GET 'https://example.invalid/file'\n" + self.limited):
            with self.subTest(error=error):
                self.assertEqual(self.run_build(error, 1), (7, 1))
