package org.llm4people.hermes;

import org.junit.Test;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.panel.SessionPanel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure-JVM regression tests for Triple-Check Round 4 PR 4:
 * - R4.2.4: screen-off & internal-transition lock guards cover pending ProfileGate setup secrets (account == null)
 * - R4.4.4: write-only AdaptiveNavBar.selectedRoute field removed
 * - R4.6.3: SessionPanel.showCreateReminder() direct reminder-editor contract
 * - R4.1.2-R4.1.5 & R4.4.3: FR/EN plurals grammar, retired workspace string removal, and stale contacts removal
 */
public final class MainActivityR4RegressionTest {

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void lockGuardsWipePendingSetupSecretsEvenBeforeAccountUnlock() {
        // Screen-off locks whenever ProfileGate exists (even when account == null on setup screen).
        assertTrue(MainActivity.shouldLockOnScreenOff(true));
        assertFalse(MainActivity.shouldLockOnScreenOff(false));

        // Returning from an internal transition (e.g. QrScannerActivity) locks on device lock OR expiry.
        assertTrue(MainActivity.shouldLockOnInternalTransitionReturn(true, true, false));
        assertTrue(MainActivity.shouldLockOnInternalTransitionReturn(true, false, true));
        assertTrue(MainActivity.shouldLockOnInternalTransitionReturn(true, true, true));

        // Active, non-expired internal transition while unlocked MUST NOT lock prematurely.
        assertFalse(MainActivity.shouldLockOnInternalTransitionReturn(true, false, false));
        assertFalse(MainActivity.shouldLockOnInternalTransitionReturn(false, true, true));

        // Null account window falls back to INTERNAL_TRANSITION_MAX_MS; every configured window uses policy helper.
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MAX_MS,
                MainActivity.internalTransitionTimeoutMs(null));
        for (LockGracePolicy.Window window : LockGracePolicy.Window.values()) {
            assertEquals(LockGracePolicy.internalTransitionTimeoutMillis(window),
                    MainActivity.internalTransitionTimeoutMs(window));
        }
    }

    @Test
    public void sessionPanelExposesDirectShowCreateReminderContract() throws Exception {
        Method showCreateReminder = SessionPanel.class.getMethod("showCreateReminder");
        assertEquals(void.class, showCreateReminder.getReturnType());
    }

    @Test
    public void adaptiveNavBarHasNoDeadSelectedRouteField() {
        for (Field field : AdaptiveNavBar.class.getDeclaredFields()) {
            if ("selectedRoute".equals(field.getName())) {
                fail("Write-only selectedRoute field must remain removed from AdaptiveNavBar");
            }
        }
    }

    @Test
    public void pluralsAndStringCleanupResourcesHaveExpectedStructureAndNoStaleContacts() throws Exception {
        Path resDir = Path.of("src/main/res");
        if (!Files.isDirectory(resDir)) {
            resDir = Path.of("app/src/main/res");
        }

        // R4.1.3 & R4.1.4: settings_strings.xml migrated both counters to <plurals>.
        String frSettings = readUtf8(resDir.resolve("values/settings_strings.xml"));
        String enSettings = readUtf8(resDir.resolve("values-en/settings_strings.xml"));
        assertTrue(frSettings.contains("<plurals name=\"hub_apps_templates_count\">"));
        assertTrue(enSettings.contains("<plurals name=\"hub_apps_templates_count\">"));
        assertTrue(enSettings.contains("<item quantity=\"one\">%1$d ready-to-use template</item>"));
        assertTrue(frSettings.contains("<plurals name=\"hub_reminders_attention_pill\">"));
        assertTrue(enSettings.contains("<plurals name=\"hub_reminders_attention_pill\">"));
        assertTrue(enSettings.contains("<item quantity=\"one\">%1$d needs attention</item>"));
        assertTrue(enSettings.contains("<item quantity=\"other\">%1$d need attention</item>"));

        // R4.1.5: ws_desk, ws_reminders, ws_lock_profile removed from FR and EN workspace_strings.xml.
        String frWorkspace = readUtf8(resDir.resolve("values/workspace_strings.xml"));
        String enWorkspace = readUtf8(resDir.resolve("values-en/workspace_strings.xml"));
        for (String removed : new String[]{"name=\"ws_desk\"", "name=\"ws_reminders\"", "name=\"ws_lock_profile\""}) {
            assertFalse("FR workspace_strings must not define " + removed, frWorkspace.contains(removed));
            assertFalse("EN workspace_strings must not define " + removed, enWorkspace.contains(removed));
        }

        // R4.4.3: stale "contacts" removed from palette ambient hint and keywords.
        String frPalette = readUtf8(resDir.resolve("values/palette_strings.xml"));
        String enPalette = readUtf8(resDir.resolve("values-en/palette_strings.xml"));
        String i18nWs = readUtf8(resDir.resolve("values/i18n_workspace_strings.xml"));
        assertFalse(frPalette.toLowerCase(Locale.ROOT).contains("contacts"));
        assertFalse(enPalette.toLowerCase(Locale.ROOT).contains("contacts"));
        for (String line : i18nWs.split("\n")) {
            if (line.contains("palette_kw_ambient")) {
                assertFalse("palette_kw_ambient must not include stale contacts", line.contains("contacts"));
            }
        }
    }
}
