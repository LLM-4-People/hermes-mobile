package org.llm4people.hermes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
