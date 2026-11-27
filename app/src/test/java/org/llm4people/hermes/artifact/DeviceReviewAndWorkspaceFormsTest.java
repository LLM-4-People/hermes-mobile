package org.llm4people.hermes.artifact;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for DeviceReview lifecycle fail-closed guard and WorkspaceForms border contrast. */
public final class DeviceReviewAndWorkspaceFormsTest {
    @Test
    public void deviceReviewFailsClosedWhenActivityIsUnavailable() {
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger unavailable = new AtomicInteger();
        DeviceAction action = DeviceAction.parseShareText("Bonjour");

        DeviceReview.review(null, null, null, action,
                completed::incrementAndGet,
                cancelled::incrementAndGet,
                unavailable::incrementAndGet);

        assertEquals(0, completed.get());
        assertEquals(0, cancelled.get());
        assertEquals("Null/unavailable activity must report unavailable once", 1, unavailable.get());
    }

    @Test
    public void workspaceFormsEditorStrokeColorsMeetWcagNonTextContrastInLightAndDarkModes() {
        for (boolean night : new boolean[]{false, true}) {
            ThemePalette palette = ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), night);
            int unfocused = WorkspaceForms.editorUnfocusedStrokeColor(palette);
            int focused = WorkspaceForms.editorFocusedStrokeColor(palette);
            double unfocusedContrast = ThemePalette.contrastRatio(unfocused, palette.surface);
            double focusedContrast = ThemePalette.contrastRatio(focused, palette.surface);
            assertTrue("Unfocused editor/chip border must not use low-contrast palette.line (night=" + night + ")",
                    unfocused != palette.line);
            assertTrue("Unfocused editor/chip border contrast must be >= 3.0 (night=" + night + ", got " + unfocusedContrast + ")",
                    unfocusedContrast >= 3.0);
            assertTrue("Focused editor border contrast must be >= 3.0 (night=" + night + ", got " + focusedContrast + ")",
                    focusedContrast >= 3.0);
        }
    }
}
