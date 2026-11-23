package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class UsageAndBadgesModelTest {
    @Test
    public void usageFormatterNeverTurnsUnknownMetricsIntoZero() {
        assertEquals(R.string.ws_unavailable, UsageFormatter.responseTimeSpec(Double.NaN, Locale.US).resId());
        assertEquals(R.string.ws_unavailable, UsageFormatter.responseTimeSpec(-1, Locale.US).resId());
        assertEquals(R.string.ws_unavailable, UsageFormatter.speedSpec(Double.NaN, Locale.US).resId());
        assertEquals(R.string.ws_unavailable, UsageFormatter.speedSpec(-5, Locale.US).resId());
        assertEquals(R.string.ws_unavailable, UsageFormatter.usdSpec(Double.NaN, Locale.US).resId());
        assertEquals(R.string.ws_unavailable,
                UsageFormatter.primaryCostSpec(Double.NaN, Double.NaN, "unknown", Locale.US).resId());
        assertEquals(R.string.ws_usage_cost_included,
                UsageFormatter.primaryCostSpec(Double.NaN, Double.NaN, "included", Locale.US).resId());

        // Pure generation TPS vs end-to-end TPS
        UsageFormatter.RunTelemetry telemetry = new UsageFormatter.RunTelemetry(
                500d, 2500d, 200d, Double.NaN, 40d, 2d);
        assertEquals(100.0, telemetry.pureGenerationTps(), 1e-6);
        assertEquals(80.0, telemetry.endToEndTps(), 1e-6);
        assertEquals(100.0, telemetry.primarySpeedTps(), 1e-6);

        // When elapsed <= ttft, pure generation TPS is unknown (NaN -> unavailable), never Infinity or 0.
        UsageFormatter.RunTelemetry impossibleTiming = new UsageFormatter.RunTelemetry(
                2000d, 1500d, 50d, Double.NaN, Double.NaN, Double.NaN);
        assertTrue(Double.isNaN(impossibleTiming.pureGenerationTps()));
        assertEquals(R.string.ws_unavailable,
                UsageFormatter.speedSpec(impossibleTiming.pureGenerationTps(), Locale.US).resId());
    }

    @Test
    public void primaryCostSpecUnifiesEstimatedAndBilledUsdFormatting() {
        // Estimated cost wraps usdSpec directly so localized currency formatting stays in one place.
        UsageFormatter.FormattedSpec estEn = UsageFormatter.primaryCostSpec(Double.NaN, 0.0125, "estimated", Locale.US);
        assertEquals(R.string.ws_usage_cost_estimated, estEn.resId());
        assertEquals(1, estEn.args().length);
        assertTrue(estEn.args()[0] instanceof UsageFormatter.FormattedSpec);
        UsageFormatter.FormattedSpec nestedEn = (UsageFormatter.FormattedSpec) estEn.args()[0];
        assertEquals(R.string.ws_usage_usd, nestedEn.resId());
        assertEquals(UsageFormatter.usdSpec(0.0125, Locale.US).args()[0], nestedEn.args()[0]);

        UsageFormatter.FormattedSpec estFr = UsageFormatter.primaryCostSpec(
                Double.NaN, 0.0125, "estimated", Locale.CANADA_FRENCH);
        assertEquals(R.string.ws_usage_cost_estimated, estFr.resId());
        UsageFormatter.FormattedSpec nestedFr = (UsageFormatter.FormattedSpec) estFr.args()[0];
        assertEquals(R.string.ws_usage_usd, nestedFr.resId());
        assertEquals(UsageFormatter.usdSpec(0.0125, Locale.CANADA_FRENCH).args()[0], nestedFr.args()[0]);

        // Billed cost takes precedence over estimated cost and "included" status.
        UsageFormatter.FormattedSpec billed = UsageFormatter.primaryCostSpec(0.25, 0.12, "included", Locale.US);
        assertEquals(R.string.ws_usage_usd, billed.resId());
        assertEquals(UsageFormatter.usdSpec(0.25, Locale.US).args()[0], billed.args()[0]);

        // Negative values must never resolve to $0.
        assertEquals(R.string.ws_unavailable,
                UsageFormatter.primaryCostSpec(-0.01, -0.02, "estimated", Locale.US).resId());
        assertEquals(R.string.ws_unavailable, UsageFormatter.usdSpec(-1.0, Locale.US).resId());
    }

    @Test
    public void modelCapabilityBadgesPreserveUnknownPricingAndFormatFriendlyLabels() {
        ModelCapabilityBadges.ModelInfo rich = new ModelCapabilityBadges.ModelInfo(
                "claude-sonnet", "Claude Sonnet", 200_000, 8_192, true, true, true, 3.0, 15.0);
        List<ModelCapabilityBadges.BadgeSpec> specs = rich.badgeSpecs(Locale.US);
        assertTrue(specs.stream().anyMatch(b -> b.resId() == R.string.ws_badge_vision));
        assertTrue(specs.stream().anyMatch(b -> b.resId() == R.string.ws_badge_tools));
        assertTrue(specs.stream().anyMatch(b -> b.resId() == R.string.ws_badge_long_context && "200k".equals(b.args()[0])));
        assertTrue(specs.stream().anyMatch(b -> b.resId() == R.string.ws_badge_reasoning));
        assertTrue(specs.stream().anyMatch(b -> b.resId() == R.string.ws_badge_pricing_pair));

        ModelCapabilityBadges.ModelInfo minimal = ModelCapabilityBadges.ModelInfo.minimal("fake-model");
        ModelCapabilityBadges.ModelInfo minimalCopy = ModelCapabilityBadges.ModelInfo.minimal("fake-model");
        assertTrue(minimal.badgeSpecs(Locale.US).isEmpty());
        assertEquals("fake-model", minimal.toString());
        assertEquals(minimal, minimal);
        assertEquals(minimal, minimalCopy);
        assertEquals(minimal.hashCode(), minimalCopy.hashCode());

        ModelCapabilityBadges.ModelInfo freeModel = new ModelCapabilityBadges.ModelInfo(
                "local-llama", "Local Llama", 32_000, 4_096, false, true, false, 0.0, 0.0);
        assertTrue(freeModel.badgeSpecs(Locale.US).stream().anyMatch(b -> b.resId() == R.string.ws_badge_pricing_included));
        assertTrue(freeModel.badgeSpecs(Locale.US).stream().anyMatch(b -> b.resId() == R.string.ws_badge_context && "32k".equals(b.args()[0])));
    }

    @Test
    public void threadActionModelValidatesExportFormatsSlugsAndForkMessageIds() {
        ThreadActionModel.ExportSpec md = ThreadActionModel.buildExportSpec("Révision d’été #1", "markdown");
        assertEquals("markdown", md.format());
        assertEquals("text/markdown", md.mimeType());
        assertEquals("revision-d-ete-1.md", md.suggestedFilename());

        ThreadActionModel.ExportSpec json = ThreadActionModel.buildExportSpec("   ", "JSON");
        assertEquals("json", json.format());
        assertEquals("application/json", json.mimeType());
        assertEquals("discussion-hermes.json", json.suggestedFilename());

        for (String badFormat : new String[]{null, "", "html", "pdf", "../md"}) {
            try { ThreadActionModel.validateExportFormat(badFormat); fail("Accepted format: " + badFormat); }
            catch (IllegalArgumentException expected) {}
        }

        assertEquals(null, ThreadActionModel.validateForkMessageId(null));
        assertEquals(Long.valueOf(42L), ThreadActionModel.validateForkMessageId(42L));
        try { ThreadActionModel.validateForkMessageId(0L); fail("Accepted 0 message id"); }
        catch (IllegalArgumentException expected) {}

        ThreadActionModel.TurnChoice compactedTurn = new ThreadActionModel.TurnChoice(7L, "Vous", "Avant compaction", "seg-1");
        assertEquals("seg-1", compactedTurn.targetSessionId("seg-2"));
        ThreadActionModel.TurnChoice plainTurn = new ThreadActionModel.TurnChoice(8L, "Hermes", "Réponse");
        assertEquals("seg-2", plainTurn.targetSessionId("seg-2"));
    }
}
