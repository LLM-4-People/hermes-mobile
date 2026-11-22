package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.llm4people.hermes.Ui;

@SuppressWarnings("deprecation")
public class DeviceReviewTest extends InstrumentationTestCase {
    private static final String QUERY = "Café A & B, Montréal";
    private static final String REQUEST = "{\"kind\":\"map_search\",\"query\":\"" + QUERY + "\"}";

    public void testRenderedPageOpensOnlyTheReviewedRequestAndPreservesDraft() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return; // WindowInspector was introduced in API 29.
        Instrumentation driver = getInstrumentation();
        Activity activity = driver.startActivitySync(driver.getTargetContext().getPackageManager()
                .getLaunchIntentForPackage(driver.getTargetContext().getPackageName()));
        AtomicInteger generation = new AtomicInteger();
        WorkspaceTestHost host = new WorkspaceTestHost() {
            @Override public BooleanSupplier deviceActionOrigin() {
                int owner = generation.get();
                return () -> owner == generation.get();
            }
        };
        class External extends Instrumentation.ActivityMonitor {
            Intent requested;
            int count;
            boolean missing;
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                count++;
                requested = new Intent(intent);
                if (missing) throw new ActivityNotFoundException();
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        }
        External external = new External();
        driver.addMonitor(external);
        try {
            Button[] review = new Button[1], stale = new Button[1];
            EditText[] draft = new EditText[1];
            runTestOnUiThread(() -> {
                LinearLayout root = Ui.column(activity);
                draft[0] = new EditText(activity);
                draft[0].setText("Brouillon privé");
                root.addView(draft[0]);
                View page = InstrumentPreview.render(activity, DeviceActionContractTest.page(REQUEST), host);
                root.addView(page);
                activity.setContentView(root);
                review[0] = (Button) find(page, "Vérifier le lieu");
                assertNotNull(review[0]);
                review[0].performClick();
                assertNotNull(inWindow(QUERY));
                assertEquals(0, external.count);
                inWindow("Annuler").performClick();
            });
            driver.waitForIdleSync();
            runTestOnUiThread(() -> {
                assertEquals(0, external.count);
                review[0].performClick();
                stale[0] = (Button) inWindow("Ouvrir la carte");
                generation.incrementAndGet();
                ArtifactDesk.refreshBindings(activity.findViewById(android.R.id.content), host);
                stale[0].performClick();
            });
            driver.waitForIdleSync();
            runTestOnUiThread(() -> {
                assertEquals(0, external.count);
                review[0].performClick();
                inWindow("Ouvrir la carte").performClick();
            });
            driver.waitForIdleSync();
            runTestOnUiThread(() -> {
                assertEquals(1, external.count);
                assertEquals(Intent.ACTION_VIEW, external.requested.getAction());
                assertEquals("geo:0,0?q=Caf%C3%A9%20A%20%26%20B%2C%20Montr%C3%A9al", external.requested.getDataString());
                assertEquals("Brouillon privé", draft[0].getText().toString());
                review[0].performClick();
                external.missing = true;
                inWindow("Ouvrir la carte").performClick();
            });
            driver.waitForIdleSync();
            runTestOnUiThread(() -> {
                assertNotNull(inWindow("Application indisponible"));
                assertEquals("Brouillon privé", draft[0].getText().toString());
                inWindow("Fermer").performClick();
            });
        } finally {
            driver.removeMonitor(external);
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }

    private static View inWindow(String label) {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            View match = find(root, label);
            if (match != null) return match;
        }
        throw new AssertionError("Missing visible control " + label);
    }

    private static View find(View root, String label) {
        if (root instanceof TextView text && label.contentEquals(text.getText())) return root;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }
}
