package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.test.InstrumentationTestCase;
import android.text.Spanned;
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
            assertCode(context, text, removed, R.color.danger);
            assertCode(context, text, added, R.color.accent);
        }
    }

    private void assertCode(Context context, Spanned text, int start, int role) {
        assertEquals("monospace", text.getSpans(start, start + 1, TypefaceSpan.class)[0].getFamily());
        assertEquals(context.getColor(role), text.getSpans(start, start + 1, ForegroundColorSpan.class)[0].getForegroundColor());
    }
}
