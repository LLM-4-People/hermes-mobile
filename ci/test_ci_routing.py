"""Deterministic positive and negative tests for fail-closed CI path routing and Pages integrity."""

from pathlib import Path
import tempfile
import unittest

import check_pages
import ci_routing


ROOT = Path(__file__).resolve().parents[1]


class CiRoutingTest(unittest.TestCase):
    def test_pages_only_runs_only_pages_check_and_skips_gradle_and_emulators(self):
        for event in ("pull_request", "merge_group", "push"):
            with self.subTest(event=event):
                res = ci_routing.classify_files(
                    ["site/index.html", "site/assets/style.css", ".github/workflows/pages.yml"],
                    event_name=event,
                )
                self.assertTrue(res["run_pages_check"])
                self.assertTrue(res["docs_only"])
                self.assertFalse(res["run_workspace"])
                self.assertFalse(res["run_android"])
                self.assertFalse(res["run_push_acceptance"])
                self.assertEqual(res["labels"], ["ci", "pages"])

    def test_ci_meta_docs_and_release_automation_uses_fast_path(self):
        fast_paths = [
            "scripts/docs.py",
            "ci/semver_bump.py",
            "ci/test_semver_bump.py",
            "ci/test_docs_generator.py",
            "ci/check_writing_quality.py",
            "ci/test_writing_quality.py",
            "ci/check_pages.py",
            ".github/workflows/auto-release.yml",
            ".github/release.yml",
        ]
        for event in ("pull_request", "merge_group"):
            with self.subTest(event=event):
                res = ci_routing.classify_files(fast_paths + ["README.md"], event_name=event)
                self.assertTrue(res["run_pages_check"])
                self.assertTrue(res["docs_only"])
                self.assertFalse(res["run_workspace"])
                self.assertFalse(res["run_android"])
                self.assertFalse(res["run_push_acceptance"])
                self.assertIn("fast path", res["reason"])

    def test_docs_templates_and_impeccable_only_skip_all_heavy_jobs(self):
        res = ci_routing.classify_files([
            "README.md",
            "docs/ambient.md",
            ".impeccable/design.json",
            ".github/ISSUE_TEMPLATE/bug_report.yml",
            ".github/PULL_REQUEST_TEMPLATE.md",
            ".github/workflows/pr-labeler.yml",
        ])
        self.assertTrue(res["docs_only"])
        self.assertFalse(res["run_pages_check"])
        self.assertFalse(res["run_workspace"])
        self.assertFalse(res["run_android"])
        self.assertFalse(res["run_push_acceptance"])
        self.assertEqual(res["labels"], ["ci", "docs"])

    def test_server_nearby_or_deploy_only_runs_workspace_and_skips_android_and_push(self):
        res = ci_routing.classify_files(["server/nearby/provider.py", "deploy/accounts.py"])
        self.assertTrue(res["run_workspace"])
        self.assertFalse(res["run_android"])
        self.assertFalse(res["run_push_acceptance"])
        self.assertFalse(res["docs_only"])
        self.assertEqual(res["labels"], ["server"])

    def test_server_mobile_push_only_runs_workspace_and_aosp_push_acceptance_without_debug_emulators(self):
        for path in ("server/mobile_push/worker.py", "server/requirements.txt", "server/logging_util.py", "server/Dockerfile"):
            with self.subTest(path=path):
                res = ci_routing.classify_files([path])
                self.assertTrue(res["run_workspace"])
                self.assertTrue(res["run_push_acceptance"])
                self.assertFalse(res["run_android"])
                self.assertFalse(res["docs_only"])

    def test_android_ci_verification_or_unknown_files_fail_closed_to_full_suite(self):
        cases = [
            ["app/src/main/java/org/llm4people/hermes/MainActivity.java", "README.md"],
            ["ci/device-check.sh"],
            ["ci/gradle_check.py"],
            ["ci/native_tests.py"],
            ["ci/sign-apk.sh"],
            ["ci/ci_routing.py"],
            ["scripts/fast_check.py"],
            ["verification/phone_actions.py"],
            [".github/workflows/checks.yml"],
            ["gradle/libs.versions.toml"],
            ["unknown-root-file.txt"],
            [],
        ]
        for event in ("pull_request", "merge_group"):
            for paths in cases:
                with self.subTest(event=event, paths=paths):
                    res = ci_routing.classify_files(paths, event_name=event)
                    self.assertTrue(res["run_workspace"])
                    self.assertTrue(res["run_android"])
                    self.assertTrue(res["run_push_acceptance"])
                    self.assertTrue(res["run_pages_check"])
                    self.assertFalse(res["docs_only"])

    def test_release_mode_and_workflow_dispatch_always_run_full_suite(self):
        for kwargs in (
            {"release_mode": True},
            {"event_name": "workflow_dispatch"},
            {"pr_labels": ["docs", "run-full-ci"]},
        ):
            with self.subTest(kwargs=kwargs):
                res = ci_routing.classify_files(["site/index.html"], **kwargs)
                self.assertTrue(res["run_workspace"])
                self.assertTrue(res["run_android"])
                self.assertTrue(res["run_push_acceptance"])
                self.assertTrue(res["run_pages_check"])
                self.assertFalse(res["docs_only"])

    def test_unrelated_pr_labels_do_not_force_full_ci_on_docs_only_pr(self):
        res = ci_routing.classify_files(["site/index.html"], pr_labels=["pages", "docs"])
        self.assertTrue(res["docs_only"])
        self.assertTrue(res["run_pages_check"])
        self.assertFalse(res["run_android"])

    def test_git_changed_files_fails_closed_on_invalid_or_zero_sha(self):
        self.assertIsNone(ci_routing.git_changed_files(ci_routing.ZERO_SHA, "a" * 40))
        self.assertIsNone(ci_routing.git_changed_files("not-a-sha", "a" * 40))
        self.assertIsNone(ci_routing.git_changed_files("", ""))

    def test_cli_writes_github_output_and_step_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "output.txt"
            summary = Path(tmp) / "summary.md"
            code = ci_routing.main([
                "--event", "pull_request",
                "--files", "site/index.html",
                "--github-output", str(out),
                "--step-summary", str(summary),
            ])
            self.assertEqual(code, 0)
            text = out.read_text(encoding="utf-8")
            self.assertIn("run_pages_check=true\n", text)
            self.assertIn("run_android=false\n", text)
            self.assertIn("docs_only=true\n", text)
            self.assertIn("Smart CI Path Routing", summary.read_text(encoding="utf-8"))


class PagesCheckTest(unittest.TestCase):
    def test_real_repository_site_passes_static_integrity_check(self):
        self.assertEqual(check_pages.validate_site(ROOT / "site"), [])

    def test_rejects_missing_files_broken_links_missing_alt_and_root_escape(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = Path(tmp) / "site"
            (site / "docs").mkdir(parents=True)
            (site / "walkthrough").mkdir(parents=True)
            (site / ".nojekyll").write_text("", encoding="utf-8")
            (site / "docs/index.html").write_text("<html><body><a href='../index.html'>Home</a></body></html>", encoding="utf-8")
            (site / "walkthrough/index.html").write_text("<html><body>OK</body></html>", encoding="utf-8")
            (site / "index.html").write_text(
                '<html><body>'
                '<img src="assets/missing.png">'
                '<a href="../../README.md">Escape</a>'
                '<a href="docs/">Docs</a>'
                '</body></html>',
                encoding="utf-8",
            )
            problems = check_pages.validate_site(site)
            joined = "\n".join(problems)
            self.assertIn("img missing alt", joined)
            self.assertIn("broken local reference", joined)
            self.assertIn("escapes site/ root", joined)


if __name__ == "__main__":
    unittest.main()
