"""Unit and structural tests for fail-closed CI Gate (`ci/ci_gate.py`)."""

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

from ci import ci_gate


ROOT = Path(__file__).resolve().parents[1]
CHECKS_WORKFLOW = ROOT / ".github/workflows/checks.yml"


def make_needs(
    *,
    run_pages_check: bool = True,
    run_workspace: bool = True,
    run_android: bool = True,
    run_push_acceptance: bool = True,
    overrides: dict[str, str] | None = None,
) -> dict[str, dict[str, object]]:
    outputs = {
        "run_pages_check": "true" if run_pages_check else "false",
        "run_workspace": "true" if run_workspace else "false",
        "run_android": "true" if run_android else "false",
        "run_push_acceptance": "true" if run_push_acceptance else "false",
    }
    needs: dict[str, dict[str, object]] = {
        "changes": {"result": "success", "outputs": outputs},
        "validate": {"result": "success"},
        "pages-check": {"result": "success" if run_pages_check else "skipped"},
        "workspace": {"result": "success" if run_workspace else "skipped"},
        "package-debug": {"result": "success" if run_android else "skipped"},
        "package": {"result": "success" if (run_android or run_push_acceptance) else "skipped"},
        "device": {"result": "success" if run_android else "skipped"},
        "device-native-1": {"result": "success" if run_android else "skipped"},
        "device-live-core": {"result": "success" if run_android else "skipped"},
        "device-journeys": {"result": "success" if run_android else "skipped"},
        "phone-handoffs": {"result": "success" if run_android else "skipped"},
        "nearby-guide": {"result": "success" if run_android else "skipped"},
        "aosp": {"result": "success" if run_push_acceptance else "skipped"},
    }
    for job_id, result in (overrides or {}).items():
        needs[job_id] = {"result": result}
    return needs


class CiGateTest(unittest.TestCase):
    def test_full_run_passes(self):
        ok, errors, rows = ci_gate.evaluate_gate(make_needs())
        self.assertTrue(ok, errors)
        self.assertEqual(errors, [])
        self.assertEqual(len(rows), len(ci_gate.ALL_UPSTREAM_JOBS))

    def test_pages_only_fast_path_passes_with_skipped_android_jobs(self):
        needs = make_needs(
            run_pages_check=True,
            run_workspace=False,
            run_android=False,
            run_push_acceptance=False,
        )
        ok, errors, _ = ci_gate.evaluate_gate(needs)
        self.assertTrue(ok, errors)

    def test_docs_only_fast_path_passes(self):
        needs = make_needs(
            run_pages_check=False,
            run_workspace=False,
            run_android=False,
            run_push_acceptance=False,
        )
        ok, errors, _ = ci_gate.evaluate_gate(needs)
        self.assertTrue(ok, errors)

    def test_server_only_path_requires_workspace_package_and_aosp(self):
        needs = make_needs(
            run_pages_check=False,
            run_workspace=True,
            run_android=False,
            run_push_acceptance=True,
        )
        ok, errors, _ = ci_gate.evaluate_gate(needs)
        self.assertTrue(ok, errors)

        # If package was skipped despite run_push_acceptance=true, must fail closed.
        needs_bad = make_needs(
            run_pages_check=False,
            run_workspace=True,
            run_android=False,
            run_push_acceptance=True,
            overrides={"package": "skipped"},
        )
        ok, errors, _ = ci_gate.evaluate_gate(needs_bad)
        self.assertFalse(ok)
        self.assertTrue(any("package" in e for e in errors))

    def test_release_mode_allows_skipped_aosp_but_still_requires_android(self):
        needs = make_needs(overrides={"aosp": "skipped"})
        ok_normal, errors_normal, _ = ci_gate.evaluate_gate(needs, release_mode=False)
        self.assertFalse(ok_normal)
        self.assertTrue(any("aosp" in e for e in errors_normal))

        ok_release, errors_release, _ = ci_gate.evaluate_gate(needs, release_mode=True)
        self.assertTrue(ok_release, errors_release)

    def test_unexpectedly_skipped_required_job_fails_closed(self):
        for job_id in ("validate", "device", "device-native-1", "nearby-guide", "pages-check", "workspace"):
            with self.subTest(job_id=job_id):
                needs = make_needs(overrides={job_id: "skipped"})
                ok, errors, _ = ci_gate.evaluate_gate(needs)
                self.assertFalse(ok)
                self.assertTrue(any(job_id in e for e in errors))

    def test_failed_or_cancelled_job_always_fails_closed(self):
        for status in ("failure", "cancelled"):
            with self.subTest(status=status, routed="on"):
                ok, errors, _ = ci_gate.evaluate_gate(make_needs(overrides={"device-live-core": status}))
                self.assertFalse(ok)
                self.assertTrue(any("device-live-core" in e for e in errors))
            with self.subTest(status=status, routed="off"):
                needs = make_needs(
                    run_pages_check=True,
                    run_workspace=False,
                    run_android=False,
                    run_push_acceptance=False,
                    overrides={"workspace": status},
                )
                ok, errors, _ = ci_gate.evaluate_gate(needs)
                self.assertFalse(ok)
                self.assertTrue(any("workspace" in e for e in errors))

    def test_failed_changes_or_bad_outputs_fails_closed(self):
        needs = make_needs()
        needs["changes"]["result"] = "failure"
        ok, errors, _ = ci_gate.evaluate_gate(needs)
        self.assertFalse(ok)
        self.assertTrue(any("changes" in e for e in errors))

        needs_bad_flag = make_needs()
        needs_bad_flag["changes"]["outputs"]["run_android"] = "maybe"
        ok, errors, _ = ci_gate.evaluate_gate(needs_bad_flag)
        self.assertFalse(ok)
        self.assertTrue(any("run_android" in e for e in errors))

    def test_missing_or_extra_job_in_needs_fails_closed(self):
        missing = make_needs()
        del missing["phone-handoffs"]
        ok, errors, _ = ci_gate.evaluate_gate(missing)
        self.assertFalse(ok)
        self.assertTrue(any("phone-handoffs" in e for e in errors))

        extra = make_needs()
        extra["mystery-job"] = {"result": "success"}
        ok, errors, _ = ci_gate.evaluate_gate(extra)
        self.assertFalse(ok)
        self.assertTrue(any("mystery-job" in e for e in errors))

    def test_cli_writes_summary_and_returns_exit_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp) / "summary.md"
            ok_proc = subprocess.run(
                [sys.executable, "-B", str(ROOT / "ci/ci_gate.py"), "--step-summary", str(summary)],
                env={"NEEDS_JSON": json.dumps(make_needs(run_workspace=False, run_android=False, run_push_acceptance=False))},
                text=True,
                capture_output=True,
            )
            self.assertEqual(ok_proc.returncode, 0, ok_proc.stderr)
            self.assertIn("PASS", summary.read_text(encoding="utf-8"))

            fail_proc = subprocess.run(
                [sys.executable, "-B", str(ROOT / "ci/ci_gate.py")],
                env={"NEEDS_JSON": json.dumps(make_needs(overrides={"device": "failure"}))},
                text=True,
                capture_output=True,
            )
            self.assertEqual(fail_proc.returncode, 1)
            self.assertIn("device", fail_proc.stderr)

            bad_json = subprocess.run(
                [sys.executable, "-B", str(ROOT / "ci/ci_gate.py")],
                env={"NEEDS_JSON": "{not-json"},
                text=True,
                capture_output=True,
            )
            self.assertEqual(bad_json.returncode, 1)

    def test_workflow_ci_gate_covers_every_job_in_checks_yml(self):
        workflow = CHECKS_WORKFLOW.read_text(encoding="utf-8")
        jobs_section = workflow.split("\njobs:\n", 1)[1]
        declared_jobs = re.findall(r"^  ([a-z0-9-]+):\s*$", jobs_section, flags=re.MULTILINE)
        self.assertIn("ci-gate", declared_jobs)
        upstream_in_workflow = [j for j in declared_jobs if j != "ci-gate"]
        self.assertEqual(
            upstream_in_workflow,
            list(ci_gate.ALL_UPSTREAM_JOBS),
            "ci/ci_gate.py ALL_UPSTREAM_JOBS must match every upstream job in checks.yml",
        )

        gate_block = workflow.split("\n  ci-gate:\n", 1)[1]
        self.assertIn("name: CI Gate", gate_block)
        self.assertIn("if: always()", gate_block)
        self.assertIn("NEEDS_JSON: ${{ toJson(needs) }}", gate_block)
        self.assertIn("python3 -B ci/ci_gate.py", gate_block)
        needs_block = gate_block.split("    needs:\n", 1)[1].split("    if:", 1)[0]
        listed_needs = re.findall(r"^      - ([a-z0-9-]+)\s*$", needs_block, flags=re.MULTILINE)
        self.assertEqual(
            listed_needs,
            upstream_in_workflow,
            "ci-gate.needs must include every upstream job in checks.yml",
        )

    def test_workflow_supports_native_merge_group_without_cancelling_in_flight_groups(self):
        workflow = CHECKS_WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(workflow, r"(?m)^  merge_group:\n    types: \[checks_requested\]$")
        self.assertIn("github.event_name != 'merge_group'", workflow)
        self.assertIn("github.event_name == 'merge_group'", workflow)
        self.assertIn("github.event.merge_group.base_sha", workflow)
        self.assertIn("github.event.merge_group.head_sha", workflow)


if __name__ == "__main__":
    unittest.main()
