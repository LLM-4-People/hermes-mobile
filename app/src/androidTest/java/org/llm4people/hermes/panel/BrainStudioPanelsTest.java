package org.llm4people.hermes.panel;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Switch;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class BrainStudioPanelsTest extends InstrumentationTestCase {
    private static final String JSON = "Content-Type: application/json\r\n";

    public void testMemoryPanelDegradesCleanlyOnLegacyGateway() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        try (Exchange legacy = new Exchange("200 OK", JSON, "{\"features\":{}}");
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, legacy.endpoint())) {
            SessionPanel panel = new SessionPanel(activity, new HermesApi(legacy.endpoint(), "test"), profile.account);
            // Regression guard: brain_* surfaces are not in WorkspaceLayout.SURFACES and must mount
            // cleanly even when MainActivity attaches a non-null ArtifactDesk.Host.
            panel.setLayoutHost(new ArtifactDesk.Host() {
                @Override public java.util.function.BooleanSupplier deviceActionOrigin() { return () -> false; }
                @Override public void onAction(String id) {}
                @Override public void onLayoutChanged() {}
                @Override public void openWorkspace(String pageId) {}
                @Override public void stageDraft(String text) {}
                @Override public void threadSlots(List<String> order) {}
                @Override public String workspaceValue(String source) { return ""; }
            });
            try {
                runTestOnUiThread(() -> panel.showMemory(null));
                assertTextVisible(activity.getString(R.string.brain_memory_title));
                assertTextVisible(activity.getString(R.string.brain_upgrade_required));
            } finally {
                runTestOnUiThread(panel::close);
            }
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    public void testSkillStudioRollsBackSwitchWhenServerRejectsToggle() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        String caps = "{\"features\":{\"skill_studio\":true}}";
        String list = """
                {"object":"list","data":[
                  {"name":"travail/revue","description":"Relire du code","category":"Qualité","enabled":true,"writable":true,"source":"user"}]}
                """;
        String toggleError = "{\"error\":{\"code\":\"toggle_failed\"}}";
        try (MultiStatusServer gateway = new MultiStatusServer(
                new String[]{"200 OK", "200 OK", "500 Internal Server Error"},
                new String[]{caps, list, toggleError})) {
            try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
                SessionPanel panel = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
                try {
                    runTestOnUiThread(() -> panel.showSkillStudio(() -> true, draft -> {}, null));
                    assertTextVisible("travail/revue");
                    Switch[] toggleRef = new Switch[1];
                    runTestOnUiThread(() -> {
                        for (View root : WindowInspector.getGlobalWindowViews()) {
                            Switch sw = findFirst(root, Switch.class);
                            if (sw != null && sw.isShown()) {
                                toggleRef[0] = sw;
                                assertTrue(sw.isChecked());
                                sw.performClick();
                                break;
                            }
                        }
                    });
                    assertNotNull("Expected skill toggle Switch", toggleRef[0]);
                    // Deterministic HTTP 500 on PUT /v1/skills/travail/revue/enabled -> switch MUST roll back to checked=true.
                    assertTextVisible(activity.getString(R.string.brain_skills_toggle_failed));
                    runTestOnUiThread(() -> assertTrue("Switch did not roll back", toggleRef[0].isChecked()));
                } finally {
                    runTestOnUiThread(panel::close);
                }
            }
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    /** Minimal deterministic loopback HTTP server supporting per-reply status codes. */
    private static final class MultiStatusServer implements Closeable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        MultiStatusServer(String[] statuses, String[] bodies) throws IOException {
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                for (int i = 0; i < statuses.length; i++) {
                    try (Socket peer = listener.accept()) {
                        peer.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
                        int contentLength = 0;
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            String lower = line.toLowerCase(java.util.Locale.ROOT);
                            if (lower.startsWith("content-length:")) {
                                contentLength = Integer.parseInt(line.substring(15).trim());
                            }
                        }
                        if (contentLength > 0) {
                            char[] discard = new char[contentLength];
                            int remaining = contentLength;
                            while (remaining > 0) {
                                int read = reader.read(discard, 0, remaining);
                                if (read < 0) break;
                                remaining -= read;
                            }
                        }
                        byte[] payload = bodies[i].getBytes(StandardCharsets.UTF_8);
                        String head = "HTTP/1.1 " + statuses[i] + "\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Content-Length: " + payload.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        OutputStream out = peer.getOutputStream();
                        out.write(head.getBytes(StandardCharsets.US_ASCII));
                        out.write(payload);
                        out.flush();
                    } catch (IOException stopped) {
                        return;
                    }
                }
            });
        }

        String endpoint() {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/p/travail";
        }

        @Override public void close() throws IOException {
            listener.close();
            executor.shutdownNow();
        }
    }

    public void testUsagePanelPreservesUnknownMetricsAsIndisponible() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        String messages = "{\"session_id\":\"s1\",\"data\":[]}";
        String session = "{\"session\":{\"id\":\"s1\",\"input_tokens\":10,\"cache_read_tokens\":0,\"cache_write_tokens\":0,\"output_tokens\":20,\"cost_status\":\"unknown\",\"estimated_cost_usd\":0}}";
        String caps = "{\"features\":{}}";
        try (Exchange gateway = new Exchange("200 OK", JSON, messages, session, caps);
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
            // Timing with elapsed_ms only (missing first_ms & output_tokens -> TTFT & speed MUST stay Indisponible).
            profile.account.write("panel/timing/s1", "{\"elapsed_ms\":1200}");
            SessionPanel panel = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
            try {
                runTestOnUiThread(() -> panel.showStats("s1", "s1"));
                assertTextVisible(activity.getString(R.string.brain_usage_response_time));
                assertTextVisible(activity.getString(R.string.brain_usage_speed));
                assertTextVisible(activity.getString(R.string.ws_unavailable));
                assertTextVisible(activity.getString(R.string.brain_usage_technical_toggle));
                assertTextVisible(activity.getString(R.string.panel_out));
            } finally {
                runTestOnUiThread(panel::close);
            }
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    public void testUsagePanelShowsProfileWideAnalyticsWithoutActiveSession() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        String caps = "{\"features\":{\"usage_analytics\":true}}";
        String analytics = "{\"days\":30,\"sessions\":7,\"api_calls\":19,\"input_tokens\":1200,\"output_tokens\":450,\"actual_cost_usd\":0.12}";
        try (Exchange gateway = new Exchange("200 OK", JSON, caps, analytics);
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
            SessionPanel panel = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
            try {
                runTestOnUiThread(() -> panel.showStats(null, null));
                assertTextVisible(activity.getString(R.string.brain_usage_profile_analytics));
                assertTextVisible(activity.getString(R.string.brain_usage_profile_runs));
                assertTextVisible("7");
                assertTextVisible(activity.getString(R.string.panel_out));
            } finally {
                runTestOnUiThread(panel::close);
            }
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    public void testNotesPanelDeepLinkOneShotUnknownBlankAndLoadErrorPreservation() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        String caps = "{\"features\":{\"smart_notes\":true}}";
        String notesJson = """
                {"notes":[
                  {"id":"n-1","title":"Plan produit","content":"Contenu détaillé de la note cible","tags":["produit"],"pinned":false,"permission":"owner"},
                  {"id":"n-2","title":"Courses","content":"Pain et lait","tags":[],"pinned":false,"permission":"owner"}]}
                """;
        String refreshedNotesJson = """
                {"notes":[
                  {"id":"n-1","title":"Plan produit v2","content":"Contenu détaillé de la note cible","tags":["produit"],"pinned":false,"permission":"owner"},
                  {"id":"n-2","title":"Courses","content":"Pain et lait","tags":[],"pinned":false,"permission":"owner"}]}
                """;
        // 1) Matching initialNoteId opens CodeReader once and does NOT reopen on refresh (one-shot);
        // 2) unknown/blank IDs render list cleanly with no CodeReader dialog;
        // 3) server 500 error keeps error + Retry visible even when typing in the filter field.
        try (MultiStatusServer gateway = new MultiStatusServer(
                new String[]{
                        "200 OK", "200 OK",
                        "200 OK", "200 OK",
                        "200 OK", "200 OK",
                        "200 OK", "200 OK",
                        "200 OK", "500 Internal Server Error"
                },
                new String[]{
                        caps, notesJson,
                        caps, refreshedNotesJson,
                        caps, notesJson,
                        caps, notesJson,
                        caps, "{\"error\":\"boom\"}"
                });
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
            SessionPanel panel = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
            String readerMarker = activity.getString(R.string.code_copy_content);
            try {
                // Positive + one-shot: initialNoteId opens target note CodeReader dialog directly;
                // dismissing ONLY the CodeReader dialog and refreshing must NOT re-open CodeReader.
                runTestOnUiThread(() -> panel.showNotes(() -> true, draft -> {}, null, "n-1"));
                assertTextVisible(readerMarker);
                assertTextVisible("Contenu détaillé de la note cible");
                clickVisibleButtonInWindow(readerMarker, activity.getString(R.string.ws_close));
                waitUntilTextNotVisible(readerMarker);
                clickVisibleButton(activity.getString(R.string.panel_refresh));
                assertTextVisible("Plan produit v2");
                assertTextNotVisible(readerMarker);
                runTestOnUiThread(panel::close);

                // Negative: unknown initialNoteId shows notes list without opening CodeReader or crashing.
                runTestOnUiThread(() -> panel.showNotes(() -> true, draft -> {}, null, "missing-id"));
                assertTextVisible("Plan produit");
                assertTextVisible("Courses");
                assertTextNotVisible(readerMarker);
                runTestOnUiThread(panel::close);

                // Negative: blank initialNoteId renders list cleanly without opening CodeReader.
                runTestOnUiThread(() -> panel.showNotes(() -> true, draft -> {}, null, "   "));
                assertTextVisible("Plan produit");
                assertTextNotVisible(readerMarker);
                runTestOnUiThread(panel::close);

                // Negative: HTTP 500 failure + Retry stays visible when user types in filter field.
                runTestOnUiThread(() -> panel.showNotes(() -> true, draft -> {}, null, "n-1"));
                assertTextVisible(activity.getString(R.string.panel_retry));
                assertTextNotVisible(readerMarker);
                runTestOnUiThread(() -> {
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        View filter = root.findViewById(R.id.brain_notes_filter);
                        if (filter instanceof android.widget.EditText et) {
                            et.setText("plan");
                            break;
                        }
                    }
                });
                assertTextVisible(activity.getString(R.string.panel_retry));
                assertTextNotVisible(readerMarker);
            } finally {
                runTestOnUiThread(panel::close);
            }
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    private void clickVisibleButton(String label) throws Throwable {
        clickVisibleButtonInWindow(null, label);
    }

    private void clickVisibleButtonInWindow(String windowMarker, String label) throws Throwable {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] clicked = {false};
            runTestOnUiThread(() -> {
                List<View> roots = WindowInspector.getGlobalWindowViews();
                for (int i = roots.size() - 1; i >= 0; i--) {
                    View root = roots.get(i);
                    if (windowMarker != null && !windowContainsText(root, windowMarker)) continue;
                    for (android.widget.Button btn : findAll(root, android.widget.Button.class)) {
                        if (btn.isShown() && btn.getText() != null
                                && label.equalsIgnoreCase(btn.getText().toString().trim())) {
                            btn.performClick();
                            clicked[0] = true;
                            return;
                        }
                    }
                }
            });
            if (clicked[0]) {
                getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(100);
        }
        fail("Expected clickable button: " + label);
    }

    private void waitUntilTextNotVisible(String unexpected) throws Throwable {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            getInstrumentation().waitForIdleSync();
            if (!isTextVisibleNow(unexpected)) return;
            SystemClock.sleep(100);
        }
        fail("Unexpected visible text remained: " + unexpected);
    }

    private void assertTextNotVisible(String unexpected) throws Throwable {
        getInstrumentation().waitForIdleSync();
        assertFalse("Unexpected visible text: " + unexpected, isTextVisibleNow(unexpected));
    }

    private boolean isTextVisibleNow(String needle) throws Throwable {
        boolean[] found = {false};
        runTestOnUiThread(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                if (windowContainsText(root, needle)) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    private static boolean windowContainsText(View root, String needle) {
        for (TextView tv : findAll(root, TextView.class)) {
            if (tv.isShown() && tv.getText() != null && tv.getText().toString().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void assertTextVisible(String expected) throws Throwable {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] found = {false};
            runTestOnUiThread(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    for (TextView tv : findAll(root, TextView.class)) {
                        if (tv.isShown() && tv.getText() != null && tv.getText().toString().contains(expected)) {
                            found[0] = true;
                            return;
                        }
                    }
                }
            });
            if (found[0]) return;
            SystemClock.sleep(100);
        }
        fail("Expected visible text: " + expected);
    }

    private static <T extends View> T findFirst(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findFirst(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> List<T> findAll(View root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends View> void collect(View node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) out.add(type.cast(node));
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), type, out);
        }
    }
}
