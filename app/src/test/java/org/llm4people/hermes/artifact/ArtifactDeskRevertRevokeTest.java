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
    public void appCustomizationDelegatesLuminanceContrastAndOnAccentToThemePalette() {
        int[] samples = {0x1B5E50, 0x9AD7BA, 0x264B73, 0xF0A89B, 0xFFFFFF, 0x000000};
        for (int rgb : samples) {
            int argb = 0xFF000000 | rgb;
            assertEquals(ThemePalette.relativeLuminance(argb), AppCustomization.relativeLuminance(rgb), 1e-12);
            assertEquals(ThemePalette.contrastRatio(argb, ThemePalette.DAY_SURFACE),
                    AppCustomization.contrastRatio(rgb, AppCustomization.LIGHT_SURFACE_RGB), 1e-12);
            assertEquals(ThemePalette.bestOnAccent(argb), AppCustomization.onAccentArgb(argb));
        }
    }
}
