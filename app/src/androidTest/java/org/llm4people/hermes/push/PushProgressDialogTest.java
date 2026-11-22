package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;

import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PushProgressDialogTest extends InstrumentationTestCase {
    public void testBackDuringDiscoveryAndHistorySuppressesSuccessAndFailure() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            for (boolean history : new boolean[]{false, true}) {
                for (boolean success : new boolean[]{false, true}) {
                    backBeforeReply(context, activity, history, success);
                }
            }
        } finally { runTestOnUiThread(activity::finish); }
    }

    private void backBeforeReply(Context context, Activity activity, boolean history, boolean success) throws Throwable {
        String body = history ? "{\"events\":[],\"next_cursor\":0}" : "{}";
        try (Exchange bridge = new Exchange(success ? "200 OK" : "503 Unavailable", "", body);
             WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context, bridge.endpoint())) {
            MobilePush push = new MobilePush(activity);
            CountDownLatch release = new CountDownLatch(1);
            PushConnection.WORK.submit(() -> release.await(20, TimeUnit.SECONDS));
            List<View> initial = new ArrayList<>();
            try {
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> {
                    initial.addAll(WindowInspector.getGlobalWindowViews());
                    push.settings(fixture.account);
                    Button start = dialogWindow(initial).findViewById(history ? android.R.id.button3 : android.R.id.button1);
                    assertEquals(history ? "Historique" : "Activer", start.getText().toString());
                    start.performClick();
                });
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> {
                    TextView message = dialogWindow(initial).findViewById(android.R.id.message);
                    assertEquals(history ? "Chargement…" : "Recherche des services de notification…",
                            message.getText().toString());
                });
                getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> assertEquals(initial, WindowInspector.getGlobalWindowViews()));
                release.countDown();
                PushConnection.WORK.submit(() -> {}).get(10, TimeUnit.SECONDS);
                assertTrue(bridge.observed().contains(history ? "/api/mobile/push/events?" : "/api/mobile/push "));
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> assertEquals(initial, WindowInspector.getGlobalWindowViews()));
            } finally {
                release.countDown();
                runTestOnUiThread(push::lock);
            }
        }
    }

    private static View dialogWindow(List<View> initial) {
        List<View> windows = new ArrayList<>(WindowInspector.getGlobalWindowViews());
        windows.removeAll(initial);
        assertEquals(1, windows.size());
        return windows.get(0);
    }
}
