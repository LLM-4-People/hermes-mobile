package org.llm4people.hermes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.push.DeliveryRouter;
import org.llm4people.hermes.push.MobilePush;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
    public void internalTransitionGuardMarksOnlyPositiveRequestCodesAndChooser() {
        // Explicit startActivityForResult (requestCode >= 0) always marks an internal transition.
        assertTrue(MainActivity.shouldMarkInternalTransition(0, null));
        assertTrue(MainActivity.shouldMarkInternalTransition(4092, Intent.ACTION_VIEW));
        assertTrue(MainActivity.shouldMarkInternalTransition(Integer.MAX_VALUE, ""));

        // ACTION_CHOOSER (requestCode == -1 via startActivity) marks an internal transition.
        assertTrue(MainActivity.shouldMarkInternalTransition(-1, Intent.ACTION_CHOOSER));

        // Arbitrary fire-and-forget startActivity (requestCode == -1, non-chooser) must NOT suppress auto-lock.
        assertFalse(MainActivity.shouldMarkInternalTransition(-1, null));
        assertFalse(MainActivity.shouldMarkInternalTransition(-1, ""));
        assertFalse(MainActivity.shouldMarkInternalTransition(-1, Intent.ACTION_VIEW));
        assertFalse(MainActivity.shouldMarkInternalTransition(-1, Intent.ACTION_MAIN));
        assertFalse(MainActivity.shouldMarkInternalTransition(-1, "android.settings.APPLICATION_DETAILS_SETTINGS"));
        assertFalse(MainActivity.shouldMarkInternalTransition(Integer.MIN_VALUE, Intent.ACTION_VIEW));
    }

    @Test
    public void showSecurityReturnsSynchronousAutoCloseableHandle() throws Exception {
        Method showSecurity = WorkspaceDialogs.class.getDeclaredMethod(
                "showSecurity",
                Activity.class, Account.class, ProfileGate.class,
                Runnable.class, Runnable.class, Runnable.class);
        assertEquals(AutoCloseable.class, showSecurity.getReturnType());
    }

    @Test
    public void detachedSyncRevocationPolicyPreservesOnlyConfigChanges() {
        assertTrue(MainActivity.shouldRevokeDetachedSyncs(false));
        assertFalse(MainActivity.shouldRevokeDetachedSyncs(true));
    }

    @Test
    public void lockedPairingHandlerReinstallsAfterLockUnlessFinishingOrDestroyed() throws Exception {
        assertTrue(MainActivity.shouldRegisterLockedPairingHandler(true, false, false));
        assertFalse(MainActivity.shouldRegisterLockedPairingHandler(false, false, false));
        assertFalse(MainActivity.shouldRegisterLockedPairingHandler(true, true, false));
        assertFalse(MainActivity.shouldRegisterLockedPairingHandler(true, false, true));
        assertFalse(MainActivity.shouldRegisterLockedPairingHandler(true, true, true));

        // When locked with handler reinstalled, warm hermes://pair delivers without re-opening setup gate.
        assertEquals(MainActivity.PairingDeepLinkRoute.DELIVER_TO_LOCKED_HANDLER,
                MainActivity.resolvePairingDeepLinkRoute(true, false, true));
        assertEquals(MainActivity.PairingDeepLinkRoute.OPEN_SETUP_GATE,
                MainActivity.resolvePairingDeepLinkRoute(true, false, false));

        Method register = MainActivity.class.getDeclaredMethod("registerLockedPairingHandler");
        assertEquals(void.class, register.getReturnType());
    }

    @Test
    public void pushPolicyAndReminderNotificationContractsExposeSharedCallbacks() throws Exception {
        Method openPolicyWithActions = MobilePush.class.getMethod(
                "openPolicy", Account.class, DeliveryRouter.Actions.class);
        assertEquals(void.class, openPolicyWithActions.getReturnType());

        Method setOpenNotifications = SessionPanel.class.getMethod(
                "setOpenNotifications", Runnable.class);
        assertEquals(void.class, setOpenNotifications.getReturnType());
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

    @Test
    public void onStopDeferralCoversAllEightCombinationsAndBiometricSessionStates() throws Exception {
        // R14.6.1: All 8 boolean combinations of (internalTransition, biometricPromptActive, deviceLocked).
        assertFalse(MainActivity.shouldDeferLockOnStop(false, false, false));
        assertFalse(MainActivity.shouldDeferLockOnStop(false, false, true));
        assertTrue(MainActivity.shouldDeferLockOnStop(false, true, false));
        assertFalse(MainActivity.shouldDeferLockOnStop(false, true, true));
        assertTrue(MainActivity.shouldDeferLockOnStop(true, false, false));
        assertFalse(MainActivity.shouldDeferLockOnStop(true, false, true));
        assertTrue(MainActivity.shouldDeferLockOnStop(true, true, false));
        assertFalse(MainActivity.shouldDeferLockOnStop(true, true, true));

        // ProfileGate exposes public hasActiveBiometricPrompt() and evaluates session states fail-closed.
        Method hasActive = ProfileGate.class.getMethod("hasActiveBiometricPrompt");
        assertEquals(boolean.class, hasActive.getReturnType());

        assertFalse(ProfileGate.isBiometricSessionActive(null));

        Constructor<BiometricVault.Session> ctor = BiometricVault.Session.class.getDeclaredConstructor(
                Runnable.class, BooleanSupplier.class, Runnable.class);
        ctor.setAccessible(true);

        AtomicBoolean osCanceled = new AtomicBoolean(false);
        BiometricVault.Session session = ctor.newInstance(
                (Runnable) () -> osCanceled.set(true),
                (BooleanSupplier) osCanceled::get,
                (Runnable) () -> {});
        assertTrue(ProfileGate.isBiometricSessionActive(session));

        osCanceled.set(true);
        assertFalse(ProfileGate.isBiometricSessionActive(session));

        AtomicBoolean promptCanceled = new AtomicBoolean(false);
        BiometricVault.Session cancelable = ctor.newInstance(
                (Runnable) () -> promptCanceled.set(true),
                (BooleanSupplier) promptCanceled::get,
                (Runnable) () -> {});
        assertTrue(ProfileGate.isBiometricSessionActive(cancelable));
        cancelable.cancel();
        assertFalse(ProfileGate.isBiometricSessionActive(cancelable));
    }

    @Test
    public void composerFallbackAndLiveUiHighlightsContractsHold() throws Exception {
        // R14.6.2: Hidden inline composer falls back to native COMPOSER; visible composer stays inline.
        assertTrue(MainActivity.shouldOpenComposerFallback(false));
        assertFalse(MainActivity.shouldOpenComposerFallback(true));
        Method openComposer = MainActivity.class.getDeclaredMethod(
                "openDiscussionComposer", Account.class, boolean.class, boolean.class);
        assertEquals(void.class, openComposer.getReturnType());

        // R14.4.1: LiveUiPatch exposes localized highlights(Context) and drops legacy frenchHighlights/targetLabel.
        Method highlights = LiveUiPatch.class.getMethod("highlights", Context.class);
        assertEquals(java.util.List.class, highlights.getReturnType());
        for (Method m : LiveUiPatch.class.getDeclaredMethods()) {
            assertFalse("Legacy frenchHighlights must stay removed", "frenchHighlights".equals(m.getName()));
        }
        for (Method m : LiveUiPatch.Op.class.getDeclaredMethods()) {
            assertFalse("Legacy Op.targetLabel must stay removed", "targetLabel".equals(m.getName()));
        }
    }
}
