package org.llm4people.hermes.update;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class UpdatePanelFormatTest {
    @Test
    public void formatBytesUsesLocaleAwareUnitsAndDecimalSeparators() {
        assertEquals("0 B", UpdatePanel.formatBytes(0L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("512 B", UpdatePanel.formatBytes(512L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("1,023 B", UpdatePanel.formatBytes(1023L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("1.0 KB", UpdatePanel.formatBytes(1024L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("2.5 KB", UpdatePanel.formatBytes(2560L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("1.0 MB", UpdatePanel.formatBytes(1024L * 1024L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("1.5 MB", UpdatePanel.formatBytes(1_572_864L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));

        assertEquals("512 o", UpdatePanel.formatBytes(512L, Locale.CANADA_FRENCH, "%1$s o", "%1$s ko", "%1$s Mo"));
        assertEquals("2,5 ko", UpdatePanel.formatBytes(2560L, Locale.CANADA_FRENCH, "%1$s o", "%1$s ko", "%1$s Mo"));
        assertEquals("1,5 Mo", UpdatePanel.formatBytes(1_572_864L, Locale.CANADA_FRENCH, "%1$s o", "%1$s ko", "%1$s Mo"));
    }

    @Test
    public void formatBytesClampsNegativeAndHandlesNullLocale() {
        assertEquals("0 B", UpdatePanel.formatBytes(-42L, Locale.US, "%1$s B", "%1$s KB", "%1$s MB"));
        assertEquals("0 o", UpdatePanel.formatBytes(Long.MIN_VALUE, Locale.CANADA_FRENCH, "%1$s o", "%1$s ko", "%1$s Mo"));
        String fallback = UpdatePanel.formatBytes(2048L, null, "%1$s B", "%1$s KB", "%1$s MB");
        assertFalse(fallback.trim().isEmpty());
    }
}
