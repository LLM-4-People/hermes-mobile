package org.llm4people.hermes.artifact;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.JsonReader;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.llm4people.hermes.Ui;

import static org.llm4people.hermes.artifact.WorkspaceLayout.SOURCES;
import static org.llm4people.hermes.artifact.WorkspaceLayout.choice;
import static org.llm4people.hermes.artifact.WorkspaceLayout.invalid;
import static org.llm4people.hermes.artifact.WorkspaceLayout.label;
import static org.llm4people.hermes.artifact.WorkspaceLayout.require;

/** Responsive KPI / Metric Grid block (value + delta + provenance, following DESIGN.md Measurement Label Rule). */
public final class MetricGridBlock {
    public static final int MAX_ITEMS = 8;
    public static final List<String> TONES = Collections.unmodifiableList(
            Arrays.asList("neutral", "positive", "warning", "danger"));

    public static final class Item {
        public final String label;
        public final String value;
        public final String delta;
        public final String unit;
        public final String tone;
        public final String provenance;
        public final String source;

        public Item(String label, String value, String delta, String unit, String tone, String provenance, String source) {
            this.label = label;
            this.value = value == null ? "" : value;
            this.delta = delta == null ? "" : delta;
            this.unit = unit == null ? "" : unit;
            this.tone = tone == null ? "neutral" : tone;
            this.provenance = provenance == null ? "" : provenance;
            this.source = source;
        }

        public String formattedValue(String boundValue) {
            String raw = source != null ? (boundValue == null || boundValue.isEmpty() ? "Indisponible" : boundValue)
                    : (value.isEmpty() ? "Indisponible" : value);
            if ("Indisponible".equals(raw) || unit.isEmpty()) return raw;
            return raw + " " + unit;
        }

        public String accessibilityText(String boundValue) {
            StringBuilder sb = new StringBuilder(label).append(", ").append(formattedValue(boundValue));
            if (!delta.isEmpty()) sb.append(", variation ").append(delta);
            if (!provenance.isEmpty()) sb.append(", ").append(provenance);
            return sb.toString();
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Item other)) return false;
            return label.equals(other.label) && value.equals(other.value) && delta.equals(other.delta)
                    && unit.equals(other.unit) && tone.equals(other.tone)
                    && provenance.equals(other.provenance) && Objects.equals(source, other.source);
        }

        @Override public int hashCode() {
            return Objects.hash(label, value, delta, unit, tone, provenance, source);
        }
    }

    private MetricGridBlock() {}

    static List<Item> readItems(JsonReader in) throws IOException {
        List<Item> items = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            require(items.size() < MAX_ITEMS);
            items.add(readItem(in));
        }
        in.endArray();
        require(!items.isEmpty());
        return Collections.unmodifiableList(items);
    }

    private static Item readItem(JsonReader in) throws IOException {
        String itemLabel = null, value = "", delta = "", unit = "", tone = "neutral", provenance = "", source = null;
        Set<String> keys = new HashSet<>();
        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            require(keys.add(key));
            switch (key) {
                case "label" -> itemLabel = label(in, 48);
                case "value" -> value = label(in, 48);
                case "delta" -> delta = label(in, 24);
                case "unit" -> unit = label(in, 16);
                case "tone" -> tone = choice(in, TONES);
                case "provenance" -> provenance = label(in, 80);
                case "source" -> source = choice(in, SOURCES);
                default -> throw invalid();
            }
        }
        in.endObject();
        require(itemLabel != null && (!value.isEmpty() || source != null));
        return new Item(itemLabel, value, delta, unit, tone, provenance, source);
    }

    static View build(Context context, List<Item> items, ThemePalette palette, ArtifactDesk.Host host) {
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(context))
                : palette;
        LinearLayout grid = Ui.column(context);
        boolean twoColumns = context.getResources().getConfiguration().fontScale <= 1.25f && items.size() > 1;
        int gap = Ui.dp(context, 8);
        if (!twoColumns) {
            for (int i = 0; i < items.size(); i++) {
                View card = card(context, items.get(i), colors, host);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = gap;
                grid.addView(card, lp);
            }
            return grid;
        }
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = Ui.row(context);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
            leftLp.setMarginEnd(gap / 2);
            row.addView(card(context, items.get(i), colors, host), leftLp);
            if (i + 1 < items.size()) {
                LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
                rightLp.setMarginStart(gap / 2);
                row.addView(card(context, items.get(i + 1), colors, host), rightLp);
            } else {
                row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowLp.topMargin = gap;
            grid.addView(row, rowLp);
        }
        return grid;
    }

    private static View card(Context c, Item item, ThemePalette colors, ArtifactDesk.Host host) {
        LinearLayout box = Ui.column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(c, 10));
        bg.setColor("tinted".equals(colors.surfaceStyle) ? colors.accentSurface : colors.surface);
        bg.setStroke(Math.max(1, Ui.dp(c, 1)), colors.line);
        box.setBackground(bg);
        Ui.pad(box, 12, 10);

        TextView labelView = Ui.text(c, item.label, colors.scaledSp(14));
        labelView.setTextColor(colors.muted);
        labelView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(labelView);

        String bound = item.source != null && host != null ? host.workspaceValue(item.source) : "";
        MetricValueView valueView = new MetricValueView(c, item, colors);
        valueView.apply(bound);
        box.addView(valueView);

        if (!item.delta.isEmpty()) {
            TextView deltaView = Ui.text(c, item.delta, colors.scaledSp(13));
            deltaView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            int toneColor = switch (item.tone) {
                case "positive" -> colors.positive;
                case "warning" -> colors.warning;
                case "danger" -> colors.danger;
                default -> colors.muted;
            };
            deltaView.setTextColor(toneColor);
            deltaView.setPadding(0, Ui.dp(c, 2), 0, 0);
            deltaView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(deltaView);
        }
        if (!item.provenance.isEmpty()) {
            TextView provView = Ui.text(c, item.provenance, colors.scaledSp(12));
            provView.setTextColor(colors.muted);
            provView.setPadding(0, Ui.dp(c, 2), 0, 0);
            provView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(provView);
        }
        return box;
    }

    /** Tagged view so WorkspaceBlocks.refresh updates live-bound metric tiles without rebuilding the screen. */
    static final class MetricValueView extends TextView {
        final Item item;

        MetricValueView(Context c, Item item, ThemePalette colors) {
            super(c);
            this.item = item;
            setTypeface(Typeface.MONOSPACE);
            setTextSize(colors.scaledSp(20));
            setTextColor(colors.ink);
            setFontFeatureSettings("tnum");
            setPadding(0, Ui.dp(c, 2), 0, 0);
        }

        void apply(String boundValue) {
            String display = item.formattedValue(boundValue);
            if (!display.contentEquals(getText())) setText(display);
            setContentDescription(item.accessibilityText(boundValue));
        }

        void refresh(ArtifactDesk.Host host) {
            if (item.source == null || host == null) return;
            apply(host.workspaceValue(item.source));
        }
    }
}
