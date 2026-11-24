package org.llm4people.hermes.stress;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.doctor.ConnectionDoctor;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.doctor.DoctorCheck;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.update.UpdatePanel;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Exhaustive mechanical UI, button, layout-geometry, viewport-matrix, rapid-tap,
 * lock-mid-action, and adversarial-input stress suite across Hermes Mobile surfaces.
 */
@SuppressWarnings("deprecation")
public final class ExhaustiveUiAndLayoutStressTest extends InstrumentationTestCase {

    private record ViewportSpec(String name, int widthDp, int heightDp, float fontScale, boolean nightMode) {}

    private static final ViewportSpec[] VIEWPORT_MATRIX = {
            new ViewportSpec("compact-phone", 320, 568, 1.0f, false),
            new ViewportSpec("standard-phone", 412, 915, 1.0f, false),
            new ViewportSpec("tablet-1200x800", 1200, 800, 1.0f, false),
            new ViewportSpec("landscape-phone", 800, 360, 1.0f, false),
            new ViewportSpec("large-font-1.5x", 412, 915, 1.5f, false),
            new ViewportSpec("night-mode", 412, 915, 1.0f, true)
    };

    private static final String[] ADVERSARIAL_STRINGS = {
            "",
            "   \t\n  ",
            "A".repeat(4096),
            "مرحبا بالعالم \u202Ereversed-bidi\u202C שלום עולם",
            "日本語🚀🔥👩🏽‍💻Z\u0301a\u0302l\u0303g\u0304o\u0305",
            "' OR 1=1; DROP TABLE sessions; --",
            "<script>alert('xss')</script><img src=x onerror=alert(1)>",
            "../../../etc/passwd",
            "Bearer sk-ant-adversarialSECRET999 ghp_1234567890abcdef token=leak_me"
    };

    private MainActivity launchMain() {
        Context app = getInstrumentation().getTargetContext();
        MainActivity activity = launchActivity(app.getPackageName(), MainActivity.class, null);
        getInstrumentation().waitForIdleSync();
        return activity;
    }

    private void finishMain(Activity activity) throws Throwable {
        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
    }

    public void testAllNativePanelsPassMechanicalLayoutIntegrityAcrossViewportFontAndNightMatrix() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = launchMain();

        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, "http://127.0.0.1:1/p/stress")) {
            Configuration originalConfig = new Configuration(activity.getResources().getConfiguration());
            try {
                for (ViewportSpec spec : VIEWPORT_MATRIX) {
                    ConnectionDoctor doctor = new ConnectionDoctor(stressProbes(spec.name()));
                    ConnectionDoctorPanel doctorPanel = new ConnectionDoctorPanel(activity, doctor, null);
                    UpdatePanel updatePanel = new UpdatePanel(activity);
                    AmbientAccessPanel ambientPanel = new AmbientAccessPanel(
                            activity, NotificationAllowlist.forAccount(profile.account), draft -> {});
                    try {
                        View[] doctorView = new View[1];
                        runTestOnUiThread(() -> {
                            applyConfiguration(activity, spec);
                            doctorView[0] = doctorPanel.buildView();
                            activity.setContentView(doctorView[0]);
                        });
                        waitForCondition(() -> doctorPanel.latestReport() != null, 8000L);
                        runTestOnUiThread(() -> {
                            measureAndLayout(activity, doctorView[0], spec);
                            assertLayoutIntegrity(activity, doctorView[0], "ConnectionDoctor/" + spec.name(), true);

                            // Expand technical details and re-verify geometry with preview & copy button visible.
                            Button tech = findButtonContaining(doctorView[0],
                                    activity.getString(R.string.doctor_technical_show));
                            assertNotNull("Technical toggle missing in " + spec.name(), tech);
                            assertTrue(tech.performClick());
                            measureAndLayout(activity, doctorView[0], spec);
                            assertLayoutIntegrity(activity, doctorView[0],
                                    "ConnectionDoctor-Tech/" + spec.name(), true);
                        });

                        // UpdatePanel geometry check
                        runTestOnUiThread(() -> {
                            View updateView = updatePanel.buildView();
                            activity.setContentView(updateView);
                            measureAndLayout(activity, updateView, spec);
                            assertLayoutIntegrity(activity, updateView, "UpdatePanel/" + spec.name(), false);
                        });

                        // AmbientAccessPanel geometry check
                        runTestOnUiThread(() -> {
                            View ambientView = ambientPanel.buildView();
                            activity.setContentView(ambientView);
                            measureAndLayout(activity, ambientView, spec);
                            assertLayoutIntegrity(activity, ambientView, "AmbientAccessPanel/" + spec.name(), false);
                        });
                    } finally {
                        runTestOnUiThread(() -> {
                            doctorPanel.close();
                            updatePanel.close();
                            ambientPanel.close();
                        });
                    }
                }
            } finally {
                runTestOnUiThread(() -> restoreConfiguration(activity, originalConfig));
            }
        } finally {
            finishMain(activity);
        }
    }

    public void testAllSessionPanelsHandlePopulatedEmptyErrorAndLockMidActionCleanly() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = launchMain();

        try {
            // Phase 1: Populated full-capability companion gateway + layout assertions on every panel.
            try (DynamicLoopbackServer gateway = new DynamicLoopbackServer(ExhaustiveUiAndLayoutStressTest::populatedReply);
                 WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
                SessionPanel panel = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
                try {
                    exercisePanel("Memory", () -> panel.showMemory(null), activity,
                            activity.getString(R.string.brain_memory_title));
                    exercisePanel("Notes", () -> panel.showNotes(() -> true, d -> {}, null), activity,
                            activity.getString(R.string.brain_notes_title));
                    exercisePanel("SharedWithMe", () -> panel.showSharedWithMe(d -> {}, null), activity,
                            activity.getString(R.string.brain_shared_with_me_title));
                    exercisePanel("GlobalSearch", () -> panel.showGlobalSearch(id -> {}, null), activity,
                            activity.getString(R.string.brain_search_title));
                    exercisePanel("SkillStudio", () -> panel.showSkillStudio(() -> true, d -> {}, null), activity,
                            activity.getString(R.string.brain_skills_title));
                    exercisePanel("WorkspaceFiles", () -> panel.showWorkspaceFiles("s1", () -> true, d -> {}, null),
                            activity, activity.getString(R.string.brain_files_title));
                    exercisePanel("ProfileDefaults", () -> panel.showProfileDefaults(null), activity,
                            activity.getString(R.string.brain_profile_defaults_title));
                    exercisePanel("Usage-NoSession", () -> panel.showStats(null, null), activity,
                            activity.getString(R.string.panel_stats));
                    exercisePanel("Usage-ActiveSession", () -> panel.showStats("s1", "s1"), activity,
                            activity.getString(R.string.panel_stats));
                    exercisePanel("Reminders", panel::showReminders, activity,
                            activity.getString(R.string.panel_reminders));
                    exercisePanel("Skills", () -> panel.showSkills(() -> true, d -> {}), activity,
                            activity.getString(R.string.skills_title));
                } finally {
                    runTestOnUiThread(panel::close);
                }
            }

            // Phase 2: All HTTP 500 error responses + rapid switching + lock-mid-action cleanup.
            try (DynamicLoopbackServer failing = new DynamicLoopbackServer(path ->
                    new HttpReply("500 Internal Server Error", "{\"error\":{\"code\":\"server_boom\"}}"));
                 WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, failing.endpoint())) {
                SessionPanel panel = new SessionPanel(activity, new HermesApi(failing.endpoint(), "test"), profile.account);
                try {
                    runTestOnUiThread(() -> {
                        panel.showMemory(null);
                        panel.showNotes(() -> true, d -> {}, null);
                        panel.showGlobalSearch(id -> {}, null);
                        panel.showSkillStudio(() -> true, d -> {}, null);
                        panel.showWorkspaceFiles("s1", () -> true, d -> {}, null);
                        panel.showProfileDefaults(null);
                        panel.showStats(null, null);
                        panel.showReminders();
                    });
                    waitForCondition(() -> failing.handledRequests() >= 8, 8_000L);
                    getInstrumentation().waitForIdleSync();
                    // Simulate immediate profile lock mid-flight while a fresh worker request is active.
                    runTestOnUiThread(() -> {
                        panel.showMemory(null);
                        panel.close();
                    });
                    getInstrumentation().waitForIdleSync();
                    waitForCondition(() -> shownOtherWindowsOnUi(activity) == 0, 5_000L);
                    runTestOnUiThread(() -> {
                        assertFalse("Activity must stay healthy after error burst and lock-mid-action",
                                activity.isFinishing());
                        assertEquals("No leaked dialog windows may remain after panel.close()",
                                0, shownOtherWindows(activity));
                    });
                } finally {
                    runTestOnUiThread(panel::close);
                }
            }
        } finally {
            finishMain(activity);
        }
    }

    public void testAdversarialInputsAndRapidDoubleTapsPreserveSecurityAndLayoutStability() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Activity activity = launchMain();

        try {
            for (String payload : ADVERSARIAL_STRINGS) {
                String sanitizedHost = DoctorCheck.sanitizeHost(payload);
                assertEquals("Hostile input must not pass host sanitization: " + payload, "none", sanitizedHost);
                String redacted = DoctorCheck.redactSecrets(payload);
                assertFalse("Bearer secret leaked", redacted.contains("sk-ant-adversarialSECRET999"));
                assertFalse("GitHub token leaked", redacted.contains("ghp_1234567890abcdef"));
                assertFalse("Query token leaked", redacted.contains("leak_me"));
            }

            // Render ConnectionDoctorPanel with adversarial server values & rapid-click every visible button.
            ConnectionDoctor doctor = new ConnectionDoctor(adversarialProbes());
            ConnectionDoctorPanel panel = new ConnectionDoctorPanel(activity, doctor, null);
            try {
                View[] rootRef = new View[1];
                runTestOnUiThread(() -> {
                    rootRef[0] = panel.buildView();
                    activity.setContentView(rootRef[0]);
                });
                waitForCondition(() -> panel.latestReport() != null, 8000L);

                DoctorCheck.Report report = panel.latestReport();
                assertNotNull(report);
                String safeText = report.toSafeReportText();
                assertFalse(safeText.contains("sk-ant-adversarialSECRET999"));
                assertFalse(safeText.contains("ghp_1234567890abcdef"));
                assertFalse(safeText.contains("leak_me"));

                runTestOnUiThread(() -> {
                    measureAndLayout(activity, rootRef[0], VIEWPORT_MATRIX[0]);
                    assertLayoutIntegrity(activity, rootRef[0], "Adversarial-Compact", true);
                    List<Button> buttons = collectButtons(rootRef[0]);
                    assertFalse("Expected interactive buttons", buttons.isEmpty());
                    for (Button b : buttons) {
                        b.performClick();
                        b.performClick();
                    }
                    panel.dismissDialogs();
                });
            } finally {
                runTestOnUiThread(panel::close);
            }
        } finally {
            finishMain(activity);
        }
    }

    public void testUnlockedMainActivityShellTabsPassLayoutIntegrityAcrossPhoneAndTablet() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        MainActivity activity = launchMain();

        try (DynamicLoopbackServer gateway = new DynamicLoopbackServer(ExhaustiveUiAndLayoutStressTest::populatedReply);
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
            Method opened = MainActivity.class.getDeclaredMethod("opened", Account.class);
            opened.setAccessible(true);
            runTestOnUiThread(() -> {
                try {
                    opened.invoke(activity, profile.account);
                } catch (ReflectiveOperationException err) {
                    throw new AssertionError(err);
                }
            });
            getInstrumentation().waitForIdleSync();
            waitForCondition(() -> {
                boolean[] ready = new boolean[1];
                try {
                    runTestOnUiThread(() -> {
                        View tab = activity.getWindow().getDecorView().findViewById(R.id.nav_tab_discussions);
                        ready[0] = tab != null && tab.getWidth() > 0 && tab.getHeight() > 0;
                    });
                } catch (Throwable ignored) {
                    return false;
                }
                return ready[0];
            }, 5_000L);

            int[] tabIds = {
                    R.id.nav_tab_discussions,
                    R.id.nav_tab_tasks,
                    R.id.nav_tab_apps,
                    R.id.top_settings_action
            };
            for (int tabId : tabIds) {
                runTestOnUiThread(() -> {
                    View decor = activity.getWindow().getDecorView();
                    View tab = decor.findViewById(tabId);
                    assertNotNull("Expected primary navigation tab " + tabId, tab);
                    assertTrue(tab.getWidth() >= Ui.dp(activity, 48) - 2);
                    assertTrue(tab.getHeight() >= Ui.dp(activity, 48) - 2);
                    assertTrue(tab.performClick());
                });
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> {
                    View content = activity.findViewById(android.R.id.content);
                    assertNotNull(content);
                    assertLayoutIntegrity(activity, content, "MainActivity-tab-" + tabId, false);
                });
            }
        } finally {
            finishMain(activity);
        }
    }

    private void exercisePanel(String name, Runnable open, Activity activity, String expectedTitle) throws Throwable {
        runTestOnUiThread(open);
        waitForWindowText(expectedTitle, 10_000L);
        getInstrumentation().waitForIdleSync();
        long settleDeadline = SystemClock.elapsedRealtime() + 2_000L;
        while (SystemClock.elapsedRealtime() < settleDeadline) {
            boolean[] pending = new boolean[1];
            runTestOnUiThread(() -> {
                View top = topFocusedWindow();
                pending[0] = top != null && top.isLayoutRequested();
            });
            if (!pending[0]) break;
            getInstrumentation().waitForIdleSync();
            SystemClock.sleep(40);
        }
        runTestOnUiThread(() -> {
            View top = topFocusedWindow();
            assertNotNull("Expected focused dialog window for " + name, top);
            assertLayoutIntegrity(activity, top, "SessionPanel/" + name, false);
        });
    }

    private void waitForWindowText(String expected, long timeoutMs) throws Throwable {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        boolean[] found = new boolean[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            runTestOnUiThread(() -> {
                View top = topFocusedWindow();
                found[0] = top != null && containsText(top, expected);
            });
            if (found[0]) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for panel heading: " + expected);
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        }
        fail("Timed out waiting for async condition");
    }

    /**
     * Mechanical geometric layout auditor:
     * 1. Zero zero-size visible buttons, switches, or edit fields.
     * 2. >= 48dp touch height (and width when strictMinWidth is enabled) on visible clickable buttons.
     * 3. Zero horizontal child overflow outside horizontal scrollers.
     * 4. Zero collapsed non-empty visible TextViews.
     */
    private static void assertLayoutIntegrity(Context context, View root, String tag, boolean strictMinWidth) {
        assertNotNull("Null root for " + tag, root);
        assertTrue("Root width collapsed in " + tag, root.getWidth() > 0);
        assertTrue("Root height collapsed in " + tag, root.getHeight() > 0);
        int minTouchPx = Ui.dp(context, 48) - 2;
        List<String> violations = new ArrayList<>();
        auditNode(root, false, minTouchPx, strictMinWidth, violations);
        if (!violations.isEmpty()) {
            fail("Layout integrity violations in " + tag + " (" + violations.size() + "): "
                    + String.join("; ", violations.subList(0, Math.min(8, violations.size()))));
        }
    }

    private static void auditNode(View view, boolean insideHorizontalScroller, int minTouchPx,
                                  boolean strictMinWidth, List<String> violations) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        int w = view.getWidth();
        int h = view.getHeight();

        if (view instanceof Button || view instanceof ImageButton || view instanceof Switch
                || view instanceof EditText) {
            if (w <= 0 || h <= 0) {
                violations.add("zero-size control " + view.getClass().getSimpleName() + " (" + w + "x" + h + ")");
            }
        }
        if ((view instanceof Button || view instanceof ImageButton) && view.isClickable() && view.isEnabled()) {
            if (h < minTouchPx) {
                violations.add("touch height <48dp on " + describeControl(view) + " (" + h + "px < " + minTouchPx + "px)");
            }
            if (strictMinWidth && w < minTouchPx) {
                violations.add("touch width <48dp on " + describeControl(view) + " (" + w + "px < " + minTouchPx + "px)");
            }
        }
        if (view instanceof TextView tv && !(view instanceof Button)) {
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0 && (w <= 0 || h <= 0)) {
                violations.add("collapsed TextView \"" + truncate(text.toString()) + "\" (" + w + "x" + h + ")");
            }
        }

        if (view instanceof ViewGroup group) {
            boolean childHorizontal = insideHorizontalScroller || (group instanceof HorizontalScrollView);
            int parentWidth = group.getWidth();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (!childHorizontal && parentWidth > 0 && child.getRight() > parentWidth + 3) {
                    violations.add("horizontal overflow child=" + describeControl(child)
                            + " right=" + child.getRight() + " > parentWidth=" + parentWidth);
                }
                auditNode(child, childHorizontal, minTouchPx, strictMinWidth, violations);
            }
        }
    }

    private static String describeControl(View view) {
        if (view instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0) {
            return view.getClass().getSimpleName() + "('" + truncate(tv.getText().toString()) + "')";
        }
        if (view.getContentDescription() != null && view.getContentDescription().length() > 0) {
            return view.getClass().getSimpleName() + "[cd='" + truncate(view.getContentDescription().toString()) + "']";
        }
        return view.getClass().getSimpleName();
    }

    private static String truncate(String s) {
        String clean = s.replace('\n', ' ').trim();
        return clean.length() > 28 ? clean.substring(0, 28) + "…" : clean;
    }

    private static void applyConfiguration(Activity activity, ViewportSpec spec) {
        Configuration cfg = new Configuration(activity.getResources().getConfiguration());
        cfg.fontScale = spec.fontScale();
        cfg.screenWidthDp = spec.widthDp();
        cfg.screenHeightDp = spec.heightDp();
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (spec.nightMode() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        activity.getResources().updateConfiguration(cfg, activity.getResources().getDisplayMetrics());
    }

    private static void restoreConfiguration(Activity activity, Configuration original) {
        activity.getResources().updateConfiguration(original, activity.getResources().getDisplayMetrics());
    }

    private static void measureAndLayout(Context context, View root, ViewportSpec spec) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int widthPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, spec.widthDp(), dm));
        int heightPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, spec.heightDp(), dm));
        root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, widthPx, heightPx);
    }

    private static View topFocusedWindow() {
        List<View> windows = WindowInspector.getGlobalWindowViews();
        for (int i = windows.size() - 1; i >= 0; i--) {
            View root = windows.get(i);
            if (root.isShown() && root.hasWindowFocus()) return root;
        }
        return null;
    }

    private static int shownOtherWindows(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int count = 0;
        for (View w : WindowInspector.getGlobalWindowViews()) {
            if (w != decor && w.isShown()) count++;
        }
        return count;
    }

    private int shownOtherWindowsOnUi(Activity activity) {
        int[] count = new int[1];
        try {
            runTestOnUiThread(() -> count[0] = shownOtherWindows(activity));
        } catch (Throwable error) {
            throw new AssertionError("Failed to inspect windows on UI thread", error);
        }
        return count[0];
    }

    private static boolean containsText(View v, String expected) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof TextView tv && tv.getText() != null && tv.getText().toString().contains(expected)) {
            return true;
        }
        if (v instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    private static Button findButtonContaining(View v, String text) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof Button b && b.getText() != null && b.getText().toString().contains(text)) return b;
        if (v instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButtonContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Button> collectButtons(View v) {
        List<Button> out = new ArrayList<>();
        collectButtonsRec(v, out);
        return out;
    }

    private static void collectButtonsRec(View v, List<Button> out) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof Button b) out.add(b);
        if (v instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectButtonsRec(group.getChildAt(i), out);
            }
        }
    }

    private static ConnectionDoctor.Probes stressProbes(String label) {
        return new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://home.example.com:8443/p/" + label; }
            @Override public String appVersion() { return "1.6.0"; }
            @Override public String languageTag() { return "en-US"; }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                return new ConnectionDoctor.ServerProbe(true, false, false, 44L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return new ConnectionDoctor.CapabilitiesProbe(true, false, 51, 51, true);
            }
            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(false, true);
            }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("1.6.0", true, "v2.1.0", true);
            }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(true, false);
            }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("en-US", "en-US", true);
            }
        };
    }

    private static ConnectionDoctor.Probes adversarialProbes() {
        return new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() {
                return "https://user:pw@home.example.com/p/secret?token=leak_me";
            }
            @Override public String appVersion() {
                return "1.0.0 Bearer sk-ant-adversarialSECRET999 <script>alert(1)</script>";
            }
            @Override public String languageTag() {
                return "en-US ghp_1234567890abcdef \u202Ertl";
            }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                return new ConnectionDoctor.ServerProbe(true, false, false, 1850L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return new ConnectionDoctor.CapabilitiesProbe(true, true, 0, 51, false);
            }
            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(false, false);
            }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("1.0.0", true,
                        "v2.0.0 ' OR 1=1; DROP TABLE sessions;--", true);
            }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(false, false);
            }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("مرحبا\u202E", "fr", false);
            }
        };
    }

    private record HttpReply(String status, String body) {}

    private static HttpReply populatedReply(String path) {
        if (path.contains("/v1/capabilities")) {
            return new HttpReply("200 OK", """
                    {"features":{"memory_api":true,"smart_notes":true,"universal_sharing":true,
                    "session_search":true,"skill_studio":true,"workspace_files":true,
                    "profile_defaults":true,"usage_analytics":true}}""");
        }
        if (path.contains("/api/memory") || path.contains("/v1/memory")) {
            return new HttpReply("200 OK", """
                    {"memory":{"entries":[{"id":"m1","text":"Prefers concise answers","pinned":true}],
                    "chars":23,"limit":2200,"enabled":true},
                    "user":{"entries":[{"id":"u1","text":"Lives in Montreal","pinned":false}],
                    "chars":17,"limit":1375,"enabled":true}}""");
        }
        if (path.contains("/api/notes") || path.contains("/v1/notes")) {
            return new HttpReply("200 OK", """
                    {"object":"list","data":[{"id":"n1","title":"Weekly plan","body":"Ship release APK",
                    "tags":["work"],"pinned":true,"updated_at":1700000000}]}""");
        }
        if (path.contains("/api/shares") || path.contains("/v1/shares")) {
            return new HttpReply("200 OK", """
                    {"object":"list","data":[{"id":"sh1","kind":"note","title":"Shared checklist",
                    "from_profile":"allison","preview":"Verify release checksum"}]}""");
        }
        if (path.contains("/api/sessions/search")) {
            return new HttpReply("200 OK", """
                    {"data":[{"session_id":"s1","title":"Release thread","snippet":"Signed APK ready"}]}""");
        }
        if (path.contains("/api/skills") || path.contains("/v1/skills")) {
            return new HttpReply("200 OK", """
                    {"object":"list","data":[{"name":"code/review","description":"Check PR diffs",
                    "category":"Coding","enabled":true,"writable":true,"source":"user"}]}""");
        }
        if (path.contains("/workspace/tree")) {
            return new HttpReply("200 OK", """
                    {"root":"workspace","entries":[{"path":"README.md","kind":"file","size_bytes":512,
                    "language":"markdown","git_status":"M"}]}""");
        }
        if (path.contains("/api/profile") || path.contains("/v1/profile")) {
            return new HttpReply("200 OK", """
                    {"name":"default","description":"Main profile","default_provider":"openai",
                    "default_model":"gpt-5","enabled_toolsets":["web","memory"],"reasoning_effort":"medium",
                    "memory_enabled":true,"voice_enabled":true,"soul_preview":"Helpful companion"}""");
        }
        if (path.contains("/api/analytics/usage")) {
            return new HttpReply("200 OK", """
                    {"days":30,"actual_cost_usd":1.25,"estimated_cost_usd":1.40,"avg_ttft_ms":380,
                    "avg_tps":42.5,"sessions":7,"api_calls":24,"input_tokens":8000,"output_tokens":3200}""");
        }
        if (path.contains("/api/sessions/s1/messages")) {
            return new HttpReply("200 OK", """
                    {"session_id":"s1","messages":[]}""");
        }
        if (path.contains("/api/sessions/s1")) {
            return new HttpReply("200 OK", """
                    {"session":{"id":"s1","title":"Release thread","input_tokens":1200,"output_tokens":400,
                    "actual_cost_usd":0.12,"estimated_cost_usd":0.15}}""");
        }
        if (path.contains("/api/sessions")) {
            return new HttpReply("200 OK", """
                    {"sessions":[{"id":"s1","title":"Release thread","updated_at":1700000000}]}""");
        }
        if (path.contains("/api/cron")) {
            return new HttpReply("200 OK", """
                    {"jobs":[{"id":"j1","name":"Morning briefing","schedule":"0 8 * * *","enabled":true}]}""");
        }
        return new HttpReply("200 OK", "{}");
    }

    private static final class DynamicLoopbackServer implements Closeable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "hermes-stress-http");
            t.setDaemon(true);
            return t;
        });
        private final AtomicInteger handledCount = new AtomicInteger();
        private volatile boolean closed;

        DynamicLoopbackServer(Function<String, HttpReply> router) throws IOException {
            listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!closed) {
                    try {
                        Socket peer = listener.accept();
                        executor.submit(() -> handle(peer, router));
                    } catch (IOException stopped) {
                        return;
                    }
                }
            });
        }

        private void handle(Socket peer, Function<String, HttpReply> router) {
            try (Socket s = peer) {
                s.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String requestLine = reader.readLine();
                String path = "/";
                if (requestLine != null) {
                    String[] parts = requestLine.split(" ");
                    if (parts.length >= 2) path = parts[1];
                }
                int contentLength = 0;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                if (contentLength > 0) {
                    char[] discard = new char[Math.min(contentLength, 4096)];
                    int rem = contentLength;
                    while (rem > 0) {
                        int n = reader.read(discard, 0, Math.min(rem, discard.length));
                        if (n < 0) break;
                        rem -= n;
                    }
                }
                HttpReply reply = router.apply(path);
                byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 " + reply.status() + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + payload.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = s.getOutputStream();
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(payload);
                out.flush();
                handledCount.incrementAndGet();
            } catch (IOException ignored) {
            }
        }

        int handledRequests() {
            return handledCount.get();
        }

        String endpoint() {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/p/stress";
        }

        @Override
        public void close() throws IOException {
            closed = true;
            listener.close();
            executor.shutdownNow();
        }
    }
}
