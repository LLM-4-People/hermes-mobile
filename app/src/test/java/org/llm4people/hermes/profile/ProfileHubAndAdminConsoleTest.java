package org.llm4people.hermes.profile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingShareSheet;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class ProfileHubAndAdminConsoleTest {
    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void restoreDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }
    @Test public void avatarInitialsSlugSuggestionAndValidationHandleAccentsAndHostileInput() {
        assertEquals("JD", Ui.initials("Jean Dupont"));
        assertEquals("A", Ui.initials("alex"));
        assertEquals("H", Ui.initials("   "));
        assertEquals("H", Ui.initials(null));

        assertEquals("elodie-martin", ProfileHubSheet.suggestSlug("Élodie Martin"));
        assertEquals("alex-2", ProfileHubSheet.suggestSlug("  Alex  2!! "));
        assertEquals("", ProfileHubSheet.suggestSlug("!!!"));

        assertTrue(ProfileHubSheet.isValidNewSlug("alex"));
        assertTrue(ProfileHubSheet.isValidNewSlug("team-42"));
        assertFalse(ProfileHubSheet.isValidNewSlug("a"));
        assertFalse(ProfileHubSheet.isValidNewSlug("Alex"));
        assertFalse(ProfileHubSheet.isValidNewSlug("../root"));
        assertFalse(ProfileHubSheet.isValidNewSlug("-leading"));
        assertFalse(ProfileHubSheet.isValidNewSlug(null));
    }

    @Test public void lastAdminFailClosedGuardMatchesServerConflictInvariant() {
        OnboardingModels.RemoteProfile soleAdmin = new OnboardingModels.RemoteProfile(
                "home", "Home", "fr", "", "", 0, 5, 0L, true, true);
        OnboardingModels.RemoteProfile secondAdmin = new OnboardingModels.RemoteProfile(
                "alex", "Alex", "en", "", "", 0, 2, 0L, true, true);
        OnboardingModels.RemoteProfile member = new OnboardingModels.RemoteProfile(
                "guest", "Guest", "fr", "", "", 0, 1, 0L, false, true);

        List<OnboardingModels.RemoteProfile> oneAdmin = List.of(soleAdmin, member);
        assertEquals(1, ProfileHubSheet.countAdmins(oneAdmin));
        assertFalse("Sole admin must never be demoted or deleted",
                ProfileHubSheet.canDemoteOrDeleteAdmin(oneAdmin, soleAdmin));
        assertTrue("Non-admin member can be managed/deleted",
                ProfileHubSheet.canDemoteOrDeleteAdmin(oneAdmin, member));

        List<OnboardingModels.RemoteProfile> twoAdmins = List.of(soleAdmin, secondAdmin, member);
        assertEquals(2, ProfileHubSheet.countAdmins(twoAdmins));
        assertTrue("Demoting one of two admins is safe",
                ProfileHubSheet.canDemoteOrDeleteAdmin(twoAdmins, secondAdmin));
        assertFalse(ProfileHubSheet.canDemoteOrDeleteAdmin(twoAdmins, null));
    }

    @Test public void adminSettingsValidatorAndDeleteConfirmationFailClosedOnBadInputs() {
        OnboardingModels.AdminSettings ok = AdminConsoleSheet.validateSettingsInput(
                "fr", "anthropic/claude-sonnet-4-5", "32", "12", "300", true);
        assertNotNull(ok);
        assertEquals("fr", ok.defaultLanguage());
        assertEquals(32, ok.maxConcurrentRuns());
        assertEquals(12, ok.perProfileConcurrentLimit());
        assertEquals(300, ok.pairingTtlSeconds());
        assertTrue(ok.allowSelfDevicePairing());

        // Per-profile cap exceeding global cap must fail closed.
        assertNull(AdminConsoleSheet.validateSettingsInput("en", "", "8", "12", "300", true));
        // Out-of-range TTL or non-numeric inputs must fail closed.
        assertNull(AdminConsoleSheet.validateSettingsInput("en", "", "32", "12", "30", true));
        assertNull(AdminConsoleSheet.validateSettingsInput("en", "", "32", "12", "9999", true));
        assertNull(AdminConsoleSheet.validateSettingsInput("en", "", "0", "1", "300", true));
        assertNull(AdminConsoleSheet.validateSettingsInput("en", "", "abc", "4", "300", true));

        assertTrue(AdminConsoleSheet.canConfirmDelete("alex", "alex"));
        assertTrue(AdminConsoleSheet.canConfirmDelete("alex", "  alex  "));
        assertFalse(AdminConsoleSheet.canConfirmDelete("alex", "Alex"));
        assertFalse(AdminConsoleSheet.canConfirmDelete("alex", ""));
        assertFalse(AdminConsoleSheet.canConfirmDelete("", ""));
        assertFalse(AdminConsoleSheet.canConfirmDelete("alex", null));
    }

    @Test public void countdownFormatterClampsNegativeDurationsSafely() {
        assertEquals("5:00", PairingShareSheet.formatCountdown(300));
        assertEquals("0:09", PairingShareSheet.formatCountdown(9));
        assertEquals("0:00", PairingShareSheet.formatCountdown(0));
        assertEquals("0:00", PairingShareSheet.formatCountdown(-42));
    }

    @Test public void usersLoadStateResolvesErrorWhenEmptyAndLoadedWhenFetchedOrCached() {
        assertEquals(AdminConsoleSheet.UsersLoadState.LOADED,
                AdminConsoleSheet.resolveUsersLoadState(true, false));
        assertEquals(AdminConsoleSheet.UsersLoadState.LOADED,
                AdminConsoleSheet.resolveUsersLoadState(true, true));
        assertEquals(AdminConsoleSheet.UsersLoadState.LOADED,
                AdminConsoleSheet.resolveUsersLoadState(false, true));
        assertEquals(AdminConsoleSheet.UsersLoadState.ERROR,
                AdminConsoleSheet.resolveUsersLoadState(false, false));
    }

    @Test public void postCloseExecutorGuardsPreventRejectedExecutionAcrossSheets() {
        AtomicInteger runs = new AtomicInteger();
        ExecutorService direct = new AbstractExecutorService() {
            private volatile boolean shutdown;
            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                if (shutdown) throw new RejectedExecutionException("shutdown");
                command.run();
            }
        };

        // Live open sheet submits cleanly.
        assertTrue(AdminConsoleSheet.submitTask(false, direct, runs::incrementAndGet, "admin"));
        assertTrue(PairingShareSheet.submitRevoke(false, direct, runs::incrementAndGet));
        assertEquals(2, runs.get());

        // Closed flag or null args fail closed without touching executor.
        assertFalse(AdminConsoleSheet.submitTask(true, direct, runs::incrementAndGet, "admin"));
        assertFalse(AdminConsoleSheet.submitTask(false, null, runs::incrementAndGet, "profile_hub"));
        assertFalse(AdminConsoleSheet.submitTask(false, direct, null, "admin"));
        assertFalse(PairingShareSheet.submitRevoke(true, direct, runs::incrementAndGet));
        assertFalse(PairingShareSheet.submitRevoke(false, null, runs::incrementAndGet));
        assertFalse(PairingShareSheet.submitRevoke(false, direct, null));
        assertEquals(2, runs.get());

        // Post-shutdown executor returns false cleanly without throwing RejectedExecutionException.
        direct.shutdownNow();
        assertFalse(AdminConsoleSheet.submitTask(false, direct, runs::incrementAndGet, "admin"));
        assertFalse(AdminConsoleSheet.submitTask(false, direct, runs::incrementAndGet, "profile_hub"));
        assertFalse(PairingShareSheet.submitRevoke(false, direct, runs::incrementAndGet));

        ExecutorService realPool = Executors.newSingleThreadExecutor();
        realPool.shutdownNow();
        assertFalse(AdminConsoleSheet.submitTask(false, realPool, runs::incrementAndGet, "admin"));
        assertFalse(PairingShareSheet.submitRevoke(false, realPool, runs::incrementAndGet));
        assertEquals(2, runs.get());

        // Simulated concurrent close race where isShutdown() was false but execute() rejects.
        ExecutorService racingPool = new AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { throw new RejectedExecutionException("raced"); }
        };
        assertFalse(AdminConsoleSheet.submitTask(false, racingPool, runs::incrementAndGet, "admin"));
        assertTrue(Diagnostics.exportRedacted().contains("admin.io_submit_rejected"));

        Diagnostics.clear();
        assertFalse(AdminConsoleSheet.submitTask(false, racingPool, runs::incrementAndGet, "profile_hub"));
        assertTrue(Diagnostics.exportRedacted().contains("profile_hub.io_submit_rejected"));

        Diagnostics.clear();
        assertFalse(PairingShareSheet.submitRevoke(false, racingPool, runs::incrementAndGet));
        assertTrue(Diagnostics.exportRedacted().contains("onboarding.pairing_revoke_rejected"));
    }

    @Test public void profileInviteNormalizationNoteChangeGuardAndCredentialHintPolicy() {
        ProfileInviteDialogs.CreateProfileParams named =
                ProfileInviteDialogs.normalizeCreateProfile("  Alex  ", "Alex Martin", " fr ", true);
        assertEquals("alex", named.slug());
        assertEquals("Alex Martin", named.displayName());
        assertEquals("fr", named.preferredLanguage());
        assertTrue(named.grantAdmin());

        // Blank display name falls back to normalized slug; null inputs fail-safe to empty strings.
        ProfileInviteDialogs.CreateProfileParams fallback =
                ProfileInviteDialogs.normalizeCreateProfile("Team-42", "   ", null, false);
        assertEquals("team-42", fallback.slug());
        assertEquals("team-42", fallback.displayName());
        assertEquals("", fallback.preferredLanguage());
        assertFalse(fallback.grantAdmin());

        ProfileInviteDialogs.CreateProfileParams allNull =
                ProfileInviteDialogs.normalizeCreateProfile(null, null, null, false);
        assertEquals("", allNull.slug());
        assertEquals("", allNull.displayName());
        assertEquals("", allNull.preferredLanguage());
        assertNull(ProfileInviteDialogs.extractCreatedTicket(null));

        // R20.5.2: ProfileScreen.changedNoteText suppresses redundant setText calls on live-region notes.
        assertNull(ProfileScreen.changedNoteText("Ready", "Ready"));
        assertNull(ProfileScreen.changedNoteText("", null));
        assertNull(ProfileScreen.changedNoteText("", ""));
        assertEquals("Mismatch", ProfileScreen.changedNoteText("Ready", "Mismatch"));
        assertEquals("", ProfileScreen.changedNoteText("Ready", null));
        assertEquals("Ready", ProfileScreen.changedNoteText(null, "Ready"));

        // R20.5.2: PIN/passphrase live-region policy announces discrete states and silences per-keystroke progress.
        assertTrue(ProfileGate.shouldAnnounceCredentialState(ProfileLockPolicy.CredentialState.READY));
        assertTrue(ProfileGate.shouldAnnounceCredentialState(ProfileLockPolicy.CredentialState.MISMATCH));
        assertTrue(ProfileGate.shouldAnnounceCredentialState(ProfileLockPolicy.CredentialState.INVALID_FORMAT));
        assertFalse(ProfileGate.shouldAnnounceCredentialState(ProfileLockPolicy.CredentialState.EMPTY));
        assertFalse(ProfileGate.shouldAnnounceCredentialState(ProfileLockPolicy.CredentialState.TOO_SHORT));
        assertFalse(ProfileGate.shouldAnnounceCredentialState(ProfileLockPolicy.CredentialState.AWAITING_CONFIRM));
        assertFalse(ProfileGate.shouldAnnounceCredentialState(null));

        // R21.5.2: AdminConsoleSheet dedupes repeated users-load error announcements across tab switches.
        assertTrue(AdminConsoleSheet.shouldAnnounceUsersError(true, "Load failed", null));
        assertFalse(AdminConsoleSheet.shouldAnnounceUsersError(true, "Load failed", "Load failed"));
        assertFalse(AdminConsoleSheet.shouldAnnounceUsersError(false, "Load failed", null));
        assertFalse(AdminConsoleSheet.shouldAnnounceUsersError(true, "", null));
        assertFalse(AdminConsoleSheet.shouldAnnounceUsersError(true, null, null));
    }
}
