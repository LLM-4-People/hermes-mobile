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
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.ArrayList;
import java.util.List;

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
            try {
                runTestOnUiThread(() -> panel.showMemory(null));
                assertTextVisible("Mémoire de Hermes");
                assertTextVisible("Cette fonction nécessite une mise à jour de votre serveur Hermes.");
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
        // Third response simulates HTTP 500 on PUT /v1/skills/travail/revue/enabled.
        try (Exchange gateway = new Exchange("200 OK", JSON, caps, list)) {
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
                    // Server has no 3rd response so connection fails -> switch MUST roll back to checked=true.
                    assertTextVisible("Impossible de modifier l’état de ce savoir-faire. L’état précédent a été rétabli.");
                    runTestOnUiThread(() -> assertTrue("Switch did not roll back", toggleRef[0].isChecked()));
                } finally {
                    runTestOnUiThread(panel::close);
                }
            }
        } finally {
            runTestOnUiThread(activity::finish);
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
                assertTextVisible("Temps de réponse");
                assertTextVisible("Vitesse");
                assertTextVisible("Indisponible");
                assertTextVisible("Détails techniques");
                assertTextVisible("Jetons de sortie");
            } finally {
                runTestOnUiThread(panel::close);
            }
        } finally {
            runTestOnUiThread(activity::finish);
        }
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
