package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.text.Selection;
import android.text.Spannable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.Ui;
import java.util.Arrays;

/** Measure a native phone-width text layout at the actual update cadence, without network latency. */
@SuppressWarnings("deprecation")
public final class StreamPaintTest extends InstrumentationTestCase {
    private static final class Preview {
        final LiveTranscript stream = new LiveTranscript();
        final LinearLayout parent;
        final TextView body;
        final int width;

        Preview(Context context) {
            parent = Ui.column(context);
            body = Ui.text(context, "", 17);
            body.setTextIsSelectable(true);
            parent.addView(body);
            width = Ui.dp(context, 340);
        }

        void paint(String source) {
            MessageText.updateLive(body, stream, source);
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            parent.layout(0, 0, parent.getMeasuredWidth(), parent.getMeasuredHeight());
        }
    }

    public void testLongMixedReplyAtTheRunFollowerCadence() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        String fixture = "### Vérifier\n- __Lire__ le résultat et `main.py`.\n```diff\n-  return 0\n+  return n\n```\n";
        StringBuilder source = new StringBuilder();
        while (source.length() < 45000) source.append(fixture);
        Preview[] preview = new Preview[1];
        runTestOnUiThread(() -> {
            preview[0] = new Preview(context);
            preview[0].paint(source.toString());
        });
        long[] nanos = new long[32];
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i < nanos.length; i++) {
            long wait = start + i * RunFollower.PAINT_INTERVAL_MS - SystemClock.uptimeMillis();
            if (wait > 0) SystemClock.sleep(wait);
            source.append(fixture, 0, i % 2 == 0 ? fixture.length() / 2 : fixture.length());
            String frame = source.toString();
            int index = i;
            runTestOnUiThread(() -> {
                long at = System.nanoTime();
                preview[0].paint(frame);
                nanos[index] = System.nanoTime() - at;
                assertTrue(preview[0].body.getText().length() <= 2 * LiveTranscript.PAGE_CHARS + 8);
                assertTrue(preview[0].body.getMeasuredHeight() > 0);
            });
        }
        Arrays.sort(nanos);
        Bundle metrics = new Bundle();
        metrics.putString("stream", "Native streaming text measure/layout, microseconds: median="
                + nanos[15] / 1000 + ", p95=" + nanos[30] / 1000 + ", max=" + nanos[31] / 1000 + "\n");
        getInstrumentation().sendStatus(0, metrics);
    }

    public void testSelectReselectClearAndRecreateInBothThemes() throws Throwable {
        Context target = getInstrumentation().getTargetContext();
        runTestOnUiThread(() -> {
            for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Configuration config = new Configuration(target.getResources().getConfiguration());
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
                Context context = target.createConfigurationContext(config);
                Preview preview = new Preview(context);
                String source = "Essai\n```diff\n+  return n\n```\n";
                preview.paint(source);
                TextView body = preview.body;
                Spannable text = (Spannable) body.getText();
                int start = text.toString().indexOf("+  return n"), end = start + "+  return n".length();
                Selection.setSelection(text, start, end);
                preview.paint(source);
                assertSame(text, body.getText());
                preview.paint(source + "Suite");
                assertEquals(start, body.getSelectionStart());
                assertEquals(end, body.getSelectionEnd());
                assertEquals("+  return n", body.getText().subSequence(start, end).toString());
                Selection.setSelection((Spannable) body.getText(), end, start + 3);
                preview.paint(source + "Suite du texte");
                assertEquals(end, body.getSelectionStart());
                assertEquals(start + 3, body.getSelectionEnd());
                StringBuilder longReply = new StringBuilder(source);
                while (longReply.length() < 4 * LiveTranscript.PAGE_CHARS) longReply.append("ligne suivante\n");
                preview.paint(longReply.toString());
                assertFalse(body.hasSelection());
                preview.paint("");
                assertEquals(0, body.length());
                assertFalse(body.hasSelection());
                preview.paint(source);
                Preview restored = new Preview(context);
                restored.paint(preview.stream.source());
                assertEquals(body.getText().toString(), restored.body.getText().toString());
                assertFalse(restored.body.hasSelection());
            }
        });
    }
}
