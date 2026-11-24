package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import org.llm4people.hermes.push.MobilePush;
import org.llm4people.hermes.net.Exchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class SecurePushWindowTest extends InstrumentationTestCase {
    public void testUnenrolledProfileOpensSecureAuthenticatedHistory() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        AccountStore accounts = new AccountStore(context);
        String label = UUID.randomUUID().toString();
        String body = "{\"events\":[{\"event_id\":\"ae941527148e4713ad209f8a372861b8\","
                + "\"kind\":\"reminder\",\"created_at\":1700000000}],\"next_cursor\":1,\"has_more\":false}";
        try (Exchange bridge = new Exchange("200 OK", "Content-Type: application/json\r\n", body);
             Account account = accounts.create(label, bridge.endpoint(), label, label.toCharArray())) {
            Activity activity = null;
            AtomicReference<MobilePush> pushRef = new AtomicReference<>();
            try {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                assertNotNull(launch);
                activity = getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                MobilePush push = new MobilePush(activity);
                pushRef.set(push);
                assertFalse(MobilePush.isEnabled(context, account.id));
                String historyLabel = activity.getString(R.string.push_settings_history);
                List<View> windows = new ArrayList<>();
                runTestOnUiThread(() -> {
                    List<View> before = new ArrayList<>(WindowInspector.getGlobalWindowViews());
                    push.settings(account);
                    windows.addAll(WindowInspector.getGlobalWindowViews());
                    windows.removeAll(before);
                    assertEquals("The settings dialog must own a window", 1, windows.size());
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) windows.get(0).getLayoutParams();
                    assertTrue("The dialog itself must be protected",
                            (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                    Button history = windows.get(0).findViewById(android.R.id.button3);
                    assertEquals(historyLabel, history.getText().toString());
                    history.performClick();
                });
                String request = bridge.observed();
                assertTrue(request.startsWith("GET /p/travail/api/mobile/push/events?after=0&limit=25 HTTP/1.1"));
                assertTrue(request.toLowerCase(Locale.ROOT).contains("authorization: bearer " + label));
                String reminderTitle = activity.getString(R.string.push_notice_reminder);
                boolean[] shown = {false};
                long end = SystemClock.elapsedRealtime() + 5000;
                while (!shown[0] && SystemClock.elapsedRealtime() < end) {
                    getInstrumentation().waitForIdleSync();
                    runTestOnUiThread(() -> {
                        for (View window : WindowInspector.getGlobalWindowViews()) {
                            if (!hasReminder(window, reminderTitle)) continue;
                            WindowManager.LayoutParams params = (WindowManager.LayoutParams) window.getLayoutParams();
                            assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                            windows.add(window);
                            shown[0] = true;
                        }
                    });
                    if (!shown[0]) SystemClock.sleep(40);
                }
                assertTrue("Authenticated history must open without push enrollment", shown[0]);
                runTestOnUiThread(push::lock);
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> assertFalse(WindowInspector.getGlobalWindowViews().contains(windows.get(windows.size()-1))));
            } finally {
                try {
                    try {
                        MobilePush activePush = pushRef.getAndSet(null);
                        if (activePush != null) {
                            runTestOnUiThread(activePush::lock);
                        }
                    } finally {
                        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                    }
                } finally {
                    accounts.forget(account.id);
                }
            }
        }
    }

    private static boolean hasReminder(View view, String reminderTitle) {
        if (view instanceof ListView list && list.getCount() > 0
                && list.getAdapter().getItem(0).toString().contains(reminderTitle)) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasReminder(group.getChildAt(i), reminderTitle)) return true;
            }
        }
        return false;
    }
}
