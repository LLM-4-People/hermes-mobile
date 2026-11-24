"""Run the production publisher against a local release service double."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import release_metadata

PUBLISH = Path(__file__).with_name("publish_release.py").resolve()
SHA = "a" * 40
VERSION = "1.2.3"
APK = f"hermes-mobile-v{VERSION}.apk"


class PublishTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.state = self.root / "state.json"
        self.state.write_text(json.dumps({"main": SHA, "ref": None, "releases": []}))
        self.stub = self.root / "gh"
        shutil.copyfile(Path(__file__).with_name("fixtures") / "release_cli.py", self.stub)
        self.stub.chmod(0o755)
        (self.root / APK).write_bytes(b"APK bytes")
        (self.root / "SHA256SUMS").write_text("checksum")
        (self.root / "release.json").write_text(json.dumps({
            "tag": f"v{VERSION}",
            "versionName": VERSION,
            "versionCode": 3,
            "apk": APK,
            "sourceCommit": SHA,
        }))
        self.env = dict(os.environ, PATH=f"{self.root}:{os.environ['PATH']}",
                        GITHUB_SHA=SHA, GITHUB_REPOSITORY="owner/app", TEST_STATE=str(self.state))

    def run_publisher(self):
        return subprocess.run([sys.executable, PUBLISH, self.root], env=self.env, capture_output=True, text=True)

    def test_interrupted_upload_resumes_and_published_rerun_is_idempotent(self):
        self.env["FAIL_UPLOAD"] = "SHA256SUMS"
        self.assertNotEqual(self.run_publisher().returncode, 0)
        partial = json.loads(self.state.read_text())["releases"][0]
        self.assertTrue(partial["draft"])
        self.assertEqual([a["name"] for a in partial["assets"]], [APK])
        self.env.pop("FAIL_UPLOAD")
        result = self.run_publisher()
        self.assertEqual(result.returncode, 0, result.stderr)
        published = self.state.read_text()
        self.assertFalse(json.loads(published)["releases"][0]["draft"])
        self.assertEqual(self.run_publisher().returncode, 0)
        self.assertEqual(self.state.read_text(), published)
        (self.root / APK).write_bytes(b"different bytes")
        self.assertNotEqual(self.run_publisher().returncode, 0)
        self.assertEqual(self.state.read_text(), published)

    def test_mismatched_or_missing_versioned_apk_name_fails_closed(self):
        manifest = self.root / "release.json"
        for bad_update, expected_err in (
            ({"apk": "Hermes-Mobile.apk"}, "disagrees with expected"),
            ({"apk": "hermes-mobile-v9.9.9.apk"}, "disagrees with expected"),
            ({"apk": None}, "disagrees with expected"),
            ({"tag": "v9.9.9"}, "tag disagrees with versionName"),
            ({"versionName": ""}, "Missing versionName"),
            ({"versionName": "1.2.3-..", "tag": "v1.2.3-..", "apk": "hermes-mobile-v1.2.3-...apk"}, "semantic release version"),
            ({"versionName": "1.2.3--", "tag": "v1.2.3--", "apk": "hermes-mobile-v1.2.3--.apk"}, "semantic release version"),
            ({"versionName": "01.2.3", "tag": "v01.2.3", "apk": "hermes-mobile-v01.2.3.apk"}, "semantic release version"),
            ({"versionName": "1.2.3-01", "tag": "v1.2.3-01", "apk": "hermes-mobile-v1.2.3-01.apk"}, "semantic release version"),
            ({"versionName": "1.2.3-rc.01", "tag": "v1.2.3-rc.01", "apk": "hermes-mobile-v1.2.3-rc.01.apk"}, "semantic release version"),
            ({"sourceCommit": "b" * 40}, "identifies a different commit"),
            ({"versionCode": "3"}, "Invalid Android versionCode"),
            ({"versionCode": True}, "Invalid Android versionCode"),
            ({"versionCode": 0}, "Invalid Android versionCode"),
        ):
            with self.subTest(bad_update=bad_update):
                data = json.loads(manifest.read_text())
                data.update(bad_update)
                manifest.write_text(json.dumps(data))
                result = self.run_publisher()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(expected_err, result.stderr)
                self.assertEqual(json.loads(self.state.read_text())["releases"], [])
                manifest.write_text(json.dumps({
                    "tag": f"v{VERSION}",
                    "versionName": VERSION,
                    "versionCode": 3,
                    "apk": APK,
                    "sourceCommit": SHA,
                }))

    def test_stale_revision_and_conflicting_tag(self):
        for main, ref, annotated in (("b" * 40, None, False), (SHA, "b" * 40, False), (SHA, "b" * 40, True)):
            state = {"main": main, "ref": ref, "annotated": annotated, "releases": []}
            self.state.write_text(json.dumps(state))
            self.assertNotEqual(self.run_publisher().returncode, 0)
            self.assertEqual(json.loads(self.state.read_text()), state)

    def test_preexisting_annotated_tag_matching_main_publishes_cleanly(self):
        state = {"main": SHA, "ref": SHA, "annotated": True, "releases": []}
        self.state.write_text(json.dumps(state))
        result = self.run_publisher()
        self.assertEqual(result.returncode, 0, result.stderr)
        published = json.loads(self.state.read_text())["releases"]
        self.assertEqual(len(published), 1)
        self.assertFalse(published[0]["draft"])

    def test_unknown_assets_in_draft_or_added_during_upload_remain_unpublished(self):
        self.env["EXTRA_ON_UPLOAD"] = "1"
        for attempt in range(2):
            with self.subTest(attempt=attempt):
                result = self.run_publisher()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("unverified extra assets", result.stderr)
                self.assertTrue(json.loads(self.state.read_text())["releases"][0]["draft"])

    def test_all_published_version_codes_must_be_smaller(self):
        metadata = json.dumps({"versionCode": 4})
        previous = {"tag_name": "v1.1.0", "draft": False, "target_commitish": "b" * 40,
                    "assets": [{"id": 21, "name": "release.json",
                                "digest": "sha256:" + hashlib.sha256(metadata.encode()).hexdigest()}]}
        self.state.write_text(json.dumps({"main": SHA, "ref": None, "releases": [previous],
                                         "assetMetadata": {"21": metadata}}))
        manifest = self.root / "release.json"
        for code in [3, 4, 5]:
            data = json.loads(manifest.read_text())
            data["versionCode"] = code
            manifest.write_text(json.dumps(data))
            result = self.run_publisher()
            if code <= 4:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("versionCode must exceed", result.stderr)
                self.assertEqual(len(json.loads(self.state.read_text())["releases"]), 1)
            else:
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_release_apk_name_enforces_semver_2_prerelease_rules(self):
        for valid in ("1.2.3", "1.2.3-0", "1.2.3-rc.1", "1.2.3-rc.0", "1.2.3-0a", "1.2.3-01a", "1.2.3-beta-1"):
            with self.subTest(valid=valid):
                self.assertEqual(release_metadata.release_apk_name(valid), f"hermes-mobile-v{valid}.apk")
        for invalid in ("1.2.3-01", "1.2.3-00", "1.2.3-rc.01", "1.2.3-..", "1.2.3--", "1.2.3-", "01.2.3"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release_metadata.release_apk_name(invalid)
