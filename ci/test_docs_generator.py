"""Unit tests for live-code introspection and HTML/Markdown mechanical generation in scripts/docs.py."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import docs


class MechanicalDocsGeneratorTest(unittest.TestCase):
    def test_repo_docs_and_pages_are_mechanically_in_sync(self):
        self.assertEqual(docs.render(ROOT, check=True), [])

    def test_live_facts_match_repo_source_of_truth(self):
        facts = docs.live_facts(ROOT)
        self.assertEqual(facts["min_sdk"], "28")
        self.assertEqual(facts["min_android"], "9")
        self.assertEqual(facts["target_sdk"], "36")
        self.assertEqual(facts["target_android"], "16")
        self.assertEqual(facts["compile_sdk"], "36")
        self.assertTrue(facts["build_tools"].startswith("36."))
        self.assertEqual(len(facts["pinned_revision"]), 40)
        self.assertEqual(facts["pinned_revision_short"], facts["pinned_revision"][:8])
        self.assertTrue(int(facts["expected_fork_capabilities"]) >= 51)
        self.assertEqual(facts["curated_workflows_count"], "6")

    def test_html_site_templates_detect_drift_and_unmanaged_pages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            subprocess.run(["git", "init", "--quiet", root], check=True)
            (root / "gradle.properties").write_text(
                "hermesMinSdk=28\nhermesTargetSdk=36\nhermesCompileSdk=36\nhermesBuildTools=36.0.0\n",
                encoding="utf-8",
            )
            sources = root / docs.SOURCE
            (sources / "site/en").mkdir(parents=True)
            (sources / "facts.json").write_text('{"project":"Hermes Mobile"}', encoding="utf-8")
            (sources / "README.md.in").write_text(
                "# {{ project }}\n\nAndroid {{ min_android }}–{{ target_android }} (API {{ min_sdk }}–{{ target_sdk }})\n",
                encoding="utf-8",
            )
            (sources / "site/en/index.html.in").write_text(
                "<!DOCTYPE html><html lang=\"en\"><title>{{ project }}</title>"
                "<p>API {{ min_sdk }}–{{ target_sdk }}</p></html>\n",
                encoding="utf-8",
            )

            # Before render, both README.md and site/en/index.html fail closed.
            self.assertEqual(len(docs.render(root, check=True)), 2)

            # Rendering writes both and becomes idempotent.
            self.assertEqual(docs.render(root, check=False), [])
            self.assertIn("Android 9–16 (API 28–36)", (root / "README.md").read_text(encoding="utf-8"))
            self.assertIn("API 28–36", (root / "site/en/index.html").read_text(encoding="utf-8"))
            self.assertEqual(docs.render(root, check=True), [])

            # Manual edit to generated HTML causes immediate drift failure.
            (root / "site/en/index.html").write_text("<html>stale</html>", encoding="utf-8")
            problems = docs.render(root, check=True)
            self.assertEqual(len(problems), 1)
            self.assertIn("site/en/index.html", problems[0])

            # Unmanaged localized site HTML fails closed.
            docs.render(root, check=False)
            (root / "site/fr").mkdir(parents=True)
            (root / "site/fr/orphan.html").write_text("<html>orphan</html>", encoding="utf-8")
            problems = docs.render(root, check=True)
            self.assertEqual(len(problems), 1)
            self.assertIn("site/fr/orphan.html", problems[0])


if __name__ == "__main__":
    unittest.main()
