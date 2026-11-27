package org.llm4people.hermes.templates;

import android.app.Activity;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class TemplatesPanelTest extends InstrumentationTestCase {
    public void testRouteRegistrationBuiltInsStarterStripAndEncryptedProfileIsolation() throws Throwable {
        Context context = getInstrumentation().getTargetContext();

        // 1. Route registration under APPS.
        RouteRegistry registry = new RouteRegistry();
        AtomicBoolean opened = new AtomicBoolean(false);
        TemplatesPanel.registerRoute(registry, () -> opened.set(true));
        assertTrue(registry.dispatch(TemplatesPanel.ROUTE_ID));
        assertTrue(opened.get());

        // 2. Six curated built-ins + starter strip strictly stages drafts (never auto-sends).
        List<WorkflowTemplate> builtIns = WorkflowTemplate.builtIn(context);
        assertEquals(6, builtIns.size());
        AtomicReference<String> staged = new AtomicReference<>();
        int minTouchPx = Ui.dp(context, 48);

        try {
            try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(context);
                 WorkspaceAccountFixture second = new WorkspaceAccountFixture(context)) {
                Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
                AtomicReference<TemplatesPanel> panelRef = new AtomicReference<>();
                try {
                    TemplateStore storeA = TemplateStore.forAccount(first.account);
                    TemplateStore storeB = TemplateStore.forAccount(second.account);

                    WorkflowTemplate customA = storeA.saveCustom(null, "Revue hebdo", "Vendredi", "Résume ma semaine.");
                    assertEquals(1, storeA.listCustom().size());
                    assertTrue("Profile B must not see Profile A's templates", storeB.listCustom().isEmpty());

                    runTestOnUiThread(() -> {
                        staged.set(null);
                        TemplatesPanel panel = new TemplatesPanel(activity, first.account, staged::set, () -> true);
                        panelRef.set(panel);
                        View panelView = panel.buildView();
                        List<Button> buttons = collectButtons(panelView);
                        assertTrue(buttons.size() >= 7);
                        String useLabel = activity.getString(R.string.templates_use_button);
                        Button firstUse = null;
                        for (Button b : buttons) {
                            assertTrue("Panel button must be >= 48dp", b.getMinHeight() >= minTouchPx);
                            if (useLabel.equals(b.getText().toString()) && firstUse == null) firstUse = b;
                        }
                        assertNotNull(firstUse);
                        firstUse.performClick();
                        assertEquals(builtIns.get(0).draft, staged.get());
                    });
                    getInstrumentation().waitForIdleSync();
                } finally {
                    try {
                        TemplatesPanel panel = panelRef.getAndSet(null);
                        if (panel != null) {
                            runTestOnUiThread(panel::close);
                        }
                    } finally {
                        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                    }
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private static List<Button> collectButtons(View root) {
        List<Button> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(View view, List<Button> out) {
        if (view instanceof Button button) {
            out.add(button);
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), out);
        }
    }
}
