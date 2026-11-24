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
            assertTrue("Expected VisibleText.isBlank parity", VisibleText.isBlank(sample));
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
            assertFalse("Expected VisibleText.isBlank parity: " + sample, VisibleText.isBlank(sample));
        }
    }
}
