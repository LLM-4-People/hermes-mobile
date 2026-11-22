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
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.journey.JourneyUi;

@SuppressWarnings("deprecation")
public class DeviceReviewTest extends InstrumentationTestCase {
    private static final String QUERY = "Café A & B, Montréal";
    private static final String REQUEST = "{\"kind\":\"map_search\",\"query\":\"" + QUERY + "\"}";

    public void testCalendarStartRemainsVisibleBelowShortAndWrappingTitles() throws Throwable {
        android.content.Context context = getInstrumentation().getTargetContext();
        for (String title : new String[]{"Atelier", "Atelier de dessin pour les personnes qui visitent la Maison des arts de Laval"}) {
            WorkspaceLayout layout = WorkspaceLayout.parse(DeviceActionContractTest.page(
                    DeviceActionContractTest.EVENT.replace("Réunion ☕", title)));
            runTestOnUiThread(() -> {
                LinearLayout page = Ui.column(context);
                WorkspaceBlocks.add(page, layout, layout.blocks.get(0), null, new WorkspaceTestHost());
                int width = Ui.dp(context, 230);
                page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                page.layout(0, 0, width, page.getMeasuredHeight());
                LinearLayout row = (LinearLayout) page.getChildAt(0);
                TextView name = (TextView) row.getChildAt(1);
                TextView time = (TextView) row.getChildAt(2);
                assertEquals(title, name.getText().toString());
                assertEquals(title.equals("Atelier") ? 1 : 2, name.getLineCount());
                assertEquals("23 novembre 2026 à 09 h 00 (UTC-05:00)", time.getText().toString());
                assertTrue(time.getTop() >= name.getBottom());
                for (int line = 0; line < time.getLineCount(); line++) assertEquals(0, time.getLayout().getEllipsisCount(line));
                assertTrue(row.getChildAt(3) instanceof Button);
            });
        }
    }

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
