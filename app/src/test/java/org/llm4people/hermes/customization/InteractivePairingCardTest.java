package org.llm4people.hermes.customization;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class InteractivePairingCardTest {
    @Test public void parsesValidPairingInviteCardAndRejectsMalformedOrHostileLinks() {
        assertEquals(InteractiveCardView.Kind.PAIRING_INVITE, InteractiveCardView.Kind.parse("pairing_invite"));
        assertEquals(InteractiveCardView.Kind.PAIRING_INVITE, InteractiveCardView.Kind.parse("pairing"));

        Map<String, Object> pairing = new LinkedHashMap<>();
        pairing.put("mode", "invite");
        pairing.put("deep_link", "hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=InviteCode987654&profile=alex&mode=invite");
        pairing.put("profile_slug", "alex");
        pairing.put("display_name", "Alex");
        pairing.put("expires_at", 1_900_000_300L);
        pairing.put("ttl_seconds", 300);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "card_pair_1");
        raw.put("kind", "pairing_invite");
        raw.put("title", "Invite for Alex");
        raw.put("subtitle", "Scan or share this 5-minute link");
        raw.put("pairing", pairing);

        InteractiveCardView.Spec spec = InteractiveCardView.Spec.fromMap(raw);
        assertEquals(InteractiveCardView.Kind.PAIRING_INVITE, spec.kind);
        assertNotNull(spec.pairingInvite);
        assertEquals("invite", spec.pairingInvite.mode());
        assertEquals("alex", spec.pairingInvite.profileSlug());
        assertEquals("Alex", spec.pairingInvite.displayName());
        assertEquals(300L, spec.pairingInvite.remainingSeconds(1_900_000_000L));
        assertEquals(0L, spec.pairingInvite.remainingSeconds(1_900_000_500L));

        // Cleartext remote HTTP or javascript: scheme must be rejected fail-closed.
        for (String badLink : new String[]{
                "",
                "javascript:alert(1)",
                "hermes://pair?host=http%3A%2F%2Fevil.example.com&code=InviteCode987654",
                "not-a-link"
        }) {
            Map<String, Object> bad = new LinkedHashMap<>(raw);
            Map<String, Object> badPair = new LinkedHashMap<>(pairing);
            badPair.put("deep_link", badLink);
            bad.put("pairing", badPair);
            try {
                InteractiveCardView.Spec.fromMap(bad);
                fail("Expected fail-closed rejection for link: " + badLink);
            } catch (IllegalArgumentException expected) {}
        }
    }
}
