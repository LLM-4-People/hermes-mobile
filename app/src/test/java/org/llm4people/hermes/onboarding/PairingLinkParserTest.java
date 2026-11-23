package org.llm4people.hermes.onboarding;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PairingLinkParserTest {

    @Test
    public void parsesCanonicalHermesDeepLinkAndRoundTrips() {
        String link = PairingLinkParser.buildDeepLink(
                "https://hermes.example.com:8443",
                "pair_AbCdEf1234567890",
                "alice",
                "Alice Martin",
                "invite");
        assertTrue(link.startsWith("hermes://pair?host=https%3A%2F%2Fhermes.example.com%3A8443"));

        PairingLinkParser.ParsedInput parsed = PairingLinkParser.parse(link);
        assertEquals(PairingLinkParser.Kind.PAIRING_TICKET, parsed.kind());
        assertEquals("https://hermes.example.com:8443", parsed.serverUrl());
        assertEquals("alice", parsed.profileSlug());
        assertEquals("pair_AbCdEf1234567890", parsed.pairingCode());
        assertEquals("Alice Martin", parsed.displayName());
        assertEquals("invite", parsed.mode());
        assertEquals("https://hermes.example.com:8443/p/alice", parsed.composedEndpoint());
        assertEquals(link, parsed.canonicalDeepLink());

        // PairingIntent accepts valid hermes://pair URIs and rejects non-pairing or malformed URIs.
        PairingLinkParser.ParsedInput fromIntent = PairingIntent.parseUriString(link);
        assertNotNull(fromIntent);
        assertEquals("pair_AbCdEf1234567890", fromIntent.pairingCode());
        assertNull(PairingIntent.parseUriString("https://hermes.example.com/pair?code=pair_AbCdEf1234567890"));
        assertNull(PairingIntent.parseUriString("hermes://pair?host=http://evil.example&code=pair_AbCdEf1234567890"));
        assertNull(PairingIntent.parseUriString("hermes://pair?host=https://hermes.example.com&code=short"));
    }

    @Test
    public void parsesHttpsInviteUrlsWithOptionalProfilePrefixAndFragment() {
        PairingLinkParser.ParsedInput queryInvite = PairingLinkParser.parse(
                "https://hermes.example.com/p/travail/pair?code=tk_9876543210abcdef&name=Bureau&mode=device");
        assertEquals(PairingLinkParser.Kind.PAIRING_TICKET, queryInvite.kind());
        assertEquals("https://hermes.example.com", queryInvite.serverUrl());
        assertEquals("travail", queryInvite.profileSlug());
        assertEquals("tk_9876543210abcdef", queryInvite.pairingCode());
        assertEquals("Bureau", queryInvite.displayName());
        assertEquals("device", queryInvite.mode());

        PairingLinkParser.ParsedInput fragmentInvite = PairingLinkParser.parse(
                "https://hermes.example.com/invite#code=frag_1234567890ab&profile=famille");
        assertEquals(PairingLinkParser.Kind.PAIRING_TICKET, fragmentInvite.kind());
        assertEquals("https://hermes.example.com", fragmentInvite.serverUrl());
        assertEquals("famille", fragmentInvite.profileSlug());
        assertEquals("frag_1234567890ab", fragmentInvite.pairingCode());
    }

    @Test
    public void parsesBareServerUrlWithProfileAutoSplit() {
        PairingLinkParser.ParsedInput bare = PairingLinkParser.parse("https://HOME.Example.com/p/Alex/");
        assertEquals(PairingLinkParser.Kind.SERVER_ONLY, bare.kind());
        assertEquals("https://home.example.com", bare.serverUrl());
        assertEquals("alex", bare.profileSlug());
        assertEquals("https://home.example.com/p/alex", bare.composedEndpoint());
        assertEquals("", bare.pairingCode());
        assertEquals("", bare.apiKey());
    }

    @Test
    public void parsesSmartPasteMultiLineLabelledAndSingleLineHostPlusKey() {
        PairingLinkParser.ParsedInput twoLines = PairingLinkParser.parse(
                "https://hermes.example.com/p/maison\nhm_live_secret_key_123456");
        assertEquals(PairingLinkParser.Kind.DIRECT_CREDENTIALS, twoLines.kind());
        assertEquals("https://hermes.example.com", twoLines.serverUrl());
        assertEquals("maison", twoLines.profileSlug());
        assertEquals("hm_live_secret_key_123456", twoLines.apiKey());

        PairingLinkParser.ParsedInput frenchLabels = PairingLinkParser.parse(
                "Serveur : https://hermes.example.com\nProfil : alex\nClé : Bearer key_abcdef123456789\nNom : Alex");
        assertEquals(PairingLinkParser.Kind.DIRECT_CREDENTIALS, frenchLabels.kind());
        assertEquals("https://hermes.example.com", frenchLabels.serverUrl());
        assertEquals("alex", frenchLabels.profileSlug());
        assertEquals("key_abcdef123456789", frenchLabels.apiKey());
        assertEquals("Alex", frenchLabels.displayName());

        PairingLinkParser.ParsedInput embeddedDeepLink = PairingLinkParser.parse(
                "Ouvrez ce lien sur votre téléphone : hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=code_embedded1234.");
        assertEquals(PairingLinkParser.Kind.PAIRING_TICKET, embeddedDeepLink.kind());
        assertEquals("code_embedded1234", embeddedDeepLink.pairingCode());

        PairingLinkParser.ParsedInput singleLine = PairingLinkParser.parse(
                "http://127.0.0.1:8642/p/dev   dev_key_1234567890");
        assertEquals(PairingLinkParser.Kind.DIRECT_CREDENTIALS, singleLine.kind());
        assertEquals("http://127.0.0.1:8642", singleLine.serverUrl());
        assertEquals("dev", singleLine.profileSlug());
        assertEquals("dev_key_1234567890", singleLine.apiKey());
    }

    @Test
    public void parsesJsonSnippetsForPairingDirectAndDeepLink() {
        PairingLinkParser.ParsedInput pairJson = PairingLinkParser.parse(
                "{\"host\":\"https://hermes.example.com\",\"code\":\"json_ticket_12345\",\"profile_slug\":\"team\",\"display_name\":\"Équipe\",\"mode\":\"invite\"}");
        assertEquals(PairingLinkParser.Kind.PAIRING_TICKET, pairJson.kind());
        assertEquals("https://hermes.example.com", pairJson.serverUrl());
        assertEquals("team", pairJson.profileSlug());
        assertEquals("json_ticket_12345", pairJson.pairingCode());
        assertEquals("Équipe", pairJson.displayName());
        assertEquals("invite", pairJson.mode());

        PairingLinkParser.ParsedInput directJson = PairingLinkParser.parse(
                "{\"server_url\":\"https://hermes.example.com/p/solo\",\"api_key\":\"sk_hermes_9876543210\",\"nested\":{\"ignored\":true}}");
        assertEquals(PairingLinkParser.Kind.DIRECT_CREDENTIALS, directJson.kind());
        assertEquals("https://hermes.example.com", directJson.serverUrl());
        assertEquals("solo", directJson.profileSlug());
        assertEquals("sk_hermes_9876543210", directJson.apiKey());

        PairingLinkParser.ParsedInput deepLinkJson = PairingLinkParser.parse(
                "{\"deep_link\":\"hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=nested_deep_12345\"}");
        assertEquals(PairingLinkParser.Kind.PAIRING_TICKET, deepLinkJson.kind());
        assertEquals("nested_deep_12345", deepLinkJson.pairingCode());
    }

    @Test
    public void rejectsHostileMalformedCleartextTraversalAndOversizedInputs() {
        String[] rejected = {
                null,
                "",
                "   \t\n  ",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "content://org.example/secret",
                "intent://pair#Intent;scheme=hermes;end",
                "http://remote.example.com/pair?code=pair_1234567890",
                "hermes://pair?host=http://10.0.0.5:8642&code=pair_1234567890",
                "hermes://other?host=https://hermes.example.com&code=pair_1234567890",
                "hermes://user:pass@pair?host=https://hermes.example.com&code=pair_1234567890",
                "https://alice:secret@hermes.example.com/pair?code=pair_1234567890",
                "https://hermes.example.com/p/../admin/pair?code=pair_1234567890",
                "hermes://pair?host=https://hermes.example.com/p/alice&profile=bob&code=pair_1234567890",
                "hermes://pair?host=https://hermes.example.com&code=short",
                "hermes://pair?host=https://hermes.example.com&code=bad$chars!12345",
                "https://hermes.example.com\u0000/pair?code=pair_1234567890",
                "{\"host\":\"https://hermes.example.com\",\"code\":\"unterminated",
                "a".repeat(PairingLinkParser.MAX_INPUT_CHARS + 1),
        };
        for (String bad : rejected) {
            assertNull("Expected rejection for: " + bad, PairingLinkParser.parseOrNull(bad));
            try {
                PairingLinkParser.parse(bad);
                fail("Expected IllegalArgumentException for: " + bad);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
