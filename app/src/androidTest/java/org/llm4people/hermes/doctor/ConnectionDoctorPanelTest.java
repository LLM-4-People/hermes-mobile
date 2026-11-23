package org.llm4people.hermes.doctor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class ConnectionDoctorPanelTest extends InstrumentationTestCase {

    public void testParseCapabilitiesJsonOnAndroidDetectsForkAndUpstream() throws Exception {
        JSONObject fullFeatures = new JSONObject();
        for (int i = 0; i < 50; i++) fullFeatures.put("cap_" + i, true);
        fullFeatures.put("smart_notes", true);
        ConnectionDoctor.CapabilitiesProbe full = ConnectionDoctor.parseCapabilitiesJson(
                new JSONObject().put("features", fullFeatures));
        assertTrue(full.companionForkDetected());
        assertFalse(full.upstreamNonFork());
        assertEquals(51, full.enabledCount());

        ConnectionDoctor.CapabilitiesProbe upstream = ConnectionDoctor.parseCapabilitiesJson(
                new JSONObject().put("features", new JSONObject()));
        assertTrue(upstream.upstreamNonFork());
        assertFalse(upstream.companionForkDetected());
        assertEquals(0, upstream.enabledCount());
    }

    public void testHealthyCompanionForkRendersCardsAndCopiesRedactedSafeReport() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        ConnectionDoctor doctor = new ConnectionDoctor(healthyProbes());
        ConnectionDoctorPanel panel = new ConnectionDoctorPanel(activity, doctor, null);
        try {
            View[] rootRef = new View[1];
            runTestOnUiThread(() -> {
                rootRef[0] = panel.buildView();
                activity.setContentView(rootRef[0]);
            });

            waitForText(rootRef[0], activity.getString(R.string.doctor_status_all_ok));
            waitForText(rootRef[0], activity.getString(R.string.doctor_check_capabilities_ok, 51, 51));
            waitForText(rootRef[0], activity.getString(R.string.doctor_check_server_ok, 28L));

            // Technical details stay collapsed by default until toggled.
            runTestOnUiThread(() -> {
                Button techBtn = findButton(rootRef[0], activity.getString(R.string.doctor_technical_show));
                assertNotNull("Expected Technical details toggle", techBtn);
                assertTrue(techBtn.performClick());
            });

            waitForText(rootRef[0], activity.getString(R.string.doctor_copy_report_action));
            runTestOnUiThread(() -> {
                Button copyBtn = findButton(rootRef[0], activity.getString(R.string.doctor_copy_report_action));
                assertNotNull("Expected Copy safe report button", copyBtn);
                assertTrue(copyBtn.performClick());
            });

            waitForText(rootRef[0], activity.getString(R.string.doctor_report_copied));
            runTestOnUiThread(() -> {
                ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
                assertNotNull(cm);
                ClipData clip = cm.getPrimaryClip();
                assertNotNull("Expected clipboard text", clip);
                String copied = clip.getItemAt(0).coerceToText(activity).toString();
                assertTrue(copied.contains("host=https://home.example.com:8443"));
                assertTrue(copied.contains("count=51/51, companion_fork=true"));
                assertFalse("Profile path must never leak into clipboard report", copied.contains("secret-profile"));
                assertFalse("Credentials must never leak into clipboard report", copied.contains("supersecret"));
            });
        } finally {
            runTestOnUiThread(() -> {
                panel.close();
                activity.finish();
            });
        }
    }

    public void testUpstreamWarningAndErrorStatesDispatchAllOneTapFixesAndSurviveRapidDoubleTap() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));

        AtomicInteger pushClicks = new AtomicInteger();
        AtomicInteger updateClicks = new AtomicInteger();
        AtomicInteger ambientClicks = new AtomicInteger();
        AtomicInteger langClicks = new AtomicInteger();

        ConnectionDoctorPanel.FixHandlers fixes = new ConnectionDoctorPanel.FixHandlers() {
            @Override public void openPushSettings() { pushClicks.incrementAndGet(); }
            @Override public void openAppUpdates() { updateClicks.incrementAndGet(); }
            @Override public void openAmbientAccess() { ambientClicks.incrementAndGet(); }
            @Override public void openLanguageSettings() { langClicks.incrementAndGet(); }
        };

        ConnectionDoctor doctor = new ConnectionDoctor(degradedProbes());
        ConnectionDoctorPanel panel = new ConnectionDoctorPanel(activity, doctor, fixes);
        try {
            // Verify RouteRegistry registration contract.
            RouteRegistry registry = new RouteRegistry();
            AtomicInteger routeOpened = new AtomicInteger();
            ConnectionDoctorPanel.registerRoute(registry, activity, routeOpened::incrementAndGet);
            List<RouteRegistry.Destination> activite = registry.destinationsIn(
                    RouteRegistry.Section.ACTIVITE, java.util.Collections.emptySet());
            assertEquals(1, activite.size());
            assertEquals(ConnectionDoctorPanel.ROUTE_ID, activite.get(0).id());
            assertTrue(registry.dispatch(ConnectionDoctorPanel.ROUTE_ID));
            assertEquals(1, routeOpened.get());

            View[] rootRef = new View[1];
            runTestOnUiThread(() -> {
                rootRef[0] = panel.buildView();
                activity.setContentView(rootRef[0]);
            });

            waitForText(rootRef[0], activity.getString(R.string.doctor_status_problem));
            waitForText(rootRef[0], activity.getString(R.string.doctor_check_capabilities_upstream, 51));

            runTestOnUiThread(() -> {
                assertClick(rootRef[0], activity.getString(R.string.doctor_fix_push));
                assertClick(rootRef[0], activity.getString(R.string.doctor_fix_updates));
                assertClick(rootRef[0], activity.getString(R.string.doctor_fix_ambient));
                assertClick(rootRef[0], activity.getString(R.string.doctor_fix_language));

                // Rapid double-tap on re-run + immediate dismissDialogs & close mid-flight must never crash.
                Button rerun = findButton(rootRef[0], activity.getString(R.string.doctor_rerun_action));
                assertNotNull(rerun);
                for (int i = 0; i < 6; i++) {
                    rerun.performClick();
                }
                panel.dismissDialogs();
                panel.close();
            });

            assertEquals(1, pushClicks.get());
            assertEquals(1, updateClicks.get());
            assertEquals(1, ambientClicks.get());
            assertEquals(1, langClicks.get());
        } finally {
            runTestOnUiThread(() -> {
                panel.close();
                activity.finish();
            });
        }
    }

    private static ConnectionDoctor.Probes healthyProbes() {
        return new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() {
                return "https://alice:supersecret@home.example.com:8443/p/secret-profile?token=tok123";
            }
            @Override public String appVersion() { return "1.5.0"; }
            @Override public String languageTag() { return "en-US"; }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                return new ConnectionDoctor.ServerProbe(true, false, false, 28L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return new ConnectionDoctor.CapabilitiesProbe(true, false, 51, 51, true);
            }
            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(true, true);
            }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("1.5.0", false, "", true);
            }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(true, true);
            }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("en-US", "en-US, en;q=0.9", true);
            }
        };
    }

    private static ConnectionDoctor.Probes degradedProbes() {
        return new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://upstream.example.com"; }
            @Override public String appVersion() { return "1.4.0"; }
            @Override public String languageTag() { return "fr-CA"; }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                return new ConnectionDoctor.ServerProbe(false, true, false, 15L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return new ConnectionDoctor.CapabilitiesProbe(true, true, 0, 51, false);
            }
            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(false, true);
            }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("1.4.0", true, "v2.0.0", true);
            }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(false, false);
            }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("fr-CA", "en-US", false);
            }
        };
    }

    private void waitForText(View root, String expected) throws Throwable {
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        boolean[] found = new boolean[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            runTestOnUiThread(() -> found[0] = containsText(root, expected));
            if (found[0]) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for text: " + expected);
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

    private static void assertClick(View root, String label) {
        Button button = findButton(root, label);
        assertNotNull("Expected button: " + label, button);
        assertTrue("Button click failed: " + label, button.performClick());
    }

    private static Button findButton(View v, String label) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof Button b && b.getText() != null && label.contentEquals(b.getText())) return b;
        if (v instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
