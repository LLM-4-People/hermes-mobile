package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.journey.JourneyUi;

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
            JourneyUi ui = new JourneyUi(driver, activity, null);
            EditText[] draft = new EditText[1];
            runTestOnUiThread(() -> {
                LinearLayout root = Ui.column(activity);
                root.setFocusableInTouchMode(true);
                draft[0] = new EditText(activity);
                draft[0].setText("Brouillon privé");
                root.addView(draft[0]);
                View page = InstrumentPreview.render(activity, DeviceActionContractTest.page(REQUEST), host);
                root.addView(page);
                activity.setContentView(root);
            });
            ui.tapButton("Vérifier le lieu");
            ui.see("Ouvrir la carte");
            ui.see(QUERY);
            assertEquals(0, external.count);
            ui.tapButton("Annuler");

            ui.tapButton("Vérifier le lieu");
            Button stale = ui.button("Ouvrir la carte");
            runTestOnUiThread(() -> {
                generation.incrementAndGet();
                ArtifactDesk.refreshBindings(activity.findViewById(android.R.id.content), host);
                stale.performClick();
            });
            driver.waitForIdleSync();
            assertEquals(0, external.count);

            ui.tapButton("Vérifier le lieu");
            ui.tapButton("Ouvrir la carte");
            runTestOnUiThread(() -> {
                assertEquals(1, external.count);
                assertEquals(Intent.ACTION_VIEW, external.requested.getAction());
                assertEquals("geo:0,0?q=Caf%C3%A9%20A%20%26%20B%2C%20Montr%C3%A9al", external.requested.getDataString());
                assertEquals("Brouillon privé", draft[0].getText().toString());
                external.missing = true;
            });
            ui.tapButton("Vérifier le lieu");
            ui.tapButton("Ouvrir la carte");
            ui.see("Application indisponible");
            ui.tapButton("Fermer");
            ui.see("Brouillon privé");
        } finally {
            driver.removeMonitor(external);
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }
}
