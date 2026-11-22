package org.llm4people.hermes.ambient;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NotificationAllowlistTest {
    private static final class MemoryStore implements NotificationAllowlist.Store {
        final Map<String, String> map = new HashMap<>();
        @Override public String read(String key) { return map.get(key); }
        @Override public void write(String key, String value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
    }

    @Test
    public void deniesEverythingByDefaultUntilExplicitOptIn() {
        MemoryStore store = new MemoryStore();
        NotificationAllowlist allowlist = NotificationAllowlist.forStore(store);

        NotificationAllowlist.Settings initial = allowlist.settings();
        assertFalse(initial.notificationsEnabled());
        assertFalse(initial.mediaEnabled());
        assertFalse(initial.calendarEnabled());
        assertFalse(initial.contactsEnabled());
        assertFalse(initial.deviceContextEnabled());
        assertTrue(allowlist.allowedPackages().isEmpty());
        assertTrue(allowlist.effectiveAllowedPackages().isEmpty());

        // Adding a package while master opt-in is OFF still keeps effective allowlist empty.
        allowlist.setAllowed("org.thoughtcrime.securesms", true);
        assertEquals(Set.of("org.thoughtcrime.securesms"), allowlist.allowedPackages());
        assertTrue(allowlist.effectiveAllowedPackages().isEmpty());

        allowlist.saveSettings(new NotificationAllowlist.Settings(true, true, false, false, true));
        assertEquals(Set.of("org.thoughtcrime.securesms"), allowlist.effectiveAllowedPackages());
        assertTrue(allowlist.isAllowed("ORG.THOUGHTCRIME.SECURESMS"));
    }

    @Test
    public void refusesHardBlockedAndInvalidPackagesAndFiltersCorruptStorage() {
        MemoryStore store = new MemoryStore();
        NotificationAllowlist allowlist = NotificationAllowlist.forStore(store);

        for (String blocked : new String[]{
                "org.llm4people.hermes",
                "com.x8bit.bitwarden",
                "com.desjardins.mobile",
                "com.google.android.apps.authenticator2",
                "com.android.systemui",
                "not_a_package",
                "",
                "../escape"
        }) {
            assertThrows(blocked, IllegalArgumentException.class, () -> allowlist.setAllowed(blocked, true));
        }

        // Defense in depth: even if sensitive entries existed in storage, reads filter them out.
        store.write(NotificationAllowlist.KEY_ALLOWLIST,
                "com.whatsapp\ncom.x8bit.bitwarden\norg.llm4people.hermes\ncom.desjardins.mobile");
        assertEquals(Set.of("com.whatsapp"), allowlist.allowedPackages());
    }

    @Test
    public void profileWipeErasesSettingsAndAllowlistAndConfirmationGuardsFailClosed() {
        MemoryStore store = new MemoryStore();
        NotificationAllowlist allowlist = NotificationAllowlist.forStore(store);
        allowlist.saveSettings(new NotificationAllowlist.Settings(true, true, true, true, true));
        allowlist.setAllowed("com.whatsapp", true);
        assertFalse(store.map.isEmpty());

        allowlist.clear();
        assertTrue(store.map.isEmpty());
        assertEquals(NotificationAllowlist.Settings.ALL_OFF, allowlist.settings());
        assertTrue(allowlist.allowedPackages().isEmpty());
        assertTrue(allowlist.effectiveAllowedPackages().isEmpty());

        assertThrows(IllegalStateException.class,
                () -> HermesNotificationListener.sendReviewedReply(null, "k", "Salut", false));
        assertThrows(IllegalStateException.class,
                () -> MediaContextBridge.dispatchConfirmedAction(null, true, "com.spotify.music",
                        MediaContextBridge.ControlAction.PAUSE, false));
    }

    @Test
    public void staleInFlightIngestionCannotLeakAcrossProfileSwitch() {
        HermesNotificationListener.clearSession();
        HermesNotificationListener.bindProfile(Set.of("org.thoughtcrime.securesms"));
        long profileAEpoch = HermesNotificationListener.currentSessionEpoch();
        // Profile switches to Profile B (allowing only WhatsApp) while Signal ingestion is in flight.
        HermesNotificationListener.bindProfile(Set.of("com.whatsapp"));

        AmbientNotification staleSignal = new AmbientNotification(
                "k-signal", "org.thoughtcrime.securesms", "Signal", "Alice",
                "Alice", "Message privé", 1000L, false, "");
        assertFalse(HermesNotificationListener.commitBufferedIfCurrent(
                profileAEpoch, Set.of("org.thoughtcrime.securesms"), staleSignal, null));
        assertTrue(HermesNotificationListener.snapshot().isEmpty());
        HermesNotificationListener.clearSession();
    }
}
