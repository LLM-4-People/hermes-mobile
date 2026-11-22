package org.llm4people.hermes.panel;

import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import java.text.NumberFormat;
import java.util.Locale;

final class UsagePanel {
    private final SessionPanel panel;
    private final String id;

    UsagePanel(SessionPanel panel, String id) { this.panel = panel; this.id = id; }

    void show() {
        LinearLayout page = panel.page("stats", R.string.panel_stats, null);
        if (id == null || id.isEmpty()) { panel.note(page, panel.t(R.string.panel_no_session)); return; }
        panel.action(page, R.string.panel_refresh, this::show);
        panel.section(page, R.string.panel_server_totals);
        LinearLayout totals = Ui.column(panel.activity);
        page.addView(totals);
        panel.load(totals, () -> panel.api.get("/api/sessions/" + HermesApi.segment(id)).getJSONObject("session"),
                row -> display(totals, row), this::show);
        panel.section(page, R.string.panel_last_run);
        JSONObject timing;
        try { timing = PanelData.parse(panel.account.read("panel/timing/" + id)); }
        catch (IllegalStateException error) { panel.failure(page, error, null); return; }
        if (timing.length() == 0) { panel.note(page, panel.t(R.string.panel_timing_empty)); return; }
        metric(page, R.string.panel_first_text, format(PanelData.measure(timing, "first_ms") / 1000, R.string.panel_seconds));
        metric(page, R.string.panel_duration, format(PanelData.measure(timing, "elapsed_ms") / 1000, R.string.panel_seconds));
        metric(page, R.string.panel_rate, format(PanelData.endToEndRate(timing), R.string.panel_tps));
    }

    private void display(LinearLayout box, JSONObject row) {
        String status = PanelData.text(row, "cost_status");
        double actual = PanelData.measure(row, "actual_cost_usd");
        double estimate = PanelData.measure(row, "estimated_cost_usd");
        // Positive totals survive a later call whose price was included or unknown.
        metric(box, R.string.panel_cost, format(actual > 0 || status.equals("actual") ? actual : Double.NaN, R.string.panel_usd));
        metric(box, R.string.panel_estimate, format(estimate > 0 || status.equals("estimated") ? estimate : Double.NaN, R.string.panel_usd));
        if (status.equals("included")) panel.note(box, panel.t(R.string.panel_included));
        double cache = PanelData.cacheRatio(row);
        metric(box, R.string.panel_cache, Double.isFinite(cache) ? NumberFormat.getPercentInstance(Locale.FRENCH).format(cache)
                : panel.t(R.string.panel_unknown));
        metric(box, R.string.panel_in, format(PanelData.measure(row, "input_tokens"), 0));
        metric(box, R.string.panel_out, format(PanelData.measure(row, "output_tokens"), 0));
    }

    private String format(double value, int unit) {
        if (!Double.isFinite(value)) return panel.t(R.string.panel_unknown);
        NumberFormat number = NumberFormat.getNumberInstance(Locale.FRENCH);
        number.setMaximumFractionDigits(unit == R.string.panel_usd ? 8 : 3);
        return unit == 0 ? number.format(value) : panel.t(unit, number.format(value));
    }

    private void metric(LinearLayout box, int label, String value) {
        TextView name = panel.note(box, panel.t(label));
        TextView number = Ui.text(panel.activity, value, 20);
        number.setTypeface(android.graphics.Typeface.MONOSPACE);
        number.setContentDescription(name.getText() + ", " + value);
        name.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(number);
        box.addView(Ui.divider(panel.activity));
    }
}
