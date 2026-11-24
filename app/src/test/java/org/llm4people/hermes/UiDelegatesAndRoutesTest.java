package org.llm4people.hermes;

import android.view.View;

import org.junit.Test;
import org.llm4people.hermes.palette.CommandRegistry;
import org.llm4people.hermes.panel.ReminderPanel;
import org.llm4people.hermes.profile.ProfileHubSheet;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * Pure-JVM regression tests for Triple-Check Round 15 SSoT helpers:
 * - R15.4.1: Ui accessibility delegate helpers are null-safe and expose canonical signatures.
 * - R15.4.4: ProfileHubSheet.suggestSlug &amp; CommandRegistry.normalize delegate accent folding to PlainTextSanitizer.
 * - R15.4.5: WorkspaceNavigation.DISCUSSION, WorkspaceNavigation.GALLERY, and ReminderPanel.ROUTE_ID public SSoT.
 */
public final class UiDelegatesAndRoutesTest {

    @Test
    public void uiAccessibilityDelegateHelpersAreNullSafeAndExposeCanonicalSignatures() throws Exception {
        // Null calls must be complete no-ops without triggering Android View stub initialization on host JVM.
        Ui.setButtonRole(null);
        Ui.setSelectableButtonRole(null);
        Ui.setCheckableState(null, true);
        Ui.setCheckableState(null, false);

        Method buttonRole = Ui.class.getMethod("setButtonRole", View.class);
        Method selectableRole = Ui.class.getMethod("setSelectableButtonRole", View.class);
        Method checkableState = Ui.class.getMethod("setCheckableState", View.class, boolean.class);
        assertEquals(void.class, buttonRole.getReturnType());
        assertEquals(void.class, selectableRole.getReturnType());
        assertEquals(void.class, checkableState.getReturnType());
    }

    @Test
    public void publicRouteConstantsMatchCanonicalWireValues() {
        assertEquals("discussion", WorkspaceNavigation.DISCUSSION);
        assertEquals("pages", WorkspaceNavigation.GALLERY);
        assertEquals("reminders", ReminderPanel.ROUTE_ID);
    }

    @Test
    public void accentFoldingInSlugAndCommandNormalizationHandlesDiacriticsAndEdgeCases() {
        assertEquals("elodie-cote", ProfileHubSheet.suggestSlug("Élodie Côté"));
        assertEquals("cafe-resume-2026", ProfileHubSheet.suggestSlug("  Café & Résumé 2026! "));
        assertEquals("", ProfileHubSheet.suggestSlug(null));
        assertEquals("", ProfileHubSheet.suggestSlug("   ---   "));

        assertEquals("memoire rappels", CommandRegistry.normalize("Mémoire & Rappels!"));
        assertEquals("a propos du reseau", CommandRegistry.normalize("À propos du Réseau"));
        assertEquals("", CommandRegistry.normalize(null));
        assertEquals("", CommandRegistry.normalize("   "));
    }
}
