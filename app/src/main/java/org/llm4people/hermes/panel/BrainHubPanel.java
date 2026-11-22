package org.llm4people.hermes.panel;

import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;

import java.util.List;
import java.util.Set;

/**
 * Native everyday-French "Cerveau de Hermes" studio hub backed directly by RouteRegistry.
 * Shows every Cerveau destination with plain-French hints and live capability readiness.
 */
final class BrainHubPanel {
    private final SessionPanel panel;
    private final RouteRegistry registry;
    private LinearLayout body;

    BrainHubPanel(SessionPanel panel, RouteRegistry registry) {
        this.panel = panel;
        this.registry = registry;
    }

    void show() {
        LinearLayout page = panel.page("brain_hub", R.string.brain_hub_title, null);
        panel.note(page, panel.t(R.string.brain_hub_subtitle));
        body = Ui.column(panel.activity);
        page.addView(body);
        load();
    }

    private void load() {
        body.removeAllViews();
        panel.request(body, () -> BrainJson.parseCapabilities(panel.api.capabilities()),
                this::render,
                error -> {
                    Diagnostics.warning("brain_hub", "capabilities_load_failed", error);
                    render(BrainCapabilities.empty());
                });
    }

    private void render(BrainCapabilities capabilities) {
        body.removeAllViews();
        Set<String> allCaps = Set.of(
                BrainCapabilities.MEMORY_API,
                BrainCapabilities.SMART_NOTES,
                BrainCapabilities.UNIVERSAL_SHARING,
                BrainCapabilities.SESSION_SEARCH,
                BrainCapabilities.SKILL_STUDIO,
                BrainCapabilities.WORKSPACE_FILES,
                BrainCapabilities.WORKSPACE_BROWSER,
                BrainCapabilities.PROFILE_DEFAULTS);
        List<RouteRegistry.Destination> all = registry.destinationsIn(RouteRegistry.Section.CERVEAU, allCaps);
        for (RouteRegistry.Destination dest : all) {
            boolean ready = dest.availableFor(capabilities.asSet());
            LinearLayout card = BrainUi.card(panel.activity);
            LinearLayout top = Ui.row(panel.activity);
            TextView title = Ui.text(panel.activity, dest.label(), 18);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(BrainUi.badge(panel.activity,
                    panel.t(ready ? R.string.brain_available : R.string.brain_needs_update), ready));
            card.addView(top);
            if (!dest.hint().isEmpty()) {
                TextView hint = Ui.text(panel.activity, dest.hint(), 14);
                hint.setTextColor(panel.activity.getColor(R.color.muted));
                Ui.pad(hint, 0, 4);
                card.addView(hint);
            }
            card.setFocusable(true);
            card.setContentDescription(dest.label() + ". " + dest.hint());
            card.setOnClickListener(v -> registry.dispatch(dest.id()));
            body.addView(card);
        }
    }
}
