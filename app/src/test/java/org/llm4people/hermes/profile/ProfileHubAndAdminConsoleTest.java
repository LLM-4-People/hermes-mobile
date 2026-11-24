package org.llm4people.hermes.profile;

import org.junit.Test;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingShareSheet;

import java.util.List;

import static org.junit.Assert.*;

public final class ProfileHubAndAdminConsoleTest {
    @Test public void avatarInitialsSlugSuggestionAndValidationHandleAccentsAndHostileInput() {
        assertEquals("JD", ProfileHubSheet.avatarInitials("Jean Dupont"));
        assertEquals("A", ProfileHubSheet.avatarInitials("alex"));
        assertEquals("H", ProfileHubSheet.avatarInitials("   "));
        assertEquals("H", ProfileHubSheet.avatarInitials(null));

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
}
