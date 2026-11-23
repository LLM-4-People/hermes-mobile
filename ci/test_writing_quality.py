"""Unit tests for ci/check_writing_quality.py (anti-AI-slop & single-language purity gate)."""

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))
import check_writing_quality as cwq


class WritingQualityTest(unittest.TestCase):
    def test_repo_passes_writing_quality_and_language_purity(self):
        self.assertEqual(cwq.check_repo(ROOT, include_android_strings=True), [])

    def test_en_ai_slop_words_phrases_hype_unlock_and_em_dash_fail_closed(self):
        samples = [
            ("Let us delve into this tapestry of features.", "AI cliché"),
            ("This robust, holistic, cutting-edge platform empowers teams.", "AI cliché"),
            ("Install the companion to unlock the full potential of your workspace.", "AI hype cliché"),
            ("Great question! Here is how it works.", "AI filler phrase"),
            ("It is not just a client, it's a new way to work.", "AI filler phrase"),
            ("In conclusion, feel free to try it today.", "AI filler phrase"),
            ("To summarize, this release ships fast.", "AI filler phrase"),
            ("One — two — three — four em-dashes on one line.", "em-dash overuse"),
        ]
        for text, expected_tag in samples:
            issues = cwq.check_text_lines("README.md", [(1, text)], "en")
            self.assertTrue(issues, f"Expected violation for: {text}")
            self.assertIn(expected_tag, issues[0])

    def test_fr_ai_slop_and_mixed_language_fail_closed(self):
        fr_slop_samples = [
            "Plongez dans ce paysage numérique révolutionnaire.",
            "Une synergie incontournable et holistique sans couture.",
            "Ce n’est pas seulement une application, mais une expérience.",
            "Excellente question ! N’hésitez pas si vous voulez en savoir plus.",
        ]
        for text in fr_slop_samples:
            issues = cwq.check_text_lines("docs/fr/README.md", [(1, text)], "fr")
            self.assertTrue(issues, f"Expected French AI slop hit for: {text}")

        en_leak = cwq.check_text_lines("site/fr/index.html", [(1, "Download Signed APK and Read Documentation")], "fr")
        self.assertTrue(en_leak)
        self.assertIn("English leak", en_leak[0])

        fr_leak = cwq.check_text_lines("site/en/index.html", [(1, "Open Activité to check Mises à jour.")], "en")
        self.assertTrue(fr_leak)
        self.assertIn("French leak", fr_leak[0])

    def test_literal_crypto_unlock_code_blocks_urls_and_lang_switchers_do_not_false_positive(self):
        clean_md = """# Clean English Doc

*[Documentation en français](docs/fr/README.md)*

Unlock this profile with your passphrase or enable quick unlock with Class-3 biometrics.
Ask Hermes to summarize research and review workspace files.
See https://example.com/delve/tapestry/seamless for reference.

```java
// Code blocks may mention technical names like robust_streamline or Activité
String label = "Activité";
```
"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            md = root / "README.md"
            md.write_text(clean_md, encoding="utf-8")
            self.assertEqual(cwq.check_markdown_file(md, root, "en"), [])

    def test_html_extractor_ignores_lang_switcher_and_catches_body_leaks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            en_dir = root / "site/en"
            en_dir.mkdir(parents=True)
            clean_html = en_dir / "index.html"
            clean_html.write_text(
                '<html lang="en"><body>'
                '<a data-lang-switch="fr" lang="fr" href="../fr/">Français</a>'
                '<p>Unlock your local profile safely.</p>'
                '<code>Activité</code>'
                '</body></html>',
                encoding="utf-8",
            )
            self.assertEqual(cwq.check_html_file(clean_html, root, "en"), [])

            dirty_html = en_dir / "dirty.html"
            dirty_html.write_text(
                '<html lang="en"><body><p>Ouvrir Activité pour les Mises à jour.</p></body></html>',
                encoding="utf-8",
            )
            issues = cwq.check_html_file(dirty_html, root, "en")
            self.assertTrue(issues)

    def test_android_xml_checks_strings_and_skips_untranslatable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            en_dir = root / "app/src/main/res/values-en"
            en_dir.mkdir(parents=True)
            xml_path = en_dir / "sample.xml"
            xml_path.write_text(
                '<resources>'
                '<string name="ok">Unlock this profile</string>'
                '<string name="skip" translatable="false">delve tapestry</string>'
                '<string name="bad">Elevate your workflow seamlessly</string>'
                '</resources>',
                encoding="utf-8",
            )
            issues = cwq.check_android_strings_file(xml_path, root, "en")
            self.assertEqual(len(issues), 1)
            self.assertIn("AI cliché", issues[0])


if __name__ == "__main__":
    unittest.main()
