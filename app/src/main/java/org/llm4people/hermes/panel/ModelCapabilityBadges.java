package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.R;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Localized model capability badges (Vision, Tools, Long context, Pricing) with unknown-safe pricing. */
public final class ModelCapabilityBadges {
    public static final int LONG_CONTEXT_THRESHOLD = 64_000;

    public record BadgeSpec(int resId, Object... args) {
        public String resolve(Context context) {
            return args == null || args.length == 0
                    ? context.getString(resId)
                    : context.getString(resId, args);
        }
    }

    public record ModelInfo(
            String id,
            String label,
            int contextLength,
            int maxOutputTokens,
            boolean vision,
            boolean tools,
            boolean reasoning,
            double inputPer1mUsd,
            double outputPer1mUsd) {
        public ModelInfo {
            id = id == null ? "" : id.trim();
            label = label == null || label.trim().isEmpty() ? id : label.trim();
            contextLength = Math.max(0, contextLength);
            maxOutputTokens = Math.max(0, maxOutputTokens);
            inputPer1mUsd = sanitizeNonNegative(inputPer1mUsd);
            outputPer1mUsd = sanitizeNonNegative(outputPer1mUsd);
        }

        public static ModelInfo minimal(String id) {
            return new ModelInfo(id, id, 0, 0, false, false, false, Double.NaN, Double.NaN);
        }

        public List<BadgeSpec> badgeSpecs(Locale locale) {
            return ModelCapabilityBadges.badgeSpecsFor(this, locale);
        }

        public List<String> badges(Context context) {
            return ModelCapabilityBadges.badgesFor(context, this);
        }

        public String summaryLine(Context context) {
            List<String> list = badges(context);
            return list.isEmpty() ? "" : String.join(" · ", list);
        }

        public String displayText(Context context) {
            String summary = summaryLine(context);
            return summary.isEmpty() ? label : label + "\n" + summary;
        }

        @Override public String toString() {
            return label;
        }

        // Explicit Double.compare equality so unknown (NaN) pricing remains reflexive on Android D8/ART.
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ModelInfo that)) return false;
            return contextLength == that.contextLength
                    && maxOutputTokens == that.maxOutputTokens
                    && vision == that.vision
                    && tools == that.tools
                    && reasoning == that.reasoning
                    && Double.compare(inputPer1mUsd, that.inputPer1mUsd) == 0
                    && Double.compare(outputPer1mUsd, that.outputPer1mUsd) == 0
                    && Objects.equals(id, that.id)
                    && Objects.equals(label, that.label);
        }

        @Override public int hashCode() {
            return Objects.hash(id, label, contextLength, maxOutputTokens, vision, tools, reasoning,
                    Double.hashCode(inputPer1mUsd), Double.hashCode(outputPer1mUsd));
        }
    }

    private ModelCapabilityBadges() {}

    public static List<BadgeSpec> badgeSpecsFor(ModelInfo info, Locale locale) {
        if (info == null) return Collections.emptyList();
        Locale effective = locale == null ? Locale.ROOT : locale;
        List<BadgeSpec> specs = new ArrayList<>(5);
        if (info.vision()) specs.add(new BadgeSpec(R.string.ws_badge_vision));
        if (info.tools()) specs.add(new BadgeSpec(R.string.ws_badge_tools));
        if (info.contextLength() >= LONG_CONTEXT_THRESHOLD) {
            specs.add(new BadgeSpec(R.string.ws_badge_long_context, formatContextRaw(info.contextLength(), effective)));
        } else if (info.contextLength() > 0) {
            specs.add(new BadgeSpec(R.string.ws_badge_context, formatContextRaw(info.contextLength(), effective)));
        }
        if (info.reasoning()) specs.add(new BadgeSpec(R.string.ws_badge_reasoning));
        BadgeSpec pricing = pricingTierSpec(info.inputPer1mUsd(), info.outputPer1mUsd(), effective);
        if (pricing != null) specs.add(pricing);
        return Collections.unmodifiableList(specs);
    }

    public static List<String> badgesFor(Context context, ModelInfo info) {
        if (info == null) return Collections.emptyList();
        Locale locale = ReminderPanel.activeLocale(context);
        List<BadgeSpec> specs = badgeSpecsFor(info, locale);
        List<String> out = new ArrayList<>(specs.size());
        for (BadgeSpec spec : specs) out.add(spec.resolve(context));
        return Collections.unmodifiableList(out);
    }

    public static String formatContextRaw(int tokens, Locale locale) {
        if (tokens <= 0) return "";
        Locale effective = locale == null ? Locale.ROOT : locale;
        if (tokens >= 1_000_000) {
            double millions = tokens / 1_000_000d;
            NumberFormat nf = NumberFormat.getNumberInstance(effective);
            nf.setMaximumFractionDigits(1);
            return nf.format(millions) + "M";
        }
        if (tokens >= 1_000) {
            return (tokens / 1_000) + "k";
        }
        return Integer.toString(tokens);
    }

    /**
     * Format known pricing without ever turning unknown (NaN) into 0.
     * Returns null when neither input nor output pricing is known.
     */
    public static BadgeSpec pricingTierSpec(double inputPer1mUsd, double outputPer1mUsd, Locale locale) {
        boolean hasIn = Double.isFinite(inputPer1mUsd) && inputPer1mUsd >= 0;
        boolean hasOut = Double.isFinite(outputPer1mUsd) && outputPer1mUsd >= 0;
        if (!hasIn && !hasOut) return null;
        if (hasIn && hasOut && inputPer1mUsd == 0d && outputPer1mUsd == 0d) {
            return new BadgeSpec(R.string.ws_badge_pricing_included);
        }
        Locale effective = locale == null ? Locale.ROOT : locale;
        NumberFormat nf = NumberFormat.getNumberInstance(effective);
        nf.setMaximumFractionDigits(2);
        if (hasIn && hasOut) {
            return new BadgeSpec(R.string.ws_badge_pricing_pair, nf.format(inputPer1mUsd), nf.format(outputPer1mUsd));
        }
        double known = hasIn ? inputPer1mUsd : outputPer1mUsd;
        return new BadgeSpec(R.string.ws_badge_pricing_single, nf.format(known));
    }

    private static double sanitizeNonNegative(double value) {
        return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
    }
}
