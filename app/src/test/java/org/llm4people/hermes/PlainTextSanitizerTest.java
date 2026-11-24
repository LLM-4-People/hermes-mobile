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
}
