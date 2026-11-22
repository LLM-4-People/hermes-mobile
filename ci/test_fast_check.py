"""Verify the parallel host test runner's suite selection and failure propagation."""

from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import fast_check


class FastCheckTest(unittest.TestCase):
    def test_workspace_only_covers_all_python_workspace_suites(self):
        names = [name for name, _ in fast_check.build_suites("/tmp/venv/python", workspace_only=True)]
        self.assertEqual(names, [
            "server-tests",
            "server-artifacts",
            "deploy",
            "verification-device",
            "verification-unifiedpush",
            "verification-phone-actions",
        ])

    def test_full_mode_adds_docs_ci_and_optional_gradle(self):
        names = [name for name, _ in fast_check.build_suites(None, workspace_only=False, with_gradle=True)]
        self.assertEqual(names[:2], ["docs", "ci"])
        self.assertEqual(names[-1], "gradle")

    def test_failure_in_any_suite_returns_nonzero(self):
        def fake_run(name, cmd):
            return (name, 1 if name == "deploy" else 0, 0.01, "", "boom" if name == "deploy" else "")

        with (patch.object(fast_check, "run_one", side_effect=fake_run),
              redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as err):
            code = fast_check.main(["--workspace-only", "--jobs", "2"])
        self.assertEqual(code, 1)
        self.assertIn("FAILURE: deploy", err.getvalue())
