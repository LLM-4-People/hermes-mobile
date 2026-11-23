"""Unit tests for live-code introspection and HTML/Markdown mechanical generation in scripts/docs.py."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "ci"))
import check_pages
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


def _scaffold_minimal_site(root):
    for rel in (
        ".nojekyll",
        "index.html",
        "docs/index.html",
        "walkthrough/index.html",
        "en/index.html",
        "fr/index.html",
        "en/docs/index.html",
        "fr/docs/index.html",
        "en/walkthrough/index.html",
        "fr/walkthrough/index.html",
    ):
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("<!DOCTYPE html><html><title>Ok</title></html>\n", encoding="utf-8")
    for asset in (
        "assets/screens/en/command-palette.png",
        "assets/screens/dark/en/command-palette.png",
        "assets/screens/fr/command-palette.png",
        "assets/screens/dark/fr/command-palette.png",
        "assets/screens/phone-transcript.png",
        "assets/marketing/hero-studio.png",
        "assets/marketing/hero-studio-fr.png",
    ):
        p = root / asset
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"\x89PNG\r\n\x1a\n")


class CheckPagesIntegrityTest(unittest.TestCase):
    def test_rejects_missing_img_dimensions_and_em_dash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_minimal_site(root)
            (root / "en/index.html").write_text(
                '<!DOCTYPE html><html lang="en"><title>Bad \u2014 page</title>'
                '<img src="../assets/screens/en/command-palette.png" alt="ok"></html>\n',
                encoding="utf-8",
            )
            joined = "\n".join(check_pages.validate_site(root))
            self.assertIn("em-dash", joined)
            self.assertIn("missing positive integer width", joined)
            self.assertIn("missing positive integer height", joined)

    def test_rejects_cross_language_images(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_minimal_site(root)
            (root / "en/index.html").write_text(
                '<!DOCTYPE html><html lang="en">'
                '<img src="../assets/screens/fr/command-palette.png" width="1080" height="2340" alt="wrong">'
                '<img src="../assets/screens/phone-transcript.png" width="1080" height="2340" alt="legacy">'
                "</html>\n",
                encoding="utf-8",
            )
            (root / "fr/index.html").write_text(
                '<!DOCTYPE html><html lang="fr">'
                '<img src="../assets/marketing/hero-studio.png" width="1920" height="1080" alt="en">'
                '<img src="../assets/screens/en/command-palette.png" width="1080" height="2340" alt="en">'
                "</html>\n",
                encoding="utf-8",
            )
            joined = "\n".join(check_pages.validate_site(root))
            self.assertIn("English page references non-English image", joined)
            self.assertIn("phone-transcript.png", joined)
            self.assertIn("French page references English-only image", joined)
            self.assertIn("hero-studio.png", joined)

    def test_accepts_clean_language_matched_dual_theme_pages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_minimal_site(root)
            (root / "en/index.html").write_text(
                '<!DOCTYPE html><html lang="en">'
                '<img src="../assets/screens/en/command-palette.png" '
                'data-src-light="../assets/screens/en/command-palette.png" '
                'data-src-dark="../assets/screens/dark/en/command-palette.png" '
                'width="1080" height="2340" alt="Command palette">'
                "</html>\n",
                encoding="utf-8",
            )
            (root / "fr/index.html").write_text(
                '<!DOCTYPE html><html lang="fr">'
                '<img src="../assets/marketing/hero-studio-fr.png" width="1920" height="1080" alt="Apercu">'
                '<img src="../assets/screens/fr/command-palette.png" '
                'data-src-light="../assets/screens/fr/command-palette.png" '
                'data-src-dark="../assets/screens/dark/fr/command-palette.png" '
                'width="1080" height="2340" alt="Palette">'
                "</html>\n",
                encoding="utf-8",
            )
            self.assertEqual(check_pages.validate_site(root), [])


if __name__ == "__main__":
    unittest.main()
