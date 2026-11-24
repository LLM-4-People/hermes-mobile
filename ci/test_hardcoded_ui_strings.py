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


FR_RES = '<resources xmlns:tools="http://schemas.android.com/tools" tools:locale="fr">'
EN_RES = '<resources xmlns:tools="http://schemas.android.com/tools" tools:locale="en">'


class HardcodedUiStringsGuardTest(unittest.TestCase):
    def test_repo_passes_with_zero_allowlists_and_accurate_ui_sinks(self):
        self.assertFalse(hasattr(guard, "MIGRATION_ALLOWLIST"))
        self.assertFalse(hasattr(guard, "PREEXISTING_WHITESPACE_STRINGS"))
        self.assertIn(("Ui", "pill"), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertIn(("Ui", "choiceChip"), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertIn(("panel", "note"), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertIn(("BrainUi", "chip"), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertIn(("BrainUi", "badge"), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertIn(("BrainUi", "liveNote"), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertIn(("ClipData", "newPlainText"), guard.QUALIFIED_UI_SINK_ARGS)
        for dead_method in ("chip", "heading", "sectionTitle", "caption", "badge", "banner", "emptyState"):
            self.assertNotIn(("Ui", dead_method), guard.QUALIFIED_UI_SINK_ARGS)
        self.assertEqual(guard.check_repo(ROOT), [])

    def test_rejects_hardcoded_french_literals_both_accented_and_unaccented(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root, "app/src/main/res/values/strings.xml", f"{FR_RES}<string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", f"{EN_RES}<string name='ok'>OK</string></resources>")
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
            _write(root, "app/src/main/res/values/strings.xml", f"{FR_RES}<string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", f"{EN_RES}<string name='ok'>OK</string></resources>")
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
                        Ui.choiceChip(ctx, "French choice chip", null);
                        screen.note("Nothing to show yet.");
                        panel.note(box, "Panel helper text");
                        BrainUi.chip(ctx, "Memory tab chip", false, null);
                        BrainUi.badge(ctx, "Pinned status badge", true);
                        BrainUi.liveNote(panel, box, "No matching notes");
                        if (ready) setText("Primary label");
                        else setText("Bypassed after else");
                        return setTitle("Bypassed after return");
                    }
                    void osSurfaces(android.widget.RemoteViews rv, android.content.pm.ShortcutInfo.Builder sb, android.service.quicksettings.Tile tile) {
                        rv.setTextViewText(1, "Widget title text");
                        rv.setContentDescription(1, "Widget accessibility text");
                        sb.setShortLabel("Quick Ask");
                        sb.setLongLabel("Open Hermes quickly");
                        tile.setLabel("Hermes Tile");
                        new NotificationChannel("ch_id", "Live updates", 3).setDescription("Channel summary text");
                        nb.setShortCriticalText("Running now");
                        new Notification.Action.Builder(null, "Stop active run", null).build();
                        new AccessibilityNodeInfo.AccessibilityAction(16, "Open full view");
                        new ProfileScreen(ctx, "Profile heading", "Profile subtitle text");
                        screen.section("Section title text", "Section helper summary");
                        screen.field("Field title text", "Field hint text", "Field helper text");
                        screen.profileRow("Profile row title", "Profile row subtitle");
                        ProfileScreen.styledField(ctx, 1, "Styled hint text", "Styled error text");
                        ProfileScreen.choiceChip(ctx, "Biometric lock choice");
                        android.content.ClipData.newPlainText("Copied report", "payload");
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
                "French choice chip",
                "Nothing to show yet.",
                "Panel helper text",
                "Memory tab chip",
                "Pinned status badge",
                "No matching notes",
                "Primary label",
                "Bypassed after else",
                "Bypassed after return",
                "Widget title text",
                "Widget accessibility text",
                "Quick Ask",
                "Open Hermes quickly",
                "Hermes Tile",
                "Live updates",
                "Channel summary text",
                "Running now",
                "Stop active run",
                "Open full view",
                "Profile heading",
                "Profile subtitle text",
                "Section title text",
                "Section helper summary",
                "Field title text",
                "Field hint text",
                "Field helper text",
                "Profile row title",
                "Profile row subtitle",
                "Styled hint text",
                "Styled error text",
                "Biometric lock choice",
                "Copied report",
            ]
            for snippet in expected_snippets:
                self.assertTrue(any(snippet in i for i in issues), f"Missing finding for {snippet!r}: {issues}")

    def test_allows_localized_resources_symbols_non_ui_calls_and_regex_matchers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root, "app/src/main/res/values/strings.xml", f"{FR_RES}<string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", f"{EN_RES}<string name='ok'>OK</string></resources>")
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
                        android.content.ClipData.newPlainText(ctx.getString(R.string.ok), "raw_payload");
                        Ui.choiceChip(ctx, ctx.getString(R.string.ok), null);
                        panel.note(box, panel.t(R.string.ok));
                        BrainUi.chip(ctx, ctx.getString(R.string.ok), true, null);
                        BrainUi.badge(ctx, ctx.getString(R.string.ok), false);
                        BrainUi.liveNote(panel, box, panel.t(R.string.ok));
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
                f"""{FR_RES}
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
                f"""{FR_RES}<string name="keep">Doublon</string></resources>""",
            )
            _write(
                root,
                "app/src/main/res/values-en/a.xml",
                f"""{EN_RES}
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

    def test_xml_parity_enforces_tools_locale_and_exempts_non_translatable_only_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Non-translatable-only XML files (colors, ids, config) require no tools:locale.
            _write(root, "app/src/main/res/values/colors.xml", "<resources><color name='ink'>#14231E</color></resources>")
            _write(root, "app/src/main/res/values/config.xml", "<resources><string name='repo' translatable='false'>owner/app</string></resources>")
            _write(root, "app/src/main/res/values/ids.xml", "<resources><item name='row_id' type='id'/></resources>")
            _write(root, "app/src/main/res/values/strings.xml", f"{FR_RES}<string name='ok'>OK</string></resources>")
            _write(root, "app/src/main/res/values-en/strings.xml", f"{EN_RES}<string name='ok'>OK</string></resources>")
            self.assertEqual(guard.check_xml_parity(root), [])

            # Missing tools:locale in values/ and values-en/ must fail.
            _write(root, "app/src/main/res/values/missing_fr.xml", "<resources><string name='m1'>Bonjour</string></resources>")
            _write(root, "app/src/main/res/values-en/missing_en.xml", "<resources><string name='m1'>Hello</string></resources>")
            # Wrong tools:locale in values/ and values-en/ must fail.
            _write(root, "app/src/main/res/values/wrong_fr.xml", f"{EN_RES}<plurals name='p1'><item quantity='one'>1</item></plurals></resources>")
            _write(root, "app/src/main/res/values-en/wrong_en.xml", f"{FR_RES}<plurals name='p1'><item quantity='one'>1</item></plurals></resources>")
            issues = guard.check_xml_parity(root)
            self.assertTrue(any("missing_fr.xml" in i and "tools:locale='fr'" in i for i in issues), issues)
            self.assertTrue(any("missing_en.xml" in i and "tools:locale='en'" in i for i in issues), issues)
            self.assertTrue(any("wrong_fr.xml" in i and "tools:locale='fr'" in i and "found 'en'" in i for i in issues), issues)
            self.assertTrue(any("wrong_en.xml" in i and "tools:locale='en'" in i and "found 'fr'" in i for i in issues), issues)


if __name__ == "__main__":
    unittest.main()
