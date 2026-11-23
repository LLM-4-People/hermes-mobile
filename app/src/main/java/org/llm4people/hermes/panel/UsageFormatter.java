package org.llm4people.hermes.panel;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Plain-French usage & timing formatting.
 * Preserves unknown/missing measurements as explicit "Indisponible" — NEVER turns unknown into 0.
 */
public final class UsageFormatter {
    public static final String UNAVAILABLE = "Indisponible";

    public record RunTelemetry(
            double ttftMs,
            double elapsedMs,
            double outputTokens,
            double serverGenerationTps,
            double reasoningTokens,
            double apiCalls) {
        public double pureGenerationTps() {
            if (Double.isFinite(serverGenerationTps) && serverGenerationTps > 0) {
                return serverGenerationTps;
            }
            if (!Double.isFinite(elapsedMs) || !Double.isFinite(ttftMs) || !Double.isFinite(outputTokens)) {
                return Double.NaN;
            }
            double genMs = elapsedMs - ttftMs;
            if (genMs <= 0 || outputTokens < 0) return Double.NaN;
            return outputTokens * 1000d / genMs;
        }

        public double endToEndTps() {
            if (!Double.isFinite(elapsedMs) || elapsedMs <= 0 || !Double.isFinite(outputTokens) || outputTokens < 0) {
                return Double.NaN;
            }
            return outputTokens * 1000d / elapsedMs;
        }

        /** Primary everyday speed value: prefer pure generation speed, fall back to end-to-end when TTFT absent. */
        public double primarySpeedTps() {
            double gen = pureGenerationTps();
            return Double.isFinite(gen) ? gen : endToEndTps();
        }
    }

    private UsageFormatter() {}

    public static String formatResponseTime(double ttftMs) {
        if (!Double.isFinite(ttftMs) || ttftMs < 0) return UNAVAILABLE;
        return formatSeconds(ttftMs / 1000d);
    }

    public static String formatSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return UNAVAILABLE;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRENCH);
        nf.setMaximumFractionDigits(seconds < 10 ? 2 : 1);
        return nf.format(seconds) + " s";
    }

    public static String formatSpeed(double tokensPerSecond) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond < 0) return UNAVAILABLE;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRENCH);
        nf.setMaximumFractionDigits(1);
        return nf.format(tokensPerSecond) + " jetons/s";
    }

    public static String formatUsd(double amount) {
        if (!Double.isFinite(amount) || amount < 0) return UNAVAILABLE;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRENCH);
        nf.setMaximumFractionDigits(amount >= 0.01 ? 4 : 6);
        return nf.format(amount) + " USD";
    }

    public static String formatPercent(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0) return UNAVAILABLE;
        return NumberFormat.getPercentInstance(Locale.FRENCH).format(ratio);
    }

    public static String formatCount(double count) {
        if (!Double.isFinite(count) || count < 0) return UNAVAILABLE;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRENCH);
        nf.setMaximumFractionDigits(0);
        return nf.format(count);
    }

    /** Primary cost display: known billed takes precedence over estimated; unknown stays "Indisponible". */
    public static String primaryCostSummary(double billedUsd, double estimatedUsd, String costStatus) {
        if ("included".equals(costStatus) && !Double.isFinite(billedUsd) && !Double.isFinite(estimatedUsd)) {
            return "Compris dans le forfait";
        }
        if (Double.isFinite(billedUsd)) return formatUsd(billedUsd);
        if (Double.isFinite(estimatedUsd)) return formatUsd(estimatedUsd) + " (estimé)";
        return UNAVAILABLE;
    }

    public static double safeMetric(double raw) {
        return Double.isFinite(raw) && raw >= 0 ? raw : Double.NaN;
    }
}
