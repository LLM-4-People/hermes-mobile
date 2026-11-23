package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.R;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Localized usage & timing formatting.
 * Preserves unknown/missing measurements as explicit unavailable — NEVER turns unknown into 0.
 */
public final class UsageFormatter {
    public record FormattedSpec(int resId, Object... args) {
        public String resolve(Context context) {
            return args == null || args.length == 0
                    ? context.getString(resId)
                    : context.getString(resId, args);
        }
    }

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

    public static FormattedSpec responseTimeSpec(double ttftMs, Locale locale) {
        if (!Double.isFinite(ttftMs) || ttftMs < 0) return new FormattedSpec(R.string.ws_unavailable);
        return secondsSpec(ttftMs / 1000d, locale);
    }

    public static FormattedSpec secondsSpec(double seconds, Locale locale) {
        if (!Double.isFinite(seconds) || seconds < 0) return new FormattedSpec(R.string.ws_unavailable);
        NumberFormat nf = NumberFormat.getNumberInstance(effective(locale));
        nf.setMaximumFractionDigits(seconds < 10 ? 2 : 1);
        return new FormattedSpec(R.string.ws_usage_seconds, nf.format(seconds));
    }

    public static FormattedSpec speedSpec(double tokensPerSecond, Locale locale) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond < 0) return new FormattedSpec(R.string.ws_unavailable);
        NumberFormat nf = NumberFormat.getNumberInstance(effective(locale));
        nf.setMaximumFractionDigits(1);
        return new FormattedSpec(R.string.ws_usage_speed, nf.format(tokensPerSecond));
    }

    public static FormattedSpec usdSpec(double amount, Locale locale) {
        if (!Double.isFinite(amount) || amount < 0) return new FormattedSpec(R.string.ws_unavailable);
        NumberFormat nf = NumberFormat.getNumberInstance(effective(locale));
        nf.setMaximumFractionDigits(amount >= 0.01 ? 4 : 6);
        return new FormattedSpec(R.string.ws_usage_usd, nf.format(amount));
    }

    public static FormattedSpec primaryCostSpec(double billedUsd, double estimatedUsd, String costStatus, Locale locale) {
        if ("included".equals(costStatus) && !Double.isFinite(billedUsd) && !Double.isFinite(estimatedUsd)) {
            return new FormattedSpec(R.string.ws_usage_cost_included);
        }
        if (Double.isFinite(billedUsd) && billedUsd >= 0) return usdSpec(billedUsd, locale);
        if (Double.isFinite(estimatedUsd) && estimatedUsd >= 0) {
            return new FormattedSpec(R.string.ws_usage_cost_estimated, formatUsdNumber(estimatedUsd, locale));
        }
        return new FormattedSpec(R.string.ws_unavailable);
    }

    public static String formatResponseTime(Context context, double ttftMs) {
        return responseTimeSpec(ttftMs, ReminderPanel.activeLocale(context)).resolve(context);
    }

    public static String formatSeconds(Context context, double seconds) {
        return secondsSpec(seconds, ReminderPanel.activeLocale(context)).resolve(context);
    }

    public static String formatSpeed(Context context, double tokensPerSecond) {
        return speedSpec(tokensPerSecond, ReminderPanel.activeLocale(context)).resolve(context);
    }

    public static String formatUsd(Context context, double amount) {
        return usdSpec(amount, ReminderPanel.activeLocale(context)).resolve(context);
    }

    public static String formatPercent(Context context, double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0) return context.getString(R.string.ws_unavailable);
        return NumberFormat.getPercentInstance(ReminderPanel.activeLocale(context)).format(ratio);
    }

    public static String formatCount(Context context, double count) {
        if (!Double.isFinite(count) || count < 0) return context.getString(R.string.ws_unavailable);
        NumberFormat nf = NumberFormat.getNumberInstance(ReminderPanel.activeLocale(context));
        nf.setMaximumFractionDigits(0);
        return nf.format(count);
    }

    /** Primary cost display: known billed takes precedence over estimated; unknown stays unavailable. */
    public static String primaryCostSummary(Context context, double billedUsd, double estimatedUsd, String costStatus) {
        if ("included".equals(costStatus) && !Double.isFinite(billedUsd) && !Double.isFinite(estimatedUsd)) {
            return context.getString(R.string.ws_usage_cost_included);
        }
        if (Double.isFinite(billedUsd) && billedUsd >= 0) return formatUsd(context, billedUsd);
        if (Double.isFinite(estimatedUsd) && estimatedUsd >= 0) {
            return context.getString(R.string.ws_usage_cost_estimated, formatUsd(context, estimatedUsd));
        }
        return context.getString(R.string.ws_unavailable);
    }

    public static double safeMetric(double raw) {
        return Double.isFinite(raw) && raw >= 0 ? raw : Double.NaN;
    }

    private static String formatUsdNumber(double amount, Locale locale) {
        NumberFormat nf = NumberFormat.getNumberInstance(effective(locale));
        nf.setMaximumFractionDigits(amount >= 0.01 ? 4 : 6);
        return nf.format(amount);
    }

    private static Locale effective(Locale locale) {
        return locale == null ? Locale.getDefault() : locale;
    }
}
