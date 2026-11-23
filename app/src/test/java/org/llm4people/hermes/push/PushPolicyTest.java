package org.llm4people.hermes.push;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.time.LocalTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PushPolicyTest {
    @Before
    public void setUp() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDown() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void defaultsEnableAllFiveCategoriesAndDisableQuietHours() {
        PushPolicy policy = PushPolicy.defaults();
        assertEquals(5, DeliveryItem.Category.values().length);
        for (DeliveryItem.Category cat : DeliveryItem.Category.values()) {
            assertTrue(policy.isCategoryEnabled(cat));
        }
        assertFalse(policy.quietHoursEnabled);
        assertFalse(policy.isQuietAt(LocalTime.of(23, 30)));
        assertEquals(PushPolicy.Decision.NOTIFY,
                policy.evaluate("briefing", DeliveryItem.Urgency.NORMAL, LocalTime.of(23, 30)));
    }

    @Test
    public void perCategoryOptOutSuppressesMatchingTypedAndLegacyKindsOnly() {
        PushPolicy noFiles = PushPolicy.defaults().withCategory(DeliveryItem.Category.FILES, false);
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noFiles.evaluate("file", DeliveryItem.Urgency.HIGH, LocalTime.of(12, 0)));
        assertEquals(PushPolicy.Decision.NOTIFY,
                noFiles.evaluate("briefing", DeliveryItem.Urgency.NORMAL, LocalTime.of(12, 0)));

        PushPolicy noReminders = PushPolicy.defaults().withCategory(DeliveryItem.Category.REMINDERS, false);
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noReminders.evaluate("reminder", DeliveryItem.Urgency.NORMAL, LocalTime.of(9, 0)));
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noReminders.evaluate("reminder_result", DeliveryItem.Urgency.NORMAL, LocalTime.of(9, 0)));

        PushPolicy noScreen = PushPolicy.defaults().withCategory(DeliveryItem.Category.SCREEN_UPDATES, false);
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noScreen.evaluate("ui_patch", DeliveryItem.Urgency.NORMAL, LocalTime.of(10, 0)));
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noScreen.evaluate("widget_update", DeliveryItem.Urgency.NORMAL, LocalTime.of(10, 0)));

        PushPolicy noActions = PushPolicy.defaults().withCategory(DeliveryItem.Category.PROPOSED_ACTIONS, false);
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noActions.evaluate("device_action", DeliveryItem.Urgency.HIGH, LocalTime.of(10, 0)));
        assertEquals(PushPolicy.Decision.SUPPRESSED_CATEGORY,
                noActions.evaluate("approval_requested", DeliveryItem.Urgency.HIGH, LocalTime.of(10, 0)));
    }

    @Test
    public void silentUrgencyNeverPostsBannerEvenWhenCategoryEnabled() {
        PushPolicy policy = PushPolicy.defaults();
        assertEquals(PushPolicy.Decision.SILENT_INBOX_ONLY,
                policy.evaluate("card", DeliveryItem.Urgency.SILENT, LocalTime.of(14, 0)));
        assertEquals(PushPolicy.Decision.SILENT_INBOX_ONLY,
                policy.evaluate("widget_update", DeliveryItem.Urgency.SILENT, LocalTime.of(14, 0)));
    }

    @Test
    public void quietHoursHandleOvernightSameDayEqualAndHighUrgencyBreakthrough() {
        // Overnight 22:00 -> 07:00
        PushPolicy overnight = PushPolicy.defaults().withQuietHours(true, 22 * 60, 7 * 60, true);
        assertTrue(overnight.isQuietAt(LocalTime.of(22, 0)));
        assertTrue(overnight.isQuietAt(LocalTime.of(23, 59)));
        assertTrue(overnight.isQuietAt(LocalTime.of(0, 0)));
        assertTrue(overnight.isQuietAt(LocalTime.of(6, 59)));
        assertFalse(overnight.isQuietAt(LocalTime.of(7, 0)));
        assertFalse(overnight.isQuietAt(LocalTime.of(21, 59)));

        assertEquals(PushPolicy.Decision.SUPPRESSED_QUIET_HOURS,
                overnight.evaluate("briefing", DeliveryItem.Urgency.NORMAL, LocalTime.of(23, 15)));
        assertEquals(PushPolicy.Decision.NOTIFY,
                overnight.evaluate("device_action", DeliveryItem.Urgency.HIGH, LocalTime.of(23, 15)));

        PushPolicy strict = overnight.withQuietHours(true, 22 * 60, 7 * 60, false);
        assertEquals(PushPolicy.Decision.SUPPRESSED_QUIET_HOURS,
                strict.evaluate("device_action", DeliveryItem.Urgency.HIGH, LocalTime.of(23, 15)));

        // Same-day 13:00 -> 15:00
        PushPolicy afternoon = PushPolicy.defaults().withQuietHours(true, 13 * 60, 15 * 60, false);
        assertFalse(afternoon.isQuietAt(LocalTime.of(12, 59)));
        assertTrue(afternoon.isQuietAt(LocalTime.of(13, 0)));
        assertTrue(afternoon.isQuietAt(LocalTime.of(14, 59)));
        assertFalse(afternoon.isQuietAt(LocalTime.of(15, 0)));

        // Equal start == end -> inactive
        PushPolicy emptyWindow = PushPolicy.defaults().withQuietHours(true, 8 * 60, 8 * 60, false);
        assertFalse(emptyWindow.isQuietAt(LocalTime.of(8, 0)));
    }

    @Test
    public void encodeDecodeRoundTripsAndFailsClosedOnCorruptInput() {
        PushPolicy custom = PushPolicy.defaults()
                .withCategory(DeliveryItem.Category.FILES, false)
                .withCategory(DeliveryItem.Category.SCREEN_UPDATES, false)
                .withQuietHours(true, 21 * 60 + 30, 6 * 60 + 45, false);
        PushPolicy restored = PushPolicy.decode(custom.encode());
        assertEquals(custom, restored);
        assertEquals("21 h 30", PushPolicy.formatMinuteFr(restored.quietStartMinute));
        assertEquals("06 h 45", PushPolicy.formatMinuteFr(restored.quietEndMinute));

        // Corrupt payloads fail closed to defaults and emit a diagnostic warning.
        for (String bad : new String[]{"v2|bad", "v1|files|x|0|0|1", "v1|files|1|9999|0|1", "garbage"}) {
            assertEquals(PushPolicy.defaults(), PushPolicy.decode(bad));
        }
        assertTrue(Diagnostics.exportRedacted().contains("push.policy_decode_invalid"));
    }

    @Test
    public void storageKeysAreOpaqueAndIsolatedAcrossProfiles() {
        String keyA = PushPolicy.storageKey("profile-secret-alice");
        String keyB = PushPolicy.storageKey("profile-secret-bob");
        assertNotEquals(keyA, keyB);
        assertEquals(keyA, PushPolicy.storageKey("profile-secret-alice"));
        assertFalse(keyA.contains("alice"));
        assertFalse(keyB.contains("bob"));
    }

    @Test
    public void rejectsOutOfRangeMinutes() {
        for (int bad : new int[]{-1, 1440, 2000}) {
            try {
                PushPolicy.defaults().withQuietHours(true, bad, 420, true);
                fail("Expected IllegalArgumentException for start=" + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
            try {
                PushPolicy.defaults().withQuietHours(true, 1320, bad, true);
                fail("Expected IllegalArgumentException for end=" + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
