package org.llm4people.hermes;

import org.junit.Test;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.onboarding.PairingLinkParser;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pure-JVM regression tests for strict unsigned ASCII hex parsing across Digests, PairingLinkParser, UpdateJson, and LiveUiPatch (R35.2.1). */
public final class DigestsHexAndParserTest {

    @Test
    public void parseUnsignedHexAcceptsValidSlicesAndRejectsSignedOrMalformedInputs() {
        assertEquals(0x0041, Digests.parseUnsignedHex("0041", 0, 4));
        assertEquals(0x0041, Digests.parseUnsignedHex("xx0041yy", 2, 4));
        assertEquals(0xAF09, Digests.parseUnsignedHex("aF09", 0, 4));
        assertEquals(0x7FFFFFFF, Digests.parseUnsignedHex("7fffffff", 0, 8));
        assertEquals(0xFFFFFFFF, Digests.parseUnsignedHex("FFFFFFFF", 0, 8));

        String[] rejected = {
                "+041", "-001", "+A", "-1", " 041", "041 ", "004g", "004G", "\u0660\u0660\u0664\u0661"
        };
        for (String bad : rejected) {
            try {
                Digests.parseUnsignedHex(bad, 0, bad.length());
                fail("Expected IllegalArgumentException for: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        for (int[] badSlice : new int[][]{{0, 0}, {0, 9}, {-1, 2}, {3, 2}}) {
            try {
                Digests.parseUnsignedHex("0041", badSlice[0], badSlice[1]);
                fail("Expected IllegalArgumentException for slice");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            Digests.parseUnsignedHex(null, 0, 2);
            fail("Expected IllegalArgumentException for null");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void pairingLinkParserRejectsSignedUnicodeEscapesAndAcceptsValidHex() {
        String validJson = "{\"host\":\"https://hermes.example.org/p/alice\",\"code\":\"\\u0041BC123456\"}";
        PairingLinkParser.ParsedInput ok = PairingLinkParser.parseOrNull(validJson);
        assertNotNull(ok);
        assertEquals("ABC123456", ok.pairingCode());

        assertNull(PairingLinkParser.parseOrNull("{\"host\":\"https://hermes.example.org/p/alice\",\"code\":\"\\u+041BC123456\"}"));
        assertNull(PairingLinkParser.parseOrNull("{\"host\":\"https://hermes.example.org/p/alice\",\"code\":\"\\u-001BC123456\"}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void updateJsonRejectsSignedUnicodeEscapesAndAcceptsValidHex() throws Exception {
        Class<?> updateJson = Class.forName("org.llm4people.hermes.update.UpdateJson");
        Method parseObject = updateJson.getDeclaredMethod("parseObject", String.class);
        parseObject.setAccessible(true);

        Map<String, Object> parsed = (Map<String, Object>) parseObject.invoke(null, "{\"tag\":\"\\u00761.0.0\"}");
        assertEquals("v1.0.0", parsed.get("tag"));

        for (String bad : new String[]{"{\"tag\":\"\\u+041\"}", "{\"tag\":\"\\u-001\"}"}) {
            try {
                parseObject.invoke(null, bad);
                fail("Expected IOException for: " + bad);
            } catch (InvocationTargetException wrapped) {
                assertTrue("Expected IOException, got " + wrapped.getCause(),
                        wrapped.getCause() instanceof IOException);
            }
        }
    }

    @Test
    public void liveUiPatchUnescRejectsSignedPercentHexAndAcceptsValidHex() throws Exception {
        Method unesc = LiveUiPatch.StateManager.class.getDeclaredMethod("unesc", String.class);
        unesc.setAccessible(true);

        assertEquals("A;B", unesc.invoke(null, "%41%3B%42"));
        for (String bad : new String[]{"%+A", "%-1", "%+0", "%-f"}) {
            try {
                unesc.invoke(null, bad);
                fail("Expected IOException for: " + bad);
            } catch (InvocationTargetException wrapped) {
                assertTrue("Expected IOException, got " + wrapped.getCause(),
                        wrapped.getCause() instanceof IOException);
            }
        }
    }
}
