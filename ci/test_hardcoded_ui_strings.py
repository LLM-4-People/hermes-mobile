"""Unit and negative tests for ci/check_hardcoded_ui_strings.py."""

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))
import check_hardcoded_ui_strings as guard


def _write(root: Path, rel: str, content: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class HardcodedUiStringsGuardTest(unittest.TestCase):
    def test_repo_passes_and_owned_packages_are_not_allowlisted(self):
        for rel in guard.MIGRATION_ALLOWLIST:
            self.assertNotIn("/artifact/", rel)
            self.assertNotIn("/panel/", rel)
            self.assertNotIn("/palette/", rel)
        self.assertEqual(guard.check_repo(ROOT), [])

    def test_rejects_hardcoded_french_literals_both_accented_and_unaccented(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root, "app/src/main/res/values/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(
                root,
                "app/src/main/java/org/example/BadFr.java",
                """
                class BadFr {
                    // Comment with éàç and Fermer must be ignored
                    /* Block comment with Nouvelle discussion ignored */
                    String a = "Paramètres avancés";
                    String b = "Fermer";
                    String c = \"\"\"
                        Aucun résultat trouvé
                        \"\"\";
                }
                """,
            )
            issues = guard.check_repo(root, allowlist={})
            self.assertEqual(len(issues), 3, issues)
            self.assertTrue(any("Paramètres avancés" in i for i in issues))
            self.assertTrue(any("Fermer" in i for i in issues))
            self.assertTrue(any("Aucun résultat trouvé" in i for i in issues))

    def test_rejects_hardcoded_english_ui_strings_in_ui_sinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root, "app/src/main/res/values/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(
                root,
                "app/src/main/java/org/example/BadEnUi.java",
                """
                class BadEnUi {
                    void render(android.content.Context ctx, android.widget.TextView tv) {
                        tv.setText("Save changes");
                        tv.setHint("Type a message");
                        tv.setContentDescription(ready ? "Close dialog" : "Cancel");
                        new AlertDialog.Builder(ctx)
                            .setTitle("Confirm delete")
                            .setMessage("This cannot be undone.")
                            .setPositiveButton("Delete", null)
                            .setNegativeButton("Cancel", null);
                        Toast.makeText(ctx, "Saved successfully", Toast.LENGTH_SHORT).show();
                        Ui.button(ctx, "Retry now", null);
                        screen.note("Nothing to show yet.");
                    }
                }
                """,
            )
            issues = guard.check_repo(root, allowlist={})
            expected_snippets = [
                "Save changes",
                "Type a message",
                "Close dialog",
                "Cancel",
                "Confirm delete",
                "This cannot be undone.",
                "Delete",
                "Saved successfully",
                "Retry now",
                "Nothing to show yet.",
            ]
            for snippet in expected_snippets:
                self.assertTrue(any(snippet in i for i in issues), f"Missing finding for {snippet!r}: {issues}")

    def test_allows_localized_resources_symbols_non_ui_calls_and_regex_matchers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root, "app/src/main/res/values/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(
                root,
                "app/src/main/java/org/example/Clean.java",
                """
                class Clean {
                    private static final Pattern OTP = Pattern.compile("code\\\\s+de\\\\s+v[ée]rification");
                    void render(android.content.Context ctx, android.widget.TextView tv, org.json.JSONObject obj) {
                        tv.setText(ctx.getString(R.string.ok));
                        tv.setText("");
                        tv.setText("·");
                        tv.setText(obj.optString("title", ""));
                        tv.setTag("save_button");
                        Diagnostics.event("panel", "opened_ok");
                        String punct = ".,!?;:)»";
                        throw new IllegalStateException("Internal state mismatch");
                    }
                }
                """,
            )
            self.assertEqual(guard.check_repo(root, allowlist={}), [])

    def test_xml_parity_catches_missing_extra_duplicate_and_array_length_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(
                root,
                "app/src/main/res/values/a.xml",
                """<resources>
                    <string name="keep">Garder</string>
                    <string name="only_fr">Uniquement en français</string>
                    <string name="cfg" translatable="false">https://example.test</string>
                    <plurals name="items"><item quantity="one">1 élément</item></plurals>
                    <string-array name="modes"><item>A</item><item>B</item></string-array>
                </resources>""",
            )
            _write(
                root,
                "app/src/main/res/values/b.xml",
                """<resources><string name="keep">Doublon</string></resources>""",
            )
            _write(
                root,
                "app/src/main/res/values-en/a.xml",
                """<resources>
                    <string name="keep">Keep</string>
                    <string name="only_en">English only</string>
                    <string name="padded"> Leading space</string>
                    <string name="cfg">Should not translate</string>
                    <string-array name="modes"><item>A</item></string-array>
                </resources>""",
            )
            issues = guard.check_xml_parity(root)
            self.assertTrue(any("duplicate <string name='keep'>" in i for i in issues), issues)
            self.assertTrue(any("missing <string name='only_fr'>" in i for i in issues), issues)
            self.assertTrue(any("missing <string name='only_en'>" in i for i in issues), issues)
            self.assertTrue(any("unquoted leading/trailing whitespace" in i and "padded" in i for i in issues), issues)
            self.assertTrue(any("translatable=\"false\"" in i and "cfg" in i for i in issues), issues)
            self.assertTrue(any("missing <plurals name='items'>" in i for i in issues), issues)
            self.assertTrue(any("string-array name='modes'" in i for i in issues), issues)

    def test_allowlist_ratchet_fails_when_allowlisted_file_is_clean_or_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root, "app/src/main/res/values/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", "<resources><string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/java/org/example/Clean.java", "class Clean { int x = 1; }")
            issues = guard.check_repo(
                root,
                allowlist={
                    "app/src/main/java/org/example/Clean.java": "Migrated",
                    "app/src/main/java/org/example/Deleted.java": "Gone",
                },
            )
            self.assertEqual(len(issues), 2, issues)
            self.assertTrue(any("has 0 violations" in i for i in issues))
            self.assertTrue(any("no longer exists" in i for i in issues))


if __name__ == "__main__":
    unittest.main()
