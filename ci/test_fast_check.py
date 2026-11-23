"""Verify the parallel host test runner's per-file fan-out and failure propagation."""

from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import fast_check


class FastCheckTest(unittest.TestCase):
    def test_workspace_only_fans_out_every_server_test_file_and_all_suites(self):
        names = [name for name, _ in fast_check.build_suites("/tmp/venv/python", workspace_only=True)]
        expected_server = [f"server-tests:{p.stem}" for p in sorted((ROOT / "server/tests").glob("test_*.py"))]
        expected_verification = [
            f"verification:{p.stem}"
            for p in sorted((ROOT / "verification").glob("test_*.py"))
            if fast_check._has_unittest_cases(p)
        ]
        self.assertTrue(expected_server)
        self.assertIn("verification:test_nearby_journey", expected_verification)
        self.assertIn("verification:test_phone_actions", expected_verification)
        self.assertEqual(names, [
            *expected_server,
            "server-artifacts",
            "deploy",
            "verification-device",
            "verification-unifiedpush",
            *expected_verification,
        ])

    def test_full_mode_covers_docs_every_ci_test_file_and_optional_gradle(self):
        names = [name for name, _ in fast_check.build_suites(None, workspace_only=False, with_gradle=True)]
        expected_ci = [
            f"ci:{p.stem}"
            for p in sorted((ROOT / "ci").glob("test_*.py"))
            if fast_check._has_unittest_cases(p)
        ]
        self.assertTrue(expected_ci)
        self.assertNotIn("ci:test_signing", names)
        self.assertEqual(names[0], "docs")
        self.assertEqual(names[1], "writing-quality")
        self.assertEqual(names[2], "hardcoded-ui-strings")
        self.assertEqual(names[3:3 + len(expected_ci)], expected_ci)
        self.assertEqual(names[-1], "gradle")

    def test_ci_only_runs_docs_and_every_ci_unittest_file_only(self):
        names = [name for name, _ in fast_check.build_suites(None, ci_only=True)]
        expected_ci = [
            f"ci:{p.stem}"
            for p in sorted((ROOT / "ci").glob("test_*.py"))
            if fast_check._has_unittest_cases(p)
        ]
        self.assertEqual(names, ["docs", "writing-quality", "hardcoded-ui-strings", *expected_ci])

    def test_failure_in_any_suite_returns_nonzero(self):
        def fake_run(name, cmd):
            return (name, 1 if name == "deploy" else 0, 0.01, "", "boom" if name == "deploy" else "")

        with (patch.object(fast_check, "run_one", side_effect=fake_run),
              redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as err):
            code = fast_check.main(["--workspace-only", "--jobs", "2"])
        self.assertEqual(code, 1)
        self.assertIn("FAILURE: deploy", err.getvalue())
