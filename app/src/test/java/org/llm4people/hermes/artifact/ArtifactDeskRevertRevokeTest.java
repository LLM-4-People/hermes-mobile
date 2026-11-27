package org.llm4people.hermes.artifact;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.net.HermesApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ArtifactDeskRevertRevokeTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void revokeAllRevokesTrackedRevertClientsAndClearsRegistry() {
        try {
            HermesApi first = new HermesApi("http://127.0.0.1:18080", "token-a", org.llm4people.hermes.net.HermesApi.Limits.defaults());
            HermesApi second = new HermesApi("http://127.0.0.1:18081", "token-b", org.llm4people.hermes.net.HermesApi.Limits.defaults());
            ArtifactDesk.trackRevertClientForTest(first);
            ArtifactDesk.trackRevertClientForTest(second);
            assertFalse(first.isRevoked());
            assertFalse(second.isRevoked());
            assertTrue(ArtifactDesk.activeRevertCountForTest() >= 2);

            ArtifactDesk.revokeAll();
            assertTrue(first.isRevoked());
            assertTrue(second.isRevoked());
            assertEquals(0, ArtifactDesk.activeRevertCountForTest());
        } finally {
            ArtifactDesk.revokeAll();
        }
    }

    @Test
    public void appCustomizationDelegatesContrastAndOnAccentToThemePalette() {
        int[] passing = {0x1B5E50, 0x264B73, 0x000000};
        int[] failing = {0x9AD7BA, 0xF0A89B, 0xFFFFFF};
        for (int rgb : passing) {
            int argb = 0xFF000000 | rgb;
            double expectedMin = Math.min(
                    ThemePalette.contrastRatio(argb, ThemePalette.DAY_PAPER),
                    ThemePalette.contrastRatio(argb, ThemePalette.DAY_SURFACE));
            assertEquals(expectedMin, ThemePalette.minLightAccentContrast(argb), 1e-12);
            assertEquals(ThemePalette.bestOnAccent(argb), AppCustomization.onAccentArgb(argb));
            String hex = ThemePalette.formatHex(argb);
            assertEquals(hex, AppCustomization.validateCustomAccentHex(hex));
        }
        for (int rgb : failing) {
            int argb = 0xFF000000 | rgb;
            double expectedMin = Math.min(
                    ThemePalette.contrastRatio(argb, ThemePalette.DAY_PAPER),
                    ThemePalette.contrastRatio(argb, ThemePalette.DAY_SURFACE));
            assertEquals(expectedMin, ThemePalette.minLightAccentContrast(argb), 1e-12);
            assertEquals(ThemePalette.bestOnAccent(argb), AppCustomization.onAccentArgb(argb));
            String hex = ThemePalette.formatHex(argb);
            boolean threw = false;
            try {
                AppCustomization.validateCustomAccentHex(hex);
            } catch (IllegalArgumentException expected) {
                threw = true;
            }
            assertTrue("Expected low-contrast accent " + hex + " to be rejected", threw);
        }
    }
}
