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
    def test_repo_passes_with_zero_allowlists_and_accurate_ui_sinks(self):
        self.assertFalse(hasattr(guard, "MIGRATION_ALLOWLIST"))
        self.assertFalse(hasattr(guard, "PREEXISTING_WHITESPACE_STRINGS"))
        self.assertIn(("Ui", "pill"), guard.QUALIFIED_UI_SINK_ARGS)
        for dead_method in ("chip", "heading", "sectionTitle", "caption", "badge", "banner", "emptyState"):
            self.assertNotIn(("Ui", dead_method), guard.QUALIFIED_UI_SINK_ARGS)
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
            issues = guard.check_repo(root)
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
                        Ui.pill(ctx, "Active badge", R.color.accent_surface, R.color.accent);
                        screen.note("Nothing to show yet.");
                        if (ready) setText("Primary label");
                        else setText("Bypassed after else");
                        return setTitle("Bypassed after return");
                    }
                    void osSurfaces(android.widget.RemoteViews rv, android.content.pm.ShortcutInfo.Builder sb, android.service.quicksettings.Tile tile) {
                        rv.setTextViewText(1, "Widget title text");
                        sb.setShortLabel("Quick Ask");
                        sb.setLongLabel("Open Hermes quickly");
                        tile.setLabel("Hermes Tile");
                        new NotificationChannel("ch_id", "Live updates", 3);
                    }
                }
                """,
            )
            issues = guard.check_repo(root)
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
                "Active badge",
                "Nothing to show yet.",
                "Primary label",
                "Bypassed after else",
                "Bypassed after return",
                "Widget title text",
                "Quick Ask",
                "Open Hermes quickly",
                "Hermes Tile",
                "Live updates",
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
            self.assertEqual(guard.check_repo(root), [])

    def test_xml_parity_catches_missing_extra_duplicate_and_array_length_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(
                root,
                "app/src/main/res/values/a.xml",
                """<resources>
                    <string name="keep">Garder</string>
                    <string name="fmt_ok">100%% %1$s (%2$d)</string>
                    <string name="fmt_bad">Valeur %1$s sur %2$d</string>
                    <string name="only_fr">Uniquement en français</string>
                    <string name="cfg" translatable="false">https://example.test</string>
                    <plurals name="items"><item quantity="one">1 élément</item></plurals>
                    <plurals name="days">
                        <item quantity="one">%1$d jour</item>
                        <item quantity="other">%1$d jours</item>
                    </plurals>
                    <plurals name="files">
                        <item quantity="one">%1$s fichier</item>
                        <item quantity="other">%1$d fichiers</item>
                    </plurals>
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
                    <string name="fmt_ok">(%2$d) %1$s 100%%</string>
                    <string name="fmt_bad">Value %1$d</string>
                    <string name="only_en">English only</string>
                    <string name="padded"> Leading space</string>
                    <string name="cfg">Should not translate</string>
                    <plurals name="days">
                        <item quantity="one">%1$d day</item>
                    </plurals>
                    <plurals name="files">
                        <item quantity="one">%1$d file</item>
                        <item quantity="other"> %1$d files</item>
                    </plurals>
                    <string-array name="modes"><item>A </item></string-array>
                </resources>""",
            )
            issues = guard.check_xml_parity(root)
            self.assertTrue(any("duplicate <string name='keep'>" in i for i in issues), issues)
            self.assertTrue(any("missing <string name='only_fr'>" in i for i in issues), issues)
            self.assertTrue(any("missing <string name='only_en'>" in i for i in issues), issues)
            self.assertTrue(any("unquoted leading/trailing whitespace" in i and "padded" in i for i in issues), issues)
            self.assertTrue(any("<plurals name='files' quantity='other'> has unquoted leading/trailing whitespace" in i for i in issues), issues)
            self.assertTrue(any("<string-array name='modes'> item[0] has unquoted leading/trailing whitespace" in i for i in issues), issues)
            self.assertTrue(any("translatable=\"false\"" in i and "cfg" in i for i in issues), issues)
            self.assertTrue(any("missing <plurals name='items'>" in i for i in issues), issues)
            self.assertTrue(any("string-array name='modes'" in i for i in issues), issues)
            self.assertTrue(any("<string name='fmt_bad'> placeholder mismatch" in i for i in issues), issues)
            self.assertFalse(any("fmt_ok" in i for i in issues), issues)
            self.assertTrue(any("<plurals name='days'> quantities" in i for i in issues), issues)
            self.assertTrue(any("<plurals name='files' quantity='one'> placeholder mismatch" in i for i in issues), issues)


if __name__ == "__main__":
    unittest.main()
