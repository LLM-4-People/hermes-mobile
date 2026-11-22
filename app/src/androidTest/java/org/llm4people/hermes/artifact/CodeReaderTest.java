package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.test.InstrumentationTestCase;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

@SuppressWarnings("deprecation")
public final class CodeReaderTest extends InstrumentationTestCase {
    private Activity activity;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context target = getInstrumentation().getTargetContext();
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        activity = getInstrumentation().startActivitySync(launch);
    }

    @Override protected void tearDown() throws Exception {
        try { getInstrumentation().runOnMainSync(activity::finish); }
        finally { super.tearDown(); }
    }

    public void testSyntaxHighlightingAppliesContrastSafePaletteInDayAndNight() {
        Context target = getInstrumentation().getTargetContext();
        String source = "public class Demo {\n  // note\n  int count = 42;\n  String s = \"ok\";\n}";
        for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            Configuration config = new Configuration(target.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
            Context themed = target.createConfigurationContext(config);
            Spanned rendered = SyntaxHighlighter.render(themed, source, SyntaxHighlighter.Language.JAVA,
                    0, true, 1, null, -1);
            assertEquals(source, rendered.toString());
            ForegroundColorSpan[] colors = rendered.getSpans(0, 6, ForegroundColorSpan.class);
            assertTrue(colors.length > 0);
            assertEquals(themed.getColor(R.color.syntax_keyword), colors[0].getForegroundColor());
            LeadingMarginSpan[] margins = rendered.getSpans(0, source.length(), LeadingMarginSpan.class);
            assertEquals(5, margins.length);
        }
    }

    public void testLineNumberToggleHorizontalScrollAndInFileSearchAcrossPages() throws Throwable {
        String longCode = "int alpha = 1;\n" + "int filler = 0;\n".repeat(420) + "int alpha = 2;";
        AlertDialog[] holder = {null};
        try {
            runTestOnUiThread(() -> holder[0] = Ui.secureDialog(
                    CodeReader.dialog(activity, "Demo.java", "java", longCode)));
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                View root = holder[0].getWindow().getDecorView();
                TextView codeView = findCodeView(root, "int alpha = 1;");
                assertNotNull(codeView);
                assertTrue(codeView.isTextSelectable());
                assertTrue(((Spanned) codeView.getText()).getSpans(0, codeView.length(), LeadingMarginSpan.class).length > 0);

                // Toggle line numbers off and back on.
                Button lineBtn = (Button) findExact(root, activity.getString(R.string.code_line_numbers));
                assertNotNull(lineBtn);
                lineBtn.performClick();
                TextView noLines = findCodeView(root, "int alpha = 1;");
                assertEquals(0, ((Spanned) noLines.getText()).getSpans(0, noLines.length(), LeadingMarginSpan.class).length);
                lineBtn.performClick();

                // Toggle horizontal scroll on.
                Button scrollBtn = (Button) findExact(root, activity.getString(R.string.code_scroll_horizontal));
                assertNotNull(scrollBtn);
                scrollBtn.performClick();
                assertNotNull(findHorizontalScroll(root));
                assertNotNull(findExact(root, activity.getString(R.string.code_wrap_lines)));

                // Open search and jump across pages between matches.
                findExact(root, activity.getString(R.string.code_search)).performClick();
                EditText query = findEditText(root);
                assertNotNull(query);
                query.setText("alpha");
                assertNotNull(findExact(root, "1 / 2"));
                TextView page1 = findCodeView(root, "int alpha = 1;");
                assertTrue(((Spanned) page1.getText()).getSpans(0, page1.length(), BackgroundColorSpan.class).length > 0);

                findExact(root, activity.getString(R.string.code_search_next)).performClick();
                assertNotNull(findExact(root, "2 / 2"));
                assertNotNull(findCodeView(root, "int alpha = 2;"));

                query.setText("inexistant_xyz");
                assertNotNull(findExact(root, activity.getString(R.string.code_search_none)));
            });
        } finally {
            runTestOnUiThread(() -> { if (holder[0] != null) holder[0].dismiss(); });
        }
    }

    private static View findExact(View view, String label) {
        if (view instanceof TextView tv && tv.isShown() && label.contentEquals(tv.getText())) return tv;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findExact(g.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static TextView findCodeView(View view, String snippet) {
        if (view instanceof TextView tv && !(tv instanceof Button) && !(tv instanceof EditText)
                && tv.isShown() && tv.getText().toString().contains(snippet)) return tv;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findCodeView(g.getChildAt(i), snippet);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText e && e.isShown()) return e;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText hit = findEditText(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static HorizontalScrollView findHorizontalScroll(View view) {
        if (view instanceof HorizontalScrollView hs && hs.isShown()) return hs;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                HorizontalScrollView hit = findHorizontalScroll(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
