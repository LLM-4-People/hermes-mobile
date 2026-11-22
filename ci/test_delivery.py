"""Regression tests for the documentation producer and release metadata."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from release_metadata import describe

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import docs


class DocsTest(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.root = Path(workspace.name).resolve()
        subprocess.run(["git", "init", "--quiet", self.root], check=True)
        self.source = self.root / docs.SOURCE
        self.source.mkdir(parents=True)
        (self.source / "facts.json").write_text('{"product":"Hermes"}')
        self.template = self.source / "README.md.in"
        self.template.write_text("# {{ product }}\n")

    def test_missing_drift_and_recovery(self):
        self.assertEqual(len(docs.render(self.root, check=True)), 1)
        self.assertFalse((self.root / "README.md").exists())
        self.assertEqual(docs.render(self.root, check=False), [])
        self.assertTrue((self.root / "README.md").read_text().startswith("# Hermes\n"))
        self.assertEqual(docs.render(self.root, check=True), [])
        self.template.write_text("# Upgrade {{ product }}\n")
        self.assertEqual(len(docs.render(self.root, check=True)), 1)

    def test_unowned_documents_and_unresolved_facts(self):
        unexpected = self.root / "extra.md"
        unexpected.write_text("user content")
        self.template.write_text("{{ missing }}")
        self.assertEqual(len(docs.render(self.root, check=False)), 2)
        self.assertEqual(unexpected.read_text(), "user content")
        self.assertFalse((self.root / "README.md").exists())

    def test_nested_sources_keep_frontmatter_and_ignored_outputs_are_out_of_scope(self):
        nested = self.source / "skills" / "example"
        nested.mkdir(parents=True)
        (nested / "SKILL.md.in").write_text("---\nname: example\n---\n# {{ product }}")
        (self.root / ".gitignore").write_text("build/\n")
        output = self.root / "build"
        output.mkdir()
        (output / "report.md").touch()
        self.assertEqual(docs.render(self.root, check=False), [])
        self.assertTrue((self.root / "skills/example/SKILL.md").read_text().startswith("---\n"))
        self.assertEqual(docs.render(self.root, check=True), [])


class MetadataTest(unittest.TestCase):
    def test_versions_from_signed_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            one = Path(folder) / "app.apk"
            one.write_bytes(b"test")
            manifest = "package: name='org.example' versionCode='3' versionName='1.2.0-rc.1'"
            metadata = describe(manifest, one, one, "b" * 64, "a" * 40)
            self.assertEqual(metadata["tag"], "v1.2.0-rc.1")
            self.assertEqual(metadata["versionCode"], 3)
            self.assertEqual(metadata["signedSha256"], metadata["unsignedSha256"])
            self.assertEqual(len(metadata["signedSha256"]), 64)
            for malformed in ["", manifest.replace("1.2.0-rc.1", "../../latest")]:
                with self.assertRaises(ValueError):
                    describe(malformed, one, one, "b" * 64, "a" * 40)
            with self.assertRaises(ValueError):
                describe(manifest, one, one, "b" * 64, "main")
