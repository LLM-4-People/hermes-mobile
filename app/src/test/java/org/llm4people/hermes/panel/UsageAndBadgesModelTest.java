package org.llm4people.hermes.panel;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class UsageAndBadgesModelTest {
    @Test
    public void usageFormatterNeverTurnsUnknownMetricsIntoZero() {
        assertEquals("Indisponible", UsageFormatter.formatResponseTime(Double.NaN));
        assertEquals("Indisponible", UsageFormatter.formatResponseTime(-1));
        assertEquals("Indisponible", UsageFormatter.formatSpeed(Double.NaN));
        assertEquals("Indisponible", UsageFormatter.formatSpeed(-5));
        assertEquals("Indisponible", UsageFormatter.formatUsd(Double.NaN));
        assertEquals("Indisponible", UsageFormatter.formatPercent(Double.NaN));
        assertEquals("Indisponible", UsageFormatter.formatCount(Double.NaN));
        assertEquals("Indisponible", UsageFormatter.primaryCostSummary(Double.NaN, Double.NaN, "unknown"));
        assertEquals("Compris dans le forfait", UsageFormatter.primaryCostSummary(Double.NaN, Double.NaN, "included"));

        // Pure generation TPS vs end-to-end TPS
        UsageFormatter.RunTelemetry telemetry = new UsageFormatter.RunTelemetry(
                500d, 2500d, 200d, Double.NaN, 40d, 2d);
        assertEquals(100.0, telemetry.pureGenerationTps(), 1e-6);
        assertEquals(80.0, telemetry.endToEndTps(), 1e-6);
        assertEquals(100.0, telemetry.primarySpeedTps(), 1e-6);

        // When elapsed <= ttft, pure generation TPS is unknown (NaN -> Indisponible), never Infinity or 0.
        UsageFormatter.RunTelemetry impossibleTiming = new UsageFormatter.RunTelemetry(
                2000d, 1500d, 50d, Double.NaN, Double.NaN, Double.NaN);
        assertTrue(Double.isNaN(impossibleTiming.pureGenerationTps()));
        assertEquals("Indisponible", UsageFormatter.formatSpeed(impossibleTiming.pureGenerationTps()));
    }

    @Test
    public void modelCapabilityBadgesPreserveUnknownPricingAndFormatFriendlyLabels() {
        ModelCapabilityBadges.ModelInfo rich = new ModelCapabilityBadges.ModelInfo(
                "claude-sonnet", "Claude Sonnet", 200_000, 8_192, true, true, true, 3.0, 15.0);
        List<String> badges = rich.badges();
        assertTrue(badges.contains("Vision"));
        assertTrue(badges.contains("Outils"));
        assertTrue(badges.contains("Long contexte (200 k)"));
        assertTrue(badges.contains("Réflexion"));
        assertTrue(badges.stream().anyMatch(b -> b.startsWith("Tarif · ") && b.contains("$/M")));

        ModelCapabilityBadges.ModelInfo minimal = ModelCapabilityBadges.ModelInfo.minimal("fake-model");
        assertTrue(minimal.badges().isEmpty());
        assertEquals("fake-model", minimal.toString());

        ModelCapabilityBadges.ModelInfo freeModel = new ModelCapabilityBadges.ModelInfo(
                "local-llama", "Local Llama", 32_000, 4_096, false, true, false, 0.0, 0.0);
        assertTrue(freeModel.badges().contains("Tarif · Inclus"));
        assertTrue(freeModel.badges().contains("Contexte 32 k"));
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
