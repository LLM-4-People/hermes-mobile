package org.llm4people.hermes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public final class Base64UrlTest {
    @Test
    public void roundTripsEmptyNullAsciiAndUtf8WithoutPadding() {
        assertEquals("", Base64Url.encode(null));
        assertEquals("", Base64Url.encode(""));
        assertEquals("", Base64Url.decode(null));
        assertEquals("", Base64Url.decode(""));

        String sample = "Bonjour Montréal — café\nLigne 2\t|émoji";
        String encoded = Base64Url.encode(sample);
        assertFalse("URL-safe unpadded output must not contain '='", encoded.contains("="));
        assertFalse("URL-safe output must not contain '+' or '/'", encoded.contains("+") || encoded.contains("/"));
        assertEquals(sample, Base64Url.decode(encoded));
    }

    @Test
    public void rejectsInvalidBase64InputFailClosed() {
        for (String bad : new String[]{"***not-base64***", "a", "abc=def"}) {
            try {
                Base64Url.decode(bad);
                fail("Expected IllegalArgumentException for invalid input: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
