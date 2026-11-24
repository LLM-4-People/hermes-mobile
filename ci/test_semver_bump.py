"""Deterministic positive and negative tests for SemVer release planning and code-driven automation."""

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

import semver_bump


SAMPLE_GRADLE = """org.gradle.jvmargs=-Xmx2048m
hermesCompileSdk=36
hermesVersionCode=4
hermesVersionName=0.2.1
"""


class SemverBumpTest(unittest.TestCase):
    def test_select_release_action_accepts_single_label_and_rejects_conflicts(self):
        self.assertIsNone(semver_bump.select_release_action(["android", "ci"]))
        self.assertEqual(semver_bump.select_release_action(["android", "release:patch"]), "patch")
        self.assertEqual(semver_bump.select_release_action(["release:minor"]), "minor")
        self.assertEqual(semver_bump.select_release_action(["release:major"]), "major")
        self.assertEqual(semver_bump.select_release_action(["release:publish", "ci"]), "publish")

        for bad in (
            ["release:patch", "release:minor"],
            ["release:minor", "release:major"],
            ["release:publish", "release:patch"],
            ["release:nightly"],
        ):
            with self.subTest(labels=bad), self.assertRaises(ValueError):
                semver_bump.select_release_action(bad)

    def test_skip_release_overrides_auto_code_changes_and_explicit_labels(self):
        action, reason = semver_bump.classify_release(
            ["skip-release"],
            changed_files=["app/src/main/java/org/llm4people/hermes/MainActivity.java"],
            pr_title="feat: add new screen",
        )
        self.assertIsNone(action)
        self.assertEqual(reason, "skip_label")

        action2, reason2 = semver_bump.classify_release(
            ["skip-release", "release:minor"],
            changed_files=["app/src/main/java/org/llm4people/hermes/MainActivity.java"],
            pr_title="feat: add new screen",
        )
        self.assertIsNone(action2)
        self.assertEqual(reason2, "skip_label")

    def test_non_shippable_paths_automatically_skip_release(self):
        non_shippable_sets = [
            ["README.md", "docs/release.md", "docs/sources/README.md.in"],
            ["site/en/index.html", "site/fr/walkthrough/index.html"],
            ["app/src/test/java/org/llm4people/hermes/ExampleTest.java"],
            ["app/src/androidTest/java/org/llm4people/hermes/journey/DeviceJourney.java"],
            ["verification/gateway_fixture.py", "verification/device/run.py"],
            [".github/workflows/checks.yml", "ci/semver_bump.py", "scripts/docs.py"],
            ["server/mobile_push/app.py", "deploy/accounts.py", "skills/hermes-mobile-workspace/SKILL.md"],
        ]
        for paths in non_shippable_sets:
            with self.subTest(paths=paths):
                action, reason = semver_bump.classify_release(
                    [], changed_files=paths, pr_title="feat: big non-app change"
                )
                self.assertIsNone(action)
                self.assertEqual(reason, "non_shippable_paths")

    def test_shippable_app_paths_infer_bump_from_title_and_labels(self):
        shippable_cases = [
            (["app/src/main/java/org/llm4people/hermes/MainActivity.java"], "feat(ui): add dual theme", [], "minor"),
            (["app/src/main/res/values/strings.xml"], "fix(i18n): tighten copy", [], "patch"),
            (["app/build.gradle"], "build(deps): bump AGP", [], "patch"),
            (["app/proguard-rules.pro"], "perf: trim R8 rules", [], "patch"),
            (["gradle/libs.versions.toml"], "feat!: drop legacy push bridge", [], "major"),
            (["gradle/wrapper/gradle-wrapper.properties"], "chore: update Gradle wrapper", [], "patch"),
            (["build.gradle"], "refactor: simplify root build", [], "patch"),
            (["settings.gradle"], "chore: configure plugin management", [], "patch"),
            (["gradle.properties"], "feat: raise targetSdk to 36", [], "minor"),
            (["app/src/main/AndroidManifest.xml"], "Update notification tile", ["enhancement"], "minor"),
            (["app/src/main/java/org/llm4people/hermes/Ui.java"], "Switch layout BREAKING CHANGE", [], "major"),
        ]
        for paths, title, labels, expected in shippable_cases:
            with self.subTest(paths=paths, title=title):
                action, reason = semver_bump.classify_release(labels, changed_files=paths, pr_title=title)
                self.assertEqual(action, expected)
                self.assertEqual(reason, "auto_code_change")

        # Explicit release label still takes precedence over automatic inference.
        action, reason = semver_bump.classify_release(
            ["release:major"],
            changed_files=["app/src/main/java/org/llm4people/hermes/Ui.java"],
            pr_title="fix: tiny change",
        )
        self.assertEqual(action, "major")
        self.assertEqual(reason, "explicit_label")

        # Bump-only chore(release) PR without release:publish does not self-loop on gradle.properties.
        loop_action, loop_reason = semver_bump.classify_release(
            [],
            changed_files=["gradle.properties", "README.md"],
            pr_title="chore(release): v0.2.0",
        )
        self.assertIsNone(loop_action)
        self.assertEqual(loop_reason, "non_shippable_paths")

    def test_bump_semver_handles_patch_minor_major_and_rejects_invalid_inputs(self):
        self.assertEqual(semver_bump.bump_semver("0.1.0", "patch"), "0.1.1")
        self.assertEqual(semver_bump.bump_semver("0.1.9", "minor"), "0.2.0")
        self.assertEqual(semver_bump.bump_semver("0.2.5", "major"), "1.0.0")
        self.assertEqual(semver_bump.bump_semver("v1.2.3-rc.1", "patch"), "1.2.4")
        with self.assertRaises(ValueError):
            semver_bump.bump_semver("not-semver", "patch")
        for bad_ver in ("1.2.3-..", "1.2.3--", "01.2.3", "1.2.3-"):
            with self.subTest(bad_ver=bad_ver), self.assertRaises(ValueError):
                semver_bump.parse_semver(bad_ver)
        with self.assertRaises(ValueError):
            semver_bump.bump_semver("1.0.0", "invalid")

    def test_fix_after_unreleased_feat_or_pending_release_pr_never_downgrades(self):
        # Unreleased feat: commit since v0.2.1 + newly merged fix: PR -> stays minor 0.3.0, never 0.2.2!
        plan = semver_bump.plan_release(
            "0.2.1",
            3,
            "patch",
            existing_tags=["v0.2.1"],
            published_max_code=3,
            unreleased_titles=[
                "fix(ui): polish spacing",
                "feat(security): add multi-mode profile lock",
            ],
        )
        self.assertEqual(plan["mode"], "open_bump_pr")
        self.assertEqual(plan["tag"], "v0.3.0")
        self.assertEqual(plan["version_name"], "0.3.0")
        self.assertEqual(plan["branch"], "release/v0.3.0")

        # Open pending release/v0.3.0 PR (e.g. from explicit release:minor label) + subsequent fix: PR -> stays v0.3.0!
        pending_plan = semver_bump.plan_release(
            "0.2.1",
            3,
            "patch",
            existing_tags=["v0.2.1"],
            published_max_code=3,
            pending_release_tags=["v0.3.0"],
        )
        self.assertEqual(pending_plan["tag"], "v0.3.0")
        self.assertEqual(pending_plan["branch"], "release/v0.3.0")

        # Unreleased breaking change + subsequent fix: PR -> stays major 1.0.0!
        major_plan = semver_bump.plan_release(
            "0.2.1",
            3,
            "patch",
            existing_tags=["v0.2.1"],
            published_max_code=3,
            unreleased_titles=["feat!: drop legacy push v1", "fix: typo"],
            pending_release_tags=["v0.3.0"],
        )
        self.assertEqual(major_plan["tag"], "v1.0.0")
        self.assertEqual(major_plan["branch"], "release/v1.0.0")

        # Already-released pending tag is ignored rather than colliding.
        stale_pending = semver_bump.plan_release(
            "0.3.0",
            4,
            "patch",
            existing_tags=["v0.3.0"],
            published_max_code=4,
            pending_release_tags=["v0.3.0"],
        )
        self.assertEqual(stale_pending["tag"], "v0.3.1")

    def test_find_superseded_release_prs_selects_only_lower_version_release_prs(self):
        open_prs = [
            {"number": 41, "headRefName": "release/v0.2.2"},
            {"number": 42, "headRefName": "release/v0.3.0"},
            {"number": 43, "headRefName": "release/v1.0.0"},
            {"number": 44, "headRefName": "release/v0.2.1"},
            {"number": 45, "headRefName": "fix/release-v0.1.0"},
            {"number": 46, "headRefName": "release/not-semver"},
            {"number": 47, "headRefName": "release/v0.2.3-01"},
            {"number": True, "headRefName": "release/v0.1.0"},
        ]
        self.assertEqual(
            semver_bump.find_superseded_release_prs(open_prs, "v0.3.0"),
            [41, 44],
        )
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = semver_bump.main(["--superseded-prs-json", json.dumps(open_prs), "--target-tag", "v0.3.0"])
        self.assertEqual(rc, 0)
        self.assertEqual(buf.getvalue().splitlines(), ["41", "44"])
        with self.assertRaises(ValueError):
            semver_bump.main(["--superseded-prs-json", "[]"])

    def test_plan_release_tags_immediately_for_first_untagged_version_and_opens_bump_pr_afterward(self):
        # First release (no tags yet, gradle.properties = 0.1.0 / code 1): tag & release immediately
        first = semver_bump.plan_release("0.1.0", 1, "minor", existing_tags=[], published_max_code=0)
        self.assertEqual(first, {
            "mode": "tag_and_release",
            "tag": "v0.1.0",
            "version_name": "0.1.0",
            "version_code": 1,
        })

        # Subsequent patch after v0.1.0 already released: open bump PR to 0.1.1 / code 2
        next_patch = semver_bump.plan_release("0.1.0", 1, "patch", existing_tags=["v0.1.0"], published_max_code=1)
        self.assertEqual(next_patch, {
            "mode": "open_bump_pr",
            "tag": "v0.1.1",
            "version_name": "0.1.1",
            "version_code": 2,
            "branch": "release/v0.1.1",
        })

        # Subsequent minor when a higher tag exists: bump from highest known tag
        next_minor = semver_bump.plan_release("0.1.0", 1, "minor", existing_tags=["v0.1.0", "v0.1.2"], published_max_code=3)
        self.assertEqual(next_minor["tag"], "v0.2.0")
        self.assertEqual(next_minor["version_code"], 4)
        self.assertEqual(next_minor["mode"], "open_bump_pr")

    def test_publish_action_validates_strictly_increasing_tag_and_version_code(self):
        ok = semver_bump.plan_release("0.2.0", 5, "publish", existing_tags=["v0.1.0"], published_max_code=4)
        self.assertEqual(ok["mode"], "tag_and_release")
        self.assertEqual(ok["tag"], "v0.2.0")

        # Duplicate tag fails closed
        with self.assertRaisesRegex(ValueError, "already exists"):
            semver_bump.plan_release("0.2.0", 5, "publish", existing_tags=["v0.2.0"], published_max_code=4)

        # Non-increasing versionCode fails closed
        with self.assertRaisesRegex(ValueError, "must exceed published max"):
            semver_bump.plan_release("0.2.0", 4, "publish", existing_tags=["v0.1.0"], published_max_code=4)

        # Non-increasing versionName fails closed
        with self.assertRaisesRegex(ValueError, "must exceed latest tag"):
            semver_bump.plan_release("0.1.0", 5, "publish", existing_tags=["v0.2.0"], published_max_code=4)

    def test_read_and_write_gradle_version_roundtrip_and_cli_auto_and_force_bump(self):
        with tempfile.TemporaryDirectory() as tmp:
            props = Path(tmp) / "gradle.properties"
            out = Path(tmp) / "github_output.txt"
            props.write_text(SAMPLE_GRADLE, encoding="utf-8")
            self.assertEqual(semver_bump.read_gradle_version(props), ("0.2.1", 4))

            # Automatic code-driven bump from app/src/main change + feat: title
            code = semver_bump.main([
                "--gradle-properties", str(props),
                "--labels-json", "[]",
                "--changed-files-json", '["app/src/main/java/org/llm4people/hermes/MainActivity.java"]',
                "--pr-title", "feat(ui): add connection doctor",
                "--tags-json", '["v0.2.1"]',
                "--published-max-code", "4",
                "--apply-bump",
                "--github-output", str(out),
            ])
            self.assertEqual(code, 0)
            self.assertEqual(semver_bump.read_gradle_version(props), ("0.3.0", 5))
            output_text = out.read_text(encoding="utf-8")
            self.assertIn("mode=open_bump_pr\n", output_text)
            self.assertIn("tag=v0.3.0\n", output_text)
            self.assertIn("version_code=5\n", output_text)
            self.assertIn("reason=auto_code_change\n", output_text)

            # Docs-only PR produces mode=none and does not touch gradle.properties
            out2 = Path(tmp) / "github_output_2.txt"
            code2 = semver_bump.main([
                "--gradle-properties", str(props),
                "--labels-json", "[]",
                "--changed-files-json", '["README.md", "site/en/index.html"]',
                "--pr-title", "docs: polish copy",
                "--tags-json", '["v0.3.0"]',
                "--published-max-code", "5",
                "--apply-bump",
                "--github-output", str(out2),
            ])
            self.assertEqual(code2, 0)
            self.assertEqual(semver_bump.read_gradle_version(props), ("0.3.0", 5))
            self.assertIn("mode=none\n", out2.read_text(encoding="utf-8"))

            # Manual force-bump override works cleanly
            out3 = Path(tmp) / "github_output_3.txt"
            code3 = semver_bump.main([
                "--gradle-properties", str(props),
                "--force-bump", "patch",
                "--tags-json", '["v0.3.0"]',
                "--published-max-code", "5",
                "--apply-bump",
                "--github-output", str(out3),
            ])
            self.assertEqual(code3, 0)
            self.assertEqual(semver_bump.read_gradle_version(props), ("0.3.1", 6))
            self.assertIn("reason=manual_dispatch\n", out3.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
