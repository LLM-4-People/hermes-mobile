package org.llm4people.hermes.panel;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Friendly French model capability badges (Vision, Outils, Long contexte, Tarif) with unknown-safe pricing. */
public final class ModelCapabilityBadges {
    public static final int LONG_CONTEXT_THRESHOLD = 64_000;

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

        public List<String> badges() {
            return ModelCapabilityBadges.badgesFor(this);
        }

        public String summaryLine() {
            List<String> list = badges();
            return list.isEmpty() ? "" : String.join(" · ", list);
        }

        @Override public String toString() {
            String summary = summaryLine();
            return summary.isEmpty() ? label : label + "\n" + summary;
        }
    }

    private ModelCapabilityBadges() {}

    public static List<String> badgesFor(ModelInfo info) {
        if (info == null) return Collections.emptyList();
        List<String> badges = new ArrayList<>(5);
        if (info.vision()) badges.add("Vision");
        if (info.tools()) badges.add("Outils");
        if (info.contextLength() >= LONG_CONTEXT_THRESHOLD) {
            badges.add("Long contexte (" + formatContext(info.contextLength()) + ")");
        } else if (info.contextLength() > 0) {
            badges.add("Contexte " + formatContext(info.contextLength()));
        }
        if (info.reasoning()) badges.add("Réflexion");
        String pricing = formatPricingTier(info.inputPer1mUsd(), info.outputPer1mUsd());
        if (!pricing.isEmpty()) badges.add(pricing);
        return Collections.unmodifiableList(badges);
    }

    public static String formatContext(int tokens) {
        if (tokens <= 0) return "";
        if (tokens >= 1_000_000) {
            double millions = tokens / 1_000_000d;
            NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRENCH);
            nf.setMaximumFractionDigits(1);
            return nf.format(millions) + " M";
        }
        if (tokens >= 1_000) {
            return (tokens / 1_000) + " k";
        }
        return Integer.toString(tokens);
    }

    /**
     * Format known pricing without ever turning unknown (NaN) into 0.
     * Returns empty string when neither input nor output pricing is known.
     */
    public static String formatPricingTier(double inputPer1mUsd, double outputPer1mUsd) {
        boolean hasIn = Double.isFinite(inputPer1mUsd) && inputPer1mUsd >= 0;
        boolean hasOut = Double.isFinite(outputPer1mUsd) && outputPer1mUsd >= 0;
        if (!hasIn && !hasOut) return "";
        if (hasIn && hasOut && inputPer1mUsd == 0d && outputPer1mUsd == 0d) {
            return "Tarif · Inclus";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRENCH);
        nf.setMaximumFractionDigits(2);
        if (hasIn && hasOut) {
            return "Tarif · " + nf.format(inputPer1mUsd) + " / " + nf.format(outputPer1mUsd) + " $/M";
        }
        double known = hasIn ? inputPer1mUsd : outputPer1mUsd;
        return "Tarif · " + nf.format(known) + " $/M";
    }

    private static double sanitizeNonNegative(double value) {
        return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
    }
}
