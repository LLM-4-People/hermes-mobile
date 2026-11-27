package org.llm4people.hermes;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/** Pure-JVM regression tests for {@link Digests} SHA-256 and hex encoding helpers. */
public final class DigestsTest {
    @Test
    public void matchesStandardSha256AndHexVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Digests.sha256Hex(new byte[0]));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Digests.sha256Hex(""));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Digests.sha256Hex("hello"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Digests.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Digests.sha256Hex("héllo 🌍".getBytes(StandardCharsets.UTF_8)),
                Digests.sha256Hex("héllo 🌍"));
        assertEquals("", Digests.toHex(new byte[0]));
        assertEquals("000f10ff", Digests.toHex(new byte[]{0x00, 0x0F, 0x10, (byte) 0xFF}));
        assertNotNull(Digests.sha256());
    }

    @Test
    public void rejectsNullInputsFailClosed() {
        assertThrows(NullPointerException.class, () -> Digests.toHex(null));
        assertThrows(NullPointerException.class, () -> Digests.sha256Hex((byte[]) null));
        assertThrows(NullPointerException.class, () -> Digests.sha256Hex((String) null));
    }
}
