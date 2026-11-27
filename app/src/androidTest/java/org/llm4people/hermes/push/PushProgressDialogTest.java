package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class PushProgressDialogTest extends InstrumentationTestCase {
    public void testBackDuringDiscoveryAndHistorySuppressesSuccessAndFailure() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        try {
            for (boolean history : new boolean[]{false, true}) {
                for (boolean success : new boolean[]{false, true}) {
                    backBeforeReply(context, launch, history, success);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private void backBeforeReply(Context context, Intent launch, boolean history, boolean success) throws Throwable {
        String body = history ? "{\"events\":[],\"next_cursor\":0}" : "{}";
        try (Exchange bridge = new Exchange(success ? "200 OK" : "503 Unavailable", "", body);
             WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context, bridge.endpoint())) {
            Activity activity = getInstrumentation().startActivitySync(
                    new Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MobilePush push = new MobilePush(activity);
            CountDownLatch release = new CountDownLatch(1);
            try {
                PushConnection.WORK.submit(() -> { release.await(); return null; });
                List<View> initial = new ArrayList<>();
                View[] progress = new View[1];
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> {
                    initial.addAll(WindowInspector.getGlobalWindowViews());
                    push.settings(fixture.account, null);
                    Button start = dialogWindow(initial).findViewById(history ? android.R.id.button3 : android.R.id.button1);
                    assertEquals(activity.getString(history ? R.string.push_settings_history : R.string.push_settings_enable),
                            start.getText().toString());
                    start.performClick();
                });
                String loading = activity.getString(history
                        ? R.string.push_settings_loading : R.string.push_settings_searching_services);
                awaitWindows("Loading dialog did not acquire input focus", () -> {
                    for (View window : WindowInspector.getGlobalWindowViews()) {
                        if (initial.contains(window) || !window.hasWindowFocus() || !window.isShown() || !window.isLaidOut()) continue;
                        TextView message = window.findViewById(android.R.id.message);
                        if (message != null && loading.contentEquals(message.getText())) {
                            progress[0] = window;
                            return true;
                        }
                    }
                    return false;
                });
                getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                awaitWindows("Back did not close " + loading, () -> !progress[0].isAttachedToWindow()
                        && !WindowInspector.getGlobalWindowViews().contains(progress[0]));
                release.countDown();
                PushConnection.WORK.submit(() -> {}).get(10, TimeUnit.SECONDS);
                assertTrue(bridge.observed().contains(history ? "/api/mobile/push/events?" : "/api/mobile/push "));
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> {
                    for (View window : WindowInspector.getGlobalWindowViews()) {
                        if (!initial.contains(window)) fail("Late push dialog after Back: " + describe(window));
                    }
                });
            } finally {
                release.countDown();
                try {
                    runTestOnUiThread(push::lock);
                } finally {
                    try {
                        PushConnection.WORK.submit(() -> {}).get(10, TimeUnit.SECONDS);
                    } finally {
                        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                    }
                }
            }
        }
    }

    private void awaitWindows(String failure, BooleanSupplier condition) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        boolean[] ready = {false};
        do {
            runTestOnUiThread(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        runTestOnUiThread(() -> {
            List<String> windows = new ArrayList<>();
            for (View window : WindowInspector.getGlobalWindowViews()) windows.add(describe(window));
            fail(failure + " while the HTTP request was gated. Windows: " + windows);
        });
    }

    private static String describe(View window) {
        TextView message = window.findViewById(android.R.id.message);
        return window + " focused=" + window.hasWindowFocus() + " attached=" + window.isAttachedToWindow()
                + " message=" + (message == null ? "" : message.getText());
    }

    private static View dialogWindow(List<View> initial) {
        List<View> windows = new ArrayList<>(WindowInspector.getGlobalWindowViews());
        windows.removeAll(initial);
        assertEquals(1, windows.size());
        return windows.get(0);
    }
}
