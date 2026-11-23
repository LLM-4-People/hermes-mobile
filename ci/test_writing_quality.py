"""Unit tests for ci/check_writing_quality.py (sentence case, anti-AI-slop, and language purity)."""

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))
import check_writing_quality as cwq


class WritingQualityTest(unittest.TestCase):
    def test_repo_passes_writing_quality_sentence_case_and_language_purity(self):
        self.assertEqual(cwq.check_repo(ROOT, include_android_strings=True), [])

    def test_en_ai_slop_words_phrases_hype_unlock_and_em_dash_fail_closed(self):
        samples = [
            ("Let us delve into this tapestry of features.", "AI cliché"),
            ("This robust, holistic, cutting-edge platform empowers teams.", "AI cliché"),
            ("Supercharge your bespoke next-level vibrant workflow effortlessly.", "AI cliché"),
            ("A groundbreaking transformative tool to unleash and orchestrate synergy.", "AI cliché"),
            ("Reimagine your workspace with seamless integration.", "AI cliché"),
            ("Install the companion to unlock the full potential of your workspace.", "AI hype cliché"),
            ("Great question! Here is how it works.", "AI filler phrase"),
            ("It is not just a client, it's a new way to work.", "AI filler phrase"),
            ("This is not only fast, but also reliable.", "AI filler phrase"),
            ("More than just a messaging app.", "AI filler phrase"),
            ("In conclusion, feel free to try it today.", "AI filler phrase"),
            ("To summarize, this release ships fast.", "AI filler phrase"),
            ("Hope this helps! Happy coding!", "AI filler phrase"),
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
            "Décupler et sublimer votre outil sur-mesure dans cet écosystème.",
            "Une solution clé en main sans effort à la pointe.",
            "Ce n’est pas seulement une application, mais une expérience.",
            "Non seulement rapide, mais aussi pratique.",
            "Excellente question ! N’hésitez pas à demander.",
            "En résumé, pour conclure en somme.",
        ]
        for text in fr_slop_samples:
            issues = cwq.check_text_lines("docs/fr/README.md", [(1, text)], "fr")
            self.assertTrue(issues, f"Expected French AI slop hit for: {text}")

        en_leak = cwq.check_text_lines("site/fr/index.html", [(1, "Download signed APK and Read documentation")], "fr")
        self.assertTrue(en_leak)
        self.assertIn("English leak", en_leak[0])

        fr_leak = cwq.check_text_lines("site/en/index.html", [(1, "Open Activité to check Mises à jour.")], "en")
        self.assertTrue(fr_leak)
        self.assertIn("French leak", fr_leak[0])

    def test_sentence_case_rejects_title_case_and_allows_proper_nouns_and_acronyms(self):
        title_case_samples = [
            ("Real Device Preview", "en"),
            ("Download Signed APK", "en"),
            ("Global Command Palette (Ctrl+K)", "en"),
            ("Built-In In-App Update Detection", "en"),
            ("End-to-End Encrypted Vault", "en"),
            ("Pin to Home Screen?", "en"),
            ("Ouvrir Les Réglages Rapides", "fr"),
        ]
        for text, lang in title_case_samples:
            offenders = cwq.check_sentence_case(text, lang)
            self.assertTrue(offenders, f"Expected Title Case rejection for: {text!r}")

        valid_sentence_case = [
            ("Hermes Mobile — Private AI companion for Android 16", "en"),
            ("Releases and in-app GitHub auto-update", "en"),
            ("Zero-root ambient intelligence with TalkBack and UnifiedPush", "en"),
            ("Global command palette (Ctrl+K) for GitHub Pages and GitHub Releases", "en"),
            ("Verify SHA-256, WCAG-AA, FTS5, WAL, and HTTPS on LLM-4-People", "en"),
            ("1. Open app info (⋮ → Allow restricted settings)", "en"),
            ("Language / Langue", "en"),
            ("Langue / Language", "fr"),
            ("Ouvrir l’Atelier et les réglages Android sur Wi-Fi", "fr"),
            ("Notifications chiffrées via UnifiedPush et Firebase", "fr"),
        ]
        for text, lang in valid_sentence_case:
            offenders = cwq.check_sentence_case(text, lang)
            self.assertEqual(offenders, [], f"Unexpected Title Case failure for {text!r}: {offenders}")

    def test_literal_crypto_unlock_code_blocks_urls_and_lang_switchers_do_not_false_positive(self):
        clean_md = """# Clean English doc

*[Documentation en français](docs/fr/README.md)*

Unlock this profile with your passphrase or enable quick unlock with Class-3 biometrics.
Ask Hermes to summarize research and review workspace files.
See https://example.com/delve/tapestry/seamless for reference.

```java
// Code blocks may mention technical names like robust_streamline or Activité
# Title Case Inside Code Block Should Be Ignored
String label = "Activité";
```
"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            md = root / "README.md"
            md.write_text(clean_md, encoding="utf-8")
            self.assertEqual(cwq.check_markdown_file(md, root, "en"), [])

    def test_markdown_and_html_reject_title_case_headings_and_buttons(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            md = root / "README.md"
            md.write_text("# Clean heading\n\n## Build & Verify Locally\n", encoding="utf-8")
            md_issues = cwq.check_markdown_file(md, root, "en")
            self.assertEqual(len(md_issues), 1)
            self.assertIn("Title Case in heading", md_issues[0])

            en_dir = root / "site/en"
            en_dir.mkdir(parents=True)
            html = en_dir / "index.html"
            html.write_text(
                '<html lang="en"><head><title>Hermes Mobile — Private AI Companion</title></head><body>'
                '<a data-lang-switch="fr" lang="fr" href="../fr/">Français</a>'
                '<h1>Clean sentence case heading</h1>'
                '<a class="btn btn-primary" href="#">Download Signed APK</a>'
                '<figcaption>Tuned for warm Day and deep Night readability.</figcaption>'
                '</body></html>',
                encoding="utf-8",
            )
            html_issues = cwq.check_html_file(html, root, "en")
            self.assertEqual(len(html_issues), 3, f"Expected 3 Title Case hits, got: {html_issues}")
            joined = "\n".join(html_issues)
            self.assertIn("<title>", joined)
            self.assertIn("<a>", joined)
            self.assertIn("<figcaption>", joined)

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

    def test_android_xml_checks_strings_sentence_case_and_skips_untranslatable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            en_dir = root / "app/src/main/res/values-en"
            en_dir.mkdir(parents=True)
            xml_path = en_dir / "sample.xml"
            xml_path.write_text(
                '<resources>'
                '<string name="ok">Unlock this profile</string>'
                '<string name="skip" translatable="false">delve tapestry Title Case</string>'
                '<string name="bad_slop">Elevate your workflow seamlessly</string>'
                '<string name="bad_case">Pin to Home Screen</string>'
                '</resources>',
                encoding="utf-8",
            )
            issues = cwq.check_android_strings_file(xml_path, root, "en")
            self.assertEqual(len(issues), 2, f"Expected 2 issues, got: {issues}")
            joined = "\n".join(issues)
            self.assertIn("AI cliché", joined)
            self.assertIn("Title Case in Android resource", joined)

    def test_android_xml_scans_plurals_and_string_arrays_and_respects_translatable_false(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            en_dir = root / "app/src/main/res/values-en"
            en_dir.mkdir(parents=True)
            xml_path = en_dir / "plurals_and_arrays.xml"
            xml_path.write_text(
                '<resources>'
                '<plurals name="clean_runs">'
                '  <item quantity="one">%d active task</item>'
                '  <item quantity="other">%d active tasks</item>'
                '</plurals>'
                '<plurals name="dirty_runs">'
                '  <item quantity="one">Open Activité for %d task</item>'
                '  <item quantity="other">%d tasks running seamlessly</item>'
                '</plurals>'
                '<plurals name="skip_plurals" translatable="false">'
                '  <item quantity="other">delve tapestry Activité</item>'
                '</plurals>'
                '<string-array name="clean_labels">'
                '  <item>Home</item>'
                '  <item>Activity</item>'
                '</string-array>'
                '<string-array name="dirty_labels">'
                '  <item>A robust choice</item>'
                '  <item translatable="false">tapestry skip me</item>'
                '</string-array>'
                '<string-array name="skip_array" translatable="false">'
                '  <item>holistic beacon</item>'
                '</string-array>'
                '</resources>',
                encoding="utf-8",
            )
            issues = cwq.check_android_strings_file(xml_path, root, "en")
            self.assertEqual(len(issues), 4, f"Expected 4 issues (3 slop/leak + 1 Title Case Activité), got: {issues}")
            joined = "\n".join(issues)
            self.assertIn("French leak", joined)
            self.assertIn("seamless", joined)
            self.assertIn("robust", joined)


if __name__ == "__main__":
    unittest.main()
