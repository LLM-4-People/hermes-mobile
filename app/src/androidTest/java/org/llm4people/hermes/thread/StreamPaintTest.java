package org.llm4people.hermes.thread;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.TextView;
import org.llm4people.hermes.Ui;
import java.util.Arrays;

/** Measure a native phone-width text layout at the actual update cadence, without network latency. */
@SuppressWarnings("deprecation")
public final class StreamPaintTest extends InstrumentationTestCase {
    public void testLongMixedReplyAtTheRunFollowerCadence() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        LiveTranscript stream = new LiveTranscript();
        String fixture = "### Vérifier\n- __Lire__ le résultat et `main.py`.\n```diff\n-  return 0\n+  return n\n```\n";
        StringBuilder source = new StringBuilder();
        while (source.length() < 45000) source.append(fixture);
        TextView[] body = new TextView[1];
        runTestOnUiThread(() -> {
            body[0] = Ui.text(context, "", 17);
            body[0].setTextIsSelectable(true);
            MessageText.updateLive(body[0], stream, source.toString());
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
                MessageText.updateLive(body[0], stream, frame);
                body[0].measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context, 340), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                body[0].layout(0, 0, body[0].getMeasuredWidth(), body[0].getMeasuredHeight());
                nanos[index] = System.nanoTime() - at;
                assertTrue(body[0].getText().length() <= 2 * LiveTranscript.PAGE_CHARS + 8);
                assertTrue(body[0].getMeasuredHeight() > 0);
            });
        }
        Arrays.sort(nanos);
        Bundle metrics = new Bundle();
        metrics.putString("stream", "Native streaming text measure/layout, microseconds: median="
                + nanos[15] / 1000 + ", p95=" + nanos[30] / 1000 + ", max=" + nanos[31] / 1000 + "\n");
        getInstrumentation().sendStatus(0, metrics);
    }
}
