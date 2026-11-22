"""Run the production publisher against a local release service double."""

import shutil
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

PUBLISH = Path(__file__).with_name("publish-release.sh").resolve()
SHA = "a" * 40


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
        (self.root / "Hermes-Mobile.apk").write_bytes(b"APK bytes")
        (self.root / "SHA256SUMS").write_text("checksum")
        (self.root / "release.json").write_text('{"tag":"v1.2.3"}')
        self.env = dict(os.environ, PATH=f"{self.root}:{os.environ['PATH']}",
                        GITHUB_SHA=SHA, GITHUB_REPOSITORY="owner/app", TEST_STATE=str(self.state))

    def run_publisher(self):
        return subprocess.run([PUBLISH, self.root], env=self.env, capture_output=True, text=True)

    def test_interrupted_upload_resumes_and_published_rerun_is_idempotent(self):
        self.env["FAIL_UPLOAD"] = "SHA256SUMS"
        self.assertNotEqual(self.run_publisher().returncode, 0)
        partial = json.loads(self.state.read_text())["releases"][0]
        self.assertTrue(partial["draft"])
        self.assertEqual([a["name"] for a in partial["assets"]], ["Hermes-Mobile.apk"])
        self.env.pop("FAIL_UPLOAD")
        result = self.run_publisher()
        self.assertEqual(result.returncode, 0, result.stderr)
        published = self.state.read_text()
        self.assertFalse(json.loads(published)["releases"][0]["draft"])
        self.assertEqual(self.run_publisher().returncode, 0)
        self.assertEqual(self.state.read_text(), published)
        (self.root / "Hermes-Mobile.apk").write_bytes(b"different bytes")
        self.assertNotEqual(self.run_publisher().returncode, 0)
        self.assertEqual(self.state.read_text(), published)

    def test_stale_revision_and_conflicting_tag(self):
        for main, ref in (("b" * 40, None), (SHA, "b" * 40)):
            state = {"main": main, "ref": ref, "releases": []}
            self.state.write_text(json.dumps(state))
            self.assertNotEqual(self.run_publisher().returncode, 0)
            self.assertEqual(json.loads(self.state.read_text()), state)
