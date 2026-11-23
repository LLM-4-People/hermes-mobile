"""Deterministic positive and negative tests for SemVer release planning and gradle.properties updates."""

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

    def test_bump_semver_handles_patch_minor_major_and_rejects_invalid_inputs(self):
        self.assertEqual(semver_bump.bump_semver("0.1.0", "patch"), "0.1.1")
        self.assertEqual(semver_bump.bump_semver("0.1.9", "minor"), "0.2.0")
        self.assertEqual(semver_bump.bump_semver("0.2.5", "major"), "1.0.0")
        self.assertEqual(semver_bump.bump_semver("v1.2.3-rc.1", "patch"), "1.2.4")
        with self.assertRaises(ValueError):
            semver_bump.bump_semver("not-semver", "patch")
        with self.assertRaises(ValueError):
            semver_bump.bump_semver("1.0.0", "invalid")

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

    def test_read_and_write_gradle_version_roundtrip_and_cli_apply_bump(self):
        with tempfile.TemporaryDirectory() as tmp:
            props = Path(tmp) / "gradle.properties"
            out = Path(tmp) / "github_output.txt"
            props.write_text(SAMPLE_GRADLE, encoding="utf-8")
            self.assertEqual(semver_bump.read_gradle_version(props), ("0.2.1", 4))

            code = semver_bump.main([
                "--gradle-properties", str(props),
                "--labels-json", '["android", "release:minor"]',
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


if __name__ == "__main__":
    unittest.main()
