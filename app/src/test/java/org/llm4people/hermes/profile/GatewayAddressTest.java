package org.llm4people.hermes.profile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GatewayAddressTest {
    @Test public void composesSingleHostnameAndOptionalProfileSlug() {
        assertEquals("https://hermes.example.com",
                GatewayAddress.normalize("https://hermes.example.com/", ""));
        assertEquals("https://hermes.example.com",
                GatewayAddress.normalize("https://hermes.example.com", null));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com", "alice"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("HTTPS://HERMES.EXAMPLE.COM/", "  Alice  "));
        assertEquals("https://hermes.example.com/p/bob_work-2",
                GatewayAddress.normalize("https://hermes.example.com", "bob_work-2"));
        assertEquals("http://127.0.0.1:8642/p/alice",
                GatewayAddress.normalize("http://127.0.0.1:8642/", "alice"));
        assertEquals("https://hermes.example.com/sub/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/sub/", "alice"));
    }

    @Test public void preservesDirectProfileUrlsAndStripsDuplicateProfilePrefixes() {
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/p/alice/", ""));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/p/p/alice"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/P/p/p/alice/"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com", "/p/alice"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com", "/p/alice/"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com", "p/alice"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com", "/p/p/alice/"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/p/", "alice"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/p/alice", "alice"));
        assertEquals("https://hermes.example.com/p/alice",
                GatewayAddress.normalize("https://hermes.example.com/p/ALICE", "/p/alice"));
    }

    @Test public void rejectsTraversalMalformedAndConflictingProfileSlugs() {
        for (String badSlug : new String[]{
                "../other", "/p/../other", "..", ".", "a/../b",
                "alice/bob", "/p/alice/extra", "alice\\bob",
                "/p", "/p/", "p/", "/p/p/", "/p//alice",
                "alice?x=1", "alice#frag", "alice%2fother", "alice bob",
                "-alice", "alice-", "_alice", "alice_",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "alice\nbob"
        }) {
            assertThrows(badSlug, IllegalArgumentException.class,
                    () -> GatewayAddress.normalizeProfileSlug(badSlug));
            assertThrows(badSlug, IllegalArgumentException.class,
                    () -> GatewayAddress.normalize("https://hermes.example.com", badSlug));
        }
        assertThrows(IllegalArgumentException.class,
                () -> GatewayAddress.normalize("https://hermes.example.com/p/bob", "alice"));
    }
}
