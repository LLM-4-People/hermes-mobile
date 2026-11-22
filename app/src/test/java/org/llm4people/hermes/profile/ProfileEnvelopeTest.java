package org.llm4people.hermes.profile;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import static org.junit.Assert.*;

public class ProfileEnvelopeTest {
    private final char[] pass = "ma phrase secrète de test".toCharArray();
    private final byte[] data = "synthetic gateway secret".getBytes(StandardCharsets.UTF_8);

    @Test public void profileRequiresOriginalDevicePassphraseAndAssociatedLabel() throws Exception {
        SecretKey device = KeyGenerator.getInstance("AES").generateKey();
        byte[] stored = ProfileEnvelope.seal(device, "a", "Équipe", pass, data);
        assertArrayEquals(data, ProfileEnvelope.open(device, "a", "Équipe", pass, stored));
        assertThrows(GeneralSecurityException.class, () -> ProfileEnvelope.open(device, "a", "Équipe", new char[]{'x'}, stored));
        assertThrows(GeneralSecurityException.class, () -> ProfileEnvelope.open(device, "b", "Équipe", pass, stored));
        assertThrows(GeneralSecurityException.class, () -> ProfileEnvelope.open(device, "a", "Autre", pass, stored));
        SecretKey other = KeyGenerator.getInstance("AES").generateKey();
        assertThrows(GeneralSecurityException.class, () -> ProfileEnvelope.open(other, "a", "Équipe", pass, stored));
        byte[] tampered = stored.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> ProfileEnvelope.open(device, "a", "Équipe", pass, tampered));
        assertThrows(GeneralSecurityException.class, () -> ProfileEnvelope.open(device, "a", "Équipe", pass, new byte[]{1,2}));
        assertFalse(Arrays.equals(stored, ProfileEnvelope.seal(device, "a", "Équipe", pass, data)));
    }

    @Test public void canonicalAddressKeepsProfilePrefixAndRefusesAmbiguousAuthority() {
        assertEquals("https://example.org:8443/p/work", GatewayAddress.normalize("HTTPS://EXAMPLE.ORG:8443/p/work/"));
        assertEquals("http://127.0.0.1", GatewayAddress.normalize("http://127.0.0.1/"));
        assertEquals("http://[::1]:8080", GatewayAddress.normalize("http://[::1]:8080"));
        for (String invalid : new String[]{"", "http://example.org", "http://127.0.0.1.example.org",
                "http://2130706433", "https://user:pass@example.org", "https://example.org?key=x",
                "https://example.org/p/team#session", "https://example.org/%2e%2e", "https://example.org:99999",
                "https://example.org/p/../other", "https://example.org/foo//bar", "https://example.org:"})
            assertThrows(invalid, IllegalArgumentException.class, () -> GatewayAddress.normalize(invalid));
    }
}
