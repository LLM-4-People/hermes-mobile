package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class PhotoSendStateTest extends InstrumentationTestCase {
    public void testImportEditAndRunTransitionsUseTheSameVisibleCaption() throws Throwable {
        Context ctx = getInstrumentation().getTargetContext();
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runTestOnUiThread(() -> {
                ThreadViews screen = new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity));
                AtomicInteger sends = new AtomicInteger();
                screen.chat("", text -> {}, sends::incrementAndGet, () -> {}, () -> {});
                Button send = findSend(screen.root, activity.getString(R.string.thread_send));
                assertNotNull(send);
                for (String spaces : new String[]{"\u00a0", "\u202f", " \u00a0\u202f "}) {
                    screen.displayDraft(spaces);
                    screen.importingPhoto(true);
                    screen.importingPhoto(false);
                    assertFalse(send.isEnabled());
                    assertEquals(spaces, screen.draft());
                    send.performClick();
                }
                String original = "  Où est l’erreur ? ";
                screen.displayDraft(original);
                assertTrue(send.isEnabled());
                screen.busy(true, false);
                screen.importingPhoto(true);
                screen.displayDraft(original + "Suite");
                screen.importingPhoto(false);
                assertFalse(send.isEnabled());
                send.performClick();
                screen.importingPhoto(true);
                screen.busy(false, false);
                assertFalse(send.isEnabled());
                screen.importingPhoto(false);
                assertTrue(send.isEnabled());
                assertEquals(original + "Suite", screen.draft());
                assertEquals(0, sends.get());
                send.performClick();
                assertEquals(1, sends.get());
            });
        } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
    }
    private static Button findSend(View view, String label) {
        if (view instanceof Button b && label.contentEquals(b.getText())) return b;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button button = findSend(group.getChildAt(i), label);
                if (button != null) return button;
            }
        }
        return null;
    }
}
