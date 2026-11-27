package org.llm4people.hermes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlainTextSanitizerTest {
    @Test
    public void handlesNullEmptyWhitespaceAndNonPositiveBounds() {
        assertEquals("", PlainTextSanitizer.collapseBounded(null, 32));
        assertEquals("", PlainTextSanitizer.collapseBounded("", 32));
        assertEquals("", PlainTextSanitizer.collapseBounded("   \t\r\n  ", 32));
        assertEquals("", PlainTextSanitizer.collapseBounded("hello", 0));
        assertEquals("", PlainTextSanitizer.collapseBounded("hello", -5));
    }

    @Test
    public void stripsControlAndFormatCharsAndCollapsesWhitespace() {
        assertEquals("Hello world", PlainTextSanitizer.collapseBounded("  Hello   world  ", 64));
        // Control chars (\u0000, \u0007, \u007F) and Unicode format chars (\u200B, \u202E) are stripped.
        assertEquals("Alertname", PlainTextSanitizer.collapseBounded("\u0000Alert\u0007\u200B\u202Ename\u007F", 64));
        // ISO control whitespace (\n, \r, \t) is stripped before the whitespace branch; non-control whitespace collapses.
        assertEquals("ab c", PlainTextSanitizer.collapseBounded("a\t\n\rb  \u3000 c", 64));
    }

    @Test
    public void respectsExactBoundaryTruncationAndNonAsciiText() {
        assertEquals("Montréal", PlainTextSanitizer.collapseBounded("Montréal", 8));
        assertEquals("Montr", PlainTextSanitizer.collapseBounded("Montréal", 5));
        // Trailing space at boundary is trimmed cleanly.
        assertEquals("Hello", PlainTextSanitizer.collapseBounded("Hello world", 6));
        assertEquals("Bonjour café ☕", PlainTextSanitizer.collapseBounded("  Bonjour   café   ☕  ", 64));
    }

    @Test
    public void preservesWholeSurrogatePairsAndStripsUnpairedSurrogatesAndPlane14Tags() {
        assertEquals("ab", PlainTextSanitizer.collapseBounded("ab😀", 3));
        assertEquals("ab😀", PlainTextSanitizer.collapseBounded("ab😀", 4));
        assertEquals("ab", PlainTextSanitizer.collapseBounded("a\uD83Db", 16));
        assertEquals("ab", PlainTextSanitizer.collapseBounded("a\uDC00b", 16));
        // Plane 14 tag space U+E0020 (\uDB40\uDC20) is supplementary Character.FORMAT and must be stripped.
        assertEquals("sk-123", PlainTextSanitizer.collapseBounded("sk-\uDB40\uDC20123", 16));
    }

    @Test
    public void collapsesUnicodeSpaceCharsAndEllipsisNeverSplitsSurrogates() {
        assertEquals("a b c d", PlainTextSanitizer.collapseBounded("a\u00A0b\u2007c\u202Fd", 32));
        assertEquals("Bonjour monde", PlainTextSanitizer.collapseWithEllipsis("Bon\u202E\uDB40\uDC20jour\u00A0\u202Fmonde", 32));
        assertEquals("ab…", PlainTextSanitizer.collapseWithEllipsis("ab😀cd", 4));
        assertEquals("ab😀…", PlainTextSanitizer.collapseWithEllipsis("ab😀cd", 5));
        assertEquals("ab", PlainTextSanitizer.collapseWithEllipsis("a\uD83Db", 10));
        assertEquals("…", PlainTextSanitizer.collapseWithEllipsis("hello", 1));
    }

    @Test
    public void foldAccentsStripsDiacriticsAndLowercasesRootLocale() {
        assertEquals("", PlainTextSanitizer.foldAccents(null));
        assertEquals("", PlainTextSanitizer.foldAccents(""));
        assertEquals("ete", PlainTextSanitizer.foldAccents("Été"));
        assertEquals("naive", PlainTextSanitizer.foldAccents("Naïve"));
        assertEquals("cœur", PlainTextSanitizer.foldAccents("CŒUR"));
        assertEquals("resume cafe", PlainTextSanitizer.foldAccents("Re\u0301sume\u0301 CAFE\u0301"));
    }

    @Test
    public void handlesMaxIntBoundsWithoutOverflowAndStripsCgj() {
        assertEquals("Hello world", PlainTextSanitizer.collapseWithEllipsis("  Hello\nworld  ", Integer.MAX_VALUE));
        assertEquals("Hello world", PlainTextSanitizer.collapseWithEllipsis("  Hello\nworld  ", Integer.MAX_VALUE - 1));
        assertEquals("Hello world", PlainTextSanitizer.collapseBounded("  Hello world  ", Integer.MAX_VALUE));
        // U+034F (Combining Grapheme Joiner) is stripped from display previews while keeping emoji VS-16 intact.
        assertEquals("sk-123", PlainTextSanitizer.collapseBounded("sk-\u034F123", 16));
        assertEquals("Bonjour café ☕\uFE0F", PlainTextSanitizer.collapseWithEllipsis("Bon\u034Fjour café ☕\uFE0F", 32));
        // R17.2.3: Whitespace right at maxChars must not silently drop subsequent words or spuriously ellipsize trailing spaces.
        assertEquals("hell…", PlainTextSanitizer.collapseWithEllipsis("hello world", 5));
        assertEquals("hello", PlainTextSanitizer.collapseWithEllipsis("hello   ", 5));
        assertEquals("hello", PlainTextSanitizer.collapseWithEllipsis("hello\n\t  ", 5));
    }

    @Test
    public void hasVisibleTextAndVisibleTextIsBlankAgreeOnAllInvisibleAndVisibleInputs() {
        String plane14Tag = "\uDB40\uDC20";
        String supplementaryVs = new String(Character.toChars(0xE0100));
        String[] invisible = {
                null,
                "",
                "   \t\r\n  ",
                "\u00A0\u2003\u2007\u202F\u3000",
                "\u0000\u0007\u001F\u007F\u0085",
                "\u200B\u200D\u202E\uFEFF",
                plane14Tag,
                "\u034F",
                "\u180B\u180E\u180F",
                "\uFE00\uFE0F",
                supplementaryVs,
                "\u115F\u1160\u3164\uFFA0",
                "\uD83D",
                "\uDC00",
                " \u00A0\u202E\u034F\uFE0F\u115F\u3164\uD83D "
        };
        for (String sample : invisible) {
            assertFalse("Expected invisible sample to be rejected", PlainTextSanitizer.hasVisibleText(sample));
        }

        String[] visible = {
                "a",
                "é",
                "字",
                "\uD83D\uDE00",
                " \u202E\u034F\uFE0F ok ",
                "\u00A0résumé\u2003\u2708\uFE0F字" + supplementaryVs
        };
        for (String sample : visible) {
            assertTrue("Expected visible sample to be accepted: " + sample, PlainTextSanitizer.hasVisibleText(sample));
        }
    }

    @Test
    public void stripsHangulFillersMongolianFvsUnattachedVsAndNeverFabricatesEllipsis() {
        // R19.2.1: Standalone strippable invisibles & variation selectors classification.
        for (int cp : new int[]{0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x180B, 0x180C, 0x180D, 0x180E, 0x180F}) {
            assertTrue("Expected strippable invisible U+" + Integer.toHexString(cp),
                    PlainTextSanitizer.isStrippableInvisible(cp));
            assertFalse(PlainTextSanitizer.isVariationSelector(cp));
            assertTrue(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("a" + new String(Character.toChars(cp)) + "b"));
        }
        for (int cp : new int[]{0xFE00, 0xFE0F, 0xE0100, 0xE01EF}) {
            assertTrue(PlainTextSanitizer.isVariationSelector(cp));
            assertFalse(PlainTextSanitizer.isStrippableInvisible(cp));
        }
        assertFalse(PlainTextSanitizer.hasUnsafeSingleLineCodePoint(null));
        assertFalse(PlainTextSanitizer.hasUnsafeSingleLineCodePoint(""));
        assertFalse(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("Bonjour café ☕\uFE0F 😀"));
        assertTrue(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("a\nb"));
        assertTrue(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("a\u202Eb"));
        assertTrue(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("a\uD83Db"));
        assertTrue(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("a\u2028b"));
        assertTrue(PlainTextSanitizer.hasUnsafeSingleLineCodePoint("a\u2029b"));

        // Padding attacks with Hangul fillers, Mongolian FVS, or leading/repeated variation selectors never consume budget.
        assertEquals("ok", PlainTextSanitizer.collapseBounded("\u3164".repeat(160) + "ok", 16));
        assertEquals("ok", PlainTextSanitizer.collapseBounded("\u180F".repeat(60) + "ok", 16));
        assertEquals("ok", PlainTextSanitizer.collapseBounded("\uFE0F".repeat(160) + "ok", 16));
        assertEquals("ok", PlainTextSanitizer.collapseWithEllipsis("\u3164".repeat(320) + "ok", 16));
        assertEquals("ok", PlainTextSanitizer.collapseWithEllipsis("\uFE0F".repeat(320) + "ok", 16));

        // Legitimate emoji with a single attached variation selector stays intact; repeated VS are dropped.
        assertEquals("☕\uFE0F Pause", PlainTextSanitizer.collapseBounded("☕\uFE0F\uFE0F\uFE0F Pause", 32));
        assertEquals("☕\uFE0F Pause", PlainTextSanitizer.collapseWithEllipsis("☕\uFE0F\uFE0F\uFE0F Pause", 32));

        // Invisible-only inputs longer than maxChars must return "" and NEVER fabricate visible "…".
        assertEquals("", PlainTextSanitizer.collapseWithEllipsis("\u3164".repeat(305), 300));
        assertEquals("", PlainTextSanitizer.collapseWithEllipsis("\uFE0F".repeat(305), 300));
        assertEquals("", PlainTextSanitizer.collapseWithEllipsis("\u180F".repeat(305), 300));
        assertEquals("", PlainTextSanitizer.collapseWithEllipsis(" \uFE0F ", 1));
        assertEquals("", PlainTextSanitizer.collapseBounded("\u3164".repeat(305), 300));
        assertEquals("", PlainTextSanitizer.collapseBounded("\uFE0F".repeat(305), 300));
    }

    @Test
    public void safeDisplayAndMultilineValidatorsAgreeOnBidiFillersCgjAndLegitimateEmoji() {
        assertTrue(PlainTextSanitizer.isSafeDisplayCodePoint('A'));
        assertTrue(PlainTextSanitizer.isSafeDisplayCodePoint('é'));
        assertTrue(PlainTextSanitizer.isSafeDisplayCodePoint(0x200D)); // ZWJ for emoji sequences
        assertTrue(PlainTextSanitizer.isSafeDisplayCodePoint(0xFE0F)); // Emoji VS-16
        assertTrue(PlainTextSanitizer.isSafeDisplayCodePoint(0x1F600)); // 😀
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint('\n'));
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint('\u0000'));
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0x202E)); // RLO BiDi override
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0x2066)); // LRI BiDi isolate
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0x2028)); // Line separator
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0x2029)); // Paragraph separator
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0x3164)); // Hangul filler
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0x034F)); // CGJ
        assertFalse(PlainTextSanitizer.isSafeDisplayCodePoint(0xD83D)); // Unpaired surrogate

        assertFalse(PlainTextSanitizer.hasUnsafeMultilineCodePoint(null, true));
        assertFalse(PlainTextSanitizer.hasUnsafeMultilineCodePoint("Ligne 1\nLigne 2\tOK ☕\uFE0F 👨‍👩‍👧‍👦", true));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("Ligne 1\tTab", false));
        assertFalse(PlainTextSanitizer.hasUnsafeMultilineCodePoint("Ligne 1\nLigne 2\nOK", false));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("Ligne 1\r\nOK", false));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("a\u202Eb", true));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("a\u2066b", true));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("a\u3164b", true));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("a\u034Fb", true));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("a\u2028b", true));
        assertTrue(PlainTextSanitizer.hasUnsafeMultilineCodePoint("a\uD83Db", true));
    }
}
