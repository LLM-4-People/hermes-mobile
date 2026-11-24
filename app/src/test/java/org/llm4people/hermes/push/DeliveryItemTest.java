package org.llm4people.hermes.push;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DeliveryItemTest {
    private static final String VALID_ID = "0123456789abcdef0123456789abcdef";

    @Test
    public void supportsAllEightTypedKindsWithPlainFrenchLabelsAndCategories() {
        String[] wireKinds = {
                "notice", "card", "file", "ui_patch",
                "widget_update", "device_action", "reminder_result", "briefing"
        };
        assertEquals(wireKinds.length, DeliveryItem.Kind.values().length);
        for (String wire : wireKinds) {
            DeliveryItem.Kind kind = DeliveryItem.Kind.fromWire(wire);
            assertNotNull("Missing kind " + wire, kind);
            assertNotNull(kind.category);
            assertTrue(kind.labelRes != 0);
            assertTrue(kind.lockscreenNoticeTitleRes != 0);
            assertTrue(kind.primaryActionRes != 0);
            assertEquals(kind.category, DeliveryItem.Category.forWireKind(wire));
        }
        assertNull(DeliveryItem.Kind.fromWire("unknown_kind"));
        assertNull(DeliveryItem.Kind.fromWire(null));
    }

    @Test
    public void urgencyParsingAndAcknowledgmentImmutability() {
        assertEquals(DeliveryItem.Urgency.NORMAL, DeliveryItem.Urgency.fromWire("normal"));
        assertEquals(DeliveryItem.Urgency.HIGH, DeliveryItem.Urgency.fromWire("HIGH"));
        assertEquals(DeliveryItem.Urgency.HIGH, DeliveryItem.Urgency.fromWire("urgent"));
        assertEquals(DeliveryItem.Urgency.SILENT, DeliveryItem.Urgency.fromWire("silent"));
        assertNull(DeliveryItem.Urgency.fromWire("bogus"));
        assertEquals(DeliveryItem.Urgency.NORMAL, DeliveryItem.Urgency.fromWireOrDefault("bogus"));
        assertTrue(DeliveryItem.isValidId("dlv_0123456789abcdef"));

        DeliveryItem item = new DeliveryItem(
                VALID_ID,
                DeliveryItem.Kind.BRIEFING,
                DeliveryItem.Urgency.NORMAL,
                "Résumé du matin",
                "3 points clés prêts",
                "{\"body\":\"Détails\"}",
                1700000000L,
                "sess-1",
                "run-1",
                null,
                0L);
        assertFalse(item.isAcknowledged());
        assertEquals(org.llm4people.hermes.R.string.push_kind_briefing_action, item.primaryActionRes());

        DeliveryItem acked = item.withAcknowledged(1700000099L);
        assertFalse(item.isAcknowledged());
        assertTrue(acked.isAcknowledged());
        assertEquals(1700000099L, acked.ackedAtEpochSec);
        // Second ack preserves first timestamp idempotently.
        assertEquals(1700000099L, acked.withAcknowledged(1700000500L).ackedAtEpochSec);
    }

    @Test
    public void rejectsMalformedIdsTitlesPayloadsAndControlCharacters() {
        for (String badId : new String[]{null, "", "../escape", "a/b", "has space", "a".repeat(65)}) {
            assertFalse("Accepted bad id " + badId, DeliveryItem.isValidId(badId));
            try {
                new DeliveryItem(badId, DeliveryItem.Kind.NOTICE, DeliveryItem.Urgency.NORMAL,
                        "Titre", "", "{}", 1L, null, null, null, 0L);
                fail("Expected rejection for id=" + badId);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        for (String badTitle : new String[]{
                null, "   ", "Line1\nLine2", "a".repeat(DeliveryItem.MAX_TITLE_CHARS + 1),
                "Titre\u202E", "Titre\u0085", "Titre\uD83D", "Titre\u2028", "Titre\u2029", "Titre\u034F",
                "Titre\u3164", "Titre\u180F",
                "\u00A0", "\u200B", "\u034F", "\uFE0F", "\u115F\u3164"}) {
            try {
                new DeliveryItem(VALID_ID, DeliveryItem.Kind.NOTICE, DeliveryItem.Urgency.NORMAL,
                        badTitle, "", "{}", 1L, null, null, null, 0L);
                fail("Expected rejection for title");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        try {
            new DeliveryItem(VALID_ID, DeliveryItem.Kind.CARD, DeliveryItem.Urgency.NORMAL,
                    "Titre", "", "x".repeat(DeliveryItem.MAX_PAYLOAD_CHARS + 1), 1L, null, null, null, 0L);
            fail("Expected rejection for oversized payload");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        for (String badLink : new String[]{
                "bad\nsession", "bad\u202Elink", "bad\u0085link", "bad\uD83Dlink",
                "bad\u2028link", "bad\u2029link", "bad\u034Flink", "bad\u3164link", "bad\u180Flink", "\u115F\u3164"}) {
            try {
                new DeliveryItem(VALID_ID, DeliveryItem.Kind.NOTICE, DeliveryItem.Urgency.NORMAL,
                        "Titre", "", "{}", 1L, badLink, null, null, 0L);
                fail("Expected rejection for unsafe link identifier");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        // Invisible-only optional link (non-ASCII space / bare variation selector) normalizes to null rather than persisting phantom whitespace.
        DeliveryItem invisibleLink = new DeliveryItem(VALID_ID, DeliveryItem.Kind.NOTICE, DeliveryItem.Urgency.NORMAL,
                "Titre", "", "{}", 1L, "\u00A0", "\uFE0F", "\u2003", 0L);
        assertNull(invisibleLink.sessionId);
        assertNull(invisibleLink.runId);
        assertNull(invisibleLink.jobId);
    }

    @Test
    public void syncQueryAppliesOneSecondOverlapAndPaginationAndSingleDeliveryPath() {
        assertEquals("/api/mobile/deliveries?include_acked=true&limit=50",
                DeliveryInbox.buildSyncQuery(DeliveryInbox.DELIVERIES_PATH, 0L, 0L));
        assertEquals("/api/mobile/deliveries?include_acked=true&limit=50&since=1699999999",
                DeliveryInbox.buildSyncQuery(DeliveryInbox.DELIVERIES_PATH, 1700000000L, 0L));
        assertEquals("/api/mobile/deliveries?include_acked=true&limit=50&since=1699999999&after=50",
                DeliveryInbox.buildSyncQuery(DeliveryInbox.DELIVERIES_PATH, 1700000000L, 50L));
        assertEquals("/api/mobile/deliveries/dlv_0123456789abcdef",
                MobilePush.deliveryPath("dlv_0123456789abcdef"));
    }

    @Test
    public void parsesEpochSecondsEpochMillisAndIso8601WireTimestampsAndRejectsInvalid() {
        assertEquals(1700000000L, DeliveryItem.parseEpochSecValue(1700000000L));
        assertEquals(1700000000L, DeliveryItem.parseEpochSecValue(1700000000000L));
        assertEquals(1700000000L, DeliveryItem.parseEpochSecValue("1700000000"));
        assertEquals(1700000000L, DeliveryItem.parseEpochSecValue("1700000000000"));
        assertEquals(1795357800L, DeliveryItem.parseEpochSecValue("2026-11-22T14:30:00Z"));
        assertEquals(1795357800L, DeliveryItem.parseEpochSecValue("2026-11-22T09:30:00-05:00"));

        assertEquals(0L, DeliveryItem.parseEpochSecValue(null));
        assertEquals(0L, DeliveryItem.parseEpochSecValue(""));
        assertEquals(0L, DeliveryItem.parseEpochSecValue("not-a-timestamp"));
        assertEquals(0L, DeliveryItem.parseEpochSecValue(-500L));
        assertEquals(0L, DeliveryItem.parseEpochSecValue(Double.NaN));
        assertEquals(0L, DeliveryItem.parseEpochSecValue(Double.POSITIVE_INFINITY));
    }

    @Test
    public void clampSummarySanitizesBidiCgjAndNeverSplitsSurrogatePairs() {
        assertEquals("", DeliveryItem.clampSummary(null));
        assertEquals("", DeliveryItem.clampSummary("  \u202E\u034F\n\t "));
        assertEquals("Bonjour monde", DeliveryItem.clampSummary("Bon\u202E\u034F\uDB40\uDC20jour\u00A0monde"));

        String emojiBoundary = "a".repeat(298) + "\uD83D\uDE00tail";
        String clamped = DeliveryItem.clampSummary(emojiBoundary);
        assertTrue(clamped.length() <= 300);
        assertTrue(clamped.endsWith("…"));
        assertFalse("Must never split surrogate pair at summary boundary",
                Character.isHighSurrogate(clamped.charAt(clamped.length() - 2)));

        // R17.2.1: Constructor and selectSummary sanitize top-level summary and fall back when invisible-only.
        DeliveryItem constructed = new DeliveryItem(
                VALID_ID, DeliveryItem.Kind.NOTICE, DeliveryItem.Urgency.NORMAL,
                "Titre", "Bon\u202E\u034Fjour " + "x".repeat(310), "{}", 1L, null, null, null, 0L);
        assertTrue(constructed.summary.length() <= 300);
        assertTrue(constructed.summary.startsWith("Bonjour "));
        assertTrue(constructed.summary.endsWith("…"));
        assertFalse(constructed.summary.contains("\u202E"));

        DeliveryItem invisibleSummary = new DeliveryItem(
                VALID_ID, DeliveryItem.Kind.NOTICE, DeliveryItem.Urgency.NORMAL,
                "Titre", "  \u202E\u034F\n\t ", "{}", 1L, null, null, null, 0L);
        assertEquals("", invisibleSummary.summary);

        assertEquals("", DeliveryItem.clampSummary("\uFE0F"));
        assertEquals("", DeliveryItem.clampSummary("\u115F\u3164"));
        assertEquals("", DeliveryItem.clampSummary("\u3164".repeat(305)));
        assertEquals("", DeliveryItem.clampSummary("\uFE0F".repeat(305)));
        assertEquals("Fallback body", DeliveryItem.selectSummary("  \u202E\u034F\n ", "  ", "Fallback\u202E body"));
        assertEquals("Fallback body", DeliveryItem.selectSummary("\uFE0F", "Fallback body"));
        assertEquals("Fallback body", DeliveryItem.selectSummary("\u3164".repeat(305), "Fallback body"));
        assertEquals("Fallback body", DeliveryItem.selectSummary("\uFE0F".repeat(305), "Fallback body"));
        assertEquals("Top summary", DeliveryItem.selectSummary("Top\u034F summary", "Fallback body"));
    }
}
