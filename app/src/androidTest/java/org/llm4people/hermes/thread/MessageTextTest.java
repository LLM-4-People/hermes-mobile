package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.test.InstrumentationTestCase;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.Ui;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public final class MessageTextTest extends InstrumentationTestCase {
    public void testProseAndPatchAreSelectableWithPassiveLinksInBothThemes() {
        Context target = getInstrumentation().getTargetContext();
        String source = "## Patch\n**À relire** [doc](https://exemple.fr)\n```diff\n-  n + n\n+  n * n\n```";
        TranscriptPage page = new TranscriptPage(source, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
        for (int mode : new int[]{Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO}) {
            Configuration config = new Configuration(target.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
            Context context = target.createConfigurationContext(config);
            Spanned text = (Spanned) MessageText.styled(context, page);
            assertEquals("Patch\nÀ relire [doc](https://exemple.fr)\n-  n + n\n+  n * n\n", text.toString());
            assertEquals(0, text.getSpans(0, text.length(), ClickableSpan.class).length);
            int word = text.toString().indexOf("À relire");
            assertEquals(Typeface.BOLD, text.getSpans(word, word + 1, StyleSpan.class)[0].getStyle());
            int removed = text.toString().indexOf("-  n"), added = text.toString().indexOf("+  n");
            assertEquals(1, text.getSpans(removed, added + 1, LineBackgroundSpan.class).length);
            assertEquals(0, text.getSpans(removed, added + 1, BackgroundColorSpan.class).length);
            assertCode(context, text, removed, R.color.danger);
            assertCode(context, text, added, R.color.accent);
        }
    }

    public void testLongFencedSourceOpensAndCopiesBeyondTheInlineExcerpt() throws Throwable {
        Context target = getInstrumentation().getTargetContext();
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        String contents = "    n += 1\n".repeat(650) + "    return n";
        String source = "Avant\n```python file=src/carre.py\n" + contents + "\n```\nAprès";
        TranscriptPage preview = new TranscriptPage(source, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
        AlertDialog[] reader = {null};
        try {
            runTestOnUiThread(() -> {
                LinearLayout entry = Ui.column(activity);
                TextView excerpt = Ui.text(activity, "", 17);
                excerpt.setTextIsSelectable(true);
                excerpt.setText(MessageText.excerpt(activity, preview));
                entry.addView(excerpt);
                assertTrue(excerpt.isTextSelectable());
                assertTrue(excerpt.getText().toString().startsWith("Avant\n    n += 1"));
                assertTrue(excerpt.length() < 100);
                MessageText.addCodeAction(entry, preview, source, builder -> reader[0] = Ui.secureDialog(builder));
                activity.setContentView(entry);
                Button open = (Button) entry.getChildAt(1);
                assertEquals("Ouvrir le code · src/carre.py · python", open.getText().toString());
                open.performClick();
            });
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                View root = reader[0].getWindow().getDecorView();
                assertNotNull(find(root, "Page 1 sur 2"));
                find(root, "Suivante").performClick();
                TextView ending = find(root, "return n");
                assertTrue(ending.isTextSelectable());
                assertTrue(ending.getText().toString().endsWith("    return n"));
                find(root, "Copier le contenu").performClick();
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                ClipData clip = clipboard.getPrimaryClip();
                assertNotNull(clip);
                assertEquals(contents, clip.getItemAt(0).getText().toString());
                assertTrue(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
            });
        } finally {
            runTestOnUiThread(() -> {
                if (reader[0] != null) reader[0].dismiss();
                activity.finish();
            });
        }
    }

    public void testActionWrapsAtPhoneWidthWithLargeText() throws Throwable {
        Context target = getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.fontScale = 1.5f;
        Context context = target.createConfigurationContext(config);
        String source = "```python file=src/calculs/mesures_de_la_semaine.py\n  return n\n```";
        TranscriptPage page = new TranscriptPage(source, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
        runTestOnUiThread(() -> {
            LinearLayout group = Ui.column(context);
            MessageText.addCodeAction(group, page, source, builder -> {});
            int width = Ui.dp(context, 320 - 36);
            group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            group.layout(0, 0, width, group.getMeasuredHeight());
            Button action = (Button) group.getChildAt(0);
            int lines = action.getLineCount();
            assertTrue(lines > 1);
            assertEquals(action.length(), action.getLayout().getLineEnd(lines - 1));
            assertEquals(0, action.getLayout().getEllipsisCount(lines - 1));
            assertTrue(action.getHeight() >= Ui.dp(context, 48));
        });
    }

    private static TextView find(View view, String words) {
        if (view instanceof TextView text && text.getText().toString().contains(words)) return text;
        if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), words);
                if (found != null) return found;
            }
        return null;
    }

    private void assertCode(Context context, Spanned text, int start, int role) {
        assertEquals("monospace", text.getSpans(start, start + 1, TypefaceSpan.class)[0].getFamily());
        assertEquals(context.getColor(role), text.getSpans(start, start + 1, ForegroundColorSpan.class)[0].getForegroundColor());
    }
}
